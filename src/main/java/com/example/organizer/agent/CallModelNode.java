package com.example.organizer.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.agent.ConversationMessage.AssistantTurn;
import com.example.organizer.agent.ConversationMessage.ToolResult;
import com.example.organizer.agent.ConversationMessage.UserText;
import com.example.organizer.config.OrganizerProperties;
import com.example.organizer.tools.ReadFileTool;
import com.example.organizer.tools.ToolCall;
import com.example.organizer.tools.ToolRegistry;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

/**
 * Graph step: sends the conversation and the tool descriptions to the model, and stores its answer.
 * The answer either contains tool calls (the graph executes them next) or not (the model is done).
 *
 * Guardrails: the number of model calls and the tokens used per run are limited, a failing model
 * stops the run safely, and file contents are sent only once (see {@link #withoutFileContents}).
 *
 * LangGraph4j: a {@link NodeAction} is a synchronous step, state in, update out (registered with
 * {@code node_async} in OrganizerGraphFactory). The returned map holds only the keys that changed.
 * LangGraph4j merges them into the state and saves a checkpoint. The node never stores anything
 * between calls itself: everything it needs next time (conversation, counters) lives in the state,
 * so the step works the same after a pause or a restart.
 *
 * The model itself is called through LangChain4j's {@link ChatModel} interface, so the node does not
 * know whether Claude or the offline keyword model answers (see OrganizerConfiguration).
 */
@Component
public class CallModelNode implements NodeAction<OrganizerState> {

    public static final String NAME = "call_model";

    private static final Logger log = LoggerFactory.getLogger(CallModelNode.class);

    /** The opening tag of a read_file result, e.g. {@code <file_content name="trip.txt" truncated="false">}. */
    private static final Pattern CONTENT_TAG = Pattern.compile(
            "^<" + ReadFileTool.CONTENT_TAG + " name=\"([^\"]*)\"[^>]*>");

    private final ChatModel model;
    private final ToolRegistry tools;
    private final int maxModelCalls;
    private final long maxTokensPerRun;

    public CallModelNode(ChatModel model, ToolRegistry tools, OrganizerProperties properties) {
        this.model = model;
        this.tools = tools;
        this.maxModelCalls = properties.agent().maxModelCalls();
        this.maxTokensPerRun = properties.agent().maxTokensPerRun();
    }

    @Override
    public Map<String, Object> apply(OrganizerState state) {
        // Limits are checked before the call. A STOP_REASON makes the graph route to the report
        // (see OrganizerGraphFactory.stepAfterModel), which ends the run normally.
        int calls = state.modelCalls();
        if (calls >= maxModelCalls) {
            log.warn("Model call limit reached ({}), stopping the run", maxModelCalls);
            return Map.of(OrganizerState.STOP_REASON,
                    "Stopped after " + maxModelCalls + " model calls (organizer.agent.max-model-calls).");
        }
        long tokensUsed = state.inputTokens() + state.outputTokens();
        if (tokensUsed >= maxTokensPerRun) {
            log.warn("Token budget used up ({} of {}), stopping the run", tokensUsed, maxTokensPerRun);
            return Map.of(OrganizerState.STOP_REASON, "Stopped after using " + tokensUsed
                    + " tokens (organizer.agent.max-tokens-per-run=" + maxTokensPerRun + ").");
        }

        log.debug("Step '{}' started: model call {} of at most {}", NAME, calls + 1, maxModelCalls);
        // The whole conversation is sent each time: the model keeps no memory between calls.
        // The tool specifications tell it which tools it may call and with which arguments.
        ChatRequest request = ChatRequest.builder()
                .messages(toChatMessages(state.messages()))
                .toolSpecifications(tools.specifications())
                .build();

        long startedAt = System.nanoTime();
        ChatResponse response;
        try {
            response = model.chat(request);
        } catch (RuntimeException exception) {
            // Timeout (after the configured retries), network error, invalid key, ...
            // Returned as a stop reason instead of thrown: an exception would abort the graph with no
            // report. This way the run still ends at the report and tells the human what happened.
            log.error("Model call failed: {}", exception.toString());
            return Map.of(OrganizerState.MODEL_CALLS, calls + 1,
                    OrganizerState.STOP_REASON, "Stopped because the model call failed: " + exception.getMessage());
        }

        // The offline model reports no usage; real providers do.
        TokenUsage usage = response.tokenUsage();
        long input = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        long output = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;

        AssistantTurn turn = fromAiMessage(response.aiMessage());
        log.info("Model answered in {} ms with {} tool calls ({} input + {} output tokens)",
                (System.nanoTime() - startedAt) / 1_000_000, turn.toolCalls().size(), input, output);
        turn.toolCalls().forEach(call -> log.debug("Tool call {}: {} {}", call.id(), call.toolName(), call.argumentsJson()));

        // The model has now seen every file content in the conversation: remove them before storing.
        List<ConversationMessage> messages = new ArrayList<>(withoutFileContents(state.messages()));
        messages.add(turn);
        // MESSAGES has no channel in OrganizerState.SCHEMA, so this list replaces the stored conversation
        // (that is what lets the compaction above take effect). The counters are replaced too, so they are
        // computed here as old value + this call.
        return Map.of(
                OrganizerState.MESSAGES, List.copyOf(messages),
                OrganizerState.MODEL_CALLS, calls + 1,
                OrganizerState.INPUT_TOKENS, state.inputTokens() + input,
                OrganizerState.OUTPUT_TOKENS, state.outputTokens() + output);
    }

    // ---------------------------------------------------------------------------------------------
    // Conversation <-> LangChain4j messages
    //
    // The graph state holds our own serializable records (see ConversationMessage), because LangChain4j's
    // message classes are not Serializable and the state is checkpointed. They are converted to
    // LangChain4j messages only for the duration of a model call:
    // - SystemMessage: the instructions; UserMessage: the task;
    // - AiMessage: an answer of the model, with its tool calls (ToolExecutionRequest);
    // - ToolExecutionResultMessage: the result of one tool call. It refers to the call's id, so the
    //   model can match results to calls; the provider rejects a conversation where a call has no result.
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the messages for one model call. The system prompt is not stored in the state: it is added
     * here on every call, so it is always the current one, always comes first and is kept apart from any data.
     */
    private static List<ChatMessage> toChatMessages(List<ConversationMessage> conversation) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(AgentInstructions.SYSTEM_PROMPT));
        for (ConversationMessage message : conversation) {
            messages.add(switch (message) {
                case UserText user -> UserMessage.from(user.text());
                case AssistantTurn turn -> toAiMessage(turn);
                case ToolResult result ->
                        ToolExecutionResultMessage.from(result.toolCallId(), result.toolName(), result.text());
            });
        }
        return messages;
    }

    private static AiMessage toAiMessage(AssistantTurn turn) {
        List<ToolExecutionRequest> requests = turn.toolCalls().stream()
                .map(call -> ToolExecutionRequest.builder()
                        .id(call.id())
                        .name(call.toolName())
                        .arguments(call.argumentsJson())
                        .build())
                .toList();
        if (requests.isEmpty()) {
            return AiMessage.from(turn.text());
        }
        // Text is optional when the model only calls tools.
        return turn.text().isBlank() ? AiMessage.from(requests) : AiMessage.from(turn.text(), requests);
    }

    /** Converts the model's answer into a serializable turn, to be stored in the graph state. */
    private static AssistantTurn fromAiMessage(AiMessage message) {
        List<ToolCall> calls = message.hasToolExecutionRequests()
                ? message.toolExecutionRequests().stream()
                        .map(request -> new ToolCall(request.id(), request.name(), request.arguments()))
                        .toList()
                : List.of();
        return new AssistantTurn(message.text(), calls);
    }

    // ---------------------------------------------------------------------------------------------
    // Keeping the conversation small: file contents are sent to the model only once.
    //
    // Without this, every model call would resend every file read so far (100 files x 4000 characters
    // is about 100k tokens per call), which costs money and can exceed the model's context window.
    // After a model call, the content of every read_file result is replaced by a short note; the model
    // is told (system prompt) to write down its decision for each file right after reading it.
    // This also keeps the checkpoints small, because they store the conversation.
    // ---------------------------------------------------------------------------------------------

    /** Returns the conversation with all file contents replaced by short notes. */
    private static List<ConversationMessage> withoutFileContents(List<ConversationMessage> messages) {
        return messages.stream()
                .map(message -> message instanceof ToolResult result ? compact(result) : message)
                .toList();
    }

    private static ConversationMessage compact(ToolResult result) {
        if (!result.toolName().equals(ReadFileTool.NAME)) {
            return result;
        }
        Matcher tag = CONTENT_TAG.matcher(result.text());
        if (!tag.find()) {
            return result; // an error or a binary file: already short
        }
        // The name is kept exactly as it was (already escaped).
        return new ToolResult(result.toolCallId(), result.toolName(),
                "<" + ReadFileTool.CONTENT_TAG + " name=\"" + tag.group(1) + "\" removed=\"true\"/>\n"
                        + "[The content was shown once and removed to save space. Use the category you noted.]");
    }
}

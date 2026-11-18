package com.example.organizer.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import com.example.organizer.agent.ConversationMessage.AssistantTurn;
import com.example.organizer.agent.ConversationMessage.UserText;
import com.example.organizer.approval.ToolCallReviewer.ApprovalScope;
import com.example.organizer.approval.ToolCallReviewer.ToolDecision;
import com.example.organizer.tools.ToolCall;

/**
 * The shared state that flows through the organizer graph.
 *
 * <h2>How LangGraph4j handles the state</h2>
 * <ul>
 *   <li>LangGraph4j keeps the state as a plain {@code Map<String, Object>} ({@link AgentState}). This class
 *       extends it only to add typed accessors ({@code messages()}, {@code modelCalls()}, ...), so the
 *       rest of the code does not deal with string keys and casts.</li>
 *   <li>A node never changes the state directly. It receives a copy and returns a map with only the keys
 *       it wants to change. LangGraph4j merges that map into the state using {@link #SCHEMA} and then
 *       creates a new OrganizerState from the merged map (the {@code OrganizerState::new} factory).</li>
 *   <li>After every node, the state is written into a checkpoint. A run paused for the human, or
 *       interrupted by a crash, continues from that checkpoint.</li>
 * </ul>
 *
 * Because the state is copied and saved with Java serialization (see {@link #SERIALIZER}), every value
 * stored in it must be Serializable. This is why the conversation uses our own records
 * (ConversationMessage, ToolCall, ApprovalScope, ToolDecision) and paths are stored as Strings.
 */
public class OrganizerState extends AgentState {

    // Keys of the state map. Each graph step reads some keys and returns new values for others.

    /** Absolute path of the folder to organize, stored as String so it can be checkpointed. */
    public static final String TARGET_FOLDER = "targetFolder";
    /**
     * The conversation with the model: task, model answers (with tool calls), tool results.
     * Replaced as a whole when written (not appended), so that file contents the model has
     * already seen can be removed from it (see CallModelNode.withoutFileContents).
     */
    public static final String MESSAGES = "messages";
    /** How many times the model was called in this run. */
    public static final String MODEL_CALLS = "modelCalls";
    /** Tokens used by the model in this run, as reported by the model provider. */
    public static final String INPUT_TOKENS = "inputTokens";
    public static final String OUTPUT_TOKENS = "outputTokens";

    /** Decisions for the pending tool calls, by tool call id. Written by the agent after asking the human. */
    public static final String TOOL_DECISIONS = "toolDecisions";
    /** Standing approvals ("this tool", "all changes") given earlier in this run. */
    public static final String APPROVAL_SCOPE = "approvalScope";

    public static final String APPLIED_CHANGES = "appliedChanges";
    public static final String REJECTED_CHANGES = "rejectedChanges";
    public static final String FAILED_CHANGES = "failedChanges";
    /** Things the human should know, e.g. a limit that left files unprocessed. */
    public static final String NOTICES = "notices";
    /** Why the run stopped early (limit reached, model error), if it did. */
    public static final String STOP_REASON = "stopReason";
    public static final String REPORT = "report";

    /**
     * How LangGraph4j merges a node's update into the state, per key. A {@link Channel} is a
     * reducer: it combines the old value and the new value.
     *
     * The result lists use an appender. execute_tools returns only the results of its own batch, and
     * LangGraph4j adds them to the lists of earlier batches, so the node does not need to read and copy
     * the existing lists. "WithDuplicate" keeps equal entries (the plain appender drops them).
     *
     * Keys not listed here have no channel, so a new value replaces the old one. MESSAGES is deliberately
     * one of them: it must be replaceable as a whole so file contents can be removed
     * (see CallModelNode.withoutFileContents), and an appender could only add.
     */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            APPLIED_CHANGES, Channels.appenderWithDuplicate(ArrayList::new),
            REJECTED_CHANGES, Channels.appenderWithDuplicate(ArrayList::new),
            FAILED_CHANGES, Channels.appenderWithDuplicate(ArrayList::new),
            NOTICES, Channels.appenderWithDuplicate(ArrayList::new)
    );

    /**
     * Turns the state into bytes and back (Java serialization). LangGraph4j uses it for two things:
     * - before each node it deep-copies the state, so a node cannot change the shared state by accident
     *   (only the map it returns counts);
     * - the checkpoint saver writes states to SQLite with it and reads them back after a restart.
     * The graph and the saver must use the same serializer, otherwise saved states cannot be read.
     * {@code OrganizerState::new} tells it how to create our state class from a deserialized map.
     */
    public static final StateSerializer<OrganizerState> SERIALIZER = new ObjectStreamStateSerializer<>(OrganizerState::new);

    public OrganizerState(Map<String, Object> initialData) {
        super(initialData);
    }

    /** The state the graph starts with (passed as {@code GraphInput.args}): the folder and the task for the model. */
    public static Map<String, Object> initialInput(Path targetFolder, String task) {
        return Map.of(
                TARGET_FOLDER, targetFolder.toAbsolutePath().normalize().toString(),
                MESSAGES, List.of(new UserText(task)),
                MODEL_CALLS, 0,
                INPUT_TOKENS, 0L,
                OUTPUT_TOKENS, 0L,
                APPROVAL_SCOPE, ApprovalScope.none());
    }

    /**
     * The state update written by the agent after the human has reviewed the pending tool calls.
     * It is written into the paused run with {@code graph.updateState}, outside of any node.
     * Both keys have no channel, so they replace the previous values.
     */
    public static Map<String, Object> humanDecision(Map<String, ToolDecision> decisions, ApprovalScope scope) {
        return Map.of(TOOL_DECISIONS, Map.copyOf(decisions), APPROVAL_SCOPE, scope);
    }

    /** Clears standing approvals and pending decisions (used when continuing a run after a crash). */
    public static Map<String, Object> forgetApprovals() {
        return Map.of(TOOL_DECISIONS, Map.of(), APPROVAL_SCOPE, ApprovalScope.none());
    }

    // Typed accessors over the underlying map (AgentState.value returns Optional<T>).
    // Missing values fall back to empty defaults, because a key is only present after the
    // step that produces it has run.

    public Path targetFolder() {
        // Always set by initialInput, so a missing value would be a programming error.
        return Path.of(this.<String>value(TARGET_FOLDER).orElseThrow());
    }

    public List<ConversationMessage> messages() {
        return this.<List<ConversationMessage>>value(MESSAGES).orElse(List.of());
    }

    /** The conversation with additional messages at the end, as a new list to write into MESSAGES. */
    public List<ConversationMessage> messagesWith(List<? extends ConversationMessage> additional) {
        List<ConversationMessage> all = new ArrayList<>(messages());
        all.addAll(additional);
        return List.copyOf(all);
    }

    /** The latest model answer, if the last message is one. */
    public Optional<AssistantTurn> lastAssistantTurn() {
        List<ConversationMessage> messages = messages();
        if (!messages.isEmpty() && messages.getLast() instanceof AssistantTurn turn) {
            return Optional.of(turn);
        }
        return Optional.empty();
    }

    /** The tool calls of the latest model answer, i.e. the calls waiting to be executed. */
    public List<ToolCall> pendingToolCalls() {
        return lastAssistantTurn().map(AssistantTurn::toolCalls).orElse(List.of());
    }

    public int modelCalls() {
        return this.<Integer>value(MODEL_CALLS).orElse(0);
    }

    public long inputTokens() {
        return this.<Long>value(INPUT_TOKENS).orElse(0L);
    }

    public long outputTokens() {
        return this.<Long>value(OUTPUT_TOKENS).orElse(0L);
    }

    public Map<String, ToolDecision> toolDecisions() {
        return this.<Map<String, ToolDecision>>value(TOOL_DECISIONS).orElse(Map.of());
    }

    public ApprovalScope approvalScope() {
        return this.<ApprovalScope>value(APPROVAL_SCOPE).orElse(ApprovalScope.none());
    }

    public List<String> appliedChanges() {
        return this.<List<String>>value(APPLIED_CHANGES).orElse(List.of());
    }

    public List<String> rejectedChanges() {
        return this.<List<String>>value(REJECTED_CHANGES).orElse(List.of());
    }

    public List<String> failedChanges() {
        return this.<List<String>>value(FAILED_CHANGES).orElse(List.of());
    }

    public List<String> notices() {
        return this.<List<String>>value(NOTICES).orElse(List.of());
    }

    public Optional<String> stopReason() {
        return this.<String>value(STOP_REASON).filter(reason -> !reason.isBlank());
    }

    public String report() {
        return this.<String>value(REPORT).orElse("");
    }
}

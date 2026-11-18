package com.example.organizer.agent;

import java.io.Serializable;
import java.util.List;

import com.example.organizer.tools.ToolCall;

/**
 * One message of the conversation between the agent and the model.
 *
 * Our own serializable types are used instead of LangChain4j's message classes, because the
 * graph state is serialized when it is checkpointed (and LangChain4j messages are not Serializable).
 * CallModelNode converts between both worlds for each model call.
 */
public sealed interface ConversationMessage extends Serializable {

    /** The task given to the model. */
    record UserText(String text) implements ConversationMessage {
    }

    /**
     * An answer of the model: some text, tool calls, or both.
     * An answer without tool calls means the model is done.
     */
    record AssistantTurn(String text, List<ToolCall> toolCalls) implements ConversationMessage {

        public AssistantTurn {
            text = text == null ? "" : text;
            toolCalls = List.copyOf(toolCalls);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /** The result of one tool call, sent back to the model. */
    record ToolResult(String toolCallId, String toolName, String text) implements ConversationMessage {
    }
}

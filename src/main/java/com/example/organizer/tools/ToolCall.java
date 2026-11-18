package com.example.organizer.tools;

import java.io.Serializable;

/**
 * A tool call requested by the model.
 * Serializable because it is stored in the graph state, which is checkpointed while the human decides.
 *
 * @param id            id given by the model; the tool result must refer to it
 * @param toolName      e.g. "move_file"
 * @param argumentsJson the arguments as sent by the model, e.g. {"fileName":"trip.txt","directory":"travel"}
 */
public record ToolCall(String id, String toolName, String argumentsJson) implements Serializable {
}

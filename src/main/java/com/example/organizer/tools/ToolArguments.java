package com.example.organizer.tools;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The arguments of a tool call. The model sends them as a JSON object, e.g. {"fileName": "trip.txt"}.
 */
public final class ToolArguments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonNode json;

    private ToolArguments(JsonNode json) {
        this.json = json;
    }

    /**
     * @throws ToolArgumentException when the text is not a JSON object
     */
    public static ToolArguments parse(String argumentsJson) {
        // Tools without parameters may receive null or "" instead of "{}".
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new ToolArguments(JSON.createObjectNode());
        }
        try {
            JsonNode node = JSON.readTree(argumentsJson);
            if (node == null || !node.isObject()) {
                throw new ToolArgumentException("Arguments must be a JSON object");
            }
            return new ToolArguments(node);
        } catch (ToolArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolArgumentException("Arguments are not valid JSON");
        }
    }

    /** Builds arguments in code (used by the offline model). */
    public static String toJson(Map<String, String> arguments) {
        return JSON.valueToTree(arguments).toString();
    }

    /**
     * @throws ToolArgumentException when the argument is missing, not a string, or blank
     */
    public String requireText(String name) {
        JsonNode value = json.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolArgumentException("Missing text argument '" + name + "'");
        }
        return value.asText().strip();
    }
}

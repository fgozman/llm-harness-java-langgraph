package com.example.organizer.tools;

/**
 * Thrown when the model called a tool with missing or unacceptable arguments.
 * The message is returned to the model so it can correct itself.
 */
public class ToolArgumentException extends RuntimeException {

    public ToolArgumentException(String message) {
        super(message);
    }
}

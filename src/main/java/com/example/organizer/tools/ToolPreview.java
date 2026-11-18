package com.example.organizer.tools;

/**
 * What a tool call would do, computed before anything is changed.
 *
 * @param description     one line for the human, e.g. "Move trip.txt -> travel/trip.txt"
 * @param changesFiles    false when the call only reads, or when there is nothing to change
 *                        (e.g. the directory already exists); such calls need no approval
 * @param overwrites      true when an existing file would be replaced
 * @param overwrittenPath the file that would be replaced (relative), or null
 */
public record ToolPreview(String description, boolean changesFiles, boolean overwrites, String overwrittenPath) {

    /** Read-only calls, and changing calls that have nothing to change. */
    public static ToolPreview noChange(String description) {
        return new ToolPreview(description, false, false, null);
    }

    public static ToolPreview change(String description) {
        return new ToolPreview(description, true, false, null);
    }

    public static ToolPreview overwrite(String description, String overwrittenPath) {
        return new ToolPreview(description, true, true, overwrittenPath);
    }
}

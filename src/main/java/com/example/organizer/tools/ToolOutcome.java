package com.example.organizer.tools;

/**
 * Result of a tool call.
 *
 * @param status       how the call ended, used for the report
 * @param modelMessage text returned to the model as the tool result
 * @param reportLine   one line for the final report; for DONE calls an optional notice (e.g. a limit was hit)
 */
public record ToolOutcome(Status status, String modelMessage, String reportLine) {

    public enum Status {
        /** Read-only call, or nothing needed to change. Shown in the report only when it has a notice. */
        DONE,
        /** The filesystem was changed. */
        APPLIED,
        /** The human did not allow the change. */
        REJECTED,
        /** The call was invalid or the change failed. */
        FAILED
    }

    public static ToolOutcome done(String modelMessage) {
        return new ToolOutcome(Status.DONE, modelMessage, null);
    }

    /** A read-only result the human should know about, e.g. "only 100 of 150 files were listed". */
    public static ToolOutcome doneWithNotice(String modelMessage, String notice) {
        return new ToolOutcome(Status.DONE, modelMessage, notice);
    }

    public static ToolOutcome applied(String description) {
        return new ToolOutcome(Status.APPLIED, "OK: " + description, description);
    }

    public static ToolOutcome rejected(String description, String reason) {
        return new ToolOutcome(Status.REJECTED,
                "REJECTED by the human: " + description + ". " + reason + " Do not retry this change.",
                description + " (" + reason + ")");
    }

    public static ToolOutcome failed(String description, String reason) {
        return new ToolOutcome(Status.FAILED, "ERROR: " + reason, description + " (" + reason + ")");
    }
}

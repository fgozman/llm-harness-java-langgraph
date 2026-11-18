package com.example.organizer.approval;

import java.util.List;

/**
 * Lets a human decide on the changes the agent's tools want to make.
 * Implementations must answer "no" when they cannot get an answer.
 * The console implementation is {@link ConsoleHumanApprover}; tests use a scripted one.
 */
public interface HumanApprover {

    /**
     * Shows all changes of a batch before they are asked one by one (only when there are several).
     */
    void announceBatch(List<ChangeRequest> changes);

    /**
     * Asks whether a change may be made.
     */
    ApprovalAnswer review(ChangeRequest change);

    /**
     * Asks whether an existing file may be replaced. Only called when the change overwrites
     * a file and the human has not approved all changes.
     */
    boolean approveOverwrite(ChangeRequest change);

    /**
     * Asks a yes/no question outside the tool approvals, e.g. "continue the unfinished run?".
     *
     * @param details lines shown before the question
     * @return true only for an explicit "yes"
     */
    boolean confirm(String question, List<String> details);

    /**
     * A change shown to the human.
     *
     * @param toolName        the tool that wants to make the change, e.g. "move_file"
     * @param description     what will happen, e.g. "Move trip.txt -> travel/trip.txt"
     * @param overwrittenPath the existing file that would be replaced, or null
     * @param position        number of this change among the changes asked about in this batch (1-based)
     * @param batchSize       how many changes of this batch the human is asked about
     */
    record ChangeRequest(String toolName, String description, String overwrittenPath, int position, int batchSize) {

        public ChangeRequest(String toolName, String description, String overwrittenPath) {
            this(toolName, description, overwrittenPath, 1, 1);
        }

        public boolean overwrites() {
            return overwrittenPath != null;
        }

        public boolean partOfBatch() {
            return batchSize > 1;
        }
    }

    /** The human's answer to a proposed change. */
    enum ApprovalAnswer {
        /** Approve only this change. */
        THIS_CHANGE,
        /** Approve this change and the remaining changes of the current batch (overwrites are still asked). */
        THIS_BATCH,
        /** Approve this change and every later change of the same tool (overwrites are still asked). */
        THIS_TOOL,
        /** Approve this change and every later change of any tool, overwrites included, without asking again. */
        ALL_CHANGES,
        /** Reject this change. */
        REJECT
    }
}

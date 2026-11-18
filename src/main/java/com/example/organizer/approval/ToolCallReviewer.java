package com.example.organizer.approval;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.approval.HumanApprover.ChangeRequest;
import com.example.organizer.tools.ToolCall;
import com.example.organizer.tools.ToolRegistry;
import com.example.organizer.tools.ToolRegistry.CheckedCall;

/**
 * Decides, for each tool call of the model, whether it may run. Asks the human when needed.
 *
 * Rules:
 * - Read-only calls, invalid calls and calls that change nothing are not shown to the human
 *   (invalid calls fail anyway when executed).
 * - A changing call is approved without asking when the human earlier chose "all changes",
 *   "this tool" for the same tool, or "the rest of this batch".
 * - Otherwise the human is asked: this change / rest of the batch / this tool / all changes / no.
 *   When several changes need an answer, they are first shown together as a list.
 * - If the change overwrites a file, the human is asked separately, unless "all changes" was chosen.
 */
@Component
public class ToolCallReviewer {

    private static final Logger log = LoggerFactory.getLogger(ToolCallReviewer.class);

    private final ToolRegistry tools;
    private final HumanApprover human;

    public ToolCallReviewer(ToolRegistry tools, HumanApprover human) {
        this.tools = tools;
        this.human = human;
    }

    /**
     * @param scope standing approvals given earlier in this run
     * @return a decision per tool call id (only for calls that change files) and the updated scope
     */
    public Review review(Path folder, List<ToolCall> calls, ApprovalScope scope) {
        // 1. Which calls change files, and which of them are not covered by an earlier answer?
        List<Pending> changing = new ArrayList<>();
        for (ToolCall call : calls) {
            CheckedCall checked = tools.check(folder, call);
            if (checked.needsApproval()) {
                changing.add(new Pending(call, checked));
            }
        }
        List<Pending> toAsk = changing.stream().filter(p -> !scope.covers(p.call().toolName())).toList();

        // Numbered requests for the calls the human will see; shown together first when there are several.
        Map<String, ChangeRequest> requests = new LinkedHashMap<>();
        for (int i = 0; i < toAsk.size(); i++) {
            Pending pending = toAsk.get(i);
            requests.put(pending.call().id(), pending.request(i + 1, toAsk.size()));
        }
        if (requests.size() > 1) {
            human.announceBatch(List.copyOf(requests.values()));
        }

        // 2. Decide each changing call in order.
        Map<String, ToolDecision> decisions = new LinkedHashMap<>();
        ApprovalScope current = scope;
        boolean restOfBatchApproved = false;

        for (Pending pending : changing) {
            ToolCall call = pending.call();
            ChangeRequest change = requests.getOrDefault(call.id(), pending.request(1, 1));

            boolean approved;
            if (current.covers(call.toolName()) || restOfBatchApproved) {
                approved = true;
                log.debug("Approved by an earlier answer: {}", change.description());
            } else {
                ApprovalAnswer answer = human.review(change);
                approved = answer != ApprovalAnswer.REJECT;
                switch (answer) {
                    case THIS_BATCH -> restOfBatchApproved = true;
                    case THIS_TOOL -> current = current.withTool(call.toolName());
                    case ALL_CHANGES -> current = current.withAllChanges();
                    default -> { /* THIS_CHANGE or REJECT: nothing to remember */ }
                }
                log.info("Human answered {} for: {}", answer, change.description());
            }

            // May it replace an existing file? "All changes" includes overwrites; otherwise ask.
            boolean overwriteAllowed = false;
            if (approved && change.overwrites()) {
                overwriteAllowed = current.allChanges() || human.approveOverwrite(change);
                log.info("Overwrite of {} {}", change.overwrittenPath(), overwriteAllowed ? "allowed" : "refused");
            }

            decisions.put(call.id(), new ToolDecision(approved, overwriteAllowed));
        }
        return new Review(decisions, current);
    }

    public record Review(Map<String, ToolDecision> decisions, ApprovalScope scope) {
    }

    /**
     * The decision for one tool call that changes files. Stored in the graph state (hence Serializable).
     *
     * @param approved         the change may be made
     * @param overwriteAllowed an existing file may be replaced
     */
    public record ToolDecision(boolean approved, boolean overwriteAllowed) implements Serializable {

        public static ToolDecision rejected() {
            return new ToolDecision(false, false);
        }
    }

    /**
     * Standing approvals the human gave during a run ("approve all", "approve this tool").
     * Kept in the graph state, so it lasts for the whole run and is forgotten afterwards.
     *
     * @param allChanges    every change is approved, overwrites included
     * @param approvedTools tools whose changes are approved (overwrites still need a separate answer)
     */
    public record ApprovalScope(boolean allChanges, Set<String> approvedTools) implements Serializable {

        public ApprovalScope {
            approvedTools = Set.copyOf(approvedTools);
        }

        public static ApprovalScope none() {
            return new ApprovalScope(false, Set.of());
        }

        public boolean covers(String toolName) {
            return allChanges || approvedTools.contains(toolName);
        }

        public ApprovalScope withTool(String toolName) {
            Set<String> tools = new HashSet<>(approvedTools);
            tools.add(toolName);
            return new ApprovalScope(allChanges, tools);
        }

        public ApprovalScope withAllChanges() {
            return new ApprovalScope(true, approvedTools);
        }
    }

    private record Pending(ToolCall call, CheckedCall checked) {

        ChangeRequest request(int position, int batchSize) {
            return new ChangeRequest(call.toolName(), checked.preview().description(),
                    checked.preview().overwrittenPath(), position, batchSize);
        }
    }
}

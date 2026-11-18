package com.example.organizer.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.approval.HumanApprover.ChangeRequest;
import com.example.organizer.approval.HumanApprover;

/**
 * Plays the human in tests: answers by rule and remembers every question it was asked.
 */
public class ScriptedHumanApprover implements HumanApprover {

    private Function<ChangeRequest, ApprovalAnswer> answerRule = change -> ApprovalAnswer.REJECT;
    private Predicate<ChangeRequest> overwriteRule = change -> false;
    private boolean confirmAnswer = false;

    private final List<List<ChangeRequest>> announcedBatches = new ArrayList<>();
    private final List<ChangeRequest> reviewed = new ArrayList<>();
    private final List<ChangeRequest> overwriteQuestions = new ArrayList<>();
    private final List<String> confirmQuestions = new ArrayList<>();

    public void answer(Function<ChangeRequest, ApprovalAnswer> rule) {
        this.answerRule = rule;
    }

    public void answerAlways(ApprovalAnswer answer) {
        answer(change -> answer);
    }

    public void allowOverwrite(boolean allowed) {
        this.overwriteRule = change -> allowed;
    }

    public void confirmWith(boolean answer) {
        this.confirmAnswer = answer;
    }

    public List<List<ChangeRequest>> announcedBatches() {
        return announcedBatches;
    }

    public List<ChangeRequest> reviewed() {
        return reviewed;
    }

    public List<ChangeRequest> overwriteQuestions() {
        return overwriteQuestions;
    }

    public List<String> confirmQuestions() {
        return confirmQuestions;
    }

    public void reset() {
        announcedBatches.clear();
        reviewed.clear();
        overwriteQuestions.clear();
        confirmQuestions.clear();
        answerAlways(ApprovalAnswer.REJECT);
        allowOverwrite(false);
        confirmWith(false);
    }

    @Override
    public void announceBatch(List<ChangeRequest> changes) {
        announcedBatches.add(changes);
    }

    @Override
    public ApprovalAnswer review(ChangeRequest change) {
        reviewed.add(change);
        return answerRule.apply(change);
    }

    @Override
    public boolean approveOverwrite(ChangeRequest change) {
        overwriteQuestions.add(change);
        return overwriteRule.test(change);
    }

    @Override
    public boolean confirm(String question, List<String> details) {
        confirmQuestions.add(question);
        return confirmAnswer;
    }
}

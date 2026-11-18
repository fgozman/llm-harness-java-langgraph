package com.example.organizer.agent;

import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.DisplayText;
import com.example.organizer.agent.ConversationMessage.AssistantTurn;

/**
 * Graph step: writes a human readable summary of what the agent did.
 * File names and the model's text are untrusted, so they are made safe for the console.
 *
 * The last step before END. The report is stored in the state (REPORT) and not printed here: the
 * final state returned by {@code graph.invoke} carries it to OrganizerRunner, which prints it.
 */
@Component
public class ReportNode implements NodeAction<OrganizerState> {

    public static final String NAME = "report";

    private static final Logger log = LoggerFactory.getLogger(ReportNode.class);

    @Override
    public Map<String, Object> apply(OrganizerState state) {
        log.debug("Step '{}' started", NAME);
        StringBuilder report = new StringBuilder();
        report.append("Organized folder: ").append(DisplayText.line(state.targetFolder().toString())).append('\n');

        state.stopReason().ifPresent(reason -> report.append(DisplayText.line(reason)).append('\n'));
        // The model's closing words, when it finished normally.
        state.lastAssistantTurn()
                .filter(turn -> !turn.hasToolCalls())
                .map(AssistantTurn::text)
                .filter(text -> !text.isBlank())
                .ifPresent(text -> report.append("Agent: ").append(DisplayText.block(text.strip())).append('\n'));

        if (state.appliedChanges().isEmpty() && state.rejectedChanges().isEmpty() && state.failedChanges().isEmpty()) {
            report.append("No changes were made.\n");
        }

        // Empty sections are left out to keep the report short.
        appendSection(report, "Applied", state.appliedChanges());
        appendSection(report, "Rejected", state.rejectedChanges());
        appendSection(report, "Failed", state.failedChanges());
        appendSection(report, "Notes", state.notices());

        report.append("Model usage: ").append(state.modelCalls()).append(" calls, ")
                .append(state.inputTokens()).append(" input + ")
                .append(state.outputTokens()).append(" output tokens\n");

        return Map.of(OrganizerState.REPORT, report.toString());
    }

    private void appendSection(StringBuilder report, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        report.append(title).append(" (").append(lines.size()).append("):\n");
        for (String line : lines) {
            report.append("  - ").append(DisplayText.line(line)).append('\n');
        }
    }
}

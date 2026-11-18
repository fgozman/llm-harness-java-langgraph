package com.example.organizer.agent;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.agent.ConversationMessage.ToolResult;
import com.example.organizer.approval.ToolCallReviewer.ToolDecision;
import com.example.organizer.tools.ToolCall;
import com.example.organizer.tools.ToolOutcome;
import com.example.organizer.tools.ToolRegistry;
import com.example.organizer.tools.ToolRegistry.CheckedCall;

/**
 * Graph step: executes the tool calls of the latest model answer.
 *
 * Human approval: before it runs, LangGraph4j asks {@link #interrupt} whether to pause. The step pauses
 * only when a call would change files and has no decision yet; batches that only read run directly.
 * The agent then asks the human, stores the decisions and resumes; on resume every changing call has
 * a decision, so the step runs.
 *
 * - Read-only calls (and calls that change nothing) run directly.
 * - Changing calls run only with an approving decision; a missing decision counts as "rejected".
 * - Every call gets a result message, because the model expects an answer for each of its calls.
 *
 * <h2>Why a dynamic interrupt ({@link InterruptibleAction})?</h2>
 * LangGraph4j can also pause statically: {@code CompileConfig.interruptBefore("execute_tools")} pauses
 * before every execution of the step. That would stop the run, and wait for the human, even for batches
 * that only read files. That is most of the batches (list_files, read_file), and the human would have
 * nothing to approve. {@link #interrupt} instead looks at the pending calls and pauses only when needed.
 *
 * LangGraph4j calls {@code interrupt} every time, right before {@code apply}, including after a resume.
 * The pause therefore ends by itself once the decisions are in the state. The same applies after a
 * crash: the decisions were cleared (OrganizerState.forgetApprovals), so the step pauses again and the
 * human is asked again.
 *
 * The node implements the async interface ({@link AsyncNodeActionWithConfig}) directly, so it can be
 * registered without the node_async adapter. The graph then sees this object, and its interrupt()
 * method, as is. The work itself is synchronous, so apply just returns an already completed future.
 */
@Component
public class ExecuteToolsNode implements AsyncNodeActionWithConfig<OrganizerState>, InterruptibleAction<OrganizerState> {

    public static final String NAME = "execute_tools";

    private static final Logger log = LoggerFactory.getLogger(ExecuteToolsNode.class);

    private final ToolRegistry tools;

    public ExecuteToolsNode(ToolRegistry tools) {
        this.tools = tools;
    }

    /**
     * Pause when at least one call would change files and the human has not decided on it yet.
     *
     * Returning an {@link InterruptionMetadata} pauses the run: LangGraph4j does not run {@link #apply},
     * keeps the latest checkpoint (next step: execute_tools), and {@code invoke} returns to OrganizerAgent
     * without a final state. Returning empty lets the step run immediately.
     * Must not change anything: it only inspects the state.
     */
    @Override
    public Optional<InterruptionMetadata<OrganizerState>> interrupt(String nodeId, OrganizerState state,
                                                                    RunnableConfig config) {
        Path folder = state.targetFolder();
        Map<String, ToolDecision> decisions = state.toolDecisions();
        boolean undecidedChange = state.pendingToolCalls().stream()
                .anyMatch(call -> !decisions.containsKey(call.id()) && tools.check(folder, call).needsApproval());
        if (undecidedChange) {
            log.debug("Pausing before '{}': changes need a human decision", NAME);
            return Optional.of(InterruptionMetadata.builder(nodeId, state)
                    .reason("Changes need human approval")
                    .build());
        }
        return Optional.empty();
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OrganizerState state, RunnableConfig config) {
        Path folder = state.targetFolder();
        Map<String, ToolDecision> decisions = state.toolDecisions();
        List<ToolCall> calls = state.pendingToolCalls();
        log.debug("Step '{}' started: {} tool calls, {} decisions", NAME, calls.size(), decisions.size());

        List<ToolResult> results = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        for (ToolCall call : calls) {
            ToolOutcome outcome = run(folder, call, decisions);
            log.debug("Tool call {} ({}) -> {}", call.id(), call.toolName(), outcome.status());

            results.add(new ToolResult(call.id(), call.toolName(), outcome.modelMessage()));
            switch (outcome.status()) {
                case APPLIED -> applied.add(outcome.reportLine());
                case REJECTED -> rejected.add(outcome.reportLine());
                case FAILED -> failed.add(outcome.reportLine());
                case DONE -> {
                    if (outcome.reportLine() != null) {
                        notices.add(outcome.reportLine());
                    }
                }
            }
        }

        if (!applied.isEmpty() || !rejected.isEmpty() || !failed.isEmpty()) {
            log.info("Tool calls: {} applied, {} rejected, {} failed", applied.size(), rejected.size(), failed.size());
        }
        // Only this batch's results are returned: the result lists have appender channels
        // (OrganizerState.SCHEMA), so LangGraph4j adds them to the earlier ones.
        // MESSAGES and TOOL_DECISIONS are replaced. The decisions are reset, so they can never be
        // applied to later tool calls.
        return completedFuture(Map.of(
                OrganizerState.MESSAGES, state.messagesWith(results),
                OrganizerState.APPLIED_CHANGES, applied,
                OrganizerState.REJECTED_CHANGES, rejected,
                OrganizerState.FAILED_CHANGES, failed,
                OrganizerState.NOTICES, notices,
                OrganizerState.TOOL_DECISIONS, Map.of()));
    }

    private ToolOutcome run(Path folder, ToolCall call, Map<String, ToolDecision> decisions) {
        CheckedCall checked = tools.check(folder, call);
        if (!checked.needsApproval()) {
            // Read-only, nothing to change, or invalid (the registry turns invalid calls into errors).
            return tools.execute(folder, checked, false);
        }
        ToolDecision decision = decisions.getOrDefault(call.id(), ToolDecision.rejected());
        if (!decision.approved()) {
            return ToolOutcome.rejected(checked.describe(), "The change was not approved.");
        }
        return tools.execute(folder, checked, decision.overwriteAllowed());
    }
}

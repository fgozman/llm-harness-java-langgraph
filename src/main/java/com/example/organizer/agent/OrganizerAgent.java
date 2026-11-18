package com.example.organizer.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.organizer.approval.HumanApprover;
import com.example.organizer.approval.ToolCallReviewer;
import com.example.organizer.journal.ChangeJournal;
import com.example.organizer.journal.FolderLocks;

/**
 * Entry point for organizing a folder.
 *
 * Runs the organizer graph. Whenever the graph pauses before executing changes, the reviewer
 * decides on them (asking the human when needed), the decisions are stored in the graph state
 * and the graph continues. Returns the final state, which contains the report.
 *
 * - Only one run per folder at a time (FolderLocks).
 * - Crash recovery: each folder has one run id. If the checkpoint store still holds an unfinished
 *   run for the folder, the human is asked whether to continue it or to discard it and start over.
 * - Every new run starts a new change journal, so the run can be undone later.
 *
 * <h2>How a run is driven with LangGraph4j</h2>
 * <pre>
 * invoke(args)   -> graph runs ... pauses before execute_tools -> returns empty
 * getState       -> read the paused state: which tool calls are pending?
 * (ask human)
 * updateState    -> save the decisions as a new checkpoint
 * invoke(resume) -> graph continues from that checkpoint ... pauses again, or reaches END -> final state
 * </pre>
 * The human is asked here, outside the graph, and not inside a node. A node that blocked on the console
 * would leave the graph in the middle of a step with no checkpoint. The pause, in contrast, is a clean
 * point that is saved: the run can wait as long as needed, and even survive a crash, without losing anything.
 *
 * {@link RunnableConfig} is how LangGraph4j identifies a run. Its thread id names the run, so all
 * checkpoints of one run belong together. A config can also point at one specific checkpoint
 * (the one returned by updateState).
 */
@Service
public class OrganizerAgent {

    private static final Logger log = LoggerFactory.getLogger(OrganizerAgent.class);

    /**
     * Runs the graph loop, and with it every node: node_async runs a node directly on the loop's thread.
     * Each invoke (the start of a run, and each resume after an approval) gets a new virtual thread,
     * named graph-0, graph-1, ... in the log. Without this, LangGraph4j creates a new pool of platform
     * threads for every invoke and never shuts it down.
     * A thread-per-task executor keeps no idle threads, so it needs no shutdown.
     */
    private static final Executor GRAPH_THREADS =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("graph-", 0).factory());

    private final CompiledGraph<OrganizerState> graph;
    private final ToolCallReviewer reviewer;
    private final HumanApprover human;
    private final CheckpointStore checkpointStore;
    private final FolderLocks locks;
    private final ChangeJournal journal;

    public OrganizerAgent(OrganizerGraphFactory graphFactory, ToolCallReviewer reviewer, HumanApprover human,
                          CheckpointStore checkpointStore, FolderLocks locks, ChangeJournal journal)
            throws GraphStateException {
        // The graph is built once and reused; runs are kept apart by their run id (see runOf).
        this.graph = graphFactory.createGraph();
        this.reviewer = reviewer;
        this.human = human;
        this.checkpointStore = checkpointStore;
        this.locks = locks;
        this.journal = journal;
    }

    /**
     * @throws FolderLocks.FolderBusyException when another run is working on the folder
     */
    public OrganizerState organize(Path targetFolder) {
        Path folder = targetFolder.toAbsolutePath().normalize();
        // Held until the run ends; released by the OS if the process dies.
        try (var lock = locks.acquire(folder)) {
            return organizeLocked(folder);
        }
    }

    private OrganizerState organizeLocked(Path folder) {
        long startedAt = System.nanoTime();
        RunnableConfig run = runOf(folder);
        String runId = run.threadId().orElse("?");

        // 1. Run the graph until it pauses for the human or finishes.
        //    invoke() returns the final state only when the graph reached END.
        //    While the graph is paused, waiting for a decision, the Optional is empty.
        Optional<OrganizerState> finalState = startOrContinue(folder, runId, run);

        // 2. Human-in-the-loop cycle: paused -> review the tool calls -> store the decisions -> continue.
        //    It repeats until the graph reaches END (one round per model answer with changes).
        while (finalState.isEmpty()) {
            RunnableConfig withDecisions = reviewAndRecordDecisions(runId, run);
            log.debug("Run {} resumed", runId);
            // GraphInput.resume() means "no new input, continue from the checkpoint the config points at".
            finalState = invokeGraph(run, GraphInput.resume(), withDecisions);
        }

        // 3. The run reached END and LangGraph4j released it (releaseThread); its checkpoints are no longer needed.
        checkpointStore.deleteCheckpointsOfFinishedRuns();

        OrganizerState result = finalState.get();
        log.info("Run {} finished in {} ms after {} model calls ({} tokens): {} applied, {} rejected, {} failed",
                runId, (System.nanoTime() - startedAt) / 1_000_000, result.modelCalls(),
                result.inputTokens() + result.outputTokens(),
                result.appliedChanges().size(), result.rejectedChanges().size(), result.failedChanges().size());
        return result;
    }

    /**
     * Continues the run a crash left behind (if the human wants to), or starts a new run.
     *
     * {@code graph.stateOf(run)} returns the latest checkpoint of the run id. A run that reached END was
     * released and is no longer found, so anything found here stopped halfway (crash, kill, or the
     * process ended while waiting for an answer).
     *
     * @return the final state, or empty when the graph paused for a human decision
     */
    private Optional<OrganizerState> startOrContinue(Path folder, String runId, RunnableConfig run) {
        Optional<StateSnapshot<OrganizerState>> unfinished = graph.stateOf(run);
        if (unfinished.isPresent()) {
            if (askToContinue(unfinished.get())) {
                log.info("Run {}: continuing the unfinished run (next step '{}')", runId, unfinished.get().nextNodeId());
                return continueUnfinishedRun(runId, run);
            }
            log.info("Run {}: discarding the unfinished run", runId);
            discard(run);
        }

        log.info("Run {} started", runId);
        journal.startNewRun(folder);
        // GraphInput.args(...) = a new run, starting at START with this initial state.
        GraphInput initialInput = GraphInput.args(OrganizerState.initialInput(folder, AgentInstructions.TASK));
        return invokeGraph(run, initialInput, run);
    }

    /** Shows what the unfinished run had done and asks whether to continue it. */
    private boolean askToContinue(StateSnapshot<OrganizerState> unfinished) {
        OrganizerState state = unfinished.state();
        List<String> details = new ArrayList<>();
        details.add("An unfinished run was found for " + state.targetFolder()
                + (unfinished.savedAt() != null ? " (last saved " + unfinished.savedAt() + ")." : "."));
        details.add("So far: " + state.modelCalls() + " model calls, " + state.appliedChanges().size()
                + " changes applied, " + state.rejectedChanges().size() + " rejected.");
        details.add("Changes already made stay in place either way.");
        return human.confirm("Continue it? (no = discard it and start a new run)", details);
    }

    /**
     * Marks the unfinished run as finished without running it further. This is the same release that
     * LangGraph4j does at END, so the next {@code stateOf} no longer finds the run.
     */
    private void discard(RunnableConfig run) {
        try {
            checkpointStore.saver().release(run);
            checkpointStore.deleteCheckpointsOfFinishedRuns();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not discard the unfinished run", exception);
        }
    }

    /**
     * Continues a run that was interrupted by a crash, from its last checkpoint.
     * Standing approvals ("this tool", "all changes") are not carried over a restart:
     * the human is asked again. ExecuteToolsNode.interrupt runs again on every resume, and its
     * decisions were just cleared, so pending changes are presented to the human again.
     */
    private Optional<OrganizerState> continueUnfinishedRun(String runId, RunnableConfig run) {
        RunnableConfig withoutApprovals = recordState(runId, run, OrganizerState.forgetApprovals());
        return invokeGraph(run, GraphInput.resume(), withoutApprovals);
    }

    /**
     * Called while the graph is paused: lets the reviewer decide on the pending tool calls and saves the
     * decisions into the run.
     *
     * @return the config that points at the checkpoint with the decisions, to resume from
     */
    private RunnableConfig reviewAndRecordDecisions(String runId, RunnableConfig run) {
        // The paused run is only in the checkpoint saver: read its latest checkpoint to find out
        // where the graph stopped and which tool calls are pending.
        StateSnapshot<OrganizerState> pausedAt = graph.getState(run);
        log.debug("Run {} paused before step '{}'", runId, pausedAt.nextNodeId());
        // Safety check: we only expect to pause at steps that need approval.
        if (!OrganizerGraphFactory.STEPS_REQUIRING_APPROVAL.contains(pausedAt.nextNodeId())) {
            log.error("Run {} paused at unexpected step '{}'", runId, pausedAt.nextNodeId());
            throw new IllegalStateException("Graph paused at an unexpected step: " + pausedAt.nextNodeId());
        }

        OrganizerState state = pausedAt.state();
        // Asks the human only for changing calls that are not covered by an earlier answer.
        ToolCallReviewer.Review review = reviewer.review(state.targetFolder(), state.pendingToolCalls(), state.approvalScope());
        return recordState(runId, run, OrganizerState.humanDecision(review.decisions(), review.scope()));
    }

    /**
     * Writes values into the paused run, as if a node had returned them.
     *
     * LangGraph4j's {@code updateState} copies the latest checkpoint, merges the values into it (using
     * OrganizerState.SCHEMA, like a node update) and saves the result as a new checkpoint. The step to run
     * next stays the same. The returned config points at the new checkpoint; resuming with it makes the
     * paused step see the new values.
     */
    private RunnableConfig recordState(String runId, RunnableConfig run, Map<String, Object> values) {
        try {
            return graph.updateState(run, values);
        } catch (Exception exception) {
            log.error("Run {}: could not update the saved state", runId, exception);
            throw new IllegalStateException("Could not update the saved state", exception);
        }
    }

    /**
     * Runs the graph until it pauses or reaches END. If it fails with an error, the run is marked
     * finished so the next start does not resume it into the same error. (A crash never reaches the
     * catch block, so crashed runs stay resumable.)
     *
     * @param run       identifies the run; used to mark it failed
     * @param input     {@code GraphInput.args(...)} for a new run, {@code GraphInput.resume()} to continue
     * @param startFrom where to start: the run itself, or a specific checkpoint returned by updateState
     * @return the final state, or empty when the graph paused for a human decision
     */
    private Optional<OrganizerState> invokeGraph(RunnableConfig run, GraphInput input, RunnableConfig startFrom) {
        try {
            return graph.invoke(input, startFrom);
        } catch (RuntimeException exception) {
            log.error("Run {} failed, it will not be resumed", run.threadId().orElse("?"), exception);
            checkpointStore.markFailed(run, exception);
            throw exception;
        }
    }

    /**
     * One run id (LangGraph4j "thread id") per folder, so a new start on the same folder finds an
     * unfinished run. (FolderLocks makes sure two processes never work on the same run at the same time.)
     */
    private RunnableConfig runOf(Path folder) {
        return RunnableConfig.builder()
                .threadId("organize:" + folder)
                // Kept by every config derived from this one (e.g. the one updateState returns for a resume).
                .emitterExecutor(GRAPH_THREADS)
                .build();
    }
}

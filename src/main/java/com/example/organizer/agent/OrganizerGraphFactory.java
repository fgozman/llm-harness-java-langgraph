package com.example.organizer.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.Map;
import java.util.Set;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.config.OrganizerProperties;


/**
 * Wires the organizer steps into a LangGraph tool-calling loop:
 *
 * <pre>
 * START -> call_model --(tool calls)--> (human) execute_tools --> call_model ...
 *                     --(no tool calls, or stopped)--> report -> END
 * </pre>
 *
 * The graph pauses at execute_tools ("(human)") whenever the model asks for changes, so they can be
 * approved or rejected first. Batches that only read run without a pause (see ExecuteToolsNode.interrupt).
 * The state is saved after every step (see CheckpointStore), so a run can continue after a crash.
 *
 * <h2>Why LangGraph4j instead of a plain while loop?</h2>
 * The loop itself ("call the model, run its tools, repeat") would be easy to write by hand. The hard
 * parts are pausing and continuing, and LangGraph4j gives us those:
 * <ul>
 *   <li><b>Pause and resume for the human.</b> A node can stop the run before it executes
 *       ({@code InterruptibleAction}). {@code invoke} then returns, the agent asks the human on the console,
 *       writes the answers into the saved state ({@code updateState}) and resumes from that point.
 *       Without a graph we would have to save the loop's local variables ourselves and jump back
 *       into the middle of the loop.</li>
 *   <li><b>Checkpoints after every step.</b> The state is saved after each node. The same mechanism
 *       that keeps the state during a pause also lets a run continue after a crash (SQLite saver).</li>
 *   <li><b>One explicit state.</b> Nodes do not share fields or call each other. Each node receives a copy
 *       of the state and returns only the keys it changes. LangGraph4j merges them using
 *       {@link OrganizerState#SCHEMA}. Every step can therefore be tested and checkpointed on its own.</li>
 *   <li><b>The flow is declared in one place</b> (this class), separate from what each step does.</li>
 * </ul>
 */
@Component
public class OrganizerGraphFactory {

    private static final Logger log = LoggerFactory.getLogger(OrganizerGraphFactory.class);

    /**
     * Steps at which the graph can pause for human approval. OrganizerAgent uses this as a safety check:
     * the graph should never stop at any other step.
     */
    public static final Set<String> STEPS_REQUIRING_APPROVAL = Set.of(ExecuteToolsNode.NAME);

    private final CallModelNode callModelNode;
    private final ExecuteToolsNode executeToolsNode;
    private final ReportNode reportNode;
    private final CheckpointStore checkpointStore;
    private final int maxModelCalls;

    public OrganizerGraphFactory(CallModelNode callModelNode,
                                 ExecuteToolsNode executeToolsNode,
                                 ReportNode reportNode,
                                 CheckpointStore checkpointStore,
                                 OrganizerProperties properties) {
        this.callModelNode = callModelNode;
        this.executeToolsNode = executeToolsNode;
        this.reportNode = reportNode;
        this.checkpointStore = checkpointStore;
        this.maxModelCalls = properties.agent().maxModelCalls();
    }

    /**
     * Builds the graph. A {@link StateGraph} is only a description (nodes and edges). {@code compile()}
     * checks it (unknown nodes, missing edges, ...) and turns it into a {@link CompiledGraph} that can run.
     * The compiled graph holds no run data, so one instance serves every run. Runs are told apart by
     * their thread id (see OrganizerAgent.runOf).
     *
     * @throws GraphStateException when the graph description is invalid (a programming error)
     */
    public CompiledGraph<OrganizerState> createGraph() throws GraphStateException {
        log.debug("Building organizer graph, human approval required before {}", STEPS_REQUIRING_APPROVAL);
        // SCHEMA: how LangGraph4j merges the values a node returns into the state (append or replace, per key).
        // SERIALIZER: how it copies the state for each node and writes it into a checkpoint. The checkpoint
        // saver uses the same serializer (CheckpointStore), so it can read saved states back.
        return new StateGraph<>(OrganizerState.SCHEMA, OrganizerState.SERIALIZER)
                // 1. Register the steps under the names that the edges below refer to.
                //    LangGraph4j runs nodes asynchronously (they return a CompletableFuture). Our nodes are
                //    plain synchronous functions, and node_async adapts them: it runs the node and wraps
                //    the result (or the exception) in a completed future.
                .addNode(CallModelNode.NAME, node_async(callModelNode))
                // ExecuteToolsNode already implements the async interface, so it is registered without an
                // adapter. It also implements InterruptibleAction: before running it, LangGraph4j calls its
                // interrupt() method, which decides whether the run must pause for the human first.
                .addNode(ExecuteToolsNode.NAME, executeToolsNode)
                .addNode(ReportNode.NAME, node_async(reportNode))

                // 2. Connect the steps. START and END are LangGraph4j's built-in entry and exit points.
                //    Every run starts by asking the model.
                .addEdge(START, CallModelNode.NAME)
                // After each model answer: run its tool calls, or finish.
                // stepAfterModel returns a label. The map turns each label into the node to run next.
                // Here the labels are simply the node names. Because the possible targets are declared,
                // compile() can check that they exist.
                .addConditionalEdges(CallModelNode.NAME,
                        edge_async(this::stepAfterModel),
                        Map.of(
                                ExecuteToolsNode.NAME, ExecuteToolsNode.NAME,
                                ReportNode.NAME, ReportNode.NAME))
                // Tool results always go back to the model, which decides what to do next.
                // This edge makes the graph a loop. The loop ends when the model answers without tool
                // calls, or when CallModelNode sets a stop reason (call or token limit reached, model failed).
                .addEdge(ExecuteToolsNode.NAME, CallModelNode.NAME)
                .addEdge(ReportNode.NAME, END)

                // 3. Validate the description and turn it into a runnable graph with checkpoints.
                .compile(compileConfig());
    }

    /**
     * Pausing needs a checkpoint saver. When the graph pauses, {@code invoke} returns and the run
     * continues later in a new {@code invoke} call. The state of the paused run must be kept somewhere
     * in between, and the checkpoint saver keeps it. Without a saver, LangGraph4j cannot resume a run,
     * and {@code getState}/{@code updateState} fail. With SQLite the state also survives a restart.
     */
    private CompileConfig compileConfig() {
        return CompileConfig.builder()
                .checkpointSaver(checkpointStore.saver())
                // When the run reaches END, LangGraph4j "releases" it: the run is marked finished and is
                // no longer returned by graph.stateOf(...), so the next start does not offer to resume it.
                // (true is the library default; it is set explicitly because crash recovery depends on it.)
                .releaseThread(true)
                .recursionLimit(maxGraphSteps())
                .build();
    }

    /**
     * LangGraph4j's own loop guard: one {@code invoke} fails with "Maximum number of iterations reached"
     * after this many steps (default 25). The run must instead end at our own limit,
     * organizer.agent.max-model-calls, which stops cleanly with a report. So this guard must never be
     * reached first.
     *
     * Read-only batches do not pause the graph, so in the worst case a whole run is one {@code invoke}:
     * START, then call_model + execute_tools for each model call, the call_model step that stops at the
     * limit, report, and two steps to finish at END. That is 2 x max-model-calls + 5 steps; a small margin
     * is added on top.
     */
    private int maxGraphSteps() {
        return 2 * maxModelCalls + 10;
    }

    /**
     * Routing decision after a model call: execute its tool calls, or write the report.
     * A stop reason (limit reached, model failed) always leads to the report, whatever the conversation holds.
     */
    private String stepAfterModel(OrganizerState state) {
        boolean hasToolCalls = state.stopReason().isEmpty() && !state.pendingToolCalls().isEmpty();
        String next = hasToolCalls ? ExecuteToolsNode.NAME : ReportNode.NAME;
        log.debug("After model call: {} tool calls, next step '{}'", state.pendingToolCalls().size(), next);
        return next;
    }
}

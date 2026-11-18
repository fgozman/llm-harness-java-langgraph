package com.example.organizer.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.example.organizer.approval.HumanApprover;
import com.example.organizer.approval.ToolCallReviewer;
import com.example.organizer.config.OrganizerProperties;
import com.example.organizer.journal.ChangeJournal;
import com.example.organizer.journal.FolderLocks;
import com.example.organizer.tools.CreateDirectoryTool;
import com.example.organizer.tools.ListFilesTool;
import com.example.organizer.tools.MoveFileTool;
import com.example.organizer.tools.ReadFileTool;
import com.example.organizer.tools.ToolRegistry;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Builds the agent by hand (without Spring), like one start of the application.
 * Two instances on the same data directory behave like two starts of the application.
 */
final class WiredAgent {

    private WiredAgent() {
    }

    static OrganizerProperties properties(Path data) {
        return new OrganizerProperties(Path.of("unused"), false, false, 4000, 100, data,
                new OrganizerProperties.AgentProperties(50, 1_000_000),
                new OrganizerProperties.CheckpointProperties(true, data.resolve("checkpoints.db")),
                new OrganizerProperties.LlmProperties(false, null, "", "", Duration.ofSeconds(1), 0, 100));
    }

    static OrganizerAgent create(OrganizerProperties properties, ChatModel model, HumanApprover human) throws Exception {
        var journal = new ChangeJournal(properties);
        var tools = new ToolRegistry(List.of(
                new ListFilesTool(properties),
                new ReadFileTool(properties),
                new CreateDirectoryTool(journal),
                new MoveFileTool(journal)));

        var store = new CheckpointStore(properties);
        var graph = new OrganizerGraphFactory(
                new CallModelNode(model, tools, properties),
                new ExecuteToolsNode(tools),
                new ReportNode(),
                store,
                properties);
        return new OrganizerAgent(graph, new ToolCallReviewer(tools, human), human,
                store, new FolderLocks(properties), journal);
    }
}

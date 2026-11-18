package com.example.organizer.tools;

import java.nio.file.Path;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * A tool the model can call to look at or change the organized folder.
 *
 * Every call goes through two phases:
 * 1. {@link #preview}: check the arguments and describe what would happen, without changing anything.
 *    The preview is shown to the human when approval is needed.
 * 2. {@link #execute}: do it. Called only after the human approved (for tools that change files).
 *
 * All file names are relative to the organized folder; tools never accept absolute paths.
 */
public interface FileTool {

    /** Name the model uses to call the tool, e.g. "move_file". */
    String name();

    /**
     * Name, description and parameters (a JSON schema), sent to the model so it knows how to call the tool.
     * LangChain4j turns it into the provider's tool format. The model never runs anything itself:
     * it only answers with a tool call (name + JSON arguments), and the agent decides whether and how to run it.
     */
    ToolSpecification specification();

    /** True when the tool can change the filesystem; such calls need human approval. */
    boolean changesFiles();

    /**
     * @throws ToolArgumentException when the arguments are missing or not acceptable
     */
    ToolPreview preview(Path folder, ToolArguments arguments);

    /**
     * @param overwriteAllowed whether the human allowed replacing an existing file
     * @throws ToolArgumentException when the arguments are missing or not acceptable
     */
    ToolOutcome execute(Path folder, ToolArguments arguments, boolean overwriteAllowed) throws Exception;
}

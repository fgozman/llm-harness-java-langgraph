package com.example.organizer.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * All tools the model may use, and the single place where tool calls are checked and run.
 * Calls to unknown tools, invalid arguments and errors never escape: they become a
 * {@link CheckedCall} with an error, or a FAILED {@link ToolOutcome} that is reported to the model.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, FileTool> toolsByName = new LinkedHashMap<>();

    /** Spring passes every FileTool bean; new tools are picked up without changing this class. */
    public ToolRegistry(List<FileTool> tools) {
        tools.forEach(tool -> toolsByName.put(tool.name(), tool));
        log.debug("Tools available to the model: {}", toolsByName.keySet());
    }

    public List<ToolSpecification> specifications() {
        return toolsByName.values().stream().map(FileTool::specification).toList();
    }

    /**
     * Validates a call and describes what it would do, without changing anything.
     */
    public CheckedCall check(Path folder, ToolCall call) {
        FileTool tool = toolsByName.get(call.toolName());
        if (tool == null) {
            return CheckedCall.invalid(call, "Unknown tool '" + call.toolName() + "'");
        }
        try {
            ToolPreview preview = tool.preview(folder, ToolArguments.parse(call.argumentsJson()));
            return new CheckedCall(call, tool, preview, null);
        } catch (ToolArgumentException exception) {
            return CheckedCall.invalid(call, exception.getMessage());
        }
    }

    /**
     * Runs a call. The caller is responsible for having the human's approval for changing tools.
     */
    public ToolOutcome execute(Path folder, CheckedCall checked, boolean overwriteAllowed) {
        if (!checked.isValid()) {
            return ToolOutcome.failed(checked.describe(), checked.error());
        }
        try {
            return checked.tool().execute(folder, ToolArguments.parse(checked.call().argumentsJson()), overwriteAllowed);
        } catch (ToolArgumentException exception) {
            return ToolOutcome.failed(checked.describe(), exception.getMessage());
        } catch (IOException exception) {
            log.warn("Tool call {} failed: {}", checked.describe(), exception.toString());
            return ToolOutcome.failed(checked.describe(), exception.getClass().getSimpleName());
        } catch (Exception exception) {
            log.error("Tool call {} failed unexpectedly", checked.describe(), exception);
            return ToolOutcome.failed(checked.describe(), "unexpected error");
        }
    }

    /**
     * A tool call after validation.
     *
     * @param tool    the tool, or null when it does not exist
     * @param preview what the call would do, or null when the call is invalid
     * @param error   why the call is invalid, or null
     */
    public record CheckedCall(ToolCall call, FileTool tool, ToolPreview preview, String error) {

        static CheckedCall invalid(ToolCall call, String error) {
            return new CheckedCall(call, null, null, error);
        }

        public boolean isValid() {
            return error == null;
        }

        /** True when the call is valid and would change the filesystem. */
        public boolean needsApproval() {
            return isValid() && tool.changesFiles() && preview.changesFiles();
        }

        public String describe() {
            return isValid() ? preview.description() : call.toolName() + " " + call.argumentsJson();
        }
    }
}

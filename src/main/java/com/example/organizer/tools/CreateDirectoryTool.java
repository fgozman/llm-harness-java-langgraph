package com.example.organizer.tools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.example.organizer.journal.ChangeJournal;
import com.example.organizer.journal.ChangeJournal.JournalEntry;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Changing tool: creates a category directory directly inside the folder.
 * The name is sanitized ("Personal Finance" becomes "personal-finance"), so the model
 * cannot create paths outside the folder.
 */
@Component
public class CreateDirectoryTool implements FileTool {

    public static final String NAME = "create_directory";
    public static final String DIRECTORY = "name";

    private final ChangeJournal journal;

    public CreateDirectoryTool(ChangeJournal journal) {
        this.journal = journal;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(NAME)
                .description("Creates a category directory in the folder. Use short, lower-case English names.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty(DIRECTORY, "Category name, e.g. finance")
                        .required(DIRECTORY)
                        .build())
                .build();
    }

    @Override
    public boolean changesFiles() {
        return true;
    }

    @Override
    public ToolPreview preview(Path folder, ToolArguments arguments) {
        Path directory = directory(folder, arguments);
        String name = directory.getFileName().toString();
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            // Nothing to change, so no approval is needed.
            return ToolPreview.noChange("Directory " + name + "/ already exists");
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolArgumentException("'" + name + "' already exists and is not a directory");
        }
        return ToolPreview.change("Create directory " + name + "/");
    }

    @Override
    public ToolOutcome execute(Path folder, ToolArguments arguments, boolean overwriteAllowed) throws Exception {
        // Check again: the folder may have changed since the human approved.
        ToolPreview preview = preview(folder, arguments);
        if (!preview.changesFiles()) {
            return ToolOutcome.done(preview.description());
        }
        // createDirectory (not createDirectories) fails if something appeared in the meantime.
        Path directory = Files.createDirectory(directory(folder, arguments));
        // Recorded after creating: if it fails, there is nothing to undo.
        journal.record(folder, JournalEntry.directory(directory.getFileName().toString()));
        return ToolOutcome.applied(preview.description());
    }

    private Path directory(Path folder, ToolArguments arguments) {
        return SafePaths.childOf(folder, SafePaths.directoryName(arguments.requireText(DIRECTORY)));
    }
}

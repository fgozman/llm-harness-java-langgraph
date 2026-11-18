package com.example.organizer.tools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.journal.ChangeJournal;
import com.example.organizer.journal.ChangeJournal.JournalEntry;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Changing tool: moves a file of the folder into one of its directories.
 * When a file with the same name is already in the directory, the move overwrites it,
 * which needs an extra approval (unless the human approved all changes).
 * An overwritten file is not lost: it is moved to the backup directory first, so the run can be undone.
 */
@Component
public class MoveFileTool implements FileTool {

    public static final String NAME = "move_file";
    public static final String FILE_NAME = "fileName";
    public static final String DIRECTORY = "directory";

    private static final Logger log = LoggerFactory.getLogger(MoveFileTool.class);

    private final ChangeJournal journal;

    public MoveFileTool(ChangeJournal journal) {
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
                .description("Moves a file into an existing category directory (create it first with create_directory).")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty(FILE_NAME, "Name of a file returned by list_files, e.g. trip.txt")
                        .addStringProperty(DIRECTORY, "Name of the category directory, e.g. travel")
                        .required(FILE_NAME, DIRECTORY)
                        .build())
                .build();
    }

    @Override
    public boolean changesFiles() {
        return true;
    }

    @Override
    public ToolPreview preview(Path folder, ToolArguments arguments) {
        Move move = resolve(folder, arguments);
        if (Files.exists(move.destination(), LinkOption.NOFOLLOW_LINKS)) {
            return ToolPreview.overwrite(move.description(), move.relativeDestination());
        }
        return ToolPreview.change(move.description());
    }

    @Override
    public ToolOutcome execute(Path folder, ToolArguments arguments, boolean overwriteAllowed) throws Exception {
        // Check again right before moving: the folder may have changed since the human approved.
        Move move = resolve(folder, arguments);
        boolean overwrites = Files.exists(move.destination(), LinkOption.NOFOLLOW_LINKS);

        if (overwrites && !overwriteAllowed) {
            return ToolOutcome.rejected(move.description(),
                    "Overwriting " + move.relativeDestination() + " was not approved.");
        }

        String relativeSource = move.source().getFileName().toString();
        if (overwrites) {
            // Keep the replaced file: back it up first, recording it in the journal before anything moves,
            // so even a crash in between leaves enough information to undo.
            Path backup = journal.backUp(folder, move.destination());
            journal.record(folder, JournalEntry.move(relativeSource, move.relativeDestination(), backup.toString()));
        } else {
            journal.record(folder, JournalEntry.move(relativeSource, move.relativeDestination(), null));
        }
        // The destination is free now (backed up or never existed). Without REPLACE_EXISTING the move fails
        // if a file appears there meanwhile, so nothing is ever replaced without approval.
        log.debug("Moving {} -> {}", move.source(), move.destination());
        Files.move(move.source(), move.destination());
        return ToolOutcome.applied(overwrites
                ? move.description() + " (replaced the existing file, backup kept)"
                : move.description());
    }

    /**
     * Validates the arguments and works out source and destination.
     * Only regular files directly in the folder can be moved, only into existing directories.
     */
    private Move resolve(Path folder, ToolArguments arguments) {
        String fileName = arguments.requireText(FILE_NAME);
        Path source = SafePaths.existingFile(folder, fileName);

        String directoryName = SafePaths.directoryName(arguments.requireText(DIRECTORY));
        Path directory = SafePaths.childOf(folder, directoryName);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolArgumentException("Directory '" + directoryName
                    + "' does not exist. Create it first with " + CreateDirectoryTool.NAME + ".");
        }

        Path destination = directory.resolve(source.getFileName());
        if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolArgumentException("'" + directoryName + "/" + source.getFileName() + "' is a directory");
        }
        return new Move(source, destination, directoryName + "/" + source.getFileName());
    }

    private record Move(Path source, Path destination, String relativeDestination) {

        String description() {
            return "Move " + source.getFileName() + " -> " + relativeDestination;
        }
    }
}

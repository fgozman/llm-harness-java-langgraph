package com.example.organizer.journal;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.approval.HumanApprover;
import com.example.organizer.journal.ChangeJournal.JournalEntry;
import com.example.organizer.tools.SafePaths;

/**
 * Undoes the last run on a folder, using its change journal, newest change first:
 * moved files go back, overwritten files are restored from their backup, created directories
 * are removed when empty. Nothing is overwritten during an undo: when a path is taken,
 * that step is skipped and reported.
 */
@Component
public class UndoService {

    private static final Logger log = LoggerFactory.getLogger(UndoService.class);

    private final ChangeJournal journal;
    private final FolderLocks locks;
    private final HumanApprover human;

    public UndoService(ChangeJournal journal, FolderLocks locks, HumanApprover human) {
        this.journal = journal;
        this.locks = locks;
        this.human = human;
    }

    /**
     * @return one line per step, for the console
     */
    public List<String> undoLastRun(Path targetFolder) {
        Path folder = targetFolder.toAbsolutePath().normalize();
        // No run may change the folder while it is being restored.
        try (var lock = locks.acquire(folder)) {
            List<JournalEntry> entries = new ArrayList<>(journal.entries(folder));
            if (entries.isEmpty()) {
                return List.of("Nothing to undo for " + folder + ".");
            }
            List<JournalEntry> newestFirst = entries.reversed();
            List<String> plan = newestFirst.stream().map(UndoService::describe).toList();
            if (!human.confirm("Undo these " + plan.size() + " changes in " + folder + "?", plan)) {
                return List.of("Undo cancelled, nothing was changed.");
            }

            List<String> results = new ArrayList<>();
            for (JournalEntry entry : newestFirst) {
                try {
                    results.add(undo(folder, entry));
                } catch (IOException | IllegalArgumentException exception) {
                    log.warn("Could not undo {}: {}", entry, exception.toString());
                    results.add("Failed: " + describe(entry) + " (" + exception.getMessage() + ")");
                }
            }
            journal.markUndone(folder);
            log.info("Undo of the last run on {} finished", folder);
            return results;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not finish the undo", exception);
        }
    }

    private String undo(Path folder, JournalEntry entry) throws IOException {
        return switch (entry.type()) {
            case MOVE -> undoMove(folder, entry);
            case DIRECTORY -> undoDirectory(folder, entry);
        };
    }

    private String undoMove(Path folder, JournalEntry entry) throws IOException {
        // The journal is our own file, but paths are still checked to stay inside the folder.
        Path from = SafePaths.insideFolder(folder, entry.from());
        Path to = SafePaths.insideFolder(folder, entry.to());
        StringBuilder result = new StringBuilder();

        // The journal entry is written before the move, so the move may not have happened (crash).
        if (exists(to) && !exists(from)) {
            Files.move(to, from);
            result.append("Moved back ").append(entry.to()).append(" -> ").append(entry.from());
        } else if (exists(from) && !exists(to)) {
            result.append("Not moved, nothing to do: ").append(entry.from());
        } else {
            result.append("Skipped ").append(entry.to()).append(": ").append(entry.from()).append(" already exists");
        }

        if (entry.backup() != null) {
            Path backup = Path.of(entry.backup());
            if (exists(backup) && !exists(to)) {
                Files.move(backup, to);
                result.append("; restored the overwritten ").append(entry.to());
            } else if (exists(backup)) {
                result.append("; could not restore the overwritten ").append(entry.to())
                        .append(" (path taken), backup kept at ").append(backup);
            }
        }
        return result.toString();
    }

    private String undoDirectory(Path folder, JournalEntry entry) throws IOException {
        Path directory = SafePaths.insideFolder(folder, entry.to());
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return "Directory " + entry.to() + "/ is already gone";
        }
        try {
            Files.delete(directory);
            return "Removed directory " + entry.to() + "/";
        } catch (DirectoryNotEmptyException exception) {
            return "Kept directory " + entry.to() + "/ (not empty)";
        }
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static String describe(JournalEntry entry) {
        return switch (entry.type()) {
            case MOVE -> "Move " + entry.to() + " back to " + entry.from()
                    + (entry.backup() != null ? " and restore the overwritten file" : "");
            case DIRECTORY -> "Remove directory " + entry.to() + "/ if empty";
        };
    }
}

package com.example.organizer.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.config.OrganizerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Records every change of the last run on a folder, so the run can be undone.
 *
 * Layout in the data directory (owner-only access):
 * <pre>
 * journal/&lt;folder key&gt;.jsonl    one JSON line per change, in the order they happened
 * backups/&lt;folder key&gt;/...      files that were replaced by an overwrite
 * </pre>
 * Only the last run is kept: starting a new run deletes the previous journal and backups.
 */
@Component
public class ChangeJournal {

    private static final Logger log = LoggerFactory.getLogger(ChangeJournal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path journalDirectory;
    private final Path backupDirectory;

    public ChangeJournal(OrganizerProperties properties) {
        Path data = properties.dataDirectory().toAbsolutePath().normalize();
        this.journalDirectory = data.resolve("journal");
        this.backupDirectory = data.resolve("backups");
    }

    /** Called when a new run starts on the folder: forgets the previous run (journal and backups). */
    public void startNewRun(Path folder) {
        try {
            Files.deleteIfExists(journalFile(folder));
            deleteRecursively(backupDirectory.resolve(DataFiles.folderKey(folder)));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not reset the change journal", exception);
        }
    }

    /** Appends an entry. Written to disk immediately, so it survives a crash. */
    public void record(Path folder, JournalEntry entry) {
        try {
            DataFiles.privateDirectory(journalDirectory);
            Path file = journalFile(folder);
            Files.writeString(file, JSON.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
            DataFiles.privateFile(file);
            log.debug("Journal: {}", entry);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write the change journal", exception);
        }
    }

    /**
     * Moves a file that is about to be overwritten into the backup directory.
     *
     * @return absolute path of the backup
     */
    public Path backUp(Path folder, Path file) throws IOException {
        // Both levels owner-only (createDirectories would create the parent with default permissions).
        DataFiles.privateDirectory(backupDirectory);
        Path directory = DataFiles.privateDirectory(backupDirectory.resolve(DataFiles.folderKey(folder)));
        // Unique name: the same file name may be backed up more than once in a run.
        Path backup = directory.resolve(System.nanoTime() + "-" + file.getFileName());
        // A plain move: also works when the data directory is on another disk (copy + delete).
        Files.move(file, backup);
        return backup;
    }

    /** The changes of the last run on the folder, in the order they happened. */
    public List<JournalEntry> entries(Path folder) {
        Path file = journalFile(folder);
        if (!Files.exists(file)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(file)) {
            List<JournalEntry> entries = new ArrayList<>();
            for (String line : lines.filter(line -> !line.isBlank()).toList()) {
                entries.add(JSON.readValue(line, JournalEntry.class));
            }
            return entries;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the change journal", exception);
        }
    }

    /** After an undo: the journal is kept for reference but cannot be undone a second time. */
    public void markUndone(Path folder) throws IOException {
        Path file = journalFile(folder);
        if (Files.exists(file)) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".undone"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path journalFile(Path folder) {
        return journalDirectory.resolve(DataFiles.folderKey(folder) + ".jsonl");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            // Deepest first, so directories are empty when they are deleted.
            for (Path entry : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(entry);
            }
        }
    }

    /**
     * One change recorded in the journal (one JSON line). Paths are relative to the organized folder,
     * except the backup, which lives in the data directory.
     *
     * @param type   what was done
     * @param from   MOVE: the file's original name; DIRECTORY: null
     * @param to     MOVE: where the file was moved; DIRECTORY: the created directory
     * @param backup MOVE that overwrote a file: absolute path of the backup of the replaced file, else null
     */
    public record JournalEntry(Type type, String from, String to, String backup) {

        public enum Type {
            DIRECTORY,
            MOVE
        }

        public static JournalEntry directory(String directory) {
            return new JournalEntry(Type.DIRECTORY, null, directory, null);
        }

        public static JournalEntry move(String from, String to, String backup) {
            return new JournalEntry(Type.MOVE, from, to, backup);
        }
    }
}

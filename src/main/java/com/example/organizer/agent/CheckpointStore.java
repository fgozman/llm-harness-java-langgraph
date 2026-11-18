package com.example.organizer.agent;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.SQLiteSaverV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.config.OrganizerProperties;
import com.example.organizer.journal.DataFiles;

/**
 * Where the graph saves its checkpoints (the state after every step), plus the housekeeping
 * LangGraph4j does not do on its own.
 *
 * With SQLite the checkpoints survive a crash: the next start on the same folder finds the
 * unfinished run and continues it. In memory they are lost when the application stops.
 *
 * A checkpoint saver is required in both cases: LangGraph4j can only pause a run for human approval
 * and resume it later when the paused state is kept somewhere (see OrganizerGraphFactory.compileConfig).
 * {@link MemorySaver} keeps it in a map for the life of the process. {@link SQLiteSaverV2} (from
 * langgraph4j-sqlite-saver) writes it to a file, so it also survives a crash or restart.
 *
 * LangGraph4j terms used here:
 * - checkpoint saver ({@link BaseCheckpointSaver}): stores the checkpoints of every run, grouped by
 *   thread id (our run id). The graph writes to it after each step and reads from it on resume.
 * - releasing a run: marking it finished. A released run is no longer returned as the current state of
 *   its thread id, so it is never resumed. LangGraph4j releases a run by itself when it reaches END
 *   (CompileConfig.releaseThread); this class also releases runs that end with an error.
 */
@Component
public class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private final BaseCheckpointSaver saver;
    /** The SQLite database, or null when checkpoints are kept in memory. */
    private final DataSource database;

    /** Chooses where the graph state is saved: a SQLite file (survives crashes) or memory. */
    public CheckpointStore(OrganizerProperties properties) throws Exception {
        OrganizerProperties.CheckpointProperties checkpoints = properties.checkpoints();
        if (!checkpoints.enabled()) {
            // Pausing for approvals still works within this process; only crash recovery is lost.
            log.info("Checkpoints are kept in memory only; an interrupted run cannot be resumed");
            this.saver = new MemorySaver();
            this.database = null;
            return;
        }

        Path databaseFile = checkpoints.database().toAbsolutePath().normalize();
        // The database holds file excerpts while a run is open: owner-only access.
        DataFiles.privateDirectory(databaseFile.getParent());
        log.info("Checkpoints are saved in SQLite database {}", databaseFile);

        SQLiteSaverV2 sqlite = SQLiteSaverV2.builder()
                .databasePath(databaseFile.toString())
                // Must be the same serializer the graph uses, so saved states can be read back.
                .stateSerializer(OrganizerState.SERIALIZER)
                // Creates the tables on first use ("CREATE TABLE IF NOT EXISTS"), keeps existing data.
                .createTables(true)
                .build();
        // The file exists now (createTables opened it).
        DataFiles.privateFile(databaseFile);
        this.saver = sqlite;
        this.database = sqlite.datasource();
    }

    public BaseCheckpointSaver saver() {
        return saver;
    }

    /**
     * Marks a run that failed with an error as finished, so it is not resumed on the next start.
     * (LangGraph4j releases a run automatically only when it reaches END.)
     * Runs killed by a crash never get here, so they stay resumable.
     */
    public void markFailed(RunnableConfig config, Throwable error) {
        try {
            saver.releaseOnError(config, error);
            deleteCheckpointsOfFinishedRuns();
        } catch (Exception exception) {
            log.warn("Could not mark the failed run as finished: {}", exception.toString());
        }
    }

    /**
     * When a run is released, LangGraph4j's SQLite saver records it in LG4JThreadTag and deletes the
     * run itself, but leaves its checkpoint rows behind. They contain the conversation, including file
     * excerpts, so they are deleted here. The tags (run name, time, error flag) are kept as history.
     */
    public void deleteCheckpointsOfFinishedRuns() {
        if (database == null) {
            return;
        }
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            int deleted = statement.executeUpdate(
                    "DELETE FROM LG4JCheckpoint WHERE thread_id NOT IN (SELECT thread_id FROM LG4JThread)");
            log.debug("Deleted {} checkpoints of finished runs", deleted);
        } catch (Exception exception) {
            log.warn("Could not delete the checkpoints of finished runs: {}", exception.toString());
        }
    }
}

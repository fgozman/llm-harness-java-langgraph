package com.example.organizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.organizer.agent.OrganizerAgent;
import com.example.organizer.agent.OrganizerState;
import com.example.organizer.config.OrganizerProperties;
import com.example.organizer.journal.FolderLocks.FolderBusyException;
import com.example.organizer.journal.UndoService;

/**
 * Starts the organizer agent once the application is up and prints its report.
 * When "organizer.generate-demo-files=true", the demo files are written first (setup, not part of the agent).
 * When "organizer.undo-last-run=true", the last run on the folder is undone instead.
 * Can be switched off with "organizer.run-on-startup=false" (used by tests).
 * Exit code 2 means another run was already working on the folder.
 */
@Component
@ConditionalOnProperty(name = "organizer.run-on-startup", havingValue = "true", matchIfMissing = true)
public class OrganizerRunner implements CommandLineRunner, ExitCodeGenerator {

    /** Exit code when another run is already working on the folder. */
    public static final int EXIT_FOLDER_BUSY = 2;

    private static final Logger log = LoggerFactory.getLogger(OrganizerRunner.class);

    private final OrganizerAgent agent;
    private final DemoFileGenerator demoFileGenerator;
    private final UndoService undoService;
    private final OrganizerProperties properties;
    private int exitCode = 0;

    public OrganizerRunner(OrganizerAgent agent,
                           DemoFileGenerator demoFileGenerator,
                           UndoService undoService,
                           OrganizerProperties properties) {
        this.agent = agent;
        this.demoFileGenerator = demoFileGenerator;
        this.undoService = undoService;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        try {
            runOnce();
        } catch (FolderBusyException exception) {
            // An expected situation, not a bug: a clear message instead of a stack trace.
            // Printed once for the user; the DEBUG copy goes to the log file only.
            log.debug(exception.getMessage());
            System.err.println(exception.getMessage());
            exitCode = EXIT_FOLDER_BUSY;
        }
    }

    /** Read by SpringApplication.exit in OrganizerApplication.main. */
    @Override
    public int getExitCode() {
        return exitCode;
    }

    private void runOnce() {
        log.info("Organizer starting: target folder={}, generate demo files={}, undo last run={}",
                properties.targetFolder().toAbsolutePath().normalize(), properties.generateDemoFiles(),
                properties.undoLastRun());

        if (properties.undoLastRun()) {
            System.out.println();
            undoService.undoLastRun(properties.targetFolder())
                    .forEach(line -> System.out.println(DisplayText.line(line)));
            return;
        }

        // Step 1 (optional setup): put example files into the folder.
        if (properties.generateDemoFiles()) {
            demoFileGenerator.generate(properties.targetFolder());
        }
        // Step 2: run the agent. This blocks while it waits for the human's answers on the console.
        OrganizerState result = agent.organize(properties.targetFolder());
        // Step 3: show what happened (the report is already safe for the console).
        System.out.println();
        System.out.println(result.report());
        // The report is also written to the log file, so it is kept after the console is closed.
        log.debug("Final report:\n{}", result.report());
    }
}

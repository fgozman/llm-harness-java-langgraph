package com.example.organizer.approval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.organizer.DisplayText;
import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.approval.HumanApprover.ChangeRequest;

/**
 * Asks for approval on the console.
 * When no answer can be read (for example no interactive console), the answer is "no":
 * nothing is changed without an explicit "yes".
 * Everything printed that comes from the folder or the model goes through {@link DisplayText},
 * so file names cannot inject terminal control sequences into the prompt.
 */
public class ConsoleHumanApprover implements HumanApprover {

    // Logs at DEBUG only: they go to the log file and do not disturb the dialogue on the console.
    private static final Logger log = LoggerFactory.getLogger(ConsoleHumanApprover.class);

    private final BufferedReader input;
    private final PrintStream output;

    // Input and output are injected (instead of using System.in/out directly) so tests can
    // simulate a human by passing prepared answers.
    public ConsoleHumanApprover(BufferedReader input, PrintStream output) {
        this.input = input;
        this.output = output;
    }

    @Override
    public void announceBatch(List<ChangeRequest> changes) {
        output.println();
        output.println("=== The agent wants to make " + changes.size() + " changes ===");
        for (ChangeRequest change : changes) {
            output.printf("  %2d. %s%s%n", change.position(), DisplayText.line(change.description()),
                    change.overwrites() ? "  (overwrites " + DisplayText.line(change.overwrittenPath()) + ")" : "");
        }
    }

    @Override
    public ApprovalAnswer review(ChangeRequest change) {
        String tool = DisplayText.line(change.toolName());
        output.println();
        output.println("=== Approval required (" + tool + ")"
                + (change.partOfBatch() ? " - change " + change.position() + " of " + change.batchSize() : "")
                + " ===");
        output.println("  " + DisplayText.line(change.description()));
        if (change.overwrites()) {
            output.println("  Warning: " + DisplayText.line(change.overwrittenPath())
                    + " already exists and would be replaced (a backup is kept).");
        }

        String batchOption = change.partOfBatch() ? " / [b] the rest of this batch" : "";
        // Keep asking until we get a valid answer.
        while (true) {
            String answer = ask("Approve? [y] this change" + batchOption + " / [t] all " + tool
                    + " changes / [a] all changes, overwrites included / [n] no: ");
            log.debug("Human answered '{}' for '{}'", answer, change.description());
            switch (answer) {
                case "y", "yes" -> {
                    return ApprovalAnswer.THIS_CHANGE;
                }
                case "b", "batch" -> {
                    if (change.partOfBatch()) {
                        return ApprovalAnswer.THIS_BATCH;
                    }
                    output.println("Please answer 'y', 't', 'a' or 'n'.");
                }
                case "t", "tool" -> {
                    return ApprovalAnswer.THIS_TOOL;
                }
                case "a", "all" -> {
                    return ApprovalAnswer.ALL_CHANGES;
                }
                // "" (just Enter, or no console at all) means NO: the safe default.
                case "n", "no", "" -> {
                    return ApprovalAnswer.REJECT;
                }
                default -> output.println("Please answer 'y'" + (change.partOfBatch() ? ", 'b'" : "")
                        + ", 't', 'a' or 'n'.");
            }
        }
    }

    @Override
    public boolean approveOverwrite(ChangeRequest change) {
        // Capital N in [y/N] signals the default: anything except "y"/"yes" keeps the existing file.
        String answer = ask("Overwrite " + DisplayText.line(change.overwrittenPath()) + "? [y/N]: ");
        boolean approved = answer.equals("y") || answer.equals("yes");
        log.debug("Human {} overwriting {}", approved ? "allowed" : "refused", change.overwrittenPath());
        return approved;
    }

    @Override
    public boolean confirm(String question, List<String> details) {
        output.println();
        details.forEach(line -> output.println("  " + DisplayText.line(line)));
        String answer = ask(question + " [y/N]: ");
        boolean confirmed = answer.equals("y") || answer.equals("yes");
        log.debug("Human answered '{}' to '{}'", answer, question);
        return confirmed;
    }

    /**
     * @return the trimmed, lower-case answer, or an empty string when no input is available
     */
    private String ask(String question) {
        output.print(question);
        output.flush();
        try {
            String line = input.readLine();
            // null = end of input (e.g. input piped from a file or no terminal attached).
            if (line == null) {
                log.debug("No console input available, treating it as 'no'");
                output.println();
                return "";
            }
            return line.strip().toLowerCase(Locale.ROOT);
        } catch (IOException exception) {
            log.warn("Could not read the answer from the console, treating it as 'no': {}", exception.toString());
            return "";
        }
    }
}

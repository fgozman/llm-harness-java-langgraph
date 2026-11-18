package com.example.organizer.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.approval.HumanApprover.ChangeRequest;

class ConsoleHumanApproverTest {

    private final ChangeRequest move = new ChangeRequest("move_file", "Move trip.txt -> travel/trip.txt", null);
    private final ChangeRequest overwritingMove =
            new ChangeRequest("move_file", "Move bill.txt -> finance/bill.txt", "finance/bill.txt");
    private final ChangeRequest moveInBatch =
            new ChangeRequest("move_file", "Move trip.txt -> travel/trip.txt", null, 2, 5);

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    @Test
    void understandsAllAnswers() {
        assertThat(approverAnswering("y").review(move)).isEqualTo(ApprovalAnswer.THIS_CHANGE);
        assertThat(approverAnswering("b").review(moveInBatch)).isEqualTo(ApprovalAnswer.THIS_BATCH);
        assertThat(approverAnswering("t").review(move)).isEqualTo(ApprovalAnswer.THIS_TOOL);
        assertThat(approverAnswering("a").review(move)).isEqualTo(ApprovalAnswer.ALL_CHANGES);
        assertThat(approverAnswering("n").review(move)).isEqualTo(ApprovalAnswer.REJECT);
        assertThat(console.toString()).contains("Approval required (move_file)", "Move trip.txt -> travel/trip.txt");
    }

    @Test
    void offersTheBatchAnswerOnlyInsideABatch() {
        assertThat(approverAnswering("b", "y").review(move)).isEqualTo(ApprovalAnswer.THIS_CHANGE);
        assertThat(console.toString()).doesNotContain("[b]").contains("Please answer");
    }

    @Test
    void showsTheBatchAsANumberedList() {
        approverAnswering().announceBatch(List.of(
                new ChangeRequest("create_directory", "Create directory travel/", null, 1, 2),
                new ChangeRequest("move_file", "Move bill.txt -> finance/bill.txt", "finance/bill.txt", 2, 2)));

        assertThat(console.toString()).contains("wants to make 2 changes", " 1. Create directory travel/",
                " 2. Move bill.txt -> finance/bill.txt  (overwrites finance/bill.txt)");
    }

    @Test
    void warnsAboutOverwrites() {
        approverAnswering("y").review(overwritingMove);

        assertThat(console.toString()).contains("finance/bill.txt already exists");
    }

    @Test
    void doesNotPrintTerminalControlCharactersFromFileNames() {
        var sneaky = new ChangeRequest("move_file", "Move a.txt\u001b[2K\r  Move harmless.txt -> x/", null);

        approverAnswering("n").review(sneaky);

        assertThat(console.toString()).doesNotContain("\u001b").doesNotContain("\r")
                .contains("Move a.txt\\u001B[2K\\u000D  Move harmless.txt");
    }

    @Test
    void asksAgainOnUnknownAnswers() {
        assertThat(approverAnswering("maybe", "y").review(move)).isEqualTo(ApprovalAnswer.THIS_CHANGE);
        assertThat(console.toString()).contains("Please answer");
    }

    @Test
    void overwriteAndConfirmNeedAnExplicitYes() {
        assertThat(approverAnswering("y").approveOverwrite(overwritingMove)).isTrue();
        assertThat(approverAnswering("").approveOverwrite(overwritingMove)).isFalse();
        assertThat(approverAnswering("whatever").approveOverwrite(overwritingMove)).isFalse();
        assertThat(approverAnswering("yes").confirm("Continue?", List.of("details"))).isTrue();
        assertThat(approverAnswering("").confirm("Continue?", List.of())).isFalse();
    }

    @Test
    void rejectsWhenNoInputIsAvailable() {
        assertThat(approverAnswering().review(move)).isEqualTo(ApprovalAnswer.REJECT);
        assertThat(approverAnswering().approveOverwrite(overwritingMove)).isFalse();
        assertThat(approverAnswering().confirm("Continue?", List.of())).isFalse();
    }

    private ConsoleHumanApprover approverAnswering(String... answers) {
        var input = new BufferedReader(new StringReader(String.join("\n", answers)));
        return new ConsoleHumanApprover(input, new PrintStream(console, true));
    }
}

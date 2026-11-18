package com.example.organizer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.organizer.agent.ConversationMessage.ToolResult;
import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.approval.HumanApprover.ChangeRequest;
import com.example.organizer.DemoFileGenerator;
import com.example.organizer.journal.FolderLocks;
import com.example.organizer.journal.UndoService;
import com.example.organizer.tools.CreateDirectoryTool;
import com.example.organizer.tools.MoveFileTool;
import com.example.organizer.tools.ReadFileTool;

/**
 * End-to-end runs of the tool-calling agent, driven by the offline keyword model.
 */
@SpringBootTest(properties = {"organizer.run-on-startup=false",
        "organizer.checkpoints.database=${java.io.tmpdir}/organizer-test-${random.uuid}/checkpoints.db",
        "organizer.data-directory=${java.io.tmpdir}/organizer-test-${random.uuid}"})
class OrganizerAgentTest {

    @TestConfiguration
    static class HumanInTheLoopForTests {

        @Bean
        @Primary
        ScriptedHumanApprover scriptedHumanApprover() {
            return new ScriptedHumanApprover();
        }
    }

    @Autowired
    private OrganizerAgent agent;

    @Autowired
    private ScriptedHumanApprover human;

    @Autowired
    private DemoFileGenerator demoFileGenerator;

    @Autowired
    private UndoService undoService;

    @Autowired
    private FolderLocks locks;

    @TempDir
    private Path folder;

    @BeforeEach
    void resetHuman() {
        human.reset();
    }

    @Test
    void approvingAllChangesAsksOnlyOnceAndOrganizesEverything() throws IOException {
        demoFileGenerator.generate(folder);
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);

        OrganizerState result = agent.organize(folder);

        assertThat(human.reviewed()).hasSize(1);
        assertThat(filesDirectlyIn(folder)).isZero();
        assertThat(folder.resolve("finance/document_01.txt")).exists();
        assertThat(folder.resolve("travel/trip.txt")).exists();
        assertThat(folder.resolve("uncategorized/misc.txt")).exists();
        // 7 directories + 12 moves
        assertThat(result.appliedChanges()).hasSize(19);
    }

    @Test
    void showsEachBatchFirstAndCanApproveTheRestOfABatch() throws IOException {
        demoFileGenerator.generate(folder);
        human.answerAlways(ApprovalAnswer.THIS_BATCH);

        agent.organize(folder);

        // One list per batch (directories, then moves), and one question per batch.
        assertThat(human.announcedBatches()).extracting(List::size).containsExactly(7, 12);
        assertThat(human.reviewed()).extracting(ChangeRequest::position).containsExactly(1, 1);
        assertThat(filesDirectlyIn(folder)).isZero();
    }

    @Test
    void approvingOneToolAsksOncePerToolAndRejectsNothing() throws IOException {
        demoFileGenerator.generate(folder);
        human.answerAlways(ApprovalAnswer.THIS_TOOL);

        OrganizerState result = agent.organize(folder);

        assertThat(human.reviewed()).extracting(ChangeRequest::toolName)
                .containsExactly(CreateDirectoryTool.NAME, MoveFileTool.NAME);
        assertThat(filesDirectlyIn(folder)).isZero();
        assertThat(result.rejectedChanges()).isEmpty();
    }

    @Test
    void approvingSingleChangesAsksForEachOne() {
        demoFileGenerator.generate(folder);
        human.answerAlways(ApprovalAnswer.THIS_CHANGE);

        agent.organize(folder);

        assertThat(human.reviewed()).hasSize(19);
    }

    @Test
    void changesNothingWhenTheHumanRejectsEverything() throws IOException {
        demoFileGenerator.generate(folder);

        OrganizerState result = agent.organize(folder);

        assertThat(filesDirectlyIn(folder)).isEqualTo(12);
        assertThat(directoriesIn(folder)).isEmpty();
        assertThat(result.appliedChanges()).isEmpty();
        assertThat(result.rejectedChanges()).hasSize(7);
        // The moves fail validation because their directories were never created.
        assertThat(result.failedChanges()).hasSize(12);
    }

    @Test
    void appliesOnlyTheApprovedChanges() {
        demoFileGenerator.generate(folder);
        human.answer(change -> change.description().contains("finance") ? ApprovalAnswer.THIS_CHANGE : ApprovalAnswer.REJECT);

        agent.organize(folder);

        assertThat(folder.resolve("finance/document_01.txt")).exists();
        assertThat(folder.resolve("trip.txt")).exists();
        assertThat(folder.resolve("travel")).doesNotExist();
    }

    @Test
    void asksBeforeOverwritingWhenNotAllChangesWereApproved() throws IOException {
        Files.createDirectories(folder.resolve("finance"));
        Files.writeString(folder.resolve("finance/bill.txt"), "old invoice");
        Files.writeString(folder.resolve("bill.txt"), "new invoice with payment details");
        human.answerAlways(ApprovalAnswer.THIS_TOOL);
        human.allowOverwrite(false);

        OrganizerState result = agent.organize(folder);

        assertThat(human.overwriteQuestions()).extracting(ChangeRequest::overwrittenPath)
                .containsExactly("finance/bill.txt");
        assertThat(folder.resolve("finance/bill.txt")).hasContent("old invoice");
        assertThat(folder.resolve("bill.txt")).exists();
        assertThat(result.rejectedChanges()).singleElement().asString().contains("Overwriting finance/bill.txt");
    }

    @Test
    void approvingAllChangesOverwritesWithoutAsking() throws IOException {
        Files.createDirectories(folder.resolve("finance"));
        Files.writeString(folder.resolve("finance/bill.txt"), "old invoice");
        Files.writeString(folder.resolve("bill.txt"), "new invoice with payment details");
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);

        agent.organize(folder);

        assertThat(human.overwriteQuestions()).isEmpty();
        assertThat(folder.resolve("finance/bill.txt")).hasContent("new invoice with payment details");
    }

    @Test
    void undoRestoresTheFolderIncludingOverwrittenFiles() throws IOException {
        demoFileGenerator.generate(folder);
        Files.createDirectories(folder.resolve("finance"));
        Files.writeString(folder.resolve("finance/document_01.txt"), "old version");
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);
        agent.organize(folder);
        assertThat(folder.resolve("finance/document_01.txt")).content().contains("Invoice");

        human.confirmWith(true);
        List<String> steps = undoService.undoLastRun(folder);

        assertThat(steps).hasSize(18); // 12 moves + 6 created directories (finance existed)
        assertThat(filesDirectlyIn(folder)).isEqualTo(12);
        assertThat(directoriesIn(folder)).containsExactly(folder.resolve("finance"));
        assertThat(folder.resolve("finance/document_01.txt")).hasContent("old version");
        // A second undo finds nothing.
        assertThat(undoService.undoLastRun(folder)).singleElement().asString().startsWith("Nothing to undo");
    }

    @Test
    void undoDoesNothingWithoutConfirmation() throws IOException {
        demoFileGenerator.generate(folder);
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);
        agent.organize(folder);

        human.confirmWith(false);
        assertThat(undoService.undoLastRun(folder)).singleElement().asString().contains("cancelled");
        assertThat(filesDirectlyIn(folder)).isZero();
    }

    @Test
    void sendsEveryFileContentToTheModelOnlyOnce() {
        demoFileGenerator.generate(folder);
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);

        OrganizerState result = agent.organize(folder);

        // After the run no full file content is left in the conversation, only the short notes.
        assertThat(result.messages()).filteredOn(ToolResult.class::isInstance)
                .map(ToolResult.class::cast)
                .filteredOn(r -> r.toolName().equals(ReadFileTool.NAME))
                .hasSize(12)
                .allSatisfy(r -> assertThat(r.text()).contains("removed=\"true\""));
    }

    @Test
    void refusesToRunWhileAnotherRunWorksOnTheFolder() {
        try (var lock = locks.acquire(folder)) {
            assertThatThrownBy(() -> agent.organize(folder)).isInstanceOf(FolderLocks.FolderBusyException.class);
        }
        // Released: works again.
        assertThat(agent.organize(folder).report()).contains("No changes were made");
    }

    @Test
    void doesNotAskWhenThereIsNothingToChange() {
        OrganizerState result = agent.organize(folder);

        assertThat(human.reviewed()).isEmpty();
        assertThat(human.confirmQuestions()).isEmpty();
        assertThat(result.report()).contains("No changes were made");
    }

    private long filesDirectlyIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).count();
        }
    }

    private List<Path> directoriesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isDirectory).toList();
        }
    }
}

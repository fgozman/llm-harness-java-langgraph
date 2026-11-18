package com.example.organizer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.approval.HumanApprover.ChangeRequest;
import com.example.organizer.approval.HumanApprover;
import com.example.organizer.DemoFileGenerator;
import com.example.organizer.model.OfflineKeywordChatModel;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * A run interrupted by a crash can continue on the next start, from the SQLite checkpoints.
 * Each "application" below is wired from scratch (own saver, own model), like a new JVM would be;
 * the only things they share are the database file and the data directory.
 */
class CrashRecoveryTest {

    @TempDir
    private Path temp;

    private Path folder;

    @BeforeEach
    void createFiles() {
        folder = temp.resolve("inbox");
        new DemoFileGenerator().generate(folder);
    }

    @Test
    void continuesTheInterruptedRunWhenTheHumanAgrees() throws Exception {
        crashWhileAskingForApproval();

        var human = approvingHuman();
        human.confirmWith(true);
        var secondStart = new Application(temp, human, call -> false);
        OrganizerState result = secondStart.agent.organize(folder);

        // Only the remaining model calls were made: move_file, then the final summary.
        assertThat(human.confirmQuestions()).singleElement().asString().startsWith("Continue it?");
        assertThat(secondStart.modelCalls.get()).isEqualTo(2);
        assertThat(result.modelCalls()).isEqualTo(5);
        // Standing approvals do not survive a restart: the human was asked again.
        assertThat(human.reviewed()).hasSize(1);
        assertThat(folder.resolve("finance/document_01.txt")).exists();
        assertThat(result.appliedChanges()).hasSize(19);

        // The finished run is not resumed again, and its checkpoints (with file excerpts) are gone.
        assertThat(countRows("LG4JThread")).isZero();
        assertThat(countRows("LG4JCheckpoint")).isZero();
    }

    @Test
    void discardsTheInterruptedRunWhenTheHumanSaysNo() throws Exception {
        crashWhileAskingForApproval();

        var human = approvingHuman();
        human.confirmWith(false);
        var secondStart = new Application(temp, human, call -> false);
        OrganizerState result = secondStart.agent.organize(folder);

        // A fresh run: all 5 model calls again.
        assertThat(secondStart.modelCalls.get()).isEqualTo(5);
        assertThat(result.appliedChanges()).hasSize(19);
        assertThat(countRows("LG4JThread")).isZero();
    }

    @Test
    void aRunThatFailsWithAnErrorIsNotResumed() throws Exception {
        // The model breaks with an Error (not caught by the node) on its second call.
        var failingStart = new Application(temp, approvingHuman(), call -> call == 2);
        assertThatThrownBy(() -> failingStart.agent.organize(folder)).isNotNull();

        var human = approvingHuman();
        OrganizerState result = new Application(temp, human, call -> false).agent.organize(folder);

        assertThat(human.confirmQuestions()).isEmpty();
        assertThat(result.appliedChanges()).hasSize(19);
    }

    /** First start: the application "crashes" while the human is asked about the first changes. */
    private void crashWhileAskingForApproval() throws Exception {
        HumanApprover crashingHuman = new HumanApprover() {
            @Override
            public void announceBatch(List<ChangeRequest> changes) {
                throw new IllegalStateException("simulated crash");
            }

            @Override
            public ApprovalAnswer review(ChangeRequest change) {
                throw new IllegalStateException("simulated crash");
            }

            @Override
            public boolean approveOverwrite(ChangeRequest change) {
                return false;
            }

            @Override
            public boolean confirm(String question, List<String> details) {
                return false;
            }
        };
        var firstStart = new Application(temp, crashingHuman, call -> false);
        assertThatThrownBy(() -> firstStart.agent.organize(folder)).hasMessageContaining("simulated crash");
        // list_files, read_file, create_directory were requested before the crash.
        assertThat(firstStart.modelCalls.get()).isEqualTo(3);
        assertThat(countRows("LG4JThread")).isEqualTo(1);
    }

    private static ScriptedHumanApprover approvingHuman() {
        var human = new ScriptedHumanApprover();
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);
        return human;
    }

    private long countRows(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("data/checkpoints.db"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rows.getLong(1);
        }
    }

    /** One "start" of the application: the agent wired by hand, with a model that counts its calls. */
    private static class Application {

        final AtomicInteger modelCalls = new AtomicInteger();
        final OrganizerAgent agent;

        /**
         * @param failOnCall which model call (1-based) breaks with an Error
         */
        Application(Path temp, HumanApprover human, IntPredicate failOnCall) throws Exception {
            ChatModel offline = new OfflineKeywordChatModel();
            ChatModel countingModel = new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    if (failOnCall.test(modelCalls.incrementAndGet())) {
                        throw new Error("model broke");
                    }
                    return offline.chat(request);
                }
            };
            this.agent = WiredAgent.create(WiredAgent.properties(temp.resolve("data")), countingModel, human);
        }
    }
}

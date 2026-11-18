package com.example.organizer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.model.OfflineKeywordChatModel;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * The graph loop, and with it every node, runs on virtual threads: at the start of a run and on
 * every resume after an approval.
 */
class VirtualThreadsTest {

    @TempDir
    private Path tmp;

    @Test
    void runsTheGraphOnVirtualThreads() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("inbox"));
        Files.writeString(folder.resolve("trip.txt"), "Flight and hotel reservation for the vacation.");
        Files.writeString(folder.resolve("bill.txt"), "Invoice: please send the payment to our bank account.");

        // The model is called from CallModelNode, so it sees the thread the graph runs on.
        List<Thread> modelCallThreads = new CopyOnWriteArrayList<>();
        ChatModel offline = new OfflineKeywordChatModel();
        ChatModel recordingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                modelCallThreads.add(Thread.currentThread());
                return offline.chat(request);
            }
        };
        var human = new ScriptedHumanApprover();
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);

        OrganizerState result = WiredAgent.create(WiredAgent.properties(tmp.resolve("data")), recordingModel, human)
                .organize(folder);

        assertThat(result.appliedChanges()).isNotEmpty();
        assertThat(modelCallThreads).isNotEmpty().allSatisfy(thread -> {
            assertThat(thread.isVirtual()).as("virtual: %s", thread).isTrue();
            assertThat(thread.getName()).startsWith("graph-");
        });
        // The run paused for approvals and was resumed, each time on a new virtual thread.
        assertThat(modelCallThreads.stream().distinct()).hasSizeGreaterThan(1);
    }
}

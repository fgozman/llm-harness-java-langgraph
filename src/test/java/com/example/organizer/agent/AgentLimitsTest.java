package com.example.organizer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.organizer.DemoFileGenerator;
import com.example.organizer.model.OfflineKeywordChatModel;
import com.example.organizer.tools.ListFilesTool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

/**
 * The run stops safely when a limit is reached or the model fails.
 */
class AgentLimitsTest {

    private static final String DATA = "organizer.data-directory=${java.io.tmpdir}/organizer-test-${random.uuid}";
    private static final String DATABASE =
            "organizer.checkpoints.database=${java.io.tmpdir}/organizer-test-${random.uuid}/checkpoints.db";

    // No test here reaches an approval: every run stops (or fails) before the first change.

    @Nested
    @SpringBootTest(properties = {"organizer.run-on-startup=false", "organizer.agent.max-model-calls=2", DATA, DATABASE})
    class ModelCallLimit {

        @Autowired
        private OrganizerAgent agent;

        @Autowired
        private DemoFileGenerator demoFileGenerator;

        @TempDir
        private Path folder;

        @Test
        void stopsAfterTheConfiguredNumberOfModelCalls() {
            demoFileGenerator.generate(folder);

            OrganizerState result = agent.organize(folder);

            // Call 1 lists, call 2 reads; the third call is not made.
            assertThat(result.modelCalls()).isEqualTo(2);
            assertThat(result.appliedChanges()).isEmpty();
            assertThat(result.report()).contains("Stopped after 2 model calls");
        }
    }

    @Nested
    @SpringBootTest(properties = {"organizer.run-on-startup=false", "organizer.agent.max-tokens-per-run=1500", DATA, DATABASE})
    class TokenBudget {

        /** The offline model, reporting 1000 tokens per call like a real provider would. */
        @TestConfiguration
        static class ExpensiveModel {
            @Bean
            @Primary
            ChatModel expensiveModel() {
                ChatModel offline = new OfflineKeywordChatModel();
                return new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        return ChatResponse.builder()
                                .aiMessage(offline.chat(request).aiMessage())
                                .tokenUsage(new TokenUsage(800, 200))
                                .build();
                    }
                };
            }
        }

        @Autowired
        private OrganizerAgent agent;

        @Autowired
        private DemoFileGenerator demoFileGenerator;

        @TempDir
        private Path folder;

        @Test
        void stopsWhenTheTokenBudgetIsUsedUp() {
            demoFileGenerator.generate(folder);

            OrganizerState result = agent.organize(folder);

            // 1000 tokens after call 1, 2000 after call 2: the budget of 1500 stops call 3.
            assertThat(result.modelCalls()).isEqualTo(2);
            assertThat(result.report()).contains("Stopped after using 2000 tokens", "1600 input + 400 output tokens");
        }
    }

    @Nested
    @SpringBootTest(properties = {"organizer.run-on-startup=false", "organizer.agent.max-model-calls=50", DATA, DATABASE})
    class LongRunWithoutPauses {

        /** A model that never finishes: every answer asks for list_files again. */
        @TestConfiguration
        static class EndlessModel {
            @Bean
            @Primary
            ChatModel endlessModel() {
                AtomicInteger calls = new AtomicInteger();
                return new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        ToolExecutionRequest listFiles = ToolExecutionRequest.builder()
                                .id("call-" + calls.incrementAndGet())
                                .name(ListFilesTool.NAME)
                                .arguments("{}")
                                .build();
                        return ChatResponse.builder().aiMessage(AiMessage.from(listFiles)).build();
                    }
                };
            }
        }

        @Autowired
        private OrganizerAgent agent;

        @TempDir
        private Path folder;

        @Test
        void reachesTheModelCallLimitInsteadOfTheGraphStepLimit() {
            // Read-only calls never pause the graph, so all 50 rounds run in one graph invocation.
            // LangGraph4j's own step limit must not end the run (with an error) before our limit does.
            OrganizerState result = agent.organize(folder);

            assertThat(result.modelCalls()).isEqualTo(50);
            assertThat(result.report()).contains("Stopped after 50 model calls");
        }
    }

    @Nested
    @SpringBootTest(properties = {"organizer.run-on-startup=false", DATA, DATABASE})
    class FailingModel {

        @TestConfiguration
        static class BrokenModel {
            @Bean
            @Primary
            ChatModel failingModel() {
                return new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        throw new RuntimeException("timeout");
                    }
                };
            }
        }

        @Autowired
        private OrganizerAgent agent;

        @TempDir
        private Path folder;

        @Test
        void stopsAndReportsWhenTheModelFails() {
            OrganizerState result = agent.organize(folder);

            assertThat(result.report()).contains("Stopped because the model call failed", "timeout");
        }
    }
}

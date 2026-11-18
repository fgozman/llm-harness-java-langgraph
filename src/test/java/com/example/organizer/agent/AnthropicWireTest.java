package com.example.organizer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.organizer.approval.HumanApprover.ApprovalAnswer;
import com.example.organizer.config.OrganizerConfiguration;
import com.example.organizer.config.OrganizerProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Runs the agent with the real AnthropicChatModel against a local fake of the Anthropic Messages API.
 * This checks what goes over the wire (system prompt, tools, tool calls, tool results, token usage)
 * and how errors and timeouts are handled, without an API key and without cost.
 */
class AnthropicWireTest {

    @TempDir
    private Path temp;

    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void organizesTheFolderThroughTheAnthropicApi() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("inbox"));
        Files.writeString(folder.resolve("trip.txt"), "Flight and hotel booked for the vacation.");
        Files.writeString(folder.resolve("bill.txt"), "Invoice: payment due to the bank.");
        // The scripted answers of "Claude", one per request.
        startServer(call -> switch (call) {
            case 1 -> answer("tool_use", text("Let me look at the folder."), toolUse("toolu_1", "list_files", "{}"));
            case 2 -> answer("tool_use",
                    toolUse("toolu_2", "read_file", "{\"fileName\":\"trip.txt\"}"),
                    toolUse("toolu_3", "read_file", "{\"fileName\":\"bill.txt\"}"));
            case 3 -> answer("tool_use", text("- trip.txt => travel\\n- bill.txt => finance"),
                    toolUse("toolu_4", "create_directory", "{\"name\":\"travel\"}"),
                    toolUse("toolu_5", "create_directory", "{\"name\":\"finance\"}"));
            case 4 -> answer("tool_use",
                    toolUse("toolu_6", "move_file", "{\"fileName\":\"trip.txt\",\"directory\":\"travel\"}"),
                    toolUse("toolu_7", "move_file", "{\"fileName\":\"bill.txt\",\"directory\":\"finance\"}"));
            default -> answer("end_turn", text("Sorted 2 files."));
        });
        var human = new ScriptedHumanApprover();
        human.answerAlways(ApprovalAnswer.ALL_CHANGES);

        OrganizerState result = WiredAgent.create(properties(Duration.ofSeconds(5), 0), anthropic(), human)
                .organize(folder);

        assertThat(folder.resolve("travel/trip.txt")).exists();
        assertThat(folder.resolve("finance/bill.txt")).exists();
        assertThat(result.report()).contains("Agent: Sorted 2 files.", "5 calls, 500 input + 100 output tokens");

        // What was sent: the Messages API, the system prompt, the four tools.
        assertThat(requestPaths).hasSize(5).allSatisfy(path -> assertThat(path).endsWith("/messages"));
        assertThat(requestBodies.getFirst()).contains("You are a file organizer agent",
                "\"list_files\"", "\"read_file\"", "\"create_directory\"", "\"move_file\"");
        // The file contents reach the model inside the data tag, as tool results...
        assertThat(requestBodies.get(2)).contains("\"tool_result\"", "toolu_2", "file_content",
                "Flight and hotel booked");
        // ...exactly once: the next request has only the note and the model's own decisions.
        assertThat(requestBodies.get(3)).doesNotContain("Flight and hotel booked")
                .contains("removed=\\\"true\\\"", "trip.txt => travel");
    }

    @Test
    void stopsSafelyWhenTheApiKeepsFailing() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("inbox"));
        startServer(call -> null); // null = HTTP 500

        OrganizerState result = WiredAgent.create(properties(Duration.ofSeconds(5), 2), anthropic(), new ScriptedHumanApprover())
                .organize(folder);

        // One attempt plus two retries, then the run stops with a clear reason.
        assertThat(requestBodies).hasSize(3);
        assertThat(result.report()).contains("Stopped because the model call failed", "No changes were made");
    }

    @Test
    void stopsSafelyWhenTheApiIsTooSlow() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("inbox"));
        startServer(call -> {
            sleep(3_000);
            return answer("end_turn", text("too late"));
        });

        long started = System.nanoTime();
        OrganizerState result = WiredAgent.create(properties(Duration.ofMillis(500), 0), anthropic(), new ScriptedHumanApprover())
                .organize(folder);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(result.report()).contains("Stopped because the model call failed");
    }

    // --- fake server ------------------------------------------------------------------------------

    private OrganizerProperties llmProperties;

    private OrganizerProperties properties(Duration timeout, int retries) {
        var base = WiredAgent.properties(temp.resolve("data"));
        llmProperties = new OrganizerProperties(base.targetFolder(), false, false, base.maxContentCharacters(),
                base.maxFiles(), base.dataDirectory(), base.agent(), base.checkpoints(),
                new OrganizerProperties.LlmProperties(true, "test-key",
                        "http://localhost:" + server.getAddress().getPort() + "/v1", "claude-test",
                        timeout, retries, 1024));
        return llmProperties;
    }

    private ChatModel anthropic() {
        return new OrganizerConfiguration().organizerChatModel(llmProperties);
    }

    /** @param script the response body for request n (1-based), or null for an HTTP 500 */
    private void startServer(IntFunction<String> script) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = script.apply(requestBodies.size());
            respond(exchange, body == null ? 500 : 200,
                    body == null ? "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}" : body);
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        }
    }

    /** A Messages API response; each request reports 100 input and 20 output tokens. */
    private static String answer(String stopReason, String... contentBlocks) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-test",
                 "content":[%s],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":100,"output_tokens":20}}
                """.formatted(String.join(",", contentBlocks), stopReason);
    }

    private static String text(String text) {
        return "{\"type\":\"text\",\"text\":\"" + text + "\"}";
    }

    private static String toolUse(String id, String name, String inputJson) {
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

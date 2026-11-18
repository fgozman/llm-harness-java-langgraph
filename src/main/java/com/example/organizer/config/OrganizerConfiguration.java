package com.example.organizer.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.organizer.approval.ConsoleHumanApprover;
import com.example.organizer.approval.HumanApprover;
import com.example.organizer.model.OfflineKeywordChatModel;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;

/**
 * The beans that are not plain @Component classes, because they need a choice or a setup step:
 * which model drives the tools, and how the human is asked.
 * (The checkpoint store sets itself up from the properties, see CheckpointStore.)
 */
@Configuration
public class OrganizerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OrganizerConfiguration.class);

    /**
     * The model that drives the agent's tools: the Anthropic model when it is enabled,
     * otherwise the offline keyword model.
     *
     * Both are LangChain4j {@link ChatModel}s. CallModelNode only knows this interface, so the model can be
     * switched (or replaced by a fake in tests) without touching the graph.
     *
     * <h4>Using another model (Ollama, OpenAI, other providers)</h4>
     * Only this method, pom.xml and application.yml change; the graph, the tools and the approvals stay the same.
     * The model must support tool calling: the agent works only through tool calls.
     * <ol>
     *   <li>pom.xml: add the provider's LangChain4j module, version {@code ${langchain4j.version}}.
     *       Ollama: {@code langchain4j-ollama}. OpenAI, and servers with an OpenAI-compatible API
     *       (vLLM, LM Studio, LiteLLM, company gateways, ...): {@code langchain4j-open-ai}.</li>
     *   <li>This method: replace {@code AnthropicChatModel.builder()...} with the provider's builder (examples
     *       below). Keep timeout, maxRetries, the answer length and httpClientBuilder: the limits of a run and
     *       the virtual threads depend on them. Adapt the log message.</li>
     *   <li>The API key check: remove it for Ollama (it has no key); otherwise name the right variable in its
     *       message.</li>
     *   <li>application.yml (or the command line): organizer.llm.base-url, model-name and api-key, e.g.
     *       {@code --organizer.llm.base-url=http://localhost:11434 --organizer.llm.model-name=qwen2.5}.</li>
     * </ol>
     *
     * Ollama (runs locally, no API key, base-url {@code http://localhost:11434}):
     * <pre>{@code
     * return OllamaChatModel.builder()
     *         .baseUrl(llm.baseUrl())
     *         .modelName(llm.modelName())      // a model with tool support, e.g. qwen2.5 or llama3.1
     *         .temperature(0.0)
     *         .timeout(llm.timeout())          // local models can be slow: raise organizer.llm.timeout
     *         .maxRetries(llm.maxRetries())
     *         .numPredict(llm.maxTokens())     // maximum length of one answer
     *         .numCtx(32_768)                  // context window, see below
     *         .httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(httpClientOnVirtualThreads(llm.baseUrl())))
     *         .build();
     * }</pre>
     * Set numCtx: Ollama's default context window is often much smaller than the model allows, and a
     * longer conversation is cut without an error, so the model loses the file list and its notes.
     *
     * OpenAI, or a server with an OpenAI-compatible API:
     * <pre>{@code
     * return OpenAiChatModel.builder()
     *         .apiKey(llm.apiKey())            // application.yml: api-key: ${OPENAI_API_KEY:}
     *         .baseUrl(llm.baseUrl())          // e.g. https://api.openai.com/v1, or the server's URL
     *         .modelName(llm.modelName())
     *         .temperature(0.0)
     *         .timeout(llm.timeout())
     *         .maxRetries(llm.maxRetries())
     *         .maxTokens(llm.maxTokens())
     *         .httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(httpClientOnVirtualThreads(llm.baseUrl())))
     *         .build();
     * }</pre>
     * OpenAI reasoning models (o-series, gpt-5) reject maxTokens and temperature: use maxCompletionTokens
     * instead and leave temperature out.
     *
     * Other providers (Gemini, Mistral, Bedrock, Azure OpenAI, ...) have their own LangChain4j module with a
     * similar builder. Some (e.g. Bedrock, Azure OpenAI) use their vendor's SDK instead of LangChain4j's HTTP
     * client; then there is no httpClientBuilder and the virtual-thread setup does not apply.
     * A model without a LangChain4j module: implement {@link ChatModel#doChat} yourself, like
     * OfflineKeywordChatModel, turning the request's tool specifications into the model's API and its tool
     * calls into an AiMessage with ToolExecutionRequests.
     *
     * Good to know:
     * <ul>
     *   <li>organizer.agent.max-tokens-per-run only works when the provider reports token usage
     *       (Anthropic, OpenAI and Ollama do).</li>
     *   <li>To switch without code changes, add a setting such as organizer.llm.provider
     *       (anthropic | ollama | openai) to OrganizerProperties and choose the builder here with a switch.</li>
     *   <li>AnthropicWireTest checks the Anthropic wiring; a new provider needs a similar test.</li>
     * </ul>
     */
    @Bean
    public ChatModel organizerChatModel(OrganizerProperties properties) {
        OrganizerProperties.LlmProperties llm = properties.llm();

        if (!llm.enabled()) {
            log.info("Using the offline keyword model");
            return new OfflineKeywordChatModel();
        }
        // Fail fast: the user asked for the LLM, so silently using the offline model would hide the problem.
        if (llm.apiKey() == null || llm.apiKey().isBlank()) {
            throw new IllegalStateException("organizer.llm.enabled=true but no API key is set. "
                    + "Set ANTHROPIC_API_KEY (or organizer.llm.api-key), or disable the LLM.");
        }

        log.info("Using Anthropic model '{}' (timeout {}, {} retries)", llm.modelName(), llm.timeout(), llm.maxRetries());
        // To use another model (Ollama, OpenAI, ...), see the guide in this method's comment.
        return AnthropicChatModel.builder()
                .apiKey(llm.apiKey())
                .baseUrl(llm.baseUrl())
                .modelName(llm.modelName())
                // temperature 0 = most deterministic answers.
                .temperature(0.0)
                // A call that takes longer fails instead of blocking the run forever.
                .timeout(llm.timeout())
                .maxRetries(llm.maxRetries())
                .maxTokens(llm.maxTokens())
                .httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(httpClientOnVirtualThreads(llm.baseUrl())))
                .build();
    }

    /**
     * The JDK HTTP client behind the model, running its internal tasks on virtual threads
     * (its default is a pool of platform threads). Works for every LangChain4j model with an
     * httpClientBuilder (Anthropic, Ollama, OpenAI, ...).
     * The model call itself waits on the calling thread, which is already a virtual graph thread
     * (see OrganizerAgent). The client's connection selector stays a JDK-internal platform thread;
     * that cannot be changed. The timeouts are still set by the model's builder (timeout above).
     */
    private static HttpClient.Builder httpClientOnVirtualThreads(String baseUrl) {
        HttpClient.Builder http = HttpClient.newBuilder()
                .executor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("llm-http-", 0).factory()));
        // With its default client, LangChain4j sends plain http:// requests (e.g. Ollama, a local proxy) over HTTP/1.1,
        // because an HTTP/2 upgrade request can lose its body on many servers. A custom client loses that
        // rule, so it is kept here to behave as before. https:// (the real API) still uses HTTP/2.
        if ("http".equalsIgnoreCase(URI.create(baseUrl).getScheme())) {
            http.version(HttpClient.Version.HTTP_1_1);
        }
        return http;
    }

    /**
     * Human approval happens on the console of the running application.
     * To approve through another channel (web UI, chat, ...), replace this bean with another
     * HumanApprover implementation; the agent does not need to change.
     */
    @Bean
    public HumanApprover humanApprover() {
        var consoleInput = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new ConsoleHumanApprover(consoleInput, System.out);
    }
}

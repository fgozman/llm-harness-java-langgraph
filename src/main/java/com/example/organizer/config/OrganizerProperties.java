package com.example.organizer.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Typed view of the "organizer.*" settings from application.yml.
 * Every value can be overridden on the command line, e.g. --organizer.target-folder=/path.
 * The @DefaultValue annotations apply when a setting is missing from the configuration.
 * The values are validated at startup (@Validated): an invalid value stops the application
 * with a clear message instead of failing in the middle of a run.
 *
 * @param targetFolder         folder whose files are organized
 * @param generateDemoFiles    write demo files into the folder before the agent runs (setup only)
 * @param undoLastRun          instead of organizing, undo the last run on the target folder
 * @param maxContentCharacters how much of each file the read_file tool returns to the model
 * @param maxFiles             how many files the list_files tool returns at most (the rest is not organized)
 * @param dataDirectory        folder for the application's own files: run locks, change journals, backups
 * @param agent                limits of the agent loop, see {@link AgentProperties}
 * @param checkpoints          where the graph state is saved, see {@link CheckpointProperties}
 * @param llm                  optional LLM settings, see {@link LlmProperties}
 */
@Validated
@ConfigurationProperties(prefix = "organizer")
public record OrganizerProperties(
        @NotNull @DefaultValue("./demo-inbox") Path targetFolder,
        @DefaultValue("false") boolean generateDemoFiles,
        @DefaultValue("false") boolean undoLastRun,
        @Positive @DefaultValue("4000") int maxContentCharacters,
        @Positive @DefaultValue("100") int maxFiles,
        @NotNull @DefaultValue("./data") Path dataDirectory,
        @Valid @DefaultValue AgentProperties agent,
        @Valid @DefaultValue CheckpointProperties checkpoints,
        @Valid @DefaultValue LlmProperties llm) {

    /**
     * @param maxModelCalls   the run stops after this many calls to the model (protects against loops and cost)
     * @param maxTokensPerRun the run stops when the model has used this many tokens (input + output)
     */
    public record AgentProperties(
            @Positive @DefaultValue("50") int maxModelCalls,
            @Positive @DefaultValue("500000") long maxTokensPerRun) {
    }

    /**
     * @param enabled  save the graph state in a SQLite database, so a run interrupted by a crash
     *                 can continue on the next start; when false the state is kept in memory only
     * @param database path of the SQLite database file (created when missing)
     */
    public record CheckpointProperties(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("./data/organizer-checkpoints.db") Path database) {
    }

    /**
     * @param enabled    use the Anthropic model; when false an offline keyword model drives the tools.
     *                   When true, an API key is required (the application does not start without it)
     * @param apiKey     Anthropic API key (usually from the ANTHROPIC_API_KEY environment variable)
     * @param baseUrl    API endpoint
     * @param modelName  model to ask, e.g. claude-sonnet-4-5
     * @param timeout    maximum time for one model call
     * @param maxRetries how often a failed model call is retried
     * @param maxTokens  maximum length of one model answer (tool calls included)
     */
    public record LlmProperties(
            @DefaultValue("false") boolean enabled,
            String apiKey,
            // OpenAI defaults, replaced by Anthropic.
            // @DefaultValue("https://api.openai.com/v1") String baseUrl,
            // @DefaultValue("gpt-4o-mini") String modelName) {
            @NotNull @DefaultValue("https://api.anthropic.com/v1") String baseUrl,
            @NotNull @DefaultValue("claude-sonnet-4-5") String modelName,
            @NotNull @DefaultValue("60s") Duration timeout,
            @PositiveOrZero @DefaultValue("2") int maxRetries,
            @Positive @DefaultValue("4096") int maxTokens) {
    }
}

package com.example.organizer.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.organizer.tools.CreateDirectoryTool;
import com.example.organizer.tools.ListFilesTool;
import com.example.organizer.tools.MoveFileTool;
import com.example.organizer.tools.ReadFileTool;
import com.example.organizer.tools.ToolArguments;
import com.example.organizer.tools.UntrustedText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * A deterministic stand-in for an LLM, used when no LLM is configured (and in tests).
 *
 * It talks to the agent exactly like a real model does (tool calls in, tool results out) and follows
 * the same instructions, so the agent loop, the tools and the human approvals behave the same with or
 * without an LLM. Each answer is worked out from the conversation so far:
 * list_files -> read_file (all files) -> create_directory (missing categories) -> move_file (all files) -> summary.
 * Like a real model, it writes its decision for each file ("- trip.txt => travel") into its answer right
 * after reading, because file contents are removed from the conversation once they have been seen.
 * Categories come from {@link KeywordContentCategorizer}.
 */
public class OfflineKeywordChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OfflineKeywordChatModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A decision line in the model's answer, e.g. "- trip.txt => travel" (the name is escaped). */
    private static final Pattern NOTE = Pattern.compile("^- (.+) => (\\S+)$", Pattern.MULTILINE);
    private static final Pattern CONTENT = Pattern.compile(
            "^<" + ReadFileTool.CONTENT_TAG + " name=\"[^\"]*\" truncated=\"(?:true|false)\">\\n(.*)\\n</"
                    + ReadFileTool.CONTENT_TAG + ">$", Pattern.DOTALL);

    // LangChain4j's ChatModel.chat(request) merges the default parameters, notifies listeners and calls
    // doChat, so implementing doChat is all a ChatModel needs. The whole conversation arrives in every request.
    @Override
    public ChatResponse doChat(ChatRequest request) {
        History history = History.of(request.messages());
        AiMessage answer = nextStep(history);
        log.debug("Offline model answers with {} tool calls",
                answer.hasToolExecutionRequests() ? answer.toolExecutionRequests().size() : 0);
        return ChatResponse.builder().aiMessage(answer).build();
    }

    private AiMessage nextStep(History history) {
        // 1. Look at the folder first.
        String listing = history.lastResultOf(ListFilesTool.NAME);
        if (listing == null) {
            return history.answer("", List.of(tool(ListFilesTool.NAME, Map.of())));
        }
        List<String> files = section(listing, "files:", "directories:");
        Set<String> existingDirectories = new LinkedHashSet<>(section(listing, "directories:", "</" + ListFilesTool.LIST_TAG + ">"));

        // 2. Read every file not read yet.
        Set<String> read = history.requestedArgument(ReadFileTool.NAME, ReadFileTool.FILE_NAME);
        List<Request> reads = files.stream()
                .filter(file -> !read.contains(file))
                .map(file -> tool(ReadFileTool.NAME, Map.of(ReadFileTool.FILE_NAME, file)))
                .toList();
        if (!reads.isEmpty()) {
            return history.answer("", reads);
        }

        // 3. Decide the categories of files read since the last answer (their contents are still there),
        //    and remember the decisions noted in earlier answers.
        Map<String, String> categoryByFile = new LinkedHashMap<>(history.notedCategories());
        Map<String, String> newDecisions = categorizeFreshReads(history, categoryByFile.keySet());
        categoryByFile.putAll(newDecisions);
        String notes = formatNotes(newDecisions);

        // 4. Create the category directories that are missing (and not requested before).
        Set<String> requestedDirectories = history.requestedArgument(CreateDirectoryTool.NAME, CreateDirectoryTool.DIRECTORY);
        List<Request> directories = new LinkedHashSet<>(categoryByFile.values()).stream()
                .filter(dir -> !existingDirectories.contains(dir) && !requestedDirectories.contains(dir))
                .map(dir -> tool(CreateDirectoryTool.NAME, Map.of(CreateDirectoryTool.DIRECTORY, dir)))
                .toList();
        if (!directories.isEmpty()) {
            return history.answer(notes, directories);
        }

        // 5. Move every categorized file (once; rejected or failed moves are not retried).
        Set<String> moved = history.requestedArgument(MoveFileTool.NAME, MoveFileTool.FILE_NAME);
        List<Request> moves = categoryByFile.entrySet().stream()
                .filter(entry -> !moved.contains(entry.getKey()))
                .map(entry -> tool(MoveFileTool.NAME, Map.of(
                        MoveFileTool.FILE_NAME, entry.getKey(),
                        MoveFileTool.DIRECTORY, entry.getValue())))
                .toList();
        if (!moves.isEmpty()) {
            return history.answer(notes, moves);
        }

        // 6. Nothing left to do: answer without tool calls, which ends the run.
        return AiMessage.from((notes.isEmpty() ? "" : notes + "\n") + "Sorted " + categoryByFile.size()
                + " files into " + new LinkedHashSet<>(categoryByFile.values()).size() + " categories.");
    }

    /** Categorizes the files whose read_file result still has its content (or is a binary file). */
    private Map<String, String> categorizeFreshReads(History history, Set<String> alreadyDecided) {
        Map<String, String> decisions = new LinkedHashMap<>();
        for (ToolExecutionRequest read : history.requests(ReadFileTool.NAME)) {
            String fileName = argument(read, ReadFileTool.FILE_NAME);
            String result = history.resultOf(read);
            if (result == null || alreadyDecided.contains(fileName)) {
                continue;
            }
            Matcher content = CONTENT.matcher(result);
            if (content.matches()) {
                String text = UntrustedText.unescape(content.group(1));
                decisions.put(fileName, KeywordContentCategorizer.categorize(fileName, text));
            } else if (result.startsWith("<" + ReadFileTool.BINARY_TAG)) {
                // No content for binary files: decide from the name alone.
                decisions.put(fileName, KeywordContentCategorizer.categorize(fileName, ""));
            }
            // Otherwise the content was already removed (decided earlier) or the read failed.
        }
        return decisions;
    }

    private static String formatNotes(Map<String, String> decisions) {
        StringBuilder notes = new StringBuilder();
        decisions.forEach((file, category) ->
                notes.append("- ").append(UntrustedText.escapeName(file)).append(" => ").append(category).append('\n'));
        return notes.toString().strip();
    }

    /** The "- name" lines between two markers of a list_files result. */
    private static List<String> section(String listing, String startMarker, String endMarker) {
        int start = listing.indexOf(startMarker);
        int end = listing.indexOf(endMarker, start);
        List<String> names = new ArrayList<>();
        for (String line : listing.substring(start + startMarker.length(), end).split("\n")) {
            if (line.startsWith("- ")) {
                names.add(UntrustedText.unescape(line.substring(2)));
            }
        }
        return names;
    }

    private static String argument(ToolExecutionRequest request, String name) {
        try {
            JsonNode value = JSON.readTree(request.arguments()).get(name);
            return value == null ? "" : value.asText();
        } catch (Exception exception) {
            return "";
        }
    }

    private static Request tool(String name, Map<String, String> arguments) {
        return new Request(name, ToolArguments.toJson(arguments));
    }

    private record Request(String toolName, String argumentsJson) {
    }

    /** The answers and tool calls made so far, and the tool results. */
    private record History(List<AiMessage> answers, List<ToolExecutionRequest> requests, Map<String, String> resultsById) {

        static History of(List<ChatMessage> messages) {
            List<AiMessage> answers = new ArrayList<>();
            List<ToolExecutionRequest> requests = new ArrayList<>();
            Map<String, String> results = new HashMap<>();
            for (ChatMessage message : messages) {
                if (message instanceof AiMessage ai) {
                    answers.add(ai);
                    if (ai.hasToolExecutionRequests()) {
                        requests.addAll(ai.toolExecutionRequests());
                    }
                } else if (message instanceof ToolExecutionResultMessage result) {
                    results.put(result.id(), result.text());
                }
            }
            return new History(answers, requests, results);
        }

        /** The decisions written in earlier answers ("- name => category"). */
        Map<String, String> notedCategories() {
            Map<String, String> noted = new LinkedHashMap<>();
            for (AiMessage answer : answers) {
                if (answer.text() == null) {
                    continue;
                }
                Matcher note = NOTE.matcher(answer.text());
                while (note.find()) {
                    noted.put(UntrustedText.unescape(note.group(1)), note.group(2));
                }
            }
            return noted;
        }

        List<ToolExecutionRequest> requests(String toolName) {
            return requests.stream().filter(request -> request.name().equals(toolName)).toList();
        }

        String resultOf(ToolExecutionRequest request) {
            return resultsById.get(request.id());
        }

        String lastResultOf(String toolName) {
            List<ToolExecutionRequest> calls = requests(toolName);
            return calls.isEmpty() ? null : resultOf(calls.getLast());
        }

        Set<String> requestedArgument(String toolName, String argumentName) {
            Set<String> values = new LinkedHashSet<>();
            requests(toolName).forEach(request -> values.add(argument(request, argumentName)));
            return values;
        }

        /** Builds an answer with tool calls; ids continue after the calls made so far, so they stay unique. */
        AiMessage answer(String text, List<Request> calls) {
            List<ToolExecutionRequest> toolRequests = new ArrayList<>();
            for (Request call : calls) {
                toolRequests.add(ToolExecutionRequest.builder()
                        .id("offline-" + (requests.size() + toolRequests.size() + 1))
                        .name(call.toolName())
                        .arguments(call.argumentsJson())
                        .build());
            }
            return text.isBlank() ? AiMessage.from(toolRequests) : AiMessage.from(text, toolRequests);
        }
    }
}

package com.example.organizer.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.config.OrganizerProperties;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Read-only tool: returns the beginning of a file (at most "organizer.max-content-characters").
 * The content is wrapped as untrusted data, see {@link UntrustedText}.
 * Only the first characters are read: enough to decide a category, and large files are never
 * loaded completely. For binary files (images, PDFs, archives, ...) only the name and size are
 * returned: their content would only be noise for the model and cost tokens.
 */
@Component
public class ReadFileTool implements FileTool {

    public static final String NAME = "read_file";
    public static final String FILE_NAME = "fileName";

    /** Tag of a text result; the content is between the opening and closing tag. */
    public static final String CONTENT_TAG = "file_content";
    /** Tag of a binary result; it has no content. */
    public static final String BINARY_TAG = "binary_file";

    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    /** How many bytes are inspected to tell text from binary. */
    private static final int BINARY_CHECK_BYTES = 1024;

    private final int maxCharacters;

    public ReadFileTool(OrganizerProperties properties) {
        this.maxCharacters = properties.maxContentCharacters();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(NAME)
                .description("Returns the beginning of a file's text content, to decide its topic. "
                        + "For binary files (images, PDFs, ...) only the name and size are returned.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty(FILE_NAME, "Name of a file returned by list_files, e.g. trip.txt")
                        .required(FILE_NAME)
                        .build())
                .build();
    }

    @Override
    public boolean changesFiles() {
        return false;
    }

    @Override
    public ToolPreview preview(Path folder, ToolArguments arguments) {
        Path file = SafePaths.existingFile(folder, arguments.requireText(FILE_NAME));
        return ToolPreview.noChange("Read " + file.getFileName());
    }

    @Override
    public ToolOutcome execute(Path folder, ToolArguments arguments, boolean overwriteAllowed) throws Exception {
        Path file = SafePaths.existingFile(folder, arguments.requireText(FILE_NAME));
        String name = UntrustedText.escapeName(file.getFileName().toString());

        if (looksBinary(file)) {
            long size = Files.size(file);
            log.debug("{} is binary ({} bytes), content not returned", file.getFileName(), size);
            return ToolOutcome.done("<" + BINARY_TAG + " name=\"" + name + "\" size_bytes=\"" + size
                    + "\"/>\nThis is a binary file; decide its category from the name.");
        }

        // One character more than allowed, to know whether the file goes on.
        String text = readStart(file, maxCharacters + 1);
        boolean truncated = text.length() > maxCharacters;
        if (truncated) {
            text = text.substring(0, maxCharacters);
        }
        log.debug("Read {} characters from {} (truncated={})", text.length(), file.getFileName(), truncated);
        return ToolOutcome.done("<" + CONTENT_TAG + " name=\"" + name + "\" truncated=\"" + truncated + "\">\n"
                + UntrustedText.escape(text)
                + "\n</" + CONTENT_TAG + ">");
    }

    /** Reads at most {@code limit} characters from the start of the file, as UTF-8. */
    private static String readStart(Path file, int limit) throws IOException {
        // Invalid bytes (e.g. Latin-1 text) must not fail: they are replaced with "\uFFFD".
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try (Reader reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] buffer = new char[limit];
            int filled = 0;
            // A single read() may return fewer characters than available, so read until full or end of file.
            while (filled < buffer.length) {
                int read = reader.read(buffer, filled, buffer.length - filled);
                if (read < 0) {
                    break;
                }
                filled += read;
            }
            return new String(buffer, 0, filled);
        }
    }

    /**
     * Text files practically never contain NUL bytes; binary formats almost always do
     * in their first kilobyte. This is the same heuristic tools like git and grep use.
     */
    private static boolean looksBinary(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            for (byte b : input.readNBytes(BINARY_CHECK_BYTES)) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        }
    }
}

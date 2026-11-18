package com.example.organizer.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.config.OrganizerProperties;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Read-only tool: lists the files to organize and the existing directories.
 * At most "organizer.max-files" files are returned, which also limits how much work
 * (and model cost) one run can cause. When files are left out, the report says so.
 */
@Component
public class ListFilesTool implements FileTool {

    public static final String NAME = "list_files";

    /** Tag around the listing; the lines inside start with "- ". */
    public static final String LIST_TAG = "file_list";

    private static final Logger log = LoggerFactory.getLogger(ListFilesTool.class);

    private final int maxFiles;

    public ListFilesTool(OrganizerProperties properties) {
        this.maxFiles = properties.maxFiles();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(NAME)
                .description("Lists the files to organize and the existing directories of the folder.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public boolean changesFiles() {
        return false;
    }

    @Override
    public ToolPreview preview(Path folder, ToolArguments arguments) {
        return ToolPreview.noChange("List files");
    }

    @Override
    public ToolOutcome execute(Path folder, ToolArguments arguments, boolean overwriteAllowed) throws Exception {
        Listing listing = scan(folder);
        List<String> files = listing.files();
        List<String> shown = files.size() > maxFiles ? files.subList(0, maxFiles) : files;
        log.debug("{} files, {} directories listed", shown.size(), listing.directories().size());

        // File names come from the folder and are untrusted: escaped (control characters included,
        // so a name cannot start a new line) and kept inside the tag.
        StringBuilder result = new StringBuilder();
        result.append('<').append(LIST_TAG).append(" total_files=\"").append(files.size())
                .append("\" shown_files=\"").append(shown.size()).append("\">\n");
        result.append("files:\n");
        shown.forEach(file -> result.append("- ").append(UntrustedText.escapeName(file)).append('\n'));
        result.append("directories:\n");
        listing.directories().forEach(dir -> result.append("- ").append(UntrustedText.escapeName(dir)).append('\n'));
        result.append("</").append(LIST_TAG).append('>');

        if (shown.size() < files.size()) {
            String notice = "Only the first " + shown.size() + " of " + files.size()
                    + " files were organized (organizer.max-files).";
            log.warn(notice);
            result.append("\nOnly the first ").append(shown.size())
                    .append(" files are shown because of a limit. Organize only the files shown.");
            return ToolOutcome.doneWithNotice(result.toString(), notice);
        }
        return ToolOutcome.done(result.toString());
    }

    /**
     * The visible files and sub-directories located directly inside the folder.
     * Hidden entries (starting with ".") and symbolic links are left out: a link could point
     * outside the folder, and its target must never be read or sent to a model.
     */
    private static Listing scan(Path folder) throws IOException {
        List<String> files = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        // A missing folder is not an error: there is simply nothing to organize.
        if (!Files.isDirectory(folder)) {
            log.warn("Folder {} does not exist or is not a directory, nothing to organize", folder);
            return new Listing(files, directories);
        }

        // Files.list is not recursive and must be closed, hence try-with-resources.
        try (Stream<Path> entries = Files.list(folder)) {
            // Sorted so every run sees the entries in the same order.
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || Files.isSymbolicLink(entry)) {
                    log.debug("Ignoring hidden entry or link: {}", name);
                } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(name);
                } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    directories.add(name);
                }
            }
        }
        return new Listing(files, directories);
    }

    /**
     * Names (not paths) of the entries directly inside a folder, sorted alphabetically.
     *
     * @param files       visible regular files, the candidates to organize
     * @param directories visible sub-directories, e.g. category folders from an earlier run
     */
    private record Listing(List<String> files, List<String> directories) {
    }
}

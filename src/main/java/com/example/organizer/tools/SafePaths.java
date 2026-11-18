package com.example.organizer.tools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Turns names chosen by the model into paths, and makes sure they stay inside the organized folder.
 * The model only ever gets plain names like "trip.txt" or "travel"; anything that looks like a path
 * ("../x", "a/b", "/etc/passwd") is refused instead of being interpreted.
 */
public final class SafePaths {

    /** Directory for files whose topic cannot be recognized (also used for blank category names). */
    public static final String UNCATEGORIZED = "uncategorized";

    // Keeps directory names readable even if a model answers with a whole sentence.
    private static final int MAX_DIRECTORY_NAME_LENGTH = 40;

    private SafePaths() {
    }

    /**
     * Turns any category label (for example a model's answer) into a safe directory name:
     * lower case, letters, digits and dashes only. "Personal Finance" becomes "personal-finance".
     */
    static String directoryName(String category) {
        if (category == null) {
            return UNCATEGORIZED;
        }
        String cleaned = category
                .strip()
                .toLowerCase(Locale.ROOT)
                // Every run of unsafe characters (spaces, "/", "..", accents, ...) becomes one dash.
                // This also prevents path tricks like "../etc" from escaping the target folder.
                .replaceAll("[^a-z0-9]+", "-")
                // Remove dashes left at the start or end, e.g. "-finance-" -> "finance".
                .replaceAll("^-+|-+$", "");

        if (cleaned.isEmpty()) {
            return UNCATEGORIZED;
        }
        return cleaned.length() > MAX_DIRECTORY_NAME_LENGTH ? cleaned.substring(0, MAX_DIRECTORY_NAME_LENGTH) : cleaned;
    }

    /**
     * @return folder/name, guaranteed to be a direct child of the folder
     * @throws ToolArgumentException when the name is not a plain, visible entry name
     */
    static Path childOf(Path folder, String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("\0")) {
            throw new ToolArgumentException("'" + name + "' must be a plain name without a path");
        }
        // Also refuses "." and "..". Hidden entries are never organized.
        if (name.startsWith(".")) {
            throw new ToolArgumentException("'" + name + "' is not allowed (hidden or relative name)");
        }

        Path base = folder.toAbsolutePath().normalize();
        Path child = base.resolve(name).normalize();
        // Second line of defense: whatever the name, the result must be directly inside the folder.
        if (!base.equals(child.getParent())) {
            throw new ToolArgumentException("'" + name + "' is outside the organized folder");
        }
        return child;
    }

    /**
     * Finds a regular file (not a link) directly in the folder. The model sees names escaped
     * (e.g. "Tom &amp; Jerry.txt"), so the escaped form is accepted too.
     *
     * @throws ToolArgumentException when there is no such file
     */
    static Path existingFile(Path folder, String name) {
        for (String candidate : new String[] {name, UntrustedText.unescape(name)}) {
            Path file = childOf(folder, candidate);
            if (!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return file;
            }
        }
        throw new ToolArgumentException("There is no file named '" + name + "'");
    }

    /**
     * Checks that a relative path read back from our own records (e.g. "travel/trip.txt" in the
     * change journal) still points inside the folder.
     *
     * @throws IllegalArgumentException when it does not
     */
    public static Path insideFolder(Path folder, String relativePath) {
        Path base = folder.toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("'" + relativePath + "' is outside the organized folder");
        }
        return resolved;
    }
}

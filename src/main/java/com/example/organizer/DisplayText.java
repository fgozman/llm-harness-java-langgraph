package com.example.organizer;

/**
 * Makes untrusted text (file names, model answers) safe to print on the console.
 *
 * Control characters are shown as visible codes (e.g. "\u001B"). Otherwise a file name could
 * contain terminal escape sequences or line breaks that redraw the approval prompt, for example
 * to hide an overwrite warning or to print a fake one.
 */
public final class DisplayText {

    private DisplayText() {
    }

    /** For single-line values such as file names and change descriptions: no line breaks survive. */
    public static String line(String text) {
        return sanitize(text, false);
    }

    /** For multi-line text such as the model's summary: line breaks stay, other control characters do not. */
    public static String block(String text) {
        return sanitize(text, true);
    }

    private static String sanitize(String text, boolean keepLineBreaks) {
        if (text == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (keepLineBreaks && c == '\n') {
                safe.append('\n');
            } else if (Character.isISOControl(c)) {
                safe.append(String.format("\\u%04X", c));
            } else {
                safe.appendCodePoint(c);
            }
        });
        return safe.toString();
    }
}

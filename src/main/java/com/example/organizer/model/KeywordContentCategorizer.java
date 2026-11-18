package com.example.organizer.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.organizer.tools.SafePaths;

/**
 * The offline model's way of choosing a category (an LLM reads and decides instead): counts how many
 * keywords of each category appear in the file name and content. The category with the most matches wins.
 */
public final class KeywordContentCategorizer {

    private static final Logger log = LoggerFactory.getLogger(KeywordContentCategorizer.class);

    // Anything that is not a letter or digit separates words (spaces, punctuation, "_", ".").
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^a-z0-9]+");

    /** On a tie, the category listed first wins. */
    private static final Map<String, Set<String>> KEYWORDS_BY_CATEGORY = createKeywords();

    private KeywordContentCategorizer() {
    }

    /**
     * @param fileName name only, without the folder (e.g. "trip.txt")
     * @param content  the beginning of the file as text (possibly truncated); empty for binary files
     * @return a category such as "finance", or "uncategorized" when no keyword matches
     */
    public static String categorize(String fileName, String content) {
        // The file name counts too: "invoice_march.txt" is a strong hint even with little content.
        List<String> words = splitIntoWords(fileName + " " + content);

        // Starting at score 0 means a file without any keyword stays "uncategorized".
        String bestCategory = SafePaths.UNCATEGORIZED;
        int bestScore = 0;

        for (var entry : KEYWORDS_BY_CATEGORY.entrySet()) {
            int score = countMatches(words, entry.getValue());
            // Strictly greater: on a tie the category listed first wins (the maps keep their order).
            if (score > bestScore) {
                bestScore = score;
                bestCategory = entry.getKey();
            }
        }
        log.debug("Keyword match for '{}': category '{}' with {} keyword hits", fileName, bestCategory, bestScore);
        return bestCategory;
    }

    private static List<String> splitIntoWords(String text) {
        return WORD_SEPARATOR.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isBlank())
                .toList();
    }

    /** Every occurrence counts, so a word repeated three times scores three points. */
    private static int countMatches(List<String> words, Set<String> keywords) {
        int matches = 0;
        for (String word : words) {
            if (keywords.contains(word)) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Keywords per category, all lower case. To support a new category, add an entry here.
     */
    private static Map<String, Set<String>> createKeywords() {
        Map<String, Set<String>> keywords = new LinkedHashMap<>();
        keywords.put("finance", Set.of(
                "invoice", "payment", "bank", "account", "tax", "vat", "budget", "salary",
                "savings", "credit", "expenses", "investment", "fund", "amount", "eur", "usd"));
        keywords.put("health", Set.of(
                "doctor", "clinic", "blood", "cholesterol", "diet", "exercise", "workout",
                "yoga", "fitness", "sleep", "heart", "running", "training", "appointment"));
        keywords.put("travel", Set.of(
                "flight", "hotel", "passport", "luggage", "vacation", "holiday", "itinerary",
                "boarding", "gate", "tour", "train", "museum", "trip", "reservation"));
        keywords.put("technology", Set.of(
                "class", "public", "server", "database", "api", "docker", "kubernetes",
                "deploy", "deployment", "registry", "bugs", "code", "http", "logs", "build"));
        keywords.put("cooking", Set.of(
                "recipe", "ingredients", "flour", "eggs", "milk", "bake", "oven", "dinner",
                "menu", "soup", "dessert", "sauce", "cook", "vegetables", "butter"));
        keywords.put("education", Set.of(
                "homework", "assignment", "course", "exam", "teacher", "lecture", "students",
                "university", "chapter", "exercises", "mathematics", "school"));
        // Unmodifiable view of the LinkedHashMap: keeps the order above, so ties are resolved
        // the same way on every run (Map.copyOf would not guarantee any order).
        return Collections.unmodifiableMap(keywords);
    }
}

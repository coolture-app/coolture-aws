package com.coolture.common.db;

import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Search by text: every word of the query must appear in one of the fields, in any order, with diacritics ignored on
 * both sides ("spiew" finds "Śpiew"). With word forms on, a word of 5 or more letters loses one Polish ending first, so
 * "koncertu" finds "koncert" and "koncertowy". Needs the database's {@code unaccent}.
 */
public final class TextSearch {

    /** Longest first and folded, since the ending is cut after folding. */
    private static final List<String> ENDINGS = List.of(
        "owie", "ach", "ami", "ych", "ymi", "ego", "emu", "owi", "om", "em", "ie", "ej", "ow", "u", "y", "i", "a", "e", "o");

    /** Each word scans the fields once, so a pasted paragraph can't make the query arbitrarily slow. */
    static final int MAX_WORDS = 8;

    private TextSearch() {}

    /**
     * Whether every word bound with {@link #bind} appears in one of {@code fields}; true when there are none. The
     * newline between the fields keeps a word from spanning two of them. The words are folded again here so both sides
     * fold by the same rules: Java folds them only to find the ending.
     */
    public static String matchesAll(String... fields) {
        return "(?::varchar[] IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(?::varchar[]) w(word)"
            + " WHERE unaccent(lower(concat_ws(chr(10), " + String.join(", ", fields) + ")))"
            + " NOT LIKE '%' || unaccent(lower(w.word)) || '%'))";
    }

    public static ParamBinder bind(ParamBinder b, String[] words) throws SQLException {
        return b.bindVarcharArray(words).bindVarcharArray(words);
    }

    /** The query's words, folded and escaped for {@code LIKE}, or null when it has none. */
    public static String[] words(String q, boolean wordForms) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return Arrays.stream(q.strip().split("\\s+"))
            .limit(MAX_WORDS)
            .map(TextSearch::fold)
            .map(word -> wordForms ? stripEnding(word) : word)
            .map(TextSearch::escapeLike)
            .toArray(String[]::new);
    }

    /** Lower case without diacritics. NFD leaves "ł" whole, so it's mapped by hand, as {@code unaccent} does. */
    static String fold(String word) {
        String lower = word.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Leaves at least 4 letters, so short words and stems stay specific. */
    static String stripEnding(String word) {
        if (word.length() >= 5) {
            for (String ending : ENDINGS) {
                if (word.endsWith(ending) && word.length() - ending.length() >= 4) {
                    return word.substring(0, word.length() - ending.length());
                }
            }
        }
        return word;
    }

    private static String escapeLike(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

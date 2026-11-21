package com.coolture.common.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TextSearchTest {

    @Test
    void words_splitOnAnySpace_foldAndKeepHyphens() {
        assertArrayEquals(new String[] {"spiew", "lodz", "stand-up"}, TextSearch.words("  Śpiew\tŁÓDŹ  stand-up ", false));
    }

    @Test
    void words_areNullForNoQuery() {
        assertNull(TextSearch.words(null, true));
        assertNull(TextSearch.words(" \t", true));
    }

    @Test
    void words_stopAtTheCap() {
        assertEquals(TextSearch.MAX_WORDS, TextSearch.words("a b c d e f g h i j", false).length);
    }

    @Test
    void words_escapeWhatLikeReads() {
        assertArrayEquals(new String[] {"50\\%", "a\\_b", "c\\\\d"}, TextSearch.words("50% a_b c\\d", false));
    }

    @Test
    void words_loseAnEndingOnlyWithWordForms() {
        assertArrayEquals(new String[] {"koncert"}, TextSearch.words("koncertu", true));
        assertArrayEquals(new String[] {"koncertu"}, TextSearch.words("koncertu", false));
    }

    @ParameterizedTest
    @CsvSource({
        "koncertu, koncert",
        "spektakle, spektakl",
        "teatrach, teatr",
        "muzyka, muzyk",
        "klasyczna, klasyczn",
        "panowie, panow",
        "aktorowie, aktor",
        "dzieci, dziec",
        "opera, oper",
        "kino, kino",
        "jazz, jazz",
        "spiew, spiew",
        "sopocie, sopoc",
    })
    void stripEnding_cutsTheLongestEnding_leavingFourLetters(String word, String stem) {
        assertEquals(stem, TextSearch.stripEnding(word));
    }
}

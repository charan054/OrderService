package com.example.orderservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The storefront's language switch (static/js/i18n.js) keeps one English-keyed dictionary per language. This keeps the lists
// in step: every English text translated into Hindi must be translated into Telugu too (and the other way round), nothing is
// left blank, and a translation keeps the placeholder-like pieces ("(", "?", "...") of the original where they matter.
class I18nDictionaryTest {

    private static String script() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/js/i18n.js"), StandardCharsets.UTF_8);
    }

    private static Map<String, String> entries(String js, String language) {
        int start = js.indexOf("    " + language + ": {");
        assertTrue(start >= 0, "no " + language + " dictionary found");
        int end = js.indexOf("\n    }", start);
        String block = js.substring(start, end);
        Map<String, String> out = new LinkedHashMap<>();
        // one `"english": "translated",` line per entry (a backslash escape inside a text is allowed)
        String text = "(?:[^\"\\\\]|\\\\.)*";
        Matcher m = Pattern.compile("^\\s*\"(" + text + ")\"\\s*:\\s*\"(" + text + ")\",?\\s*$", Pattern.MULTILINE).matcher(block);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    void hindiAndTeluguTranslateTheSameEnglishTexts() throws IOException {
        Map<String, String> hindi = entries(script(), "hi");
        Map<String, String> telugu = entries(script(), "te");

        assertTrue(hindi.size() > 50, "the Hindi dictionary looks too small to be real: " + hindi.size());
        assertEquals(hindi.keySet(), telugu.keySet());
    }

    @Test
    void noTranslationIsBlankOrJustTheEnglishAgain() throws IOException {
        for (String language : new String[]{"hi", "te"}) {
            entries(script(), language).forEach((english, translated) -> {
                assertFalse(translated.isBlank(), language + " has a blank translation for: " + english);
                if (english.chars().anyMatch(Character::isLetter)) {
                    assertFalse(english.equals(translated), language + " just repeats the English for: " + english);
                }
            });
        }
    }

    @Test
    void textsThatEndInAnOpenBracketKeepItSoTheCountAfterThemStillReads() throws IOException {
        for (String language : new String[]{"hi", "te"}) {
            Map<String, String> dictionary = entries(script(), language);
            for (String key : new String[]{"On sale (", "Wishlist ("}) {
                assertTrue(dictionary.get(key).endsWith("("), language + " must keep the opening bracket for: " + key);
            }
        }
    }

    @Test
    void thePageLoadsTheScriptAndHasALanguagePicker() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/shop.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("/js/i18n.js"));
        assertTrue(html.contains("id=\"langSelect\""));
    }
}

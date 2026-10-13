package com.example.orderservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * There is no JavaScript engine in the JDK, so this is a small scanner rather than a parser: it reads the inline
 * script blocks of the static dashboards and reports the mistakes that have actually broken a page - a quoted string
 * left open at a line break (e.g. a raw newline where an escaped one was meant), a template literal or comment never
 * closed, and unbalanced brackets. A broken page script kills the whole page, and nothing else in the build would notice.
 */
class StaticPageScriptTest {
    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>");

    @Test
    void everyDashboardPageHasWellFormedInlineScripts() throws IOException {
        List<String> problems = new ArrayList<>();
        int scripts = 0;
        try (Stream<Path> files = Files.list(STATIC)) {
            for (Path page : files.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
                String html = Files.readString(page, StandardCharsets.UTF_8);
                Matcher m = SCRIPT.matcher(html);
                while (m.find()) {
                    scripts++;
                    int firstLine = lineOf(html, m.start(1));
                    for (String problem : check(m.group(1))) {
                        problems.add(page.getFileName() + ": " + problem + " (relative to the script starting at line " + firstLine + ")");
                    }
                }
            }
        }
        assertTrue(scripts > 0, "found no inline scripts to check");
        assertEquals(List.of(), problems);
    }

    @Test
    void theScannerCatchesTheMistakesItExistsFor() {
        assertEquals(1, check("var a = \"first\nsecond\";").size());
        assertEquals(1, check("var a = `open").size());
        assertEquals(1, check("function f() { return 1;").size());
        assertEquals(1, check("var a = (1 + 2));").size());
        assertEquals(1, check("/* never closed").size());
        assertEquals(1, check("var a = `x ${ b `").size());
    }

    @Test
    void theScannerAcceptsRealisticCode() {
        String ok = "const re = /^\\d+$/; const s = 'it\\'s' + \"a\\\nb\"; // note ' \"\n"
                + "const t = `x ${a + `y${b}`} {` + (c ? '}' : ')'); /* ) */ if (/[\"']/.test(s)) { go(); }\n"
                + "function safe(u) { return /^https?:\\/\\//i.test(u || \"\") ? u : \"\"; }\n"
                + "const h = `${JSON.stringify(x).replace(/\"/g, \"&quot;\")}`; const q = a / b / c;";
        assertEquals(List.of(), check(ok));
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Problems found in one script body, each with the line (within the body) it was found at. */
    static List<String> check(String js) {
        Scanner scanner = new Scanner(js);
        scanner.code(false);
        return scanner.problems;
    }

    private static final Set<String> REGEX_AFTER_WORD = Set.of(
            "return", "typeof", "case", "in", "of", "delete", "void", "throw", "new", "else", "do", "await", "yield");

    private static final class Scanner {
        final String js;
        final int n;
        int i;
        int line = 1;
        final List<String> problems = new ArrayList<>();
        boolean stopped;

        Scanner(String js) {
            this.js = js;
            this.n = js.length();
        }

        private void problem(int atLine, String text) {
            problems.add("line " + atLine + ": " + text);
        }

        /** Scans code; in a template hole it returns just after the } that closes the hole. */
        void code(boolean inHole) {
            Deque<int[]> brackets = new ArrayDeque<>();
            char prev = 0;
            String prevWord = "";
            while (i < n && !stopped) {
                char c = js.charAt(i);
                if (c == '\n') {
                    line++;
                    i++;
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                    int j = i;
                    while (j < n && (Character.isLetterOrDigit(js.charAt(j)) || js.charAt(j) == '_' || js.charAt(j) == '$')) {
                        j++;
                    }
                    prevWord = js.substring(i, j);
                    prev = 'a';
                    i = j;
                    continue;
                }
                if (c == '/' && i + 1 < n && js.charAt(i + 1) == '/') {
                    while (i < n && js.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (c == '/' && i + 1 < n && js.charAt(i + 1) == '*') {
                    int startLine = line;
                    i += 2;
                    while (i + 1 < n && !(js.charAt(i) == '*' && js.charAt(i + 1) == '/')) {
                        if (js.charAt(i) == '\n') {
                            line++;
                        }
                        i++;
                    }
                    if (i + 1 >= n) {
                        problem(startLine, "comment is never closed");
                        stopped = true;
                        return;
                    }
                    i += 2;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    quoted(c);
                    prev = c;
                    prevWord = "";
                    continue;
                }
                if (c == '`') {
                    int startLine = line;
                    i++;
                    template(startLine);
                    prev = '`';
                    prevWord = "";
                    continue;
                }
                if (c == '/' && (prev == 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0
                        || (prev == 'a' && REGEX_AFTER_WORD.contains(prevWord)))) {
                    if (regex()) {
                        prev = '/';
                        prevWord = "";
                        continue;
                    }
                }
                if (c == '(' || c == '[' || c == '{') {
                    brackets.push(new int[]{c, line});
                } else if (c == ')' || c == ']' || c == '}') {
                    if (brackets.isEmpty()) {
                        if (inHole && c == '}') {
                            i++;
                            return;
                        }
                        problem(line, "unexpected " + c);
                    } else {
                        int[] open = brackets.pop();
                        char want = open[0] == '(' ? ')' : open[0] == '[' ? ']' : '}';
                        if (want != c) {
                            problem(line, c + " does not match the " + (char) open[0] + " opened at line " + open[1]);
                        }
                    }
                }
                prev = c;
                prevWord = "";
                i++;
            }
            if (inHole) {
                if (!stopped) {
                    problem(line, "${ in a template literal is never closed");
                }
                stopped = true;
                return;
            }
            if (!stopped) {
                for (int[] open : brackets) {
                    problem(open[1], (char) open[0] + " is never closed");
                }
            }
        }

        private void quoted(char quote) {
            int startLine = line;
            i++;
            while (i < n) {
                char d = js.charAt(i);
                if (d == '\\') {
                    if (i + 1 < n && js.charAt(i + 1) == '\n') {
                        line++; // an escaped line break continues the string
                    }
                    i += 2;
                    continue;
                }
                if (d == '\n') {
                    problem(startLine, "string is not closed before the end of the line");
                    stopped = true; // what follows cannot be trusted, so report this one mistake only
                    return;
                }
                i++;
                if (d == quote) {
                    return;
                }
            }
            problem(startLine, "string is never closed");
            stopped = true;
        }

        /** After the opening backtick; leaves i after the closing one. */
        private void template(int startLine) {
            while (i < n && !stopped) {
                char c = js.charAt(i);
                if (c == '\\') {
                    if (i + 1 < n && js.charAt(i + 1) == '\n') {
                        line++;
                    }
                    i += 2;
                    continue;
                }
                if (c == '\n') {
                    line++;
                }
                if (c == '`') {
                    i++;
                    return;
                }
                if (c == '$' && i + 1 < n && js.charAt(i + 1) == '{') {
                    i += 2;
                    code(true);
                    continue;
                }
                i++;
            }
            if (!stopped) {
                problem(startLine, "template literal is never closed");
                stopped = true;
            }
        }

        /** At a "/" where a regular expression can start: skips it and returns true, or false if it is not one. */
        private boolean regex() {
            int j = i + 1;
            boolean inClass = false;
            while (j < n && js.charAt(j) != '\n') {
                char d = js.charAt(j);
                if (d == '\\') {
                    j += 2;
                    continue;
                }
                if (d == '[') {
                    inClass = true;
                } else if (d == ']') {
                    inClass = false;
                } else if (d == '/' && !inClass) {
                    i = j + 1;
                    while (i < n && Character.isLetter(js.charAt(i))) {
                        i++; // flags
                    }
                    return true;
                }
                j++;
            }
            return false;
        }
    }
}

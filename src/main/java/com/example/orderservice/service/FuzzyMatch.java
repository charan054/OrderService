package com.example.orderservice.service;

import java.util.Locale;

/** Typo-tolerant matching of a search term against product names ("shampo" -> "Shampoo", "cetafil" -> "Cetaphil"). */
public final class FuzzyMatch {
    private FuzzyMatch() {
    }

    /** Classic edit distance (insert, delete, replace), stopping early once it must exceed {@code limit}. */
    static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int best = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                best = Math.min(best, cur[j]);
            }
            if (best > limit) {
                return limit + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** How many typos a word of this length may contain: none for very short words, 1 up to 5 letters, then 2. */
    static int allowedTypos(int length) {
        return length <= 3 ? 0 : length <= 5 ? 1 : 2;
    }

    /**
     * True when every word of the query is close to some word of the text (or to the start of one, so a half-typed word
     * with a slip still matches). Both sides are compared lower-cased.
     */
    public static boolean matches(String query, String text) {
        if (query == null || text == null) {
            return false;
        }
        String[] queryWords = query.toLowerCase(Locale.ROOT).trim().split("[^a-z0-9]+");
        String[] textWords = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        boolean any = false;
        for (String q : queryWords) {
            if (q.isEmpty()) {
                continue;
            }
            any = true;
            int typos = allowedTypos(q.length());
            boolean found = false;
            for (String t : textWords) {
                if (t.isEmpty()) {
                    continue;
                }
                if (distance(q, t, typos) <= typos
                        || (t.length() > q.length() && distance(q, t.substring(0, q.length()), typos) <= typos)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return any;
    }
}

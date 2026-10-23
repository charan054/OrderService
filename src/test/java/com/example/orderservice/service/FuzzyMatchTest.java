package com.example.orderservice.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzyMatchTest {

    @Test
    void editDistanceCountsInsertsDeletesAndReplacements() {
        assertEquals(0, FuzzyMatch.distance("soap", "soap", 2));
        assertEquals(1, FuzzyMatch.distance("shampo", "shampoo", 2));
        assertEquals(2, FuzzyMatch.distance("cetafil", "cetaphil", 2));
        assertEquals(3, FuzzyMatch.distance("abc", "xyz", 2));          // beyond the limit reports limit + 1
    }

    @Test
    void commonTyposStillFindTheProduct() {
        assertTrue(FuzzyMatch.matches("shampo", "Dove Shampoo 340ml"));
        assertTrue(FuzzyMatch.matches("cetafil", "Cetaphil Gentle Cleanser"));
        assertTrue(FuzzyMatch.matches("loreal shampo", "L'Oreal Paris Shampoo"));
        assertTrue(FuzzyMatch.matches("mong dal", "Moong Dal 1kg"));
    }

    @Test
    void aHalfTypedWordWithASlipMatchesTheStartOfAWord() {
        assertTrue(FuzzyMatch.matches("cetaf", "Cetaphil Gentle Cleanser"));
    }

    @Test
    void unrelatedAndVeryShortTermsDoNotMatch() {
        assertFalse(FuzzyMatch.matches("laptop", "Dove Shampoo"));
        assertFalse(FuzzyMatch.matches("cat", "Cot"));                  // 3 letters or fewer must be exact
        assertFalse(FuzzyMatch.matches("", "Dove Shampoo"));
        assertFalse(FuzzyMatch.matches(null, "Dove Shampoo"));
        assertFalse(FuzzyMatch.matches("shampo conditioner", "Dove Shampoo"));   // every word has to match something
    }
}

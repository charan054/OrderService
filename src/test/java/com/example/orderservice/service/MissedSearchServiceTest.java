package com.example.orderservice.service;

import com.example.orderservice.entity.MissedSearch;
import com.example.orderservice.repository.MissedSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MissedSearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    @Mock
    private MissedSearchRepository repository;
    private MissedSearchService service;

    @BeforeEach
    void setUp() {
        service = new MissedSearchService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstMissCreatesARowWithCountOne() {
        when(repository.findByTerm("green tea")).thenReturn(Optional.empty());

        service.record("  Green   TEA ");

        ArgumentCaptor<MissedSearch> saved = ArgumentCaptor.forClass(MissedSearch.class);
        verify(repository).save(saved.capture());
        assertEquals("green tea", saved.getValue().getTerm());
        assertEquals(1, saved.getValue().getTimes());
        assertEquals(NOW, saved.getValue().getLastSearchedAt());
    }

    @Test
    void aRepeatMissBumpsTheExistingCount() {
        MissedSearch row = new MissedSearch();
        row.setTerm("oats");
        row.setTimes(4);
        when(repository.findByTerm("oats")).thenReturn(Optional.of(row));

        service.record("Oats");

        verify(repository).save(row);
        assertEquals(5, row.getTimes());
    }

    @Test
    void tooShortOrTooLongOrBlankTermsAreIgnored() {
        service.record(null);
        service.record("  ");
        service.record("ab");
        service.record("x".repeat(61));

        verifyNoInteractions(repository);
        assertNull(MissedSearchService.normalise("ab"));
    }

    @Test
    void aStorageFailureNeverReachesTheCaller() {
        when(repository.findByTerm("oats")).thenThrow(new IllegalStateException("db down"));

        service.record("oats");

        verify(repository, never()).save(any());
    }
}

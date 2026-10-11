package com.example.orderservice.service;

import com.example.orderservice.entity.MissedSearch;
import com.example.orderservice.repository.MissedSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/** Remembers storefront searches that matched nothing so an admin can see what shoppers are looking for. */
@Service
public class MissedSearchService {
    private static final Logger log = LoggerFactory.getLogger(MissedSearchService.class);
    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 60;

    private final MissedSearchRepository repository;
    private final Clock clock;

    public MissedSearchService(MissedSearchRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Never throws: a failure to record must not break the search the shopper is waiting on. */
    public void record(String rawTerm) {
        String term = normalise(rawTerm);
        if (term == null) {
            return;
        }
        try {
            MissedSearch row = repository.findByTerm(term).orElseGet(() -> {
                MissedSearch fresh = new MissedSearch();
                fresh.setTerm(term);
                return fresh;
            });
            row.setTimes(row.getTimes() + 1);
            row.setLastSearchedAt(clock.instant());
            repository.save(row);
        } catch (RuntimeException e) {
            // Two first-time searches for the same term can race on the unique key; losing one count is fine.
            log.debug("Could not record missed search '{}': {}", term, e.getMessage());
        }
    }

    public List<MissedSearch> top() {
        return repository.findTop50ByOrderByTimesDescLastSearchedAtDesc();
    }

    /** Lower-cased, whitespace-collapsed; null when too short or too long to be a real product search. */
    static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String term = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return term.length() < MIN_LENGTH || term.length() > MAX_LENGTH ? null : term;
    }
}

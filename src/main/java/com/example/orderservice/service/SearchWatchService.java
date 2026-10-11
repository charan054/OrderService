package com.example.orderservice.service;

import com.example.orderservice.entity.SearchWatch;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.SearchWatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** A customer's saved searches ("tell me when you stock this"); the emailing lives in StockAlertService. */
@Service
public class SearchWatchService {
    static final int MAX_PER_CUSTOMER = 20;

    private final SearchWatchRepository watches;
    private final Clock clock;

    public SearchWatchService(SearchWatchRepository watches, Clock clock) {
        this.watches = watches;
        this.clock = clock;
    }

    /** Idempotent: watching the same term again returns the existing watch (and re-arms it if it already fired). */
    @Transactional
    public SearchWatch add(long phno, String rawTerm) {
        String term = MissedSearchService.normalise(rawTerm);
        if (term == null) {
            throw new ProductException("Enter a search of " + MissedSearchService.MIN_LENGTH + " to "
                    + MissedSearchService.MAX_LENGTH + " characters.");
        }
        SearchWatch existing = watches.findByCustomerPhnoAndTerm(phno, term).orElse(null);
        if (existing != null) {
            if (existing.getNotifiedAt() != null) {
                existing.setNotifiedAt(null);
                return watches.save(existing);
            }
            return existing;
        }
        if (watches.countByCustomerPhno(phno) >= MAX_PER_CUSTOMER) {
            throw new ProductException("You can watch up to " + MAX_PER_CUSTOMER + " searches. Remove one first.");
        }
        SearchWatch watch = new SearchWatch();
        watch.setCustomerPhno(phno);
        watch.setTerm(term);
        watch.setCreatedAt(clock.instant());
        return watches.save(watch);
    }

    public List<SearchWatch> list(long phno) {
        return watches.findByCustomerPhnoOrderByIdDesc(phno);
    }

    @Transactional
    public void remove(long phno, String rawTerm) {
        String term = MissedSearchService.normalise(rawTerm);
        if (term != null) {
            watches.findByCustomerPhnoAndTerm(phno, term).ifPresent(watches::delete);
        }
    }
}

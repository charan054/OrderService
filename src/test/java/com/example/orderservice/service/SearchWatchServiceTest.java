package com.example.orderservice.service;

import com.example.orderservice.entity.SearchWatch;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.SearchWatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchWatchServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    @Mock
    private SearchWatchRepository watches;
    private SearchWatchService service;

    @BeforeEach
    void setUp() {
        service = new SearchWatchService(watches, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void addingANewTermSavesItNormalised() {
        when(watches.findByCustomerPhnoAndTerm(PHNO, "green tea")).thenReturn(Optional.empty());
        when(watches.countByCustomerPhno(PHNO)).thenReturn(0L);
        when(watches.save(any(SearchWatch.class))).thenAnswer(i -> i.getArgument(0));

        SearchWatch saved = service.add(PHNO, "  Green  TEA ");

        assertEquals("green tea", saved.getTerm());
        assertEquals(PHNO, saved.getCustomerPhno());
        assertEquals(NOW, saved.getCreatedAt());
        assertNull(saved.getNotifiedAt());
    }

    @Test
    void addingAnAlreadyFiredTermReArmsIt() {
        SearchWatch fired = new SearchWatch();
        fired.setTerm("oats");
        fired.setNotifiedAt(NOW);
        when(watches.findByCustomerPhnoAndTerm(PHNO, "oats")).thenReturn(Optional.of(fired));
        when(watches.save(fired)).thenReturn(fired);

        assertNull(service.add(PHNO, "oats").getNotifiedAt());
    }

    @Test
    void anInvalidTermOrTooManyWatchesAreRejected() {
        assertThrows(ProductException.class, () -> service.add(PHNO, "ab"));

        when(watches.findByCustomerPhnoAndTerm(PHNO, "oats")).thenReturn(Optional.empty());
        when(watches.countByCustomerPhno(PHNO)).thenReturn((long) SearchWatchService.MAX_PER_CUSTOMER);
        assertThrows(ProductException.class, () -> service.add(PHNO, "oats"));
        verify(watches, never()).save(any());
    }

    @Test
    void removingDeletesOnlyThatCustomersWatch() {
        SearchWatch mine = new SearchWatch();
        when(watches.findByCustomerPhnoAndTerm(PHNO, "oats")).thenReturn(Optional.of(mine));

        service.remove(PHNO, "OATS");

        verify(watches).delete(mine);
    }
}

package com.example.orderservice.service;

import com.example.orderservice.dto.BirthdayCouponResult;
import com.example.orderservice.dto.BulkCouponRequest;
import com.example.orderservice.dto.BulkCouponResult;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CustomerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BirthdayCouponServiceTest {
    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private CouponBatchService couponBatch;
    @Mock
    private MailService mailService;
    @Mock
    private EmailPreferenceService preferences;

    private BirthdayCouponService service;

    // 2026-10-14 noon UTC; the service zone is UTC so "today" is 14 October
    @BeforeEach
    void setUp() {
        service = new BirthdayCouponService(accounts, couponBatch, mailService, preferences,
                Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC), 10, 3, 14, "UTC", "");
    }

    private CustomerAccount account(int month, int day, String email) {
        CustomerAccount a = new CustomerAccount();
        a.setPhno(9000000001L);
        a.setEmail(email);
        a.setBirthMonth(month);
        a.setBirthDay(day);
        return a;
    }

    // ---------- window ----------

    @Test
    void windowCoversTheDaysAroundTheBirthday() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        assertEquals(2026, BirthdayCouponService.occurrenceYearNear(MonthDay.of(10, 14), today, 3));
        assertEquals(2026, BirthdayCouponService.occurrenceYearNear(MonthDay.of(10, 17), today, 3));
        assertEquals(2026, BirthdayCouponService.occurrenceYearNear(MonthDay.of(10, 11), today, 3));
        assertNull(BirthdayCouponService.occurrenceYearNear(MonthDay.of(10, 18), today, 3));
        assertNull(BirthdayCouponService.occurrenceYearNear(MonthDay.of(3, 1), today, 3));
    }

    @Test
    void windowWorksAcrossNewYear() {
        assertEquals(2027, BirthdayCouponService.occurrenceYearNear(MonthDay.of(1, 2), LocalDate.of(2026, 12, 30), 3));
        assertEquals(2026, BirthdayCouponService.occurrenceYearNear(MonthDay.of(12, 30), LocalDate.of(2027, 1, 1), 3));
    }

    @Test
    void aFeb29BirthdayIsCelebratedOnFeb28InACommonYear() {
        assertEquals(2027, BirthdayCouponService.occurrenceYearNear(MonthDay.of(2, 29), LocalDate.of(2027, 2, 28), 0));
    }

    // ---------- save / clear ----------

    @Test
    void setSavesAValidDateAndRejectsAnImpossibleOne() {
        CustomerAccount a = account(1, 1, "a@b.c");
        a.setBirthMonth(null);
        a.setBirthDay(null);
        when(accounts.findById(9000000001L)).thenReturn(Optional.of(a));

        assertEquals("02-29", service.set(9000000001L, 2, 29));
        assertEquals(2, a.getBirthMonth());
        assertThrows(ProductException.class, () -> service.set(9000000001L, 2, 30));
        assertThrows(ProductException.class, () -> service.set(9000000001L, 13, 1));
    }

    @Test
    void setNeedsAnAccount() {
        when(accounts.findById(9000000002L)).thenReturn(Optional.empty());
        assertThrows(ProductException.class, () -> service.set(9000000002L, 5, 5));
    }

    @Test
    void clearRemovesTheBirthday() {
        CustomerAccount a = account(5, 5, "a@b.c");
        when(accounts.findById(9000000001L)).thenReturn(Optional.of(a));

        service.clear(9000000001L);

        assertNull(a.getBirthMonth());
        assertNull(a.getBirthDay());
        assertTrue(service.get(9000000001L).isEmpty());
    }

    // ---------- run ----------

    @Test
    void runEmailsACouponOnceAndRemembersTheYear() {
        CustomerAccount a = account(10, 15, "a@b.c");
        when(accounts.findAll()).thenReturn(List.of(a));
        when(couponBatch.generate(any(BulkCouponRequest.class)))
                .thenReturn(new BulkCouponResult("BDAY", 10, Instant.now(), List.of("BDAY-ABCD2345")));
        when(preferences.footer(anyLong())).thenReturn("");
        when(mailService.send(eq("a@b.c"), anyString(), anyString())).thenReturn(true);

        BirthdayCouponResult first = service.run();

        assertEquals(1, first.emailsSent());
        assertEquals(2026, a.getBirthdayCouponYear());
        ArgumentCaptor<BulkCouponRequest> request = ArgumentCaptor.forClass(BulkCouponRequest.class);
        verify(couponBatch).generate(request.capture());
        assertEquals(1, request.getValue().count());
        assertEquals(10.0, request.getValue().discountPercent());
        assertEquals(Instant.parse("2026-10-28T12:00:00Z"), request.getValue().expiryDate());

        assertEquals(0, service.run().emailsSent(), "already sent for this year's birthday");
        verify(mailService, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void runSkipsOutsideTheWindowOptOutsAndMissingEmails() {
        CustomerAccount far = account(3, 1, "a@b.c");
        CustomerAccount optedOut = account(10, 14, "b@b.c");
        optedOut.setMarketingOptOut(true);
        CustomerAccount noEmail = account(10, 14, " ");
        CustomerAccount none = new CustomerAccount();
        when(accounts.findAll()).thenReturn(List.of(far, optedOut, noEmail, none));

        BirthdayCouponResult result = service.run();

        assertEquals(0, result.emailsSent());
        assertEquals(1, result.optedOut());
        assertEquals(1, result.customersWithoutEmail());
        verifyNoInteractions(couponBatch, mailService);
    }

    @Test
    void aFailedSendIsRetriedOnTheNextRun() {
        CustomerAccount a = account(10, 14, "a@b.c");
        when(accounts.findAll()).thenReturn(List.of(a));
        when(couponBatch.generate(any(BulkCouponRequest.class)))
                .thenReturn(new BulkCouponResult("BDAY", 10, Instant.now(), List.of("BDAY-ABCD2345")));
        when(preferences.footer(anyLong())).thenReturn("");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(false);

        BirthdayCouponResult result = service.run();

        assertEquals(1, result.sendFailures());
        assertNull(a.getBirthdayCouponYear());
    }
}

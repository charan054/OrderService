package com.example.orderservice.service;

import com.example.orderservice.dto.CustomerDataExport;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.LoyaltyTransactionRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.OrderFeedbackRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.ReferralRepository;
import com.example.orderservice.repository.SavedCartRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.StockWaitlistRepository;
import com.example.orderservice.repository.WishlistRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * "Download my data": a read-only snapshot of everything stored about one phone number in this service. It reads
 * straight from the repositories rather than through the feature services, because those apply side effects on
 * read (e.g. loyalty points expiry) and an export must never change the data it is exporting.
 */
@Service
public class CustomerDataExportService {
    private final CustomerAccountRepository accounts;
    private final CartRepository orders;
    private final ShippingAddressRepository addresses;
    private final WishlistRepository wishlist;
    private final StockWaitlistRepository waitlist;
    private final LoyaltyAccountRepository loyalty;
    private final LoyaltyTransactionRepository loyaltyTransactions;
    private final ProductQuestionRepository questions;
    private final OrderFeedbackRepository feedback;
    private final SavedCartRepository savedCarts;
    private final NotificationLogRepository notifications;
    private final ReferralRepository referrals;
    private final Clock clock;

    public CustomerDataExportService(CustomerAccountRepository accounts, CartRepository orders,
                                     ShippingAddressRepository addresses, WishlistRepository wishlist,
                                     StockWaitlistRepository waitlist, LoyaltyAccountRepository loyalty,
                                     LoyaltyTransactionRepository loyaltyTransactions,
                                     ProductQuestionRepository questions, OrderFeedbackRepository feedback,
                                     SavedCartRepository savedCarts, NotificationLogRepository notifications,
                                     ReferralRepository referrals, Clock clock) {
        this.accounts = accounts;
        this.orders = orders;
        this.addresses = addresses;
        this.wishlist = wishlist;
        this.waitlist = waitlist;
        this.loyalty = loyalty;
        this.loyaltyTransactions = loyaltyTransactions;
        this.questions = questions;
        this.feedback = feedback;
        this.savedCarts = savedCarts;
        this.notifications = notifications;
        this.referrals = referrals;
        this.clock = clock;
    }

    public CustomerDataExport export(long phno) {
        String digits = String.valueOf(phno);
        if (digits.length() != 10 || !digits.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
        CustomerAccount account = accounts.findById(phno).orElse(null);
        List<Cart> myOrders = orders.findBycustomerPhno(phno);
        List<Long> orderIds = myOrders.stream().map(Cart::getOrderId).toList();
        // An empty IN () list is invalid SQL on some databases - skip the query when there are no orders.
        var myNotifications = orderIds.isEmpty() ? List.<com.example.orderservice.entity.NotificationLog>of()
                : notifications.findByOrderIdInOrderBySentAtDesc(orderIds);
        List<com.example.orderservice.entity.OrderFeedback> myFeedback = feedback.findByCustomerPhnoOrderByIdDesc(phno);
        LoyaltyAccount loyaltyAccount = loyalty.findById(phno).orElse(null);
        SavedCart savedCart = savedCarts.findById(phno).orElse(null);

        return new CustomerDataExport(Instant.now(clock), phno,
                account == null ? null : new CustomerDataExport.Account(
                        account.getEmail(), account.getVerifiedAt(), account.getReferralCode()),
                myOrders, addresses.findByCustomerPhno(phno), wishlist.findByCustomerPhno(phno),
                waitlist.findByCustomerPhno(phno), loyaltyAccount,
                loyaltyTransactions.findByCustomerPhnoOrderByTimestampDesc(phno),
                questions.findByAskerPhnoOrderByIdDesc(phno), myFeedback, savedCart, myNotifications,
                new CustomerDataExport.Referrals(referrals.findByReferrerPhno(phno).size(),
                        referrals.findByRefereePhno(phno).isPresent()));
    }
}

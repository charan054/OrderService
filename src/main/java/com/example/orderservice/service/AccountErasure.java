package com.example.orderservice.service;

import com.example.orderservice.dto.AccountDeletionResult;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.entity.StoreCreditAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of "delete my account", all in ONE transaction so a failure part-way leaves everything as it was.
 * <ul>
 *   <li>Erased: sign-in (email binding, referral code, sessions, codes), saved addresses, wishlist and its share link,
 *   back-in-stock alerts, saved cart, subscriptions, support requests and their messages, loyalty and store-credit
 *   accounts with their history, referrals, per-coupon redemption counts and any cash-on-delivery override.</li>
 *   <li>Kept but anonymised: orders (name becomes a placeholder, phone number 0, and the UPI id and free-text notes are
 *   cleared) because invoices and tax reports need them; the address an order shipped to is reduced to its state, which
 *   decides CGST/SGST versus IGST; product questions, order ratings and redeemed gift cards lose the phone number.</li>
 *   <li>Not touched: the admin audit log (it records actions and ids, never customer data) and internal order notes.</li>
 * </ul>
 * Phone number 0 is not a valid mobile number, so anonymised rows can never be matched to a real person or sign-in.
 */
@Component
public class AccountErasure {
    static final String PLACEHOLDER_NAME = "Deleted customer";

    @PersistenceContext
    private EntityManager em;

    private int run(String jpql, long phno) {
        return em.createQuery(jpql).setParameter("p", phno).executeUpdate();
    }

    @Transactional
    public AccountDeletionResult erase(long phno, int reviewsAnonymised) {
        // Orders first: they decide which addresses must be kept (reduced) rather than deleted.
        int orders = em.createQuery("update Cart c set c.customerName = :n, c.customerPhno = 0, c.upiId = null, "
                        + "c.deliveryNote = null, c.cancelNote = null where c.customerPhno = :p")
                .setParameter("n", PLACEHOLDER_NAME).setParameter("p", phno).executeUpdate();
        // An address an order shipped to keeps only its state (place of supply for GST); every other one is deleted.
        run("update ShippingAddress a set a.customerPhno = 0, a.label = null, a.line1 = 'Address erased', a.line2 = null, "
                + "a.city = null, a.pincode = null, a.isDefault = false where a.customerPhno = :p "
                + "and a.id in (select c.shippingAddressId from Cart c where c.shippingAddressId is not null)", phno);
        int addresses = run("delete from ShippingAddress a where a.customerPhno = :p", phno);

        run("update ProductQuestion q set q.askerPhno = 0 where q.askerPhno = :p", phno);
        run("update OrderFeedback f set f.customerPhno = 0, f.comment = null where f.customerPhno = :p", phno);
        run("update GiftCard g set g.redeemedBy = 0 where g.redeemedBy = :p", phno);

        int wishlist = run("delete from Wishlist w where w.customerPhno = :p", phno);
        run("delete from WishlistShare s where s.customerPhno = :p", phno);
        int alerts = run("delete from StockWaitlist w where w.customerPhno = :p", phno);
        SavedCart savedCart = em.find(SavedCart.class, phno);
        if (savedCart != null) {
            em.remove(savedCart);
        }
        int subscriptions = run("delete from Subscription s where s.customerPhno = :p", phno);
        run("delete from SupportMessage m where m.ticketId in (select t.id from SupportTicket t where t.customerPhno = :p)", phno);
        int tickets = run("delete from SupportTicket t where t.customerPhno = :p", phno);

        LoyaltyAccount loyalty = em.find(LoyaltyAccount.class, phno);
        int points = loyalty == null ? 0 : loyalty.getPointsBalance();
        run("delete from LoyaltyTransaction t where t.customerPhno = :p", phno);
        run("delete from LoyaltyAccount a where a.customerPhno = :p", phno);
        StoreCreditAccount credit = em.find(StoreCreditAccount.class, phno);
        double creditBalance = credit == null ? 0 : credit.getBalance();
        run("delete from StoreCreditTransaction t where t.phno = :p", phno);
        run("delete from StoreCreditAccount a where a.phno = :p", phno);

        run("delete from Referral r where r.referrerPhno = :p or r.refereePhno = :p", phno);
        run("delete from CouponRedemption r where r.customerPhno = :p", phno);
        run("delete from CodOverride o where o.phno = :p", phno);

        run("delete from CustomerSession s where s.phno = :p", phno);
        run("delete from CustomerLoginCode c where c.phno = :p", phno);
        run("delete from AccountDeletionCode c where c.phno = :p", phno);
        run("delete from CustomerAccount a where a.phno = :p", phno);
        em.flush();
        em.clear();
        return new AccountDeletionResult(orders, addresses, wishlist, alerts, subscriptions, tickets, reviewsAnonymised,
                points, creditBalance);
    }
}

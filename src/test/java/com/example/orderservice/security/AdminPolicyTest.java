package com.example.orderservice.security;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.kafka.OrderKafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminPolicyTest {
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private static AdminRole required(String method, String path) {
        return AdminPolicy.requiredRole(method, path);
    }

    @Test
    void supportCanLookThingsUpAndAnswerCustomers() {
        assertThat(required("GET", "/cart/byphno")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("GET", "/cart/42/invoice")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("GET", "/customer/admin/lookup")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("POST", "/support/admin/tickets/7/reply")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("PUT", "/questions/3/answer")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("POST", "/ordernotes")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("GET", "/cart/display")).isEqualTo(AdminRole.SUPPORT);
    }

    @Test
    void moneyAndConfigurationNeedAManager() {
        for (String[] call : new String[][]{
                {"POST", "/cart/42/ship"}, {"POST", "/cart/42/deliver"}, {"POST", "/cart/42/markpaid"},
                {"POST", "/cart/42/cancel"}, {"POST", "/cart/42/return"}, {"POST", "/cart/42/items/3/cancel"},
                {"POST", "/cart/bulk/ship"}, {"POST", "/coupons/add"}, {"POST", "/coupons/bulk"}, {"POST", "/pincodes/add"},
                {"DELETE", "/pincodes/remove"}, {"GET", "/coupons/all"}, {"GET", "/cart/analytics"}, {"GET", "/cart/orders/export"},
                {"GET", "/cart/analytics/customers/export"}, {"PUT", "/customer/admin/cod"}, {"DELETE", "/questions/3"},
                {"DELETE", "/ordernotes/9"}, {"PUT", "/cart/reviews/5/hide"}, {"POST", "/cart/checkout"}, {"PUT", "/cart/42/delivery"}}) {
            assertThat(required(call[0], call[1])).as(call[0] + " " + call[1]).isEqualTo(AdminRole.MANAGER);
        }
    }

    @Test
    void accountsTheAuditLogAndHandingOutBalancesNeedAnOwner() {
        for (String[] call : new String[][]{
                {"GET", "/admin/accounts"}, {"POST", "/admin/accounts"}, {"PUT", "/admin/accounts/asha/role"},
                {"PUT", "/admin/accounts/asha/password"}, {"GET", "/admin/anything-new"}, {"GET", "/audit/recent"},
                {"POST", "/storecredit/adjust"}, {"POST", "/loyalty/adjust"}, {"PUT", "/customer/admin/email"}}) {
            assertThat(required(call[0], call[1])).as(call[0] + " " + call[1]).isEqualTo(AdminRole.OWNER);
        }
    }

    @Test
    void anyAdminMaySignOutAndManageTheirOwnLogin() {
        assertThat(required("POST", "/admin/login")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("POST", "/admin/logout")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("GET", "/admin/me")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("PUT", "/admin/me/password")).isEqualTo(AdminRole.SUPPORT);
    }

    @Test
    void thePathMatchesTheWayTheRouterDoes() {
        // Same method on a different path, a different method on the same path, and a look-alike all fall through
        // to the default rather than inheriting a SUPPORT rule.
        assertThat(required("DELETE", "/cart/byphno")).isEqualTo(AdminRole.MANAGER);
        assertThat(required("POST", "/cart/all")).isEqualTo(AdminRole.MANAGER);
        assertThat(required("GET", "/cart/42/invoice/email")).isEqualTo(AdminRole.MANAGER);
        assertThat(required("GET", "/cart/all/extra")).isEqualTo(AdminRole.MANAGER);
        assertThat(required("GET", "/cart/%61ll")).isEqualTo(AdminRole.SUPPORT);
        assertThat(required("GET", "/cart/orders/export")).isEqualTo(AdminRole.MANAGER);
    }

    @Test
    void roleOrderingIsSupportThenManagerThenOwner() {
        assertThat(AdminPolicy.allows(AdminRole.SUPPORT, "GET", "/cart/all")).isTrue();
        assertThat(AdminPolicy.allows(AdminRole.SUPPORT, "POST", "/cart/1/ship")).isFalse();
        assertThat(AdminPolicy.allows(AdminRole.MANAGER, "POST", "/cart/1/ship")).isTrue();
        assertThat(AdminPolicy.allows(AdminRole.MANAGER, "GET", "/audit/recent")).isFalse();
        assertThat(AdminPolicy.allows(AdminRole.OWNER, "GET", "/audit/recent")).isTrue();
        assertThat(AdminPolicy.allows(AdminRole.OWNER, "POST", "/cart/1/ship")).isTrue();
    }

    // A typo in a rule would silently leave that endpoint on the MANAGER default (or, worse, hide that an OWNER rule
    // protects nothing), so every non-wildcard rule must name a real endpoint.
    @Test
    void everyNonWildcardRuleNamesARealEndpoint() {
        Set<String> real = new HashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod method : methods) {
                    real.add(method + " " + normalise(pattern));
                }
            }
        }
        for (AdminPolicy.Rule rule : AdminPolicy.RULES) {
            for (String method : rule.methods()) {
                if (!rule.path().contains("**")) {
                    assertThat(real).as("rule %s %s", method, rule.path()).contains(method + " " + normalise(rule.path()));
                }
            }
        }
    }

    private static String normalise(String pattern) {
        return pattern.replaceAll("\\{[^}]*}", "{}");
    }
}

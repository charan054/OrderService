package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.MarginCheck;
import com.example.orderservice.dto.ProductCost;
import com.example.orderservice.exception.ProductException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Warns when a coupon's percent off would make a product sell below what it costs the shop. */
@Service
public class CouponMarginService {
    private final ProductClient productClient;
    private final String serviceApiKey;

    public CouponMarginService(ProductClient productClient, @Value("${internal.service.api-key}") String serviceApiKey) {
        this.productClient = productClient;
        this.serviceApiKey = serviceApiKey;
    }

    public MarginCheck check(double discountPercent) {
        if (Double.isNaN(discountPercent) || discountPercent < 0 || discountPercent > 100) {
            throw new ProductException("Discount percent must be between 0 and 100");
        }
        List<ProductCost> costs = productClient.getCosts(serviceApiKey);
        List<MarginCheck.AtLoss> atLoss = costs.stream()
                .map(c -> {
                    double after = round2(c.productPrice() * (1 - discountPercent / 100.0));
                    return new MarginCheck.AtLoss(c.productId(), c.productName(), c.productPrice(), c.costPrice(), after,
                            round2(c.costPrice() - after));
                })
                .filter(r -> r.lossPerUnit() > 0)
                .sorted(Comparator.comparingDouble(MarginCheck.AtLoss::lossPerUnit).reversed())
                .toList();
        return new MarginCheck(discountPercent, costs.size(), atLoss);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

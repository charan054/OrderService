package com.example.orderservice.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** The carrier's own "track this parcel" page for the couriers shops here commonly use; null for anyone else. */
public final class TrackingUrls {
    private static final Map<String, String> TEMPLATES = Map.of(
            "delhivery", "https://www.delhivery.com/track-v2/package/%s",
            "bluedart", "https://www.bluedart.com/tracking?trackfor=%s",
            "dtdc", "https://www.dtdc.in/tracking/shipment-tracking.asp?strCnno=%s",
            "indiapost", "https://www.indiapost.gov.in/_layouts/15/dop.portal.tracking/trackconsignment.aspx?consignmentno=%s",
            "ecomexpress", "https://ecomexpress.in/tracking/?awb_field=%s",
            "xpressbees", "https://www.xpressbees.com/shipment/tracking?awb=%s");

    private TrackingUrls() {
    }

    public static String forShipment(String carrier, String trackingNumber) {
        if (carrier == null || trackingNumber == null || trackingNumber.isBlank()) {
            return null;
        }
        String key = carrier.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        String template = TEMPLATES.get(key);
        return template == null ? null : String.format(template, URLEncoder.encode(trackingNumber.trim(), StandardCharsets.UTF_8));
    }
}

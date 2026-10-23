package com.example.orderservice.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrackingUrlsTest {

    @Test
    void knownCarriersGetTheirOwnTrackingPageWhateverTheSpellingOrCase() {
        assertEquals("https://www.delhivery.com/track-v2/package/AB123", TrackingUrls.forShipment("Delhivery", "AB123"));
        assertEquals("https://www.bluedart.com/tracking?trackfor=777", TrackingUrls.forShipment("  BLUE DART ", "777"));
        assertEquals("https://www.dtdc.in/tracking/shipment-tracking.asp?strCnno=X1", TrackingUrls.forShipment("dtdc", "X1"));
    }

    @Test
    void theTrackingNumberIsUrlEncoded() {
        assertEquals("https://www.delhivery.com/track-v2/package/A+B%26C", TrackingUrls.forShipment("Delhivery", "A B&C"));
    }

    @Test
    void unknownCarrierOrMissingNumberGivesNoLink() {
        assertNull(TrackingUrls.forShipment("Local Courier", "123"));
        assertNull(TrackingUrls.forShipment("Delhivery", " "));
        assertNull(TrackingUrls.forShipment("Delhivery", null));
        assertNull(TrackingUrls.forShipment(null, "123"));
    }
}

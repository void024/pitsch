package com.pitsch.backend.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import org.junit.jupiter.api.Test;

class BillingUnitTests {

    static final String SECRET = "whsec_test_secret";
    static final Instant NOW = Instant.parse("2030-05-01T12:00:00Z");

    StripeClient client() {
        PitschProperties p = new PitschProperties();
        p.getBilling().setProvider("stripe");
        p.getBilling().setStripeWebhookSecret(SECRET);
        return new StripeClient(p, new Json(new ObjectMapper()), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static String sign(long t, String payload) {
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(StripeClient.hmac(SECRET, t + "." + payload));
    }

    @Test
    void stripeSignaturesAreVerified() {
        StripeClient c = client();
        String payload = "{\"id\":\"evt_1\",\"type\":\"customer.subscription.updated\"}";
        long t = NOW.getEpochSecond();
        assertTrue(c.verifySignature(payload, sign(t, payload)));
        assertTrue(c.verifySignature(payload, "t=" + t + ",v1=00ff," + sign(t, payload).substring(sign(t, payload).indexOf("v1="))),
                "any matching v1 signature is accepted (secret rotation)");
        assertFalse(c.verifySignature(payload + " ", sign(t, payload)), "body changed");
        assertFalse(c.verifySignature(payload, sign(t - 3600, payload)), "replayed outside the tolerance window");
        assertFalse(c.verifySignature(payload, "t=" + t + ",v1=zz"));
        assertFalse(c.verifySignature(payload, null));
    }

    @Test
    void stripeStatusesMapConservatively() {
        assertEquals("ACTIVE", BillingService.mapStatus("active"));
        assertEquals("TRIALING", BillingService.mapStatus("trialing"));
        assertEquals("PAST_DUE", BillingService.mapStatus("past_due"));
        assertEquals("CANCELED", BillingService.mapStatus("unpaid"));
        assertEquals("INCOMPLETE", BillingService.mapStatus("something_new"));
    }

    @Test
    void lapsedSubscriptionsFallBackToFree() {
        Subscription s = new Subscription();
        s.setPlanCode("PRO");
        s.setStatus("ACTIVE");
        assertEquals("PRO", s.effectivePlanCode());
        s.setStatus("PAST_DUE");
        assertEquals("PRO", s.effectivePlanCode());
        s.setStatus("CANCELED");
        assertEquals("FREE", s.effectivePlanCode());
        s.setStatus("INCOMPLETE");
        assertEquals("FREE", s.effectivePlanCode());
    }

    @Test
    void priceIdsAreParsedFromConfiguration() {
        PitschProperties.Billing b = new PitschProperties().getBilling();
        b.setStripePriceIds("pro:price_1, TEAM:price_2,broken");
        assertEquals("price_1", b.priceIdsByPlan().get("PRO"));
        assertEquals("price_2", b.priceIdsByPlan().get("TEAM"));
        assertEquals(2, b.priceIdsByPlan().size());
    }
}

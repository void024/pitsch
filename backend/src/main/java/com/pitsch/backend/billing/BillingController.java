package com.pitsch.backend.billing;

import java.util.List;
import java.util.Map;

import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.PublicEndpoint;
import com.pitsch.backend.auth.RequiresPermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BillingController {

    public record CheckoutRequest(String planCode) { }

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/api/v1/billing")
    @RequiresPermission(Permission.USAGE_READ)
    public BillingService.BillingView view(AuthPrincipal principal) {
        return billing.view(principal.orgId());
    }

    @GetMapping("/api/v1/billing/plans")
    public List<BillingService.PlanView> plans() {
        return billing.plans();
    }

    @PostMapping("/api/v1/billing/checkout")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public Map<String, String> checkout(AuthPrincipal principal, @RequestBody CheckoutRequest req) {
        return Map.of("url", billing.checkout(principal, req.planCode()));
    }

    @PostMapping("/api/v1/billing/portal")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public Map<String, String> portal(AuthPrincipal principal) {
        return Map.of("url", billing.portal(principal));
    }

    /** Stripe webhook: authenticated by its signature (raw body), not by a session. */
    @PostMapping("/api/v1/webhooks/stripe")
    @PublicEndpoint
    public ResponseEntity<Map<String, Object>> webhook(@RequestBody String payload,
                                                       @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        billing.handleWebhook(payload, signature);
        return ResponseEntity.ok(Map.of("received", true));
    }
}

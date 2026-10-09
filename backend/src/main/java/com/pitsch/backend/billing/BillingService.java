package com.pitsch.backend.billing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.integration.InboundEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans, subscription state and the Stripe lifecycle. Subscription state changes ONLY through verified Stripe
 * webhooks (or by an operator in the database for invoiced/enterprise plans) — a redirect back from Checkout never
 * upgrades a workspace by itself.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    public record PlanView(String code, String name, int monthlyPriceCents, JsonNode limits, boolean purchasable) { }

    public record BillingView(String planCode, String effectivePlanCode, String status, String provider,
                              Instant currentPeriodEnd, boolean cancelAtPeriodEnd, boolean onlineBillingEnabled,
                              boolean hasBillingAccount, Map<String, EntitlementService.Quota> usage, List<PlanView> plans) { }

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final EntitlementService entitlements;
    private final StripeClient stripe;
    private final InboundEventService inbound;
    private final AuditService audit;
    private final PitschProperties props;
    private final Json json;

    public BillingService(SubscriptionRepository subscriptions, PlanRepository plans, EntitlementService entitlements,
                          StripeClient stripe, InboundEventService inbound, AuditService audit, PitschProperties props,
                          Json json) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.entitlements = entitlements;
        this.stripe = stripe;
        this.inbound = inbound;
        this.audit = audit;
        this.props = props;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<PlanView> plans() {
        Map<String, String> prices = props.getBilling().priceIdsByPlan();
        List<PlanView> out = new ArrayList<>();
        for (Plan p : plans.findAll().stream().filter(Plan::isActive).sorted(Comparator.comparingInt(Plan::getSortOrder)).toList()) {
            boolean purchasable = stripe.configured() && priceId(p, prices) != null;
            out.add(new PlanView(p.getCode(), p.getName(), p.getMonthlyPriceCents(), json.read(p.getLimitsJson()), purchasable));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public BillingView view(Long orgId) {
        Subscription s = subscriptions.findByOrganizationId(orgId).orElse(null);
        Map<String, EntitlementService.Quota> usage = new LinkedHashMap<>();
        entitlements.quotas(orgId).forEach((k, v) -> usage.put(k.name(), v));
        return new BillingView(s == null ? "FREE" : s.getPlanCode(), s == null ? "FREE" : s.effectivePlanCode(),
                s == null ? Subscription.Status.ACTIVE.name() : s.getStatus(), s == null ? "NONE" : s.getProvider(),
                s == null ? null : s.getCurrentPeriodEnd(), s != null && s.isCancelAtPeriodEnd(), stripe.configured(),
                s != null && s.getProviderCustomerId() != null, usage, plans());
    }

    /** Starts a Stripe Checkout session; returns the hosted URL to redirect to. */
    @Transactional(readOnly = true)
    public String checkout(AuthPrincipal principal, String planCode) {
        principal.require(Permission.BILLING_MANAGE);
        Plan plan = plans.findById(planCode == null ? "" : planCode.toUpperCase(Locale.ROOT))
                .filter(Plan::isActive).orElseThrow(() -> ApiException.badRequest("Unknown plan"));
        String price = priceId(plan, props.getBilling().priceIdsByPlan());
        if (!stripe.configured() || price == null) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "The " + plan.getName() + " plan cannot be purchased online. Contact sales.");
        }
        Long orgId = principal.orgId();
        Subscription sub = subscriptions.findByOrganizationId(orgId).orElse(null);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "subscription");
        form.put("line_items[0][price]", price);
        form.put("line_items[0][quantity]", "1");
        form.put("success_url", props.getFrontendUrl() + "/settings/billing?checkout=success");
        form.put("cancel_url", props.getFrontendUrl() + "/settings/billing?checkout=cancelled");
        form.put("client_reference_id", String.valueOf(orgId));
        form.put("metadata[organization_id]", String.valueOf(orgId));
        form.put("subscription_data[metadata][organization_id]", String.valueOf(orgId));
        form.put("allow_promotion_codes", "true");
        if (sub != null && sub.getProviderCustomerId() != null) {
            form.put("customer", sub.getProviderCustomerId());
        } else {
            form.put("customer_email", principal.email());
        }
        JsonNode session = stripe.post("checkout/sessions", form, null);
        String url = session.path("url").asText(null);
        if (url == null || !url.startsWith("https://")) {
            throw new ApiException(ErrorCode.INTEGRATION_ERROR, "The billing provider returned no checkout page.");
        }
        return url;
    }

    /** Stripe customer portal (update card, cancel, invoices). */
    @Transactional(readOnly = true)
    public String portal(AuthPrincipal principal) {
        principal.require(Permission.BILLING_MANAGE);
        Subscription sub = subscriptions.findByOrganizationId(principal.orgId()).orElse(null);
        if (sub == null || sub.getProviderCustomerId() == null) {
            throw ApiException.badRequest("This workspace has no billing account yet.");
        }
        JsonNode session = stripe.post("billing_portal/sessions", Map.of("customer", sub.getProviderCustomerId(),
                "return_url", props.getFrontendUrl() + "/settings/billing"), null);
        String url = session.path("url").asText(null);
        if (url == null || !url.startsWith("https://")) {
            throw new ApiException(ErrorCode.INTEGRATION_ERROR, "The billing provider returned no portal page.");
        }
        return url;
    }

    /** Verified, de-duplicated Stripe webhook. Unknown event types are acknowledged and ignored. */
    @Transactional
    public void handleWebhook(String payload, String signature) {
        if (!stripe.verifySignature(payload, signature)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Invalid signature");
        }
        JsonNode event = json.read(payload);
        if (event == null || event.path("id").asText("").isEmpty()) {
            throw ApiException.badRequest("Malformed event");
        }
        if (!inbound.firstDelivery("stripe", event.path("id").asText())) {
            return;
        }
        String type = event.path("type").asText("");
        JsonNode obj = event.path("data").path("object");
        switch (type) {
            case "checkout.session.completed" -> linkCheckout(obj);
            case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" ->
                    applySubscription(obj, "customer.subscription.deleted".equals(type));
            default -> log.debug("Ignoring Stripe event type {}", type);
        }
    }

    private void linkCheckout(JsonNode session) {
        Long orgId = orgIdFrom(session.path("metadata"), session.path("client_reference_id").asText(null));
        if (orgId == null) {
            return;
        }
        Subscription sub = subscriptions.findByOrganizationId(orgId).orElse(null);
        if (sub == null) {
            return;
        }
        String customer = session.path("customer").asText(null);
        String subscriptionId = session.path("subscription").asText(null);
        if (customer != null) {
            sub.setProviderCustomerId(customer);
        }
        if (subscriptionId != null) {
            sub.setProviderSubscriptionId(subscriptionId);
        }
        sub.setProvider("STRIPE");
        subscriptions.save(sub);
    }

    private void applySubscription(JsonNode s, boolean deleted) {
        String subscriptionId = s.path("id").asText(null);
        Subscription sub = subscriptionId == null ? null : subscriptions.findByProviderSubscriptionId(subscriptionId).orElse(null);
        if (sub == null) {
            Long orgId = orgIdFrom(s.path("metadata"), null);
            sub = orgId == null ? null : subscriptions.findByOrganizationId(orgId).orElse(null);
        }
        if (sub == null) {
            String customer = s.path("customer").asText(null);
            sub = customer == null ? null : subscriptions.findByProviderCustomerId(customer).orElse(null);
        }
        if (sub == null) {
            log.warn("Stripe subscription {} does not match any workspace", subscriptionId);
            return;
        }
        String oldPlan = sub.getPlanCode();
        String oldStatus = sub.getStatus();
        JsonNode item = s.path("items").path("data").path(0);
        String priceId = item.path("price").path("id").asText(null);
        String plan = planForPrice(priceId);
        if (plan != null) {
            sub.setPlanCode(plan);
        }
        sub.setProvider("STRIPE");
        sub.setProviderSubscriptionId(subscriptionId);
        if (s.hasNonNull("customer")) {
            sub.setProviderCustomerId(s.path("customer").asText());
        }
        sub.setStatus(deleted ? Subscription.Status.CANCELED.name() : mapStatus(s.path("status").asText("")));
        sub.setCancelAtPeriodEnd(s.path("cancel_at_period_end").asBoolean(false));
        // Older API versions put the period on the subscription, newer ones on the item.
        long start = s.path("current_period_start").asLong(item.path("current_period_start").asLong(0));
        long end = s.path("current_period_end").asLong(item.path("current_period_end").asLong(0));
        sub.setCurrentPeriodStart(start > 0 ? Instant.ofEpochSecond(start) : null);
        sub.setCurrentPeriodEnd(end > 0 ? Instant.ofEpochSecond(end) : null);
        subscriptions.save(sub);
        audit.record(sub.getOrganizationId(), null, AuditAction.SUBSCRIPTION_CHANGED, "SUBSCRIPTION", sub.getId(),
                Map.of("plan", oldPlan + "->" + sub.getPlanCode(), "status", oldStatus + "->" + sub.getStatus()));
    }

    static String mapStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "active" -> Subscription.Status.ACTIVE.name();
            case "trialing" -> Subscription.Status.TRIALING.name();
            case "past_due" -> Subscription.Status.PAST_DUE.name();
            case "canceled", "unpaid", "incomplete_expired" -> Subscription.Status.CANCELED.name();
            default -> Subscription.Status.INCOMPLETE.name();   // incomplete, paused, anything new
        };
    }

    private String planForPrice(String priceId) {
        if (priceId == null) {
            return null;
        }
        for (Map.Entry<String, String> e : props.getBilling().priceIdsByPlan().entrySet()) {
            if (e.getValue().equals(priceId)) {
                return e.getKey();
            }
        }
        return plans.findAll().stream().filter(p -> priceId.equals(p.getStripePriceId())).map(Plan::getCode).findFirst().orElse(null);
    }

    private static String priceId(Plan plan, Map<String, String> configured) {
        String fromEnv = configured.get(plan.getCode());
        return fromEnv != null ? fromEnv : plan.getStripePriceId();
    }

    private static Long orgIdFrom(JsonNode metadata, String fallback) {
        String v = metadata.path("organization_id").asText(null);
        if (v == null) {
            v = fallback;
        }
        try {
            return v == null ? null : Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

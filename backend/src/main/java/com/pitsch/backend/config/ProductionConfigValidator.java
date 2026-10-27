package com.pitsch.backend.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup validation. In production the application refuses to start (and lists every problem at once,
 * without printing secret values) if it would run with insecure or simulated settings. In other modes the same checks
 * only log warnings. Tested by ProductionConfigValidatorTest.
 */
@Component
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);
    private static final List<String> KNOWN_WEAK_SECRETS = List.of("dev-only-secret", "change-me", "changeme", "secret",
            "pitsch-secret", "your-secret");

    public ProductionConfigValidator(PitschProperties props, Environment env) {
        List<String> problems = validate(props, env.getProperty("spring.datasource.url", ""),
                env.getProperty("spring.mail.host", ""), Boolean.parseBoolean(env.getProperty("springdoc.api-docs.enabled", "false")));
        if (problems.isEmpty()) {
            return;
        }
        if (props.isProduction()) {
            throw new IllegalStateException("Refusing to start in production mode:\n - " + String.join("\n - ", problems));
        }
        for (String p : problems) {
            log.warn("[{} mode] {}", props.getMode(), p);
        }
    }

    /** Pure function for tests. Returns human-readable problems (never containing secret values). */
    public static List<String> validate(PitschProperties p, String datasourceUrl, String smtpHost, boolean apiDocsEnabled) {
        List<String> out = new ArrayList<>();
        PitschProperties.Security sec = p.getSecurity();

        String jwt = sec.getJwtSecret();
        if (blank(jwt)) {
            out.add("JWT_SECRET is not set");
        } else if (jwt.length() < 43) {
            out.add("JWT_SECRET must be at least 43 characters (32 random bytes, base64)");
        } else if (KNOWN_WEAK_SECRETS.stream().anyMatch(w -> jwt.toLowerCase(Locale.ROOT).contains(w))) {
            out.add("JWT_SECRET looks like a placeholder/default value");
        }
        if (blank(sec.getEncryptionKeys())) {
            out.add("PITSCH_ENCRYPTION_KEYS is not set (needed to encrypt OAuth tokens)");
        }
        if (!sec.isCookieSecure()) {
            out.add("COOKIE_SECURE must be true");
        }
        if (!sec.isRequireEmailVerification()) {
            out.add("REQUIRE_EMAIL_VERIFICATION must be true");
        }
        if (sec.getPasswordHashIterations() < 210_000) {
            out.add("PASSWORD_HASH_ITERATIONS must be at least 210000 (OWASP PBKDF2-SHA256)");
        }

        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:postgresql:")) {
            out.add("DB_URL must point to PostgreSQL (H2 and other databases are not supported in production)");
        }

        List<String> origins = p.getCors().getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            out.add("CORS_ALLOWED_ORIGINS is empty");
        } else {
            for (String o : origins) {
                if ("*".equals(o.trim())) {
                    out.add("CORS_ALLOWED_ORIGINS must not contain *");
                } else if (!o.trim().startsWith("https://")) {
                    out.add("CORS origin '" + o.trim() + "' must use https");
                }
            }
        }
        httpsUrl(out, "FRONTEND_URL", p.getFrontendUrl());
        httpsUrl(out, "PUBLIC_API_URL", p.getPublicApiUrl());

        if (blank(p.getAi().getInternalToken()) || p.getAi().getInternalToken().length() < 32) {
            out.add("AI_SERVICE_TOKEN must be set (at least 32 characters) and match the AI service");
        }
        if (blank(p.getAi().getBaseUrl())) {
            out.add("AI_SERVICE_URL is not set");
        }

        if (!"smtp".equalsIgnoreCase(p.getMail().getProvider())) {
            out.add("MAIL_PROVIDER must be smtp (log mail is for development only)");
        } else if (blank(smtpHost)) {
            out.add("SMTP_HOST is not set (verification and password-reset emails cannot be delivered)");
        }

        if (!"s3".equalsIgnoreCase(p.getStorage().getProvider())) {
            out.add("STORAGE_PROVIDER must be s3 (local disk storage is for development only)");
        } else if (blank(p.getStorage().getS3().getBucket())) {
            out.add("S3_BUCKET is not set");
        }

        PitschProperties.Google g = p.getGoogle();
        boolean anyGoogle = !blank(g.getClientId()) || !blank(g.getClientSecret()) || !blank(g.getRedirectUri());
        if (anyGoogle && !g.isConfigured()) {
            out.add("Google OAuth is partially configured: set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and GOOGLE_REDIRECT_URI together");
        }
        if (g.isConfigured() && !g.getRedirectUri().startsWith("https://")) {
            out.add("GOOGLE_REDIRECT_URI must use https");
        }
        if (!blank(g.getPubsubTopic()) && (blank(g.getPubsubAudience()) || blank(g.getPubsubServiceAccount()))) {
            out.add("Gmail push needs GOOGLE_PUBSUB_AUDIENCE and GOOGLE_PUBSUB_SERVICE_ACCOUNT to authenticate pushes");
        }

        if (p.getBilling().isStripe()
                && (blank(p.getBilling().getStripeSecretKey()) || blank(p.getBilling().getStripeWebhookSecret()))) {
            out.add("BILLING_PROVIDER=stripe needs STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET");
        }
        if (p.getJobs().isInline()) {
            out.add("pitsch.jobs.inline must be false");
        }
        if (apiDocsEnabled) {
            // Allowed deliberately (e.g. a private network), but never by accident.
            log.warn("API_DOCS_ENABLED=true: the OpenAPI document and Swagger UI are publicly reachable.");
        }
        return out;
    }

    private static void httpsUrl(List<String> out, String name, String value) {
        if (blank(value)) {
            out.add(name + " is not set");
            return;
        }
        try {
            URI u = URI.create(value);
            if (!"https".equals(u.getScheme())) {
                out.add(name + " must use https");
            }
        } catch (IllegalArgumentException e) {
            out.add(name + " is not a valid URL");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

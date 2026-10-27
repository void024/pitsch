package com.pitsch.backend.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of every {@code pitsch.*} property (see application.properties for the environment variables). */
@ConfigurationProperties(prefix = "pitsch")
public class PitschProperties {

    public enum Mode {
        PRODUCTION, DEVELOPMENT, DEMO, TEST;

        public static Mode parse(String value) {
            return value == null || value.isBlank() ? PRODUCTION : Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private String mode = "production";
    private String frontendUrl = "http://localhost:5173";
    private String publicApiUrl = "http://localhost:8080";
    private String defaultTimezone = "UTC";
    private final Cors cors = new Cors();
    private final Security security = new Security();
    private final RateLimit rateLimit = new RateLimit();
    private final Ai ai = new Ai();
    private final Google google = new Google();
    private final Storage storage = new Storage();
    private final Uploads uploads = new Uploads();
    private final Malware malware = new Malware();
    private final Mail mail = new Mail();
    private final Jobs jobs = new Jobs();
    private final Billing billing = new Billing();
    private final Retention retention = new Retention();

    public Mode mode() {
        return Mode.parse(mode);
    }

    public boolean isProduction() {
        return mode() == Mode.PRODUCTION;
    }

    public boolean isDemo() {
        return mode() == Mode.DEMO;
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getFrontendUrl() { return frontendUrl; }
    public void setFrontendUrl(String v) { this.frontendUrl = v == null ? null : v.replaceAll("/+$", ""); }
    public String getPublicApiUrl() { return publicApiUrl; }
    public void setPublicApiUrl(String v) { this.publicApiUrl = v == null ? null : v.replaceAll("/+$", ""); }
    public String getDefaultTimezone() { return defaultTimezone; }
    public void setDefaultTimezone(String defaultTimezone) { this.defaultTimezone = defaultTimezone; }
    public Cors getCors() { return cors; }
    public Security getSecurity() { return security; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Ai getAi() { return ai; }
    public Google getGoogle() { return google; }
    public Storage getStorage() { return storage; }
    public Uploads getUploads() { return uploads; }
    public Malware getMalware() { return malware; }
    public Mail getMail() { return mail; }
    public Jobs getJobs() { return jobs; }
    public Billing getBilling() { return billing; }
    public Retention getRetention() { return retention; }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public static class Security {
        private String jwtSecret = "";
        private String jwtIssuer = "pitsch";
        private int accessTokenMinutes = 15;
        private int refreshTokenDays = 30;
        private int passwordHashIterations = 600_000;
        private int maxFailedLogins = 5;
        private int lockoutMinutes = 15;
        private boolean requireEmailVerification = true;
        private boolean cookieSecure = true;
        private String cookieSameSite = "Lax";
        private String cookieDomain = "";
        private String encryptionKeys = "";
        /** A rotated refresh token presented again within this window (parallel tabs) is not treated as theft. */
        private int refreshReuseGraceSeconds = 20;
        public int getRefreshReuseGraceSeconds() { return refreshReuseGraceSeconds; }
        public void setRefreshReuseGraceSeconds(int v) { this.refreshReuseGraceSeconds = v; }

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public String getJwtIssuer() { return jwtIssuer; }
        public void setJwtIssuer(String jwtIssuer) { this.jwtIssuer = jwtIssuer; }
        public int getAccessTokenMinutes() { return accessTokenMinutes; }
        public void setAccessTokenMinutes(int v) { this.accessTokenMinutes = v; }
        public int getRefreshTokenDays() { return refreshTokenDays; }
        public void setRefreshTokenDays(int v) { this.refreshTokenDays = v; }
        public int getPasswordHashIterations() { return passwordHashIterations; }
        public void setPasswordHashIterations(int v) { this.passwordHashIterations = v; }
        public int getMaxFailedLogins() { return maxFailedLogins; }
        public void setMaxFailedLogins(int v) { this.maxFailedLogins = v; }
        public int getLockoutMinutes() { return lockoutMinutes; }
        public void setLockoutMinutes(int v) { this.lockoutMinutes = v; }
        public boolean isRequireEmailVerification() { return requireEmailVerification; }
        public void setRequireEmailVerification(boolean v) { this.requireEmailVerification = v; }
        public boolean isCookieSecure() { return cookieSecure; }
        public void setCookieSecure(boolean v) { this.cookieSecure = v; }
        public String getCookieSameSite() { return cookieSameSite; }
        public void setCookieSameSite(String v) { this.cookieSameSite = v; }
        public String getCookieDomain() { return cookieDomain; }
        public void setCookieDomain(String v) { this.cookieDomain = v; }
        public String getEncryptionKeys() { return encryptionKeys; }
        public void setEncryptionKeys(String v) { this.encryptionKeys = v; }
    }

    public static class RateLimit {
        private int authPerMinute = 10;
        private int apiPerMinute = 600;
        private int aiPerMinute = 30;
        public int getAuthPerMinute() { return authPerMinute; }
        public void setAuthPerMinute(int v) { this.authPerMinute = v; }
        public int getApiPerMinute() { return apiPerMinute; }
        public void setApiPerMinute(int v) { this.apiPerMinute = v; }
        public int getAiPerMinute() { return aiPerMinute; }
        public void setAiPerMinute(int v) { this.aiPerMinute = v; }
    }

    public static class Ai {
        private String baseUrl = "http://localhost:8000";
        private String internalToken = "";
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 240;
        private int maxAttempts = 3;
        private long retryBackoffMillis = 2000;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getInternalToken() { return internalToken; }
        public void setInternalToken(String v) { this.internalToken = v; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int v) { this.connectTimeoutSeconds = v; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int v) { this.readTimeoutSeconds = v; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
        public long getRetryBackoffMillis() { return retryBackoffMillis; }
        public void setRetryBackoffMillis(long v) { this.retryBackoffMillis = v; }
    }

    public static class Google {
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";
        private boolean loginEnabled = false;
        private String pubsubTopic = "";
        private String pubsubAudience = "";
        private String pubsubServiceAccount = "";
        private int gmailSyncIntervalMinutes = 10;
        private String gmailIngestQuery = "in:inbox";
        private int apiTimeoutSeconds = 20;

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }

        public boolean isPushConfigured() {
            return pubsubTopic != null && !pubsubTopic.isBlank();
        }

        public String getClientId() { return clientId; }
        public void setClientId(String v) { this.clientId = v; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String v) { this.clientSecret = v; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String v) { this.redirectUri = v; }
        public boolean isLoginEnabled() { return loginEnabled; }
        public void setLoginEnabled(boolean v) { this.loginEnabled = v; }
        public String getPubsubTopic() { return pubsubTopic; }
        public void setPubsubTopic(String v) { this.pubsubTopic = v; }
        public String getPubsubAudience() { return pubsubAudience; }
        public void setPubsubAudience(String v) { this.pubsubAudience = v; }
        public String getPubsubServiceAccount() { return pubsubServiceAccount; }
        public void setPubsubServiceAccount(String v) { this.pubsubServiceAccount = v; }
        public int getGmailSyncIntervalMinutes() { return gmailSyncIntervalMinutes; }
        public void setGmailSyncIntervalMinutes(int v) { this.gmailSyncIntervalMinutes = v; }
        public String getGmailIngestQuery() { return gmailIngestQuery; }
        public void setGmailIngestQuery(String v) { this.gmailIngestQuery = v; }
        public int getApiTimeoutSeconds() { return apiTimeoutSeconds; }
        public void setApiTimeoutSeconds(int v) { this.apiTimeoutSeconds = v; }
    }

    public static class Storage {
        private String provider = "s3";
        private String localDir = "./data/files";
        private int signedUrlMinutes = 10;
        private final S3 s3 = new S3();
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getLocalDir() { return localDir; }
        public void setLocalDir(String v) { this.localDir = v; }
        public int getSignedUrlMinutes() { return signedUrlMinutes; }
        public void setSignedUrlMinutes(int v) { this.signedUrlMinutes = v; }
        public S3 getS3() { return s3; }

        public static class S3 {
            private String bucket = "";
            private String region = "auto";
            private String endpoint = "";
            /** Optional endpoint used only for presigned download URLs (browser-reachable host), e.g. MinIO in Docker. */
            private String publicEndpoint = "";
            private String accessKeyId = "";
            private String secretAccessKey = "";
            private boolean pathStyle = false;
            public String getBucket() { return bucket; }
            public void setBucket(String v) { this.bucket = v; }
            public String getRegion() { return region; }
            public void setRegion(String v) { this.region = v; }
            public String getEndpoint() { return endpoint; }
            public void setEndpoint(String v) { this.endpoint = v; }
            public String getPublicEndpoint() { return publicEndpoint; }
            public void setPublicEndpoint(String v) { this.publicEndpoint = v; }
            public String getAccessKeyId() { return accessKeyId; }
            public void setAccessKeyId(String v) { this.accessKeyId = v; }
            public String getSecretAccessKey() { return secretAccessKey; }
            public void setSecretAccessKey(String v) { this.secretAccessKey = v; }
            public boolean isPathStyle() { return pathStyle; }
            public void setPathStyle(boolean v) { this.pathStyle = v; }
        }
    }

    public static class Uploads {
        private int maxFileMb = 15;
        private int maxFiles = 10;
        private int maxPptxUncompressedMb = 150;
        private int maxZipEntries = 3000;
        public int getMaxFileMb() { return maxFileMb; }
        public void setMaxFileMb(int v) { this.maxFileMb = v; }
        public int getMaxFiles() { return maxFiles; }
        public void setMaxFiles(int v) { this.maxFiles = v; }
        public int getMaxPptxUncompressedMb() { return maxPptxUncompressedMb; }
        public void setMaxPptxUncompressedMb(int v) { this.maxPptxUncompressedMb = v; }
        public int getMaxZipEntries() { return maxZipEntries; }
        public void setMaxZipEntries(int v) { this.maxZipEntries = v; }
        public long maxFileBytes() { return maxFileMb * 1024L * 1024L; }
    }

    public static class Malware {
        private String scanner = "none";
        private String clamavHost = "localhost";
        private int clamavPort = 3310;
        public String getScanner() { return scanner; }
        public void setScanner(String v) { this.scanner = v; }
        public String getClamavHost() { return clamavHost; }
        public void setClamavHost(String v) { this.clamavHost = v; }
        public int getClamavPort() { return clamavPort; }
        public void setClamavPort(int v) { this.clamavPort = v; }
    }

    public static class Mail {
        private String provider = "smtp";
        private String from = "Pitsch <no-reply@pitsch.app>";
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getFrom() { return from; }
        public void setFrom(String v) { this.from = v; }
    }

    public static class Jobs {
        private boolean enabled = true;
        private boolean inline = false;
        private int threads = 4;
        private long pollIntervalMs = 1000;
        private int lockTimeoutMinutes = 20;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isInline() { return inline; }
        public void setInline(boolean v) { this.inline = v; }
        public int getThreads() { return threads; }
        public void setThreads(int v) { this.threads = v; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }
        public int getLockTimeoutMinutes() { return lockTimeoutMinutes; }
        public void setLockTimeoutMinutes(int v) { this.lockTimeoutMinutes = v; }
    }

    public static class Billing {
        private String provider = "none";
        private String stripeSecretKey = "";
        private String stripeWebhookSecret = "";
        /** "PLAN:price_id" pairs, comma-separated (e.g. "PRO:price_123,TEAM:price_456"); overrides plans.stripe_price_id. */
        private String stripePriceIds = "";
        public String getStripePriceIds() { return stripePriceIds; }
        public void setStripePriceIds(String v) { this.stripePriceIds = v; }
        public boolean isStripe() { return "stripe".equalsIgnoreCase(provider); }
        public java.util.Map<String, String> priceIdsByPlan() {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            if (stripePriceIds == null) {
                return out;
            }
            for (String pair : stripePriceIds.split(",")) {
                int i = pair.indexOf(':');
                if (i > 0 && i < pair.length() - 1) {
                    out.put(pair.substring(0, i).trim().toUpperCase(java.util.Locale.ROOT), pair.substring(i + 1).trim());
                }
            }
            return out;
        }
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getStripeSecretKey() { return stripeSecretKey; }
        public void setStripeSecretKey(String v) { this.stripeSecretKey = v; }
        public String getStripeWebhookSecret() { return stripeWebhookSecret; }
        public void setStripeWebhookSecret(String v) { this.stripeWebhookSecret = v; }
    }

    public static class Retention {
        private int agentIoDays = 90;
        private int auditDays = 730;
        public int getAgentIoDays() { return agentIoDays; }
        public void setAgentIoDays(int v) { this.agentIoDays = v; }
        public int getAuditDays() { return auditDays; }
        public void setAuditDays(int v) { this.auditDays = v; }
    }
}

package com.pitsch.backend.integration.google;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.integration.IntegrationException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Google OAuth 2.0 Authorization Code flow with PKCE (S256), refresh and revocation. */
@Component
public class GoogleOAuthClient {

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds, String scope, String idToken) { }

    public record Identity(String subject, String email, boolean emailVerified, String name) { }

    static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    private final PitschProperties props;
    private final RestClient rest;
    private final Json json;

    public GoogleOAuthClient(PitschProperties props, Json json) {
        this.props = props;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(props.getGoogle().getApiTimeoutSeconds()));
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    public String redirectUri() {
        String configured = props.getGoogle().getRedirectUri();
        return configured == null || configured.isBlank()
                ? props.getPublicApiUrl() + "/api/v1/integrations/google/callback" : configured;
    }

    public String authorizationUrl(List<String> scopes, String state, String codeVerifier, String loginHint) {
        requireConfigured();
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(AUTH_URL)
                .queryParam("client_id", props.getGoogle().getClientId())
                .queryParam("redirect_uri", redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", scopes))
                .queryParam("access_type", "offline")
                .queryParam("include_granted_scopes", "true")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .queryParam("code_challenge", challenge(codeVerifier))
                .queryParam("code_challenge_method", "S256");
        if (loginHint != null && !loginHint.isBlank()) {
            b.queryParam("login_hint", loginHint);
        }
        return b.encode().build().toUriString();
    }

    public Tokens exchange(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", props.getGoogle().getClientId());
        form.add("client_secret", props.getGoogle().getClientSecret());
        form.add("redirect_uri", redirectUri());
        form.add("grant_type", "authorization_code");
        form.add("code_verifier", codeVerifier);
        return tokens(post(form));
    }

    public Tokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", props.getGoogle().getClientId());
        form.add("client_secret", props.getGoogle().getClientSecret());
        form.add("grant_type", "refresh_token");
        return tokens(post(form));
    }

    /** Best effort: Google returns 400 for tokens that are already invalid, which is fine. */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        try {
            rest.post().uri(REVOKE_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
        } catch (RestClientResponseException | ResourceAccessException ignored) {
            // already revoked or unreachable; local tokens are deleted regardless
        }
    }

    /**
     * Reads the identity from the ID token returned by the token endpoint. Per OpenID Connect Core 3.1.3.7 the TLS
     * channel to Google's token endpoint authenticates the token, so its signature need not be re-verified here; the
     * audience is still checked.
     */
    public Identity identity(String idToken) {
        if (idToken == null) {
            throw new IntegrationException("Google did not return an identity token", false, false, 0);
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new IntegrationException("Malformed identity token", false, false, 0);
        }
        JsonNode claims = json.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        String aud = Json.text(claims, "aud");
        if (aud == null || !aud.equals(props.getGoogle().getClientId())) {
            throw new IntegrationException("Identity token audience mismatch", false, false, 0);
        }
        return new Identity(Json.text(claims, "sub"), Json.text(claims, "email"),
                claims.path("email_verified").asBoolean(false), Json.text(claims, "name"));
    }

    public void requireConfigured() {
        if (!props.getGoogle().isConfigured()) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "Google integration is not configured on this server (GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET).");
        }
    }

    private JsonNode post(MultiValueMap<String, String> form) {
        requireConfigured();
        try {
            return rest.post().uri(TOKEN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            JsonNode body = json.readSafely(e.getResponseBodyAsString());
            String error = Json.text(body, "error");
            boolean invalidGrant = "invalid_grant".equals(error);
            throw new IntegrationException(invalidGrant ? "Google access was revoked or expired. Reconnect your account."
                    : "Google OAuth request failed (" + e.getStatusCode().value() + ")",
                    e.getStatusCode().is5xxServerError(), invalidGrant, e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new IntegrationException("Could not reach Google", true, false, 0);
        }
    }

    private static Tokens tokens(JsonNode body) {
        if (body == null || Json.text(body, "access_token") == null) {
            throw new IntegrationException("Google returned no access token", true, false, 0);
        }
        return new Tokens(Json.text(body, "access_token"), Json.text(body, "refresh_token"),
                body.path("expires_in").asLong(3600), Json.text(body, "scope"), Json.text(body, "id_token"));
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

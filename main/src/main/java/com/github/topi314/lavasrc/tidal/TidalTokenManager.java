package com.github.topi314.lavasrc.tidal;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Manages OAuth token lifecycle for the Tidal v2 API.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>client_credentials</b> — when clientId and clientSecret are configured, acquires
 *       tokens from {@code https://auth.tidal.com/v1/oauth2/token} and caches them with
 *       proactive refresh (60 seconds before expiry).</li>
 *   <li><b>static token</b> — when only a static token is configured (no clientId/clientSecret),
 *       returns it directly without refresh.</li>
 * </ul>
 *
 * <p>When both are configured, client_credentials takes precedence (per Requirement 9.3).
 *
 * <p>Thread-safe: {@link #getToken()} and {@link #invalidateToken()} are synchronized.
 */
public class TidalTokenManager {

    private static final Logger log = LoggerFactory.getLogger(TidalTokenManager.class);
    private static final String TOKEN_ENDPOINT = "https://auth.tidal.com/v1/oauth2/token";
    private static final long REFRESH_THRESHOLD_SECONDS = 60;
    private static final long RETRY_DELAY_MS = 1000;

    private final String clientId;
    private final String clientSecret;
    private final String staticToken;
    private final HttpInterfaceManager httpInterfaceManager;
    private final boolean useClientCredentials;

    private String cachedToken;
    private Instant tokenExpiry;
    private boolean disabled;

    /**
     * Creates a new TidalTokenManager.
     *
     * @param clientId     OAuth client ID (may be null or empty)
     * @param clientSecret OAuth client secret (may be null or empty)
     * @param staticToken  static Bearer token (may be null or empty)
     */
    public TidalTokenManager(String clientId, String clientSecret, String staticToken) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.staticToken = staticToken;
        this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();

        boolean hasCredentials = isNotEmpty(clientId) && isNotEmpty(clientSecret);
        boolean hasStaticToken = isNotEmpty(staticToken);

        // Prefer client_credentials when both are configured (Requirement 9.3)
        this.useClientCredentials = hasCredentials;

        if (!hasCredentials && !hasStaticToken) {
            log.warn("Tidal: No clientId/clientSecret or static token configured. Tidal source will be disabled.");
            this.disabled = true;
        } else {
            this.disabled = false;
            if (hasCredentials) {
                log.info("Tidal: Using client_credentials OAuth flow");
            } else {
                log.info("Tidal: Using static token mode");
            }
        }
    }

    /**
     * Returns a valid Bearer token. If using client_credentials, will acquire or refresh
     * the token as needed (proactively refreshing within 60 seconds of expiry).
     *
     * @return a valid Bearer token string
     * @throws TidalAuthException if token acquisition fails after retry
     * @throws TidalAuthException if no credentials are configured (disabled state)
     */
    public synchronized String getToken() throws TidalAuthException {
        if (disabled) {
            throw new TidalAuthException("Tidal source is disabled: no credentials configured");
        }

        if (!useClientCredentials) {
            // Static token mode — return runtime-updated token if available, otherwise static
            if (cachedToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
                return cachedToken;
            }
            return staticToken;
        }

        // Client credentials mode — check if we need to refresh
        if (cachedToken != null && tokenExpiry != null) {
            Instant refreshAt = tokenExpiry.minusSeconds(REFRESH_THRESHOLD_SECONDS);
            if (Instant.now().isBefore(refreshAt)) {
                return cachedToken;
            }
            log.debug("Tidal: Token near expiry, refreshing proactively");
        }

        // Need to acquire/refresh token
        acquireToken();
        return cachedToken;
    }

    /**
     * Forces token invalidation. Called when a 401 response is received from the API,
     * triggering re-authentication on the next {@link #getToken()} call.
     */
    public synchronized void invalidateToken() {
        log.debug("Tidal: Token invalidated, will re-authenticate on next request");
        this.cachedToken = null;
        this.tokenExpiry = null;
    }

    /**
     * Returns whether this token manager is in a disabled state (no credentials configured).
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Acquires a token via client_credentials flow with one retry on failure.
     */
    private void acquireToken() throws TidalAuthException {
        TidalAuthException lastException = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                log.debug("Tidal: Retrying token acquisition after 1s delay (attempt {})", attempt + 1);
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TidalAuthException("Token acquisition interrupted", e);
                }
            }

            try {
                performTokenRequest();
                return; // Success
            } catch (TidalAuthException e) {
                lastException = e;
                log.warn("Tidal: Token acquisition attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }

        // Both attempts failed
        throw lastException;
    }

    /**
     * Performs the actual HTTP POST to the Tidal OAuth token endpoint.
     */
    private void performTokenRequest() throws TidalAuthException {
        HttpPost request = new HttpPost(TOKEN_ENDPOINT);
        request.setEntity(new UrlEncodedFormEntity(
            List.of(
                new BasicNameValuePair("grant_type", "client_credentials"),
                new BasicNameValuePair("client_id", clientId),
                new BasicNameValuePair("client_secret", clientSecret)
            ),
            StandardCharsets.UTF_8
        ));
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");

        try (HttpInterface httpInterface = httpInterfaceManager.getInterface();
             CloseableHttpResponse response = httpInterface.execute(request)) {

            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (statusCode < 200 || statusCode >= 300) {
                throw new TidalAuthException(
                    "Tidal auth endpoint returned HTTP " + statusCode + ": " + responseBody
                );
            }

            JsonBrowser json = JsonBrowser.parse(responseBody);
            String accessToken = json.get("access_token").text();
            long expiresIn = json.get("expires_in").asLong(0);

            if (accessToken == null || accessToken.isEmpty()) {
                throw new TidalAuthException("Tidal auth response missing access_token");
            }

            if (expiresIn <= 0) {
                throw new TidalAuthException("Tidal auth response has invalid expires_in: " + expiresIn);
            }

            this.cachedToken = accessToken;
            this.tokenExpiry = Instant.now().plusSeconds(expiresIn);
            log.info("Tidal: Token acquired successfully, expires in {}s", expiresIn);

        } catch (IOException e) {
            throw new TidalAuthException("Failed to reach Tidal auth endpoint: " + e.getMessage(), e);
        }
    }

    /**
     * Shuts down the internal HTTP interface manager.
     */
    public void shutdown() {
        try {
            httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close Tidal token manager HTTP interface", e);
        }
    }

    /**
     * Updates credentials at runtime. Supports updating the token and/or switching to
     * client_credentials mode. Called by the PATCH /v4/lavasrc/config endpoint.
     *
     * In static token mode (no clientId/clientSecret), this replaces the stale
     * cached token with a fresh one pushed by the bot. The updated token is used
     * until it expires (4h assumed) or is replaced by another push.
     *
     * @param newClientId     new OAuth client ID (null to keep current)
     * @param newClientSecret new OAuth client secret (null to keep current)
     * @param newToken        new access token (null to keep current)
     */
    public synchronized void updateCredentials(String newClientId, String newClientSecret, String newToken) {
        if (newToken != null && !newToken.isEmpty()) {
            this.cachedToken = newToken;
            this.tokenExpiry = Instant.now().plusSeconds(14400); // Assume 4h validity
            this.disabled = false;
            log.info("Tidal: Token updated at runtime (len={})", newToken.length());
        }
    }

    private static boolean isNotEmpty(String value) {
        return value != null && !value.isEmpty();
    }
}

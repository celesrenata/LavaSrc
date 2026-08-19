package com.github.topi314.lavasrc.tidal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates all HTTP communication with the Tidal v2 API ({@code openapi.tidal.com/v2}).
 *
 * <p>Handles request construction (headers, URL encoding, country code filtering),
 * authentication via {@link TidalTokenManager}, cursor-based pagination for collections,
 * and retry logic for transient errors (401, 429, 5xx).</p>
 *
 * <p>All methods return raw {@link JsonNode} documents; the caller uses
 * {@link JsonApiParser} to convert them into domain objects.</p>
 */
public class TidalV2ApiClient {

    private static final Logger log = LoggerFactory.getLogger(TidalV2ApiClient.class);
    private static final String BASE_URL = "https://openapi.tidal.com/v2";
    private static final String JSONAPI_MEDIA_TYPE = "application/vnd.api+json";
    private static final long RETRY_5XX_DELAY_MS = 2000;

    private final TidalTokenManager tokenManager;
    private final String countryCode;
    private final HttpInterfaceManager httpInterfaceManager;
    private final ObjectMapper objectMapper;
    private final JsonApiParser jsonApiParser;

    /**
     * Creates a new TidalV2ApiClient.
     *
     * @param tokenManager         manages Bearer token acquisition and refresh
     * @param countryCode          ISO country code for catalog filtering (defaults to "US" if null/empty)
     * @param httpInterfaceManager lavaplayer HTTP interface manager for making requests
     */
    public TidalV2ApiClient(TidalTokenManager tokenManager, String countryCode,
                            HttpInterfaceManager httpInterfaceManager) {
        this.tokenManager = tokenManager;
        this.countryCode = (countryCode == null || countryCode.isEmpty()) ? "US" : countryCode;
        this.httpInterfaceManager = httpInterfaceManager;
        this.objectMapper = new ObjectMapper();
        this.jsonApiParser = new JsonApiParser();
    }

    // ─── Public API Methods ──────────────────────────────────────────────────────

    /**
     * Searches for tracks via the Tidal v2 search endpoint.
     *
     * @param query the search query (will be URL-encoded)
     * @param limit maximum number of results (maps to page[limit])
     * @return the full JSON:API document, or null if no results / 404
     * @throws TidalApiException if the request fails after retries
     */
    public JsonNode searchTracks(String query, int limit) throws TidalApiException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = BASE_URL + "/searchresults/" + encodedQuery
            + "?include=tracks"
            + "&page[limit]=" + limit
            + "&filter[countryCode]=" + countryCode;

        return executeWithRetry(url);
    }

    /**
     * Fetches a single track by ID with artist and album includes.
     *
     * @param trackId the Tidal track ID
     * @return the full JSON:API document, or null if not found (404)
     * @throws TidalApiException if the request fails after retries
     */
    public JsonNode getTrack(String trackId) throws TidalApiException {
        String url = BASE_URL + "/tracks/" + trackId
            + "?include=artists,albums"
            + "&filter[countryCode]=" + countryCode;

        return executeWithRetry(url);
    }

    /**
     * Fetches a single album by ID with items included.
     *
     * @param albumId the Tidal album ID
     * @return the full JSON:API document, or null if not found (404)
     * @throws TidalApiException if the request fails after retries
     */
    public JsonNode getAlbum(String albumId) throws TidalApiException {
        String url = BASE_URL + "/albums/" + albumId
            + "?include=items"
            + "&filter[countryCode]=" + countryCode;

        return executeWithRetry(url);
    }

    /**
     * Fetches a single playlist by UUID with items included.
     *
     * @param playlistUuid the Tidal playlist UUID
     * @return the full JSON:API document, or null if not found (404)
     * @throws TidalApiException if the request fails after retries
     */
    public JsonNode getPlaylist(String playlistUuid) throws TidalApiException {
        String url = BASE_URL + "/playlists/" + playlistUuid
            + "?include=items"
            + "&filter[countryCode]=" + countryCode;

        return executeWithRetry(url);
    }

    /**
     * Fetches all tracks from an album using cursor-based pagination.
     *
     * @param albumId   the Tidal album ID
     * @param maxTracks maximum number of tracks to accumulate
     * @return list of track resource JsonNodes from the included array, or empty list if not found
     * @throws TidalApiException if the request fails after retries
     */
    public List<JsonNode> getAlbumTracks(String albumId, int maxTracks) throws TidalApiException {
        String initialUrl = BASE_URL + "/albums/" + albumId
            + "?include=items"
            + "&filter[countryCode]=" + countryCode;

        return fetchPaginatedTracks(initialUrl, maxTracks);
    }

    /**
     * Fetches all tracks from a playlist using cursor-based pagination.
     *
     * @param playlistUuid the Tidal playlist UUID
     * @param maxTracks    maximum number of tracks to accumulate
     * @return list of track resource JsonNodes from the included array, or empty list if not found
     * @throws TidalApiException if the request fails after retries
     */
    public List<JsonNode> getPlaylistTracks(String playlistUuid, int maxTracks) throws TidalApiException {
        String initialUrl = BASE_URL + "/playlists/" + playlistUuid
            + "?include=items"
            + "&filter[countryCode]=" + countryCode;

        return fetchPaginatedTracks(initialUrl, maxTracks);
    }

    // ─── Pagination ──────────────────────────────────────────────────────────────

    /**
     * Fetches tracks from a paginated collection endpoint, following cursor links
     * until no more pages exist or maxTracks is reached.
     */
    private List<JsonNode> fetchPaginatedTracks(String initialUrl, int maxTracks) throws TidalApiException {
        List<JsonNode> allTracks = new ArrayList<>();

        JsonNode document = executeWithRetry(initialUrl);
        if (document == null) {
            return Collections.emptyList();
        }

        // Extract track resources from the included array
        extractTracksFromIncluded(document, allTracks, maxTracks);

        // Follow pagination cursors
        while (allTracks.size() < maxTracks) {
            var nextUrl = jsonApiParser.getNextPageUrl(document);
            if (nextUrl.isEmpty()) {
                break;
            }

            String nextPageUrl = nextUrl.get();
            // If the URL is relative, prepend the base
            if (!nextPageUrl.startsWith("http")) {
                nextPageUrl = "https://openapi.tidal.com" + nextPageUrl;
            }

            log.debug("Tidal: Following pagination cursor, accumulated {} tracks so far", allTracks.size());
            document = executeWithRetry(nextPageUrl);
            if (document == null) {
                break;
            }

            extractTracksFromIncluded(document, allTracks, maxTracks);
        }

        log.debug("Tidal: Pagination complete, accumulated {} tracks total", allTracks.size());
        return allTracks;
    }

    /**
     * Extracts track resources (type "tracks") from the JSON:API document's included array
     * and adds them to the accumulator list, respecting the maxTracks limit.
     */
    private void extractTracksFromIncluded(JsonNode document, List<JsonNode> accumulator, int maxTracks) {
        JsonNode included = document.path("included");
        if (!included.isArray()) {
            return;
        }

        for (JsonNode resource : included) {
            if (accumulator.size() >= maxTracks) {
                break;
            }
            JsonNode type = resource.path("type");
            if (type.isTextual() && "tracks".equals(type.asText())) {
                accumulator.add(resource);
            }
        }
    }

    // ─── Request Execution with Retry Logic ──────────────────────────────────────

    /**
     * Executes a GET request with retry logic for transient errors.
     *
     * <p>Retry strategy:
     * <ul>
     *   <li>HTTP 401: invalidate token, re-authenticate, retry once</li>
     *   <li>HTTP 429: read Retry-After header, sleep, retry once</li>
     *   <li>HTTP 5xx: sleep 2s, retry once</li>
     *   <li>HTTP 404: return null (no retry)</li>
     *   <li>All retries exhausted: log WARN, throw TidalApiException</li>
     * </ul>
     *
     * @param url the full URL to request
     * @return parsed JSON:API document, or null for 404
     * @throws TidalApiException if all retries are exhausted
     */
    private JsonNode executeWithRetry(String url) throws TidalApiException {
        RequestOutcome firstAttempt = executeRequest(url);

        switch (firstAttempt.type) {
            case SUCCESS:
                return firstAttempt.body;

            case NOT_FOUND:
                return null;

            case UNAUTHORIZED:
                // Invalidate token, re-auth, retry once
                log.debug("Tidal: Got 401, invalidating token and retrying");
                tokenManager.invalidateToken();
                RequestOutcome retry = executeRequest(url);
                if (retry.type == OutcomeType.SUCCESS) {
                    return retry.body;
                }
                if (retry.type == OutcomeType.NOT_FOUND) {
                    return null;
                }
                log.warn("Tidal: Request failed after 401 retry: {} (HTTP {})", url, retry.statusCode);
                throw new TidalApiException(
                    "Tidal API request failed after 401 retry: HTTP " + retry.statusCode,
                    retry.statusCode
                );

            case RATE_LIMITED:
                // Read Retry-After, sleep, retry once
                long retryAfterMs = firstAttempt.retryAfterMs;
                log.debug("Tidal: Got 429, sleeping {}ms before retry", retryAfterMs);
                sleepQuietly(retryAfterMs);
                RequestOutcome retryAfterWait = executeRequest(url);
                if (retryAfterWait.type == OutcomeType.SUCCESS) {
                    return retryAfterWait.body;
                }
                if (retryAfterWait.type == OutcomeType.NOT_FOUND) {
                    return null;
                }
                log.warn("Tidal: Request failed after 429 retry: {} (HTTP {})", url, retryAfterWait.statusCode);
                throw new TidalApiException(
                    "Tidal API request failed after rate-limit retry: HTTP " + retryAfterWait.statusCode,
                    retryAfterWait.statusCode
                );

            case SERVER_ERROR:
                // Sleep 2s, retry once
                log.debug("Tidal: Got 5xx ({}), sleeping 2s before retry", firstAttempt.statusCode);
                sleepQuietly(RETRY_5XX_DELAY_MS);
                RequestOutcome retryAfter5xx = executeRequest(url);
                if (retryAfter5xx.type == OutcomeType.SUCCESS) {
                    return retryAfter5xx.body;
                }
                if (retryAfter5xx.type == OutcomeType.NOT_FOUND) {
                    return null;
                }
                log.warn("Tidal: Request failed after 5xx retry: {} (HTTP {})", url, retryAfter5xx.statusCode);
                throw new TidalApiException(
                    "Tidal API request failed after server-error retry: HTTP " + retryAfter5xx.statusCode,
                    retryAfter5xx.statusCode
                );

            case CLIENT_ERROR:
            default:
                // Unrecoverable client error (4xx other than 401/404/429)
                log.warn("Tidal: Request failed with unrecoverable error: {} (HTTP {})", url, firstAttempt.statusCode);
                throw new TidalApiException(
                    "Tidal API request failed: HTTP " + firstAttempt.statusCode,
                    firstAttempt.statusCode
                );
        }
    }

    /**
     * Executes a single HTTP GET request against the Tidal v2 API.
     * Returns a structured outcome describing what happened.
     */
    private RequestOutcome executeRequest(String url) throws TidalApiException {
        String token;
        try {
            token = tokenManager.getToken();
        } catch (TidalAuthException e) {
            throw new TidalApiException("Failed to obtain auth token for Tidal API request", e);
        }

        HttpGet request = new HttpGet(url);
        request.setHeader("Authorization", "Bearer " + token);
        request.setHeader("Accept", JSONAPI_MEDIA_TYPE);

        try (HttpInterface httpInterface = httpInterfaceManager.getInterface();
             CloseableHttpResponse response = httpInterface.execute(request)) {

            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode >= 200 && statusCode < 300) {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode body = objectMapper.readTree(responseBody);
                return RequestOutcome.success(body);
            }

            if (statusCode == 404) {
                log.debug("Tidal: Resource not found (404): {}", url);
                return RequestOutcome.notFound();
            }

            if (statusCode == 401) {
                return RequestOutcome.unauthorized();
            }

            if (statusCode == 429) {
                long retryAfterMs = parseRetryAfterHeader(response);
                return RequestOutcome.rateLimited(retryAfterMs);
            }

            if (statusCode >= 500) {
                return RequestOutcome.serverError(statusCode);
            }

            // Other 4xx errors
            return RequestOutcome.clientError(statusCode);

        } catch (IOException e) {
            throw new TidalApiException("Network error during Tidal API request: " + url, e);
        }
    }

    /**
     * Parses the Retry-After header from a 429 response.
     * Returns delay in milliseconds. Defaults to 1000ms if header is missing or unparseable.
     */
    private long parseRetryAfterHeader(CloseableHttpResponse response) {
        var retryAfterHeader = response.getFirstHeader("Retry-After");
        if (retryAfterHeader == null) {
            return 1000L;
        }
        try {
            long seconds = Long.parseLong(retryAfterHeader.getValue().trim());
            return Math.max(seconds * 1000L, 100L);
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }

    /**
     * Sleeps for the given duration, handling interruption gracefully.
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Internal Outcome Types ──────────────────────────────────────────────────

    private enum OutcomeType {
        SUCCESS,
        NOT_FOUND,
        UNAUTHORIZED,
        RATE_LIMITED,
        SERVER_ERROR,
        CLIENT_ERROR
    }

    /**
     * Encapsulates the result of a single HTTP request attempt.
     */
    private static class RequestOutcome {
        final OutcomeType type;
        final JsonNode body;
        final int statusCode;
        final long retryAfterMs;

        private RequestOutcome(OutcomeType type, JsonNode body, int statusCode, long retryAfterMs) {
            this.type = type;
            this.body = body;
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
        }

        static RequestOutcome success(JsonNode body) {
            return new RequestOutcome(OutcomeType.SUCCESS, body, 200, 0);
        }

        static RequestOutcome notFound() {
            return new RequestOutcome(OutcomeType.NOT_FOUND, null, 404, 0);
        }

        static RequestOutcome unauthorized() {
            return new RequestOutcome(OutcomeType.UNAUTHORIZED, null, 401, 0);
        }

        static RequestOutcome rateLimited(long retryAfterMs) {
            return new RequestOutcome(OutcomeType.RATE_LIMITED, null, 429, retryAfterMs);
        }

        static RequestOutcome serverError(int statusCode) {
            return new RequestOutcome(OutcomeType.SERVER_ERROR, null, statusCode, 0);
        }

        static RequestOutcome clientError(int statusCode) {
            return new RequestOutcome(OutcomeType.CLIENT_ERROR, null, statusCode, 0);
        }
    }
}

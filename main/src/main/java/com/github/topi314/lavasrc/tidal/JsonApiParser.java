package com.github.topi314.lavasrc.tidal;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Utility class for parsing Tidal v2 JSON:API compound documents into LavasRC's
 * internal model. Pure data transformation — no HTTP calls or side effects.
 *
 * <p>Handles resource resolution from the {@code included} array, ISO 8601 duration
 * parsing, and cursor-based pagination link extraction.</p>
 */
public class JsonApiParser {

    /**
     * Parses a single JSON:API track resource into a {@link TidalTrackInfo}.
     *
     * @param resource the track resource node (must have {@code type}, {@code id}, {@code attributes})
     * @param included the top-level {@code included} array from the compound document (may be null)
     * @return a populated TidalTrackInfo with resolved relationships
     */
    public TidalTrackInfo parseTrack(JsonNode resource, JsonNode included) {
        if (resource == null || resource.isNull() || resource.isMissingNode()) {
            return new TidalTrackInfo("", "", "", "", "", "", 0L, 0);
        }

        String id = textOrEmpty(resource, "id");
        JsonNode attributes = resource.path("attributes");

        String title = textOrEmpty(attributes, "title");
        String isrc = textOrEmpty(attributes, "isrc");
        long durationMs = parseDuration(attributes.path("duration"));
        int trackNumber = intOrZero(attributes, "trackNumber");

        // Resolve artist names from relationships
        String artistName = resolveArtistNames(resource, included);

        // Resolve album name and artwork from relationships
        String albumName = "";
        String albumArtUrl = "";
        JsonNode albumRelData = resource.path("relationships").path("albums").path("data");
        if (albumRelData.isArray() && albumRelData.size() > 0) {
            JsonNode firstAlbumRef = albumRelData.get(0);
            Optional<JsonNode> albumResource = resolveRelationship(
                textOrEmpty(firstAlbumRef, "type"),
                textOrEmpty(firstAlbumRef, "id"),
                included
            );
            if (albumResource.isPresent()) {
                JsonNode albumAttrs = albumResource.get().path("attributes");
                albumName = textOrEmpty(albumAttrs, "title");
                albumArtUrl = extractAlbumArtUrl(albumAttrs);
            }
        }

        return new TidalTrackInfo(id, title, artistName, albumName, albumArtUrl, isrc, durationMs, trackNumber);
    }

    /**
     * Parses an array of JSON:API track resources into a list of {@link TidalTrackInfo}.
     *
     * @param dataArray the {@code data} array from a JSON:API collection response
     * @param included  the top-level {@code included} array from the compound document (may be null)
     * @return a list of parsed track info objects; empty list if dataArray is null or not an array
     */
    public List<TidalTrackInfo> parseTracks(JsonNode dataArray, JsonNode included) {
        if (dataArray == null || dataArray.isNull() || dataArray.isMissingNode() || !dataArray.isArray()) {
            return Collections.emptyList();
        }

        List<TidalTrackInfo> tracks = new ArrayList<>();
        for (JsonNode resource : dataArray) {
            // Only parse resources with type "tracks"
            String type = textOrEmpty(resource, "type");
            if ("tracks".equals(type) || type.isEmpty()) {
                tracks.add(parseTrack(resource, included));
            }
        }
        return tracks;
    }

    /**
     * Resolves a relationship reference by matching {@code type} and {@code id} against
     * the {@code included} array.
     *
     * @param type     the resource type to match (e.g., "artists", "albums")
     * @param id       the resource ID to match
     * @param included the top-level {@code included} array (may be null)
     * @return the matching included resource, or empty if not found
     */
    public Optional<JsonNode> resolveRelationship(String type, String id, JsonNode included) {
        if (included == null || included.isNull() || included.isMissingNode() || !included.isArray()) {
            return Optional.empty();
        }
        if (type == null || type.isEmpty() || id == null || id.isEmpty()) {
            return Optional.empty();
        }

        for (JsonNode resource : included) {
            String resourceType = textOrEmpty(resource, "type");
            String resourceId = textOrEmpty(resource, "id");
            if (type.equals(resourceType) && id.equals(resourceId)) {
                return Optional.of(resource);
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the next page URL from a JSON:API document's {@code links} object.
     *
     * @param document the root JSON:API document node
     * @return the next page URL if present, or empty
     */
    public Optional<String> getNextPageUrl(JsonNode document) {
        if (document == null || document.isNull() || document.isMissingNode()) {
            return Optional.empty();
        }

        JsonNode links = document.path("links");
        if (links.isMissingNode() || links.isNull()) {
            return Optional.empty();
        }

        JsonNode next = links.path("next");
        if (next.isMissingNode() || next.isNull() || !next.isTextual()) {
            return Optional.empty();
        }

        String nextUrl = next.asText();
        if (nextUrl.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(nextUrl);
    }

    /**
     * Resolves artist names from a track resource's relationships.artists via the included array.
     * Multiple artists are joined with ", ".
     */
    private String resolveArtistNames(JsonNode resource, JsonNode included) {
        JsonNode artistRelData = resource.path("relationships").path("artists").path("data");
        if (!artistRelData.isArray() || artistRelData.size() == 0) {
            return "";
        }

        StringJoiner joiner = new StringJoiner(", ");
        for (JsonNode artistRef : artistRelData) {
            String refType = textOrEmpty(artistRef, "type");
            String refId = textOrEmpty(artistRef, "id");
            Optional<JsonNode> artistResource = resolveRelationship(refType, refId, included);
            if (artistResource.isPresent()) {
                String name = textOrEmpty(artistResource.get().path("attributes"), "name");
                if (!name.isEmpty()) {
                    joiner.add(name);
                }
            }
        }

        return joiner.toString();
    }

    /**
     * Extracts album artwork URL from album attributes.
     * Looks for {@code imageCover[0].url}.
     */
    private String extractAlbumArtUrl(JsonNode albumAttributes) {
        JsonNode imageCover = albumAttributes.path("imageCover");
        if (!imageCover.isArray() || imageCover.size() == 0) {
            return "";
        }
        JsonNode firstImage = imageCover.get(0);
        return textOrEmpty(firstImage, "url");
    }

    /**
     * Parses an ISO 8601 duration string (e.g., "PT3M45S") to milliseconds.
     * Returns 0 if the node is missing, null, or unparseable.
     */
    private long parseDuration(JsonNode durationNode) {
        if (durationNode == null || durationNode.isNull() || durationNode.isMissingNode() || !durationNode.isTextual()) {
            return 0L;
        }
        String durationStr = durationNode.asText();
        if (durationStr.isEmpty()) {
            return 0L;
        }
        try {
            return Duration.parse(durationStr).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Safely extracts a text value from a JsonNode field. Returns empty string if missing/null.
     */
    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    /**
     * Safely extracts an integer value from a JsonNode field. Returns 0 if missing/null.
     */
    private static int intOrZero(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0;
        }
        return value.asInt(0);
    }
}

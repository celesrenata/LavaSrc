package com.github.topi314.lavasrc.tidal;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing Tidal URLs and search prefixes into structured resource references.
 * Supports track, album, and playlist URLs (with and without /browse/ prefix),
 * as well as tdsearch: and tdsearch:isrc: query prefixes.
 */
public final class TidalUrlParser {

    /**
     * Matches Tidal URLs in the forms:
     * - https://tidal.com/track/{id}
     * - https://tidal.com/browse/track/{id}
     * - https://listen.tidal.com/track/{id}
     * - https://www.tidal.com/browse/album/{id}
     * - https://tidal.com/playlist/{uuid}
     * etc., with optional trailing path segments and query parameters.
     */
    public static final Pattern URL_PATTERN = Pattern.compile(
        "https?://(?:(?:listen|www)\\.)?tidal\\.com/(?:browse/)?(?<type>track|album|playlist)/(?<id>[a-zA-Z0-9\\-]+)(?:/.*)?(?:\\?.*)?"
    );

    /**
     * The search prefix for text-based Tidal searches.
     */
    public static final String SEARCH_PREFIX = "tdsearch:";

    /**
     * The ISRC search prefix (must appear after SEARCH_PREFIX).
     */
    public static final String ISRC_PREFIX = "isrc:";

    private TidalUrlParser() {
        // utility class - not instantiable
    }

    /**
     * Represents the type of Tidal resource identified by the parser.
     */
    public enum ResourceType {
        TRACK,
        ALBUM,
        PLAYLIST,
        SEARCH,
        ISRC_SEARCH
    }

    /**
     * Holds the parsed resource type and its identifier (track/album ID, playlist UUID, or query string).
     */
    public static final class TidalResource {
        private final ResourceType type;
        private final String id;

        public TidalResource(ResourceType type, String id) {
            this.type = type;
            this.id = id;
        }

        public ResourceType getType() {
            return type;
        }

        public String getId() {
            return id;
        }

        @Override
        public String toString() {
            return "TidalResource{type=" + type + ", id='" + id + "'}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TidalResource that = (TidalResource) o;
            return type == that.type && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + id.hashCode();
        }
    }

    /**
     * Parses a Tidal URL or search prefix into a {@link TidalResource}.
     *
     * @param input the URL or search string to parse
     * @return an Optional containing the parsed resource, or empty if the input is not recognized
     */
    public static Optional<TidalResource> parse(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }

        // Check for search prefix first (tdsearch:...)
        if (input.startsWith(SEARCH_PREFIX)) {
            String remainder = input.substring(SEARCH_PREFIX.length());
            if (remainder.isEmpty()) {
                return Optional.empty();
            }

            // Check for ISRC sub-prefix (tdsearch:isrc:{code})
            if (remainder.startsWith(ISRC_PREFIX)) {
                String isrcCode = remainder.substring(ISRC_PREFIX.length());
                if (isrcCode.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new TidalResource(ResourceType.ISRC_SEARCH, isrcCode));
            }

            return Optional.of(new TidalResource(ResourceType.SEARCH, remainder));
        }

        // Try URL pattern match
        Matcher matcher = URL_PATTERN.matcher(input);
        if (matcher.matches()) {
            String typeStr = matcher.group("type");
            String id = matcher.group("id");

            ResourceType resourceType;
            switch (typeStr) {
                case "track":
                    resourceType = ResourceType.TRACK;
                    break;
                case "album":
                    resourceType = ResourceType.ALBUM;
                    break;
                case "playlist":
                    resourceType = ResourceType.PLAYLIST;
                    break;
                default:
                    return Optional.empty();
            }

            return Optional.of(new TidalResource(resourceType, id));
        }

        return Optional.empty();
    }
}

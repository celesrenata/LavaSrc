package com.github.topi314.lavasrc.tidal;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class TidalSourceManager extends MirroringAudioSourceManager {

	public static final String SEARCH_PREFIX = "tdsearch:";
	public static final int PLAYLIST_MAX_PAGE_ITEMS = 750;
	public static final int ALBUM_MAX_PAGE_ITEMS = 120;

	private static final Logger log = LoggerFactory.getLogger(TidalSourceManager.class);

	private final TidalTokenManager tokenManager;
	private final TidalV2ApiClient apiClient;
	private final JsonApiParser jsonApiParser;
	private final String countryCode;
	private int searchLimit = 6;

	/**
	 * New primary constructor using v2 API with client credentials and/or static token.
	 */
	public TidalSourceManager(String[] providers, String countryCode,
	                          Function<Void, AudioPlayerManager> audioPlayerManager,
	                          String clientId, String clientSecret, String token) {
		this(countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers),
			clientId, clientSecret, token);
	}

	/**
	 * Backward-compatible constructor — passes null for clientId and clientSecret,
	 * using the token as a static Bearer token fallback.
	 */
	public TidalSourceManager(String[] providers, String countryCode,
	                          Function<Void, AudioPlayerManager> audioPlayerManager,
	                          String tidalToken) {
		this(providers, countryCode, audioPlayerManager, null, null, tidalToken);
	}

	/**
	 * Full constructor with explicit resolver.
	 */
	public TidalSourceManager(String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager,
	                          MirroringAudioTrackResolver mirroringAudioTrackResolver,
	                          String clientId, String clientSecret, String token) {
		super(audioPlayerManager, mirroringAudioTrackResolver);
		this.countryCode = (countryCode == null || countryCode.isEmpty()) ? "US" : countryCode;
		this.tokenManager = new TidalTokenManager(clientId, clientSecret, token);
		this.jsonApiParser = new JsonApiParser();

		if (this.tokenManager.isDisabled()) {
			log.warn("Tidal: No valid credentials configured. Tidal source will be disabled.");
			this.apiClient = null;
		} else {
			this.apiClient = new TidalV2ApiClient(this.tokenManager, this.countryCode, this.httpInterfaceManager);
		}
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	@Override
	public String getSourceName() {
		return "tidal";
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extendedAudioTrackInfo = super.decodeTrack(input);
		return new TidalAudioTrack(trackInfo, extendedAudioTrackInfo.albumName, extendedAudioTrackInfo.albumUrl,
			extendedAudioTrackInfo.artistUrl, extendedAudioTrackInfo.previewUrl, this);
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		if (tokenManager.isDisabled() || apiClient == null) {
			return null;
		}

		try {
			// Use TidalUrlParser to parse the input reference
			Optional<TidalUrlParser.TidalResource> parsed = TidalUrlParser.parse(reference.identifier);

			if (parsed.isPresent()) {
				TidalUrlParser.TidalResource resource = parsed.get();
				switch (resource.getType()) {
					case TRACK:
						return loadTrack(resource.getId());
					case ALBUM:
						return loadAlbum(resource.getId());
					case PLAYLIST:
						return loadPlaylist(resource.getId());
					case SEARCH:
						return getSearch(resource.getId());
					case ISRC_SEARCH:
						return getSearch(resource.getId());
					default:
						return null;
				}
			}
		} catch (TidalApiException e) {
			if (e.getStatusCode() == 404) {
				return AudioReference.NO_TRACK;
			}
			log.warn("Tidal: API error loading item '{}': {}", reference.identifier, e.getMessage());
			throw new RuntimeException("Tidal API error: " + e.getMessage(), e);
		}

		return null;
	}

	private AudioItem getSearch(String query) throws TidalApiException {
		JsonNode document = apiClient.searchTracks(query, searchLimit);
		if (document == null) {
			log.warn("Tidal: searchTracks returned null for query '{}'", query);
			return AudioReference.NO_TRACK;
		}

		// The search response includes track resources in the 'included' array,
		// but they lack relationships (no artist/album info). We extract the track IDs
		// and fetch each individually with include=artists,albums for full metadata.
		JsonNode included = document.path("included");
		List<String> trackIds = new ArrayList<>();
		if (included.isArray()) {
			for (JsonNode resource : included) {
				if ("tracks".equals(resource.path("type").asText(""))) {
					String id = resource.path("id").asText("");
					if (!id.isEmpty()) {
						trackIds.add(id);
					}
				}
			}
		}

		if (trackIds.isEmpty()) {
			log.info("Tidal: No tracks found in search results for '{}'", query);
			return AudioReference.NO_TRACK;
		}

		// Fetch each track individually to get full metadata (artist, album, duration)
		List<AudioTrack> tracks = new ArrayList<>();
		for (String trackId : trackIds) {
			if (tracks.size() >= searchLimit) break;
			try {
				JsonNode trackDoc = apiClient.getTrack(trackId);
				if (trackDoc == null) continue;
				JsonNode data = trackDoc.path("data");
				JsonNode trackIncluded = trackDoc.path("included");
				TidalTrackInfo info = jsonApiParser.parseTrack(data, trackIncluded);
				if (!info.getId().isEmpty() && info.getDurationMs() > 0) {
					tracks.add(new TidalAudioTrack(info.toAudioTrackInfo(), this));
				}
			} catch (TidalApiException e) {
				log.debug("Tidal: Failed to fetch track {} during search enrichment: {}", trackId, e.getMessage());
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new BasicAudioPlaylist("Tidal Search: " + query, tracks, null, true);
	}

	private AudioItem loadTrack(String trackId) throws TidalApiException {
		JsonNode document = apiClient.getTrack(trackId);
		if (document == null) {
			return AudioReference.NO_TRACK;
		}

		JsonNode data = document.path("data");
		JsonNode included = document.path("included");

		TidalTrackInfo trackInfo = jsonApiParser.parseTrack(data, included);
		if (trackInfo.getId().isEmpty() || trackInfo.getDurationMs() == 0) {
			return AudioReference.NO_TRACK;
		}

		return new TidalAudioTrack(trackInfo.toAudioTrackInfo(), this);
	}

	private AudioItem loadAlbum(String albumId) throws TidalApiException {
		List<JsonNode> trackNodes = apiClient.getAlbumTracks(albumId, ALBUM_MAX_PAGE_ITEMS);
		if (trackNodes.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonNode trackNode : trackNodes) {
			TidalTrackInfo info = jsonApiParser.parseTrack(trackNode, null);
			if (!info.getId().isEmpty() && info.getDurationMs() > 0) {
				tracks.add(new TidalAudioTrack(info.toAudioTrackInfo(), this));
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		// Use first track info for album metadata
		String albumUrl = "https://tidal.com/album/" + albumId;
		return new TidalAudioPlaylist(
			"Tidal Album: " + albumId,
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			albumUrl,
			null,
			null,
			tracks.size()
		);
	}

	private AudioItem loadPlaylist(String playlistUuid) throws TidalApiException {
		List<JsonNode> trackNodes = apiClient.getPlaylistTracks(playlistUuid, PLAYLIST_MAX_PAGE_ITEMS);
		if (trackNodes.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonNode trackNode : trackNodes) {
			TidalTrackInfo info = jsonApiParser.parseTrack(trackNode, null);
			if (!info.getId().isEmpty() && info.getDurationMs() > 0) {
				tracks.add(new TidalAudioTrack(info.toAudioTrackInfo(), this));
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		String playlistUrl = "https://tidal.com/playlist/" + playlistUuid;
		return new TidalAudioPlaylist(
			"Tidal Playlist: " + playlistUuid,
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			playlistUrl,
			null,
			null,
			tracks.size()
		);
	}

	/**
	 * Parses track resources from the JSON:API included array into AudioTrack list.
	 */
	private List<AudioTrack> parseTracksFromIncluded(JsonNode included) {
		if (included == null || included.isNull() || included.isMissingNode() || !included.isArray()) {
			return Collections.emptyList();
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonNode resource : included) {
			String type = resource.path("type").asText("");
			if ("tracks".equals(type)) {
				TidalTrackInfo info = jsonApiParser.parseTrack(resource, included);
				if (!info.getId().isEmpty() && info.getDurationMs() > 0) {
					tracks.add(new TidalAudioTrack(info.toAudioTrackInfo(), this));
				}
			}
		}
		return tracks;
	}

	@Override
	public void shutdown() {
		super.shutdown();
		if (tokenManager != null) {
			tokenManager.shutdown();
		}
	}
}

package com.github.topi314.lavasrc.tidal;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

/**
 * Internal value class representing parsed Tidal v2 API track data.
 * Immutable — all fields set via constructor.
 */
public class TidalTrackInfo {

    private final String id;
    private final String title;
    private final String artistName;
    private final String albumName;
    private final String albumArtUrl;
    private final String isrc;
    private final long durationMs;
    private final int trackNumber;
    private final String uri;

    public TidalTrackInfo(String id, String title, String artistName, String albumName,
                          String albumArtUrl, String isrc, long durationMs, int trackNumber) {
        this.id = id;
        this.title = title;
        this.artistName = artistName;
        this.albumName = albumName;
        this.albumArtUrl = albumArtUrl;
        this.isrc = isrc;
        this.durationMs = durationMs;
        this.trackNumber = trackNumber;
        this.uri = "https://tidal.com/track/" + id;
    }

    /**
     * Converts this Tidal track info to Lavaplayer's AudioTrackInfo format.
     */
    public AudioTrackInfo toAudioTrackInfo() {
        return new AudioTrackInfo(
            this.title,
            this.artistName,
            this.durationMs,
            this.id,
            false,
            this.uri,
            this.albumArtUrl,
            this.isrc
        );
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getArtistName() {
        return artistName;
    }

    public String getAlbumName() {
        return albumName;
    }

    public String getAlbumArtUrl() {
        return albumArtUrl;
    }

    public String getIsrc() {
        return isrc;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getTrackNumber() {
        return trackNumber;
    }

    public String getUri() {
        return uri;
    }
}

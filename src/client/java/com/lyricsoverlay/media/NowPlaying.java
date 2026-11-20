package com.lyricsoverlay.media;

/**
 * Snapshot of what Windows says is playing right now.
 *
 * @param posMs       position Windows reported
 * @param updMs       unix time (ms) at which posMs was true
 * @param durMs       track length, or 0 if the app doesn't report it
 * @param clockBaseMs fallback clock for apps that don't report a timeline
 */
public record NowPlaying(String app, String title, String artist, String album, boolean playing,
						 long posMs, long updMs, long durMs, String coverPath, long clockBaseMs) {

	public String key() {
		return (artist == null ? "" : artist) + "\u0000" + (title == null ? "" : title);
	}

	public boolean hasTimeline() {
		return durMs > 0;
	}

	/** Best estimate of the playback position right now. */
	public long positionNow() {
		long now = System.currentTimeMillis();
		long pos;
		if (hasTimeline()) {
			pos = posMs;
			if (playing && updMs > 0) {
				long elapsed = now - updMs;
				// Ignore nonsense timestamps (clock jumps etc.)
				if (elapsed >= 0 && elapsed < 6 * 60 * 60 * 1000L) pos += elapsed;
			}
			pos = Math.min(pos, durMs);
		} else {
			pos = now - clockBaseMs;
		}
		return Math.max(0, pos);
	}

	/** Friendly name of the app the music comes from. */
	public String sourceName() {
		String a = app == null ? "" : app.toLowerCase();
		if (a.contains("spotify")) return "Spotify";
		if (a.contains("chrome")) return "Chrome";
		if (a.contains("msedge") || a.contains("edge")) return "Edge";
		if (a.contains("firefox")) return "Firefox";
		if (a.contains("opera")) return "Opera";
		if (a.contains("brave")) return "Brave";
		if (a.contains("applemusic") || a.contains("apple")) return "Apple Music";
		if (a.contains("itunes")) return "iTunes";
		if (a.contains("zunemusic") || a.contains("media")) return "Media Player";
		if (a.contains("yandex")) return "Yandex Music";
		if (a.contains("deezer")) return "Deezer";
		if (a.contains("tidal")) return "TIDAL";
		if (a.contains("soundcloud")) return "SoundCloud";
		return app == null || app.isEmpty() ? "Unknown" : app;
	}

	/** True when the music is (probably) coming from a web browser — titles need extra cleaning. */
	public boolean fromBrowser() {
		String s = sourceName();
		return s.equals("Chrome") || s.equals("Edge") || s.equals("Firefox") || s.equals("Opera") || s.equals("Brave");
	}
}

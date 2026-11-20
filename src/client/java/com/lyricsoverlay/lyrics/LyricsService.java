package com.lyricsoverlay.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsoverlay.LyricsOverlayClient;
import com.lyricsoverlay.media.NowPlaying;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds synced lyrics for the current song using LRCLIB (free, no account / API key).
 */
public final class LyricsService {
	public enum Status {NONE, LOADING, SYNCED, PLAIN_ONLY, INSTRUMENTAL, NOT_FOUND, ERROR}

	public record Result(Status status, Lyrics lyrics, String matched) {
	}

	private static final String API = "https://lrclib.net/api";
	private static final String UA = "LyricsOverlay/1.0 (Minecraft Fabric mod; https://lrclib.net)";

	private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "LyricsOverlay-Lyrics");
		t.setDaemon(true);
		return t;
	});
	private final Map<String, Result> cache = new ConcurrentHashMap<>();

	private volatile String currentKey = "";
	private volatile Result current = new Result(Status.NONE, Lyrics.EMPTY, "");

	public Result current() {
		return current;
	}

	/** True if {@link #current()} belongs to this song (not the previous one). */
	public boolean isFor(NowPlaying np) {
		return np != null && np.key().equals(currentKey);
	}

	/** Call often; starts a lookup when the song changes. */
	public void update(NowPlaying np) {
		if (np == null) return;
		String key = np.key();
		if (key.equals(currentKey)) return;
		currentKey = key;
		Result cached = cache.get(key);
		if (cached != null) {
			current = cached;
			return;
		}
		current = new Result(Status.LOADING, Lyrics.EMPTY, "");
		pool.submit(() -> {
			Result r = fetch(np);
			if (r.status() != Status.ERROR) cache.put(key, r);
			if (key.equals(currentKey)) current = r;
		});
	}

	/** Forget the cached result for the current song and search again. */
	public void reload(NowPlaying np) {
		if (np == null) return;
		cache.remove(np.key());
		currentKey = "";
		update(np);
	}

	private Result fetch(NowPlaying np) {
		List<TitleCleaner.Query> queries = TitleCleaner.queries(np.artist(), np.title(), np.album(), np.fromBrowser());
		double durSec = np.durMs() / 1000.0;
		Result plainFallback = null;
		boolean anyOk = false;
		try {
			for (TitleCleaner.Query q : queries) {
				String url = API + "/search?track_name=" + enc(q.title())
						+ (q.artist().isEmpty() ? "" : "&artist_name=" + enc(q.artist()));
				JsonArray arr = getArray(url);
				if (arr == null) continue;
				anyOk = true;
				Result r = pick(arr, q, durSec);
				if (r != null) {
					if (r.status() == Status.SYNCED || r.status() == Status.INSTRUMENTAL) return log(np, r);
					if (plainFallback == null) plainFallback = r;
				}
			}
			// free-text search as a last try
			TitleCleaner.Query first = queries.isEmpty() ? new TitleCleaner.Query(np.artist(), np.title()) : queries.get(0);
			JsonArray arr = getArray(API + "/search?q=" + enc((first.artist() + " " + first.title()).trim()));
			if (arr != null) {
				anyOk = true;
				Result r = pick(arr, first, durSec);
				if (r != null && (r.status() == Status.SYNCED || r.status() == Status.INSTRUMENTAL)) return log(np, r);
				if (r != null && plainFallback == null) plainFallback = r;
			}
		} catch (Exception e) {
			LyricsOverlayClient.LOG.warn("Lyrics lookup failed for {} - {}: {}", np.artist(), np.title(), e.toString());
			return new Result(Status.ERROR, Lyrics.EMPTY, "");
		}
		if (plainFallback != null) return log(np, plainFallback);
		return log(np, new Result(anyOk ? Status.NOT_FOUND : Status.ERROR, Lyrics.EMPTY, ""));
	}

	private Result log(NowPlaying np, Result r) {
		LyricsOverlayClient.LOG.info("Lyrics for '{} - {}': {} {}", np.artist(), np.title(), r.status(), r.matched());
		return r;
	}

	/** Choose the best search result: synced lyrics, close duration, similar names. */
	private Result pick(JsonArray arr, TitleCleaner.Query q, double durSec) {
		JsonObject best = null;
		double bestScore = -1;
		for (JsonElement el : arr) {
			if (!el.isJsonObject()) continue;
			JsonObject o = el.getAsJsonObject();
			boolean synced = has(o, "syncedLyrics");
			boolean instrumental = o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean();
			double titleSim = TitleCleaner.similarity(str(o, "trackName"), q.title());
			double artistSim = q.artist().isEmpty() ? 0.5 : TitleCleaner.similarity(str(o, "artistName"), q.artist());
			if (titleSim < 0.5) continue;
			if (!q.artist().isEmpty() && artistSim < 0.3) continue;
			double score = titleSim * 2 + artistSim * 2 + (synced ? 3 : 0) + (instrumental ? 1 : 0);
			if (durSec > 1 && o.has("duration") && !o.get("duration").isJsonNull()) {
				double diff = Math.abs(o.get("duration").getAsDouble() - durSec);
				if (diff > 20) continue;          // a different version of the song
				score += diff <= 2 ? 2 : diff <= 6 ? 1 : 0;
			}
			if (score > bestScore) {
				bestScore = score;
				best = o;
			}
		}
		if (best == null) return null;
		String matched = str(best, "artistName") + " - " + str(best, "trackName");
		if (best.has("instrumental") && !best.get("instrumental").isJsonNull() && best.get("instrumental").getAsBoolean())
			return new Result(Status.INSTRUMENTAL, Lyrics.EMPTY, matched);
		if (has(best, "syncedLyrics")) {
			Lyrics l = Lyrics.parseLrc(best.get("syncedLyrics").getAsString());
			if (!l.isEmpty()) return new Result(Status.SYNCED, l, matched);
		}
		if (has(best, "plainLyrics")) return new Result(Status.PLAIN_ONLY, Lyrics.EMPTY, matched);
		return null;
	}

	private JsonArray getArray(String url) throws Exception {
		HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
		con.setConnectTimeout(6000);
		con.setReadTimeout(10000);
		con.setRequestProperty("User-Agent", UA);
		con.setRequestProperty("Accept", "application/json");
		try {
			int code = con.getResponseCode();
			if (code == 404) return new JsonArray();
			if (code / 100 != 2) throw new RuntimeException("HTTP " + code);
			String body;
			try (InputStream in = con.getInputStream()) {
				body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			JsonElement el = JsonParser.parseString(body);
			return el.isJsonArray() ? el.getAsJsonArray() : null;
		} finally {
			con.disconnect();
		}
	}

	private static boolean has(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() && !o.get(k).getAsString().isBlank();
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	public void shutdown() {
		pool.shutdownNow();
	}
}

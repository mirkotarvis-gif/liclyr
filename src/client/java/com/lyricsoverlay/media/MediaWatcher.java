package com.lyricsoverlay.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsoverlay.Config;
import com.lyricsoverlay.LyricsOverlayClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Runs a small hidden PowerShell helper that asks Windows what is playing
 * (Spotify, browsers/YouTube, Apple Music, ... — anything that shows up in the
 * Windows media flyout) and keeps the latest {@link NowPlaying} snapshot.
 */
public final class MediaWatcher {
	private volatile NowPlaying current;
	private volatile String status = "starting";
	private volatile boolean running = true;
	private volatile Process process;
	private Thread thread;

	// fallback clock for apps without a timeline
	private String clockKey = "";
	private long clockBase = 0;
	private boolean clockPlaying = false;
	private long clockPausedAt = 0;

	public NowPlaying current() {
		return current;
	}

	public String status() {
		return status;
	}

	public void start() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
			status = "Song detection only works on Windows 10/11";
			LyricsOverlayClient.LOG.warn(status);
			return;
		}
		thread = new Thread(this::loop, "LyricsOverlay-Media");
		thread.setDaemon(true);
		thread.start();
	}

	public void stop() {
		running = false;
		Process p = process;
		if (p != null) p.destroyForcibly();
	}

	private void loop() {
		int failures = 0;
		while (running) {
			long started = System.currentTimeMillis();
			try {
				runHelper();
			} catch (Exception e) {
				status = "media helper error: " + e.getMessage();
				LyricsOverlayClient.LOG.warn("Media helper failed", e);
			}
			if (!running) return;
			current = null;
			// back off if it keeps dying quickly
			failures = (System.currentTimeMillis() - started < 15000) ? failures + 1 : 0;
			sleep(Math.min(30000, 1500L * Math.max(1, failures)));
		}
	}

	private void runHelper() throws IOException, InterruptedException {
		Path dir = Config.dir().resolve("cache");
		Files.createDirectories(dir);
		Path script = dir.resolve("media.ps1");
		try (InputStream in = MediaWatcher.class.getResourceAsStream("/lyricsoverlay/media.ps1")) {
			if (in == null) throw new IOException("media.ps1 missing from mod jar");
			Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
		}

		ProcessBuilder pb = new ProcessBuilder(
				"powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
				"-File", script.toAbsolutePath().toString(),
				"-ParentPid", Long.toString(ProcessHandle.current().pid()),
				"-CoverDir", dir.toAbsolutePath().toString());
		pb.redirectErrorStream(false);
		Process p = pb.start();
		process = p;
		status = "waiting for music";

		Thread err = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
				String l;
				while ((l = r.readLine()) != null) {
					if (!l.isBlank()) LyricsOverlayClient.LOG.debug("[media.ps1] {}", l);
				}
			} catch (IOException ignored) {
			}
		}, "LyricsOverlay-MediaErr");
		err.setDaemon(true);
		err.start();

		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = r.readLine()) != null) {
				line = line.trim();
				if (line.startsWith("﻿")) line = line.substring(1);
				if (!line.startsWith("{")) continue;
				try {
					handle(JsonParser.parseString(line).getAsJsonObject());
				} catch (Exception e) {
					LyricsOverlayClient.LOG.debug("Bad media line: {}", line);
				}
			}
		} finally {
			p.destroyForcibly();
			process = null;
		}
	}

	private void handle(JsonObject o) {
		if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
			current = null;
			if (o.has("err")) {
				status = "Windows media error: " + o.get("err").getAsString();
				if (o.has("fatal")) LyricsOverlayClient.LOG.warn(status);
			} else {
				status = "nothing playing";
			}
			return;
		}
		String title = str(o, "title");
		String artist = str(o, "artist");
		if (title.isBlank()) {
			current = null;
			status = "nothing playing";
			return;
		}
		boolean playing = "Playing".equalsIgnoreCase(str(o, "status"));
		String key = artist + "\u0000" + title;
		long now = System.currentTimeMillis();

		// fallback clock (only used when the app gives no timeline)
		if (!key.equals(clockKey)) {
			clockKey = key;
			clockBase = now;
			clockPlaying = playing;
			clockPausedAt = now;
		} else if (clockPlaying && !playing) {
			clockPausedAt = now;
			clockPlaying = false;
		} else if (!clockPlaying && playing) {
			clockBase += now - clockPausedAt;
			clockPlaying = true;
		}
		long base = clockPlaying ? clockBase : clockBase + (now - clockPausedAt);

		current = new NowPlaying(str(o, "app"), title, artist, str(o, "album"), playing,
				num(o, "posMs"), num(o, "updMs"), num(o, "durMs"), str(o, "cover"), base);
		status = playing ? "playing" : "paused";
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static long num(JsonObject o, String k) {
		try {
			return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
		}
	}
}

package com.lyricsoverlay.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lyricsoverlay.Config;
import com.lyricsoverlay.LyricsOverlayClient;
import com.lyricsoverlay.lyrics.LyricsService;
import com.lyricsoverlay.media.NowPlaying;
import com.lyricsoverlay.render.FontManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny local web server (127.0.0.1 only) hosting the settings page.
 * Changes apply in-game instantly, so you can tweak with the game running.
 */
public final class SettingsServer {
	private final String token;
	private ServerSocket server;
	private int port;
	private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "LyricsOverlay-Web");
		t.setDaemon(true);
		return t;
	});

	public SettingsServer() {
		byte[] b = new byte[12];
		new SecureRandom().nextBytes(b);
		token = HexFormat.of().formatHex(b);
	}

	public String url() {
		return server == null ? null : "http://127.0.0.1:" + port + "/?t=" + token;
	}

	public void start(int preferredPort) {
		for (int p = preferredPort; p < preferredPort + 20; p++) {
			try {
				server = new ServerSocket(p, 20, InetAddress.getLoopbackAddress());
				port = p;
				break;
			} catch (IOException ignored) {
			}
		}
		if (server == null) {
			LyricsOverlayClient.LOG.warn("Could not open a port for the settings page");
			return;
		}
		Thread t = new Thread(() -> {
			while (!server.isClosed()) {
				try {
					Socket s = server.accept();
					pool.submit(() -> handle(s));
				} catch (IOException e) {
					if (server.isClosed()) return;
				}
			}
		}, "LyricsOverlay-WebAccept");
		t.setDaemon(true);
		t.start();
		LyricsOverlayClient.LOG.info("Lyrics Overlay settings page: {}", url());
	}

	public void stop() {
		try {
			if (server != null) server.close();
		} catch (IOException ignored) {
		}
		pool.shutdownNow();
	}

	// ---------------------------------------------------------------- tiny HTTP/1.1

	private void handle(Socket s) {
		try (s) {
			s.setSoTimeout(8000);
			InputStream in = s.getInputStream();
			String head = readHead(in);
			if (head == null) return;
			String[] lines = head.split("\r\n");
			String[] req = lines[0].split(" ");
			if (req.length < 2) return;
			String method = req[0], target = req[1];
			Map<String, String> headers = new HashMap<>();
			for (int i = 1; i < lines.length; i++) {
				int c = lines[i].indexOf(':');
				if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
			}
			int len = 0;
			try {
				len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
			} catch (NumberFormatException ignored) {
			}
			if (len > 2_000_000) {
				send(s.getOutputStream(), 413, "text/plain", "too big".getBytes());
				return;
			}
			byte[] body = in.readNBytes(len);

			String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
			OutputStream out = s.getOutputStream();

			if (path.equals("/") || path.equals("/index.html")) {
				send(out, 200, "text/html; charset=utf-8", resource("/lyricsoverlay/settings.html"));
				return;
			}
			if (!path.startsWith("/api/")) {
				send(out, 404, "text/plain", "not found".getBytes());
				return;
			}
			// API calls need the secret from the page URL (stops random websites poking at it)
			if (!token.equals(headers.get("x-token"))) {
				send(out, 403, "text/plain", "bad token".getBytes());
				return;
			}
			String host = headers.getOrDefault("host", "");
			if (!host.startsWith("127.0.0.1") && !host.startsWith("localhost")) {
				send(out, 403, "text/plain", "bad host".getBytes());
				return;
			}
			switch (method + " " + path) {
				case "GET /api/config" -> json(out, LyricsOverlayClient.config.toJson());
				case "GET /api/defaults" -> json(out, new Config().toJson());
				case "POST /api/config" -> {
					try {
						Config c = Config.fromJson(new String(body, StandardCharsets.UTF_8));
						LyricsOverlayClient.applyConfig(c);
						json(out, "{\"ok\":true}");
					} catch (Exception e) {
						send(out, 400, "application/json", ("{\"ok\":false,\"error\":" + quote(e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
					}
				}
				case "GET /api/fonts" -> {
					JsonArray a = new JsonArray();
					for (String f : FontManager.list()) a.add(f);
					json(out, a.toString());
				}
				case "GET /api/status" -> json(out, status().toString());
				case "POST /api/reload-lyrics" -> {
					LyricsOverlayClient.lyrics.reload(LyricsOverlayClient.media.current());
					json(out, "{\"ok\":true}");
				}
				case "POST /api/open-fonts" -> {
					LyricsOverlayClient.openFolder(Config.dir().resolve("fonts"));
					json(out, "{\"ok\":true}");
				}
				case "POST /api/edit" -> {
					LyricsOverlayClient.requestEditor();
					json(out, "{\"ok\":true}");
				}
				default -> send(out, 404, "text/plain", "not found".getBytes());
			}
		} catch (SocketTimeoutException ignored) {
		} catch (Exception e) {
			LyricsOverlayClient.LOG.debug("settings request failed", e);
		}
	}

	private JsonObject status() {
		JsonObject o = new JsonObject();
		NowPlaying np = LyricsOverlayClient.media.current();
		LyricsService.Result r = LyricsOverlayClient.lyrics.current();
		o.addProperty("media", LyricsOverlayClient.media.status());
		if (np != null) {
			o.addProperty("title", np.title());
			o.addProperty("artist", np.artist());
			o.addProperty("source", np.sourceName());
			o.addProperty("playing", np.playing());
			o.addProperty("posMs", np.positionNow());
			o.addProperty("durMs", np.durMs());
		}
		o.addProperty("lyrics", r.status().name());
		o.addProperty("matched", r.matched());
		return o;
	}

	private static String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int state = 0, b;
		while ((b = in.read()) != -1) {
			buf.write(b);
			if (buf.size() > 32_000) return null;
			state = switch (state) {
				case 0 -> b == '\r' ? 1 : 0;
				case 1 -> b == '\n' ? 2 : 0;
				case 2 -> b == '\r' ? 3 : 0;
				case 3 -> b == '\n' ? 4 : 0;
				default -> 0;
			};
			if (state == 4) return buf.toString(StandardCharsets.ISO_8859_1);
		}
		return null;
	}

	private static void json(OutputStream out, String json) throws IOException {
		send(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
	}

	private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
		String reason = switch (code) {
			case 200 -> "OK";
			case 400 -> "Bad Request";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			default -> "Error";
		};
		String h = "HTTP/1.1 " + code + " " + reason + "\r\n"
				+ "Content-Type: " + type + "\r\n"
				+ "Content-Length: " + body.length + "\r\n"
				+ "Cache-Control: no-store\r\n"
				+ "Connection: close\r\n\r\n";
		out.write(h.getBytes(StandardCharsets.ISO_8859_1));
		out.write(body);
		out.flush();
	}

	private static byte[] resource(String name) throws IOException {
		try (InputStream in = SettingsServer.class.getResourceAsStream(name)) {
			if (in == null) return "missing page".getBytes();
			return in.readAllBytes();
		}
	}

	private static String quote(String s) {
		JsonArray a = new JsonArray();
		a.add(s == null ? "error" : s);
		String j = a.toString();
		return j.substring(1, j.length() - 1);
	}
}

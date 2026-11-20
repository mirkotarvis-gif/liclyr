package com.lyricsoverlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All settings. Saved as config/lyricsoverlay/config.json.
 * Every field has a default so old/partial files still load fine.
 */
public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public boolean enabled = true;
	public Lyrics lyrics = new Lyrics();
	public Card card = new Card();
	public Behavior behavior = new Behavior();

	public static class Lyrics {
		public boolean show = true;
		/** Centre of the lyric line, as a fraction of the screen (0..1). */
		public double x = 0.5;
		public double y = 0.36;
		/** Overall size multiplier (mouse wheel in the editor changes this). */
		public double scale = 1.0;

		/** "Minecraft" = the game's own pixel font. Anything else = installed Windows font, or "file:Name.ttf" from the fonts folder. */
		public String font = "Minecraft";
		public double fontSize = 34;
		public boolean bold = false;
		public boolean italic = false;
		public boolean uppercase = false;
		public double letterSpacing = 0.0;
		/** Wrap long lines when wider than this fraction of the screen. */
		public double maxWidth = 0.7;
		public String align = "center"; // left / center / right

		public String color = "#FFE6CF";
		public boolean gradient = false;
		public String color2 = "#FF8A3D";
		public double opacity = 1.0;

		public boolean glow = true;
		public String glowColor = "#FF6A1F";
		public double glowSize = 10;
		public double glowStrength = 1.4;

		public boolean outline = false;
		public String outlineColor = "#000000";
		public double outlineWidth = 2;

		public boolean shadow = false;
		public String shadowColor = "#000000";
		public double shadowOffset = 3;
		public double shadowBlur = 4;

		public boolean background = false;
		public String bgColor = "#000000";
		public double bgOpacity = 0.45;
		public double bgPadding = 10;
		public double bgRadius = 10;

		/** fade / slide_up / slide_down / pop / wipe / blur_in / none */
		public String animation = "slide_up";
		public int fadeInMs = 280;
		public int fadeOutMs = 220;
	}

	public static class Card {
		public boolean show = true;
		/** Top-left corner of the card, as a fraction of the screen. */
		public double x = 0.008;
		public double y = 0.012;
		public double scale = 1.0;
		public double width = 300;

		public boolean background = true;
		public String bgColor = "#14141C";
		public double bgOpacity = 0.82;
		public double cornerRadius = 7;
		public boolean border = true;
		public String borderColor = "#3A3A4A";
		public double padding = 8;

		public boolean showCover = true;
		public double coverSize = 58;
		public double coverRadius = 4;

		public String font = "Minecraft";
		public boolean showTitle = true;
		public String titleColor = "#FFFFFF";
		public double titleSize = 15;
		public boolean titleBold = false;
		public boolean showArtist = true;
		public String artistColor = "#B9B9C9";
		public double artistSize = 12.5;
		public boolean showAlbum = false;
		public String albumColor = "#8A8A9A";
		public double albumSize = 11;

		public boolean showProgress = true;
		public boolean showTimes = true;
		public String timeColor = "#A0A0B0";
		public double timeSize = 10;
		public String accentColor = "#FF7A30";
		public String trackColor = "#45455A";
		public double barHeight = 4;

		public boolean textShadow = false;
		public boolean showSource = false;
		public boolean hideWhenPaused = false;
	}

	public static class Behavior {
		/** Shift lyric timing. Positive = lyrics show EARLIER. */
		public int offsetMs = 150;
		/** Hide the lyric if the next line is far away (instrumental break). 0 = never. */
		public double hideAfterSec = 8;
		public boolean hideLyricsWhenPaused = true;
		public boolean hideWhenNothingPlaying = true;
		/** Port for the browser settings page (http://127.0.0.1:PORT). */
		public int settingsPort = 25599;
	}

	// ---------------------------------------------------------------- load/save

	public static Path dir() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("lyricsoverlay");
	}

	public static Config load() {
		Path file = dir().resolve("config.json");
		try {
			if (Files.exists(file)) {
				Config c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
				if (c != null) return c.fixNulls();
			}
		} catch (Exception e) {
			LyricsOverlayClient.LOG.warn("Could not read config, using defaults", e);
		}
		Config c = new Config();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.createDirectories(dir());
			Files.writeString(dir().resolve("config.json"), toJson(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LyricsOverlayClient.LOG.warn("Could not save config", e);
		}
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static Config fromJson(String json) {
		Config c = GSON.fromJson(json, Config.class);
		if (c == null) throw new IllegalArgumentException("empty config");
		return c.fixNulls();
	}

	public Config copy() {
		return fromJson(toJson());
	}

	private Config fixNulls() {
		if (lyrics == null) lyrics = new Lyrics();
		if (card == null) card = new Card();
		if (behavior == null) behavior = new Behavior();
		Lyrics d = new Lyrics();
		if (lyrics.font == null) lyrics.font = d.font;
		if (lyrics.align == null) lyrics.align = d.align;
		if (lyrics.animation == null) lyrics.animation = d.animation;
		if (card.font == null) card.font = new Card().font;
		return this;
	}
}

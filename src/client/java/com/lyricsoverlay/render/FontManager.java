package com.lyricsoverlay.render;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Installed Windows fonts + any .ttf/.otf dropped into config/lyricsoverlay/fonts. */
public final class FontManager {
	public static final String MINECRAFT = "Minecraft";

	private static Path fontsDir;
	private static final Map<String, Font> fileFonts = new ConcurrentHashMap<>();
	private static volatile String[] systemFamilies;

	private FontManager() {
	}

	public static void init(Path dir) {
		fontsDir = dir;
		try {
			Files.createDirectories(dir);
			Path readme = dir.resolve("PUT_FONTS_HERE.txt");
			if (!Files.exists(readme)) {
				Files.writeString(readme, """
						Drop .ttf or .otf font files in this folder, then pick them in the
						Lyrics Overlay settings page (they show up as "file:YourFont.ttf").
						Tip: pixel fonts like "Monocraft", "Minecraftia" or "Press Start 2P"
						look great with the glow effect.
						""");
			}
		} catch (Exception ignored) {
		}
	}

	public static boolean isMinecraft(String name) {
		return name == null || name.isBlank() || name.equalsIgnoreCase(MINECRAFT);
	}

	public static List<String> list() {
		List<String> out = new ArrayList<>();
		out.add(MINECRAFT);
		if (fontsDir != null) {
			File[] files = fontsDir.toFile().listFiles((d, n) -> n.toLowerCase().endsWith(".ttf") || n.toLowerCase().endsWith(".otf"));
			if (files != null) {
				Arrays.sort(files);
				for (File f : files) out.add("file:" + f.getName());
			}
		}
		out.addAll(Arrays.asList(systemFamilies()));
		return out;
	}

	private static String[] systemFamilies() {
		String[] s = systemFamilies;
		if (s == null) {
			try {
				s = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
			} catch (Throwable t) {
				s = new String[]{"SansSerif", "Serif", "Monospaced"};
			}
			systemFamilies = s;
		}
		return s;
	}

	/** Get a font; unknown names fall back to a clean sans-serif. */
	public static Font get(String name, boolean bold, boolean italic, float size, float tracking) {
		int style = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
		Font base;
		if (name != null && name.startsWith("file:")) {
			base = fileFonts.computeIfAbsent(name, n -> {
				try {
					return Font.createFont(n.toLowerCase().endsWith(".otf") ? Font.TRUETYPE_FONT : Font.TRUETYPE_FONT,
							fontsDir.resolve(n.substring(5)).toFile());
				} catch (Exception e) {
					return new Font("SansSerif", Font.PLAIN, 12);
				}
			});
			base = base.deriveFont(style, size);
		} else {
			String fam = isMinecraft(name) ? "SansSerif" : name;
			base = new Font(fam, style, 1).deriveFont(size);
			if (!base.getFamily().equalsIgnoreCase(fam) && !"SansSerif".equals(fam)) {
				// font not installed
				base = new Font("SansSerif", style, 1).deriveFont(size);
			}
		}
		if (tracking != 0) base = base.deriveFont(Map.of(TextAttribute.TRACKING, tracking));
		return base;
	}

	/** Fallback used for characters the chosen font can't draw (emoji, CJK, Cyrillic in some fonts...). */
	public static Font fallback(Font like) {
		Font f = new Font("Dialog", like.getStyle(), 1).deriveFont(like.getSize2D());
		Object tr = like.getAttributes().get(TextAttribute.TRACKING);
		if (tr instanceof Float t && t != 0) f = f.deriveFont(Map.of(TextAttribute.TRACKING, t));
		return f;
	}
}

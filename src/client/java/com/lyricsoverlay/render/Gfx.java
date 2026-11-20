package com.lyricsoverlay.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.text.AttributedString;

/** Small drawing helpers shared by the lyric and card renderers. */
public final class Gfx {
	private Gfx() {
	}

	/** "#RRGGBB" or "#AARRGGBB" -> ARGB int. */
	public static int argb(String hex, double opacity) {
		int c = 0xFFFFFFFF;
		try {
			String h = hex == null ? "FFFFFF" : hex.trim().replace("#", "");
			if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
			long v = Long.parseLong(h, 16);
			c = h.length() <= 6 ? (int) (0xFF000000L | v) : (int) v;
		} catch (Exception ignored) {
		}
		int a = (int) Math.round(((c >>> 24) & 0xFF) * clamp(opacity, 0, 1));
		return (a << 24) | (c & 0xFFFFFF);
	}

	public static Color color(String hex, double opacity) {
		return new Color(argb(hex, opacity), true);
	}

	public static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}

	public static void hints(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
	}

	/** Text with per-character font fallback so missing glyphs don't turn into boxes. */
	public static AttributedString attributed(String text, Font font) {
		AttributedString as = new AttributedString(text.isEmpty() ? " " : text);
		if (text.isEmpty()) {
			as.addAttribute(TextAttribute.FONT, font);
			return as;
		}
		Font fb = FontManager.fallback(font);
		int runStart = 0;
		Boolean runOk = null;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			boolean ok = Character.isWhitespace(cp) ? (runOk == null || runOk) : font.canDisplay(cp);
			if (runOk == null) {
				runOk = ok;
			} else if (ok != runOk) {
				as.addAttribute(TextAttribute.FONT, runOk ? font : fb, runStart, i);
				runStart = i;
				runOk = ok;
			}
			i += Character.charCount(cp);
		}
		as.addAttribute(TextAttribute.FONT, runOk == null || runOk ? font : fb, runStart, text.length());
		return as;
	}

	/** Draws {@code shape} as a soft blurred shadow/glow of the given colour onto {@code dst}. */
	public static void drawBlurred(BufferedImage dst, Shape shape, java.awt.Stroke extraStroke, double dx, double dy,
								   double radius, int argb, double strength) {
		int w = dst.getWidth(), h = dst.getHeight();
		BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D mg = mask.createGraphics();
		hints(mg);
		mg.setColor(Color.WHITE);
		mg.translate(dx, dy);
		mg.fill(shape);
		if (extraStroke != null) {
			mg.setStroke(extraStroke);
			mg.draw(shape);
		}
		mg.dispose();

		int[] px = ((DataBufferInt) mask.getRaster().getDataBuffer()).getData();
		int[] alpha = new int[w * h];
		for (int i = 0; i < px.length; i++) alpha[i] = px[i] >>> 24;
		int r = (int) Math.max(1, Math.round(radius / 2.2));
		if (radius > 0.5) {
			for (int pass = 0; pass < 3; pass++) {
				boxBlurH(alpha, w, h, r);
				boxBlurV(alpha, w, h, r);
			}
		}
		int rgb = argb & 0xFFFFFF;
		double ca = ((argb >>> 24) & 0xFF) / 255.0;
		for (int i = 0; i < px.length; i++) {
			int a = (int) Math.min(255, Math.round(alpha[i] * ca * strength));
			px[i] = (a << 24) | rgb;
		}
		Graphics2D g = dst.createGraphics();
		g.drawImage(mask, 0, 0, null);
		g.dispose();
	}

	private static void boxBlurH(int[] a, int w, int h, int r) {
		int[] row = new int[w];
		int div = r * 2 + 1;
		for (int y = 0; y < h; y++) {
			int off = y * w;
			int sum = 0;
			for (int i = -r; i <= r; i++) sum += a[off + Math.min(w - 1, Math.max(0, i))];
			for (int x = 0; x < w; x++) {
				row[x] = sum / div;
				int add = Math.min(w - 1, x + r + 1), rem = Math.max(0, x - r);
				sum += a[off + add] - a[off + rem];
			}
			System.arraycopy(row, 0, a, off, w);
		}
	}

	private static void boxBlurV(int[] a, int w, int h, int r) {
		int[] col = new int[h];
		int div = r * 2 + 1;
		for (int x = 0; x < w; x++) {
			int sum = 0;
			for (int i = -r; i <= r; i++) sum += a[Math.min(h - 1, Math.max(0, i)) * w + x];
			for (int y = 0; y < h; y++) {
				col[y] = sum / div;
				int add = Math.min(h - 1, y + r + 1), rem = Math.max(0, y - r);
				sum += a[add * w + x] - a[rem * w + x];
			}
			for (int y = 0; y < h; y++) a[y * w + x] = col[y];
		}
	}
}

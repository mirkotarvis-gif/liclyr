package com.lyricsoverlay.render;

import com.lyricsoverlay.Config;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Draws the "now playing" card (cover, title, artist, progress) into an image. */
public final class CardRenderer {
	/** Text the game must draw itself (only used when the font is "Minecraft"). */
	public record TextItem(String text, double x, double y, double sizePx, double maxW, int argb, boolean shadow, boolean rightAlign) {
	}

	public record Result(BufferedImage image, double barX, double barY, double barW, double barH, List<TextItem> texts) {
	}

	public record Info(String title, String artist, String album, String source, long posMs, long durMs, boolean playing) {
	}

	private CardRenderer() {
	}

	public static Result render(Info info, BufferedImage cover, Config.Card c, double px) {
		boolean vanilla = FontManager.isMinecraft(c.font);
		List<TextItem> items = new ArrayList<>();
		FontRenderContext frc = new FontRenderContext(null, true, true);

		double p = c.padding * px;
		double coverS = c.showCover ? c.coverSize * px : 0;
		double W = Math.max(60, c.width) * px;
		double textX = p + coverS + (coverS > 0 ? p * 1.2 : 0);
		double textW = Math.max(20, W - textX - p);
		double gap = 2.5 * px;

		record Row(String text, double size, String color, boolean bold, boolean isTime) {
		}
		List<Row> rows = new ArrayList<>();
		if (c.showTitle) rows.add(new Row(info.title(), c.titleSize, c.titleColor, c.titleBold, false));
		if (c.showArtist && !info.artist().isBlank()) rows.add(new Row(info.artist(), c.artistSize, c.artistColor, false, false));
		if (c.showAlbum && !info.album().isBlank()) rows.add(new Row(info.album(), c.albumSize, c.albumColor, false, false));
		if (c.showSource) rows.add(new Row("via " + info.source(), c.albumSize, c.albumColor, false, false));
		boolean progressRow = c.showProgress || c.showTimes;

		double contentH = 0;
		for (Row r : rows) contentH += r.size * px * 1.18 + gap;
		double timeH = c.timeSize * px * 1.2;
		double progH = progressRow ? Math.max(timeH, c.barHeight * px) + gap * 1.5 : 0;
		contentH += progH;
		if (!rows.isEmpty() && !progressRow) contentH -= gap;

		double H = Math.max(coverS, contentH) + p * 2;
		int iw = (int) Math.ceil(W), ih = (int) Math.ceil(H);
		BufferedImage img = new BufferedImage(Math.max(1, iw), Math.max(1, ih), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		Gfx.hints(g);

		double rad = c.cornerRadius * px * 2;
		if (c.background) {
			Shape bg = new RoundRectangle2D.Double(0.5, 0.5, iw - 1, ih - 1, rad, rad);
			g.setColor(Gfx.color(c.bgColor, c.bgOpacity));
			g.fill(bg);
			if (c.border) {
				g.setColor(Gfx.color(c.borderColor, Math.min(1, c.bgOpacity + 0.1)));
				g.setStroke(new BasicStroke((float) Math.max(1, px)));
				g.draw(bg);
			}
		}

		// cover
		if (coverS > 0) {
			double cy = (H - coverS) / 2;
			Shape clip = new RoundRectangle2D.Double(p, cy, coverS, coverS, c.coverRadius * px * 2, c.coverRadius * px * 2);
			if (cover != null) {
				Shape old = g.getClip();
				g.setClip(clip);
				// centre-crop to a square
				int cw = cover.getWidth(), ch = cover.getHeight(), sq = Math.min(cw, ch);
				g.drawImage(cover, (int) Math.round(p), (int) Math.round(cy), (int) Math.round(p + coverS), (int) Math.round(cy + coverS),
						(cw - sq) / 2, (ch - sq) / 2, (cw - sq) / 2 + sq, (ch - sq) / 2 + sq, null);
				g.setClip(old);
			} else {
				g.setColor(new Color(Gfx.argb(c.trackColor, 1), true));
				g.fill(clip);
				Font nf = new Font("Dialog", Font.BOLD, 1).deriveFont((float) (coverS * 0.5));
				TextLayout tl = new TextLayout("♪", nf, frc);
				g.setColor(Gfx.color(c.accentColor, 1));
				tl.draw(g, (float) (p + (coverS - tl.getAdvance()) / 2), (float) (cy + coverS / 2 + tl.getAscent() * 0.35));
			}
		}

		// text rows
		double y = (H - contentH) / 2;
		for (Row r : rows) {
			double sz = r.size * px;
			if (vanilla) {
				items.add(new TextItem(r.text, textX, y, sz, textW, Gfx.argb(r.color, 1), c.textShadow, false));
			} else {
				drawText(g, frc, r.text, c.font, r.bold, sz, textX, y, textW, Gfx.color(r.color, 1), c.textShadow, false);
			}
			y += sz * 1.18 + gap;
		}

		// progress row
		double barX = 0, barY = 0, barW = 0, barH = 0;
		if (progressRow) {
			y += gap * 0.5;
			double rowH = Math.max(timeH, c.barHeight * px);
			double tsz = c.timeSize * px;
			String left = fmt(info.posMs(), info.durMs());
			String right = info.durMs() > 0 ? fmt(info.durMs(), info.durMs()) : "--:--";
			Color tc = Gfx.color(c.timeColor, 1);
			double tY = y + (rowH - tsz) / 2;
			if (c.showProgress) {
				double lw = 0, rw = 0;
				if (c.showTimes) {
					lw = measure(frc, left, c.font, tsz, vanilla);
					rw = measure(frc, right, c.font, tsz, vanilla);
					emitText(g, frc, items, vanilla, left, c, tsz, textX, tY, lw + 4, tc, false);
					emitText(g, frc, items, vanilla, right, c, tsz, textX + textW, tY, rw + 4, tc, true);
				}
				double inner = c.showTimes ? tsz * 0.55 : 0;
				barX = textX + lw + inner;
				barW = Math.max(4, textX + textW - rw - inner - barX);
				barH = Math.max(1, c.barHeight * px);
				barY = y + (rowH - barH) / 2;
				g.setColor(Gfx.color(c.trackColor, 1));
				g.fill(new RoundRectangle2D.Double(barX, barY, barW, barH, barH, barH));
			} else {
				String both = left + " / " + right;
				emitText(g, frc, items, vanilla, both, c, tsz, textX, tY, textW, tc, false);
			}
		}
		g.dispose();
		return new Result(img, barX, barY, barW, barH, items);
	}

	private static void emitText(Graphics2D g, FontRenderContext frc, List<TextItem> items, boolean vanilla, String text,
								 Config.Card c, double size, double x, double y, double maxW, Color col, boolean right) {
		if (vanilla) {
			items.add(new TextItem(text, x, y, size, maxW, col.getRGB(), c.textShadow, right));
		} else {
			drawText(g, frc, text, c.font, false, size, x, y, maxW, col, c.textShadow, right);
		}
	}

	private static void drawText(Graphics2D g, FontRenderContext frc, String text, String fontName, boolean bold, double size,
								 double x, double top, double maxW, Color color, boolean shadow, boolean rightAlign) {
		if (text == null || text.isEmpty()) return;
		Font f = FontManager.get(fontName, bold, false, (float) size, 0);
		String t = ellipsize(frc, text, f, maxW);
		TextLayout tl = new TextLayout(Gfx.attributed(t, f).getIterator(), frc);
		double dx = rightAlign ? x - tl.getAdvance() : x;
		double base = top + (size - (tl.getAscent() + tl.getDescent())) / 2 + tl.getAscent();
		Shape s = tl.getOutline(AffineTransform.getTranslateInstance(dx, base));
		if (shadow) {
			g.setColor(new Color(0, 0, 0, 140));
			double o = Math.max(1, size / 12);
			g.fill(AffineTransform.getTranslateInstance(o, o).createTransformedShape(s));
		}
		g.setColor(color);
		g.fill(s);
	}

	private static String ellipsize(FontRenderContext frc, String text, Font f, double maxW) {
		if (adv(frc, text, f) <= maxW) return text;
		String ell = "…";
		int lo = 0, hi = text.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (adv(frc, text.substring(0, mid).stripTrailing() + ell, f) <= maxW) lo = mid;
			else hi = mid - 1;
		}
		return lo <= 0 ? ell : text.substring(0, lo).stripTrailing() + ell;
	}

	private static double adv(FontRenderContext frc, String text, Font f) {
		if (text.isEmpty()) return 0;
		return new TextLayout(Gfx.attributed(text, f).getIterator(), frc).getAdvance();
	}

	private static double measure(FontRenderContext frc, String text, String font, double size, boolean vanilla) {
		if (vanilla) {
			// Minecraft font: digits are 6 units wide, ':' 2, '/' 6, '-' 6, space 4; line height 9 units.
			double units = 0;
			for (char ch : text.toCharArray()) units += ch == ':' ? 2 : ch == ' ' ? 4 : 6;
			return Math.max(0, units - 1) * size / 9.0;
		}
		return adv(frc, text, FontManager.get(font, false, false, (float) size, 0));
	}

	public static String fmt(long ms, long durMs) {
		long s = Math.max(0, ms / 1000);
		if (durMs >= 3600_000) return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
		return String.format("%d:%02d", s / 60, s % 60);
	}
}

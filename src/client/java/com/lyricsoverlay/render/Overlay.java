package com.lyricsoverlay.render;

import com.lyricsoverlay.Config;
import com.lyricsoverlay.LyricsOverlayClient;
import com.lyricsoverlay.gui.EditScreen;
import com.lyricsoverlay.lyrics.Lyrics;
import com.lyricsoverlay.lyrics.LyricsService;
import com.lyricsoverlay.media.NowPlaying;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Draws the lyric line and the now-playing card on the HUD. */
public final class Overlay {
	private static final String PLACEHOLDER_LYRIC = "♪ Lyrics will show up right here ♪";

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "LyricsOverlay-Render");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	// ------------------------------------------------------------ lyric state
	private static final class Shown {
		final String id, text;
		final long since;
		long leaveAt = -1;

		Shown(String id, String text, long since) {
			this.id = id;
			this.text = text;
			this.since = since;
		}
	}

	private Shown cur, prev;

	private static final class Job {
		CompletableFuture<NativeImage> future;
		GpuImage gpu;
	}

	/** LRU cache of rendered lyric lines. */
	private final LinkedHashMap<String, Job> lineCache = new LinkedHashMap<>(16, 0.75f, true);

	// ------------------------------------------------------------ card state
	private String cardKey = "";
	private CompletableFuture<CardBuilt> cardPending;
	private CardBuilt cardShown;
	private double cardAlpha = 0;
	private long lastFrame = System.nanoTime();

	private record CardBuilt(GpuImage gpu, NativeImage pendingNative, CardRenderer.Result layout) {
	}

	private volatile BufferedImage cover;
	private volatile String coverKey = "";

	// ------------------------------------------------------------ editor hit boxes (GUI coords)
	public volatile double[] lyricsBox = null; // x, y, w, h
	public volatile double[] cardBox = null;

	public void render(GuiGraphics g, DeltaTracker delta) {
		Config cfg = LyricsOverlayClient.config;
		Minecraft mc = Minecraft.getInstance();
		boolean editing = EditScreen.isOpen();
		long nowNs = System.nanoTime();
		double dt = Math.min(0.1, (nowNs - lastFrame) / 1e9);
		lastFrame = nowNs;
		if (!cfg.enabled && !editing) {
			lyricsBox = cardBox = null;
			return;
		}

		int fbW = mc.getWindow().getWidth(), fbH = mc.getWindow().getHeight();
		if (fbW <= 0 || fbH <= 0 || g.guiWidth() <= 0) return;
		double gs = fbW / (double) g.guiWidth();

		NowPlaying np = LyricsOverlayClient.media.current();
		LyricsService.Result lr = LyricsOverlayClient.lyrics.current();

		g.pose().pushMatrix();
		g.pose().scale((float) (1.0 / gs));
		try {
			renderCard(g, mc, cfg, np, editing, fbW, fbH, gs, dt);
			renderLyrics(g, mc, cfg, np, lr, editing, fbW, fbH, gs);
		} catch (Throwable t) {
			LyricsOverlayClient.LOG.error("Overlay render failed", t);
		} finally {
			g.pose().popMatrix();
		}
	}

	// =====================================================================================
	// Lyrics
	// =====================================================================================

	private void renderLyrics(GuiGraphics g, Minecraft mc, Config cfg, NowPlaying np, LyricsService.Result lr,
							  boolean editing, int fbW, int fbH, double gs) {
		Config.Lyrics s = cfg.lyrics;
		long now = System.currentTimeMillis();

		String targetId = null, targetText = null;
		if (s.show && np != null && LyricsOverlayClient.lyrics.isFor(np) && lr.status() == LyricsService.Status.SYNCED
				&& (np.playing() || !cfg.behavior.hideLyricsWhenPaused)) {
			List<Lyrics.Line> lines = lr.lyrics().lines();
			long pos = np.positionNow() + cfg.behavior.offsetMs;
			int idx = lr.lyrics().indexAt(pos);
			if (idx >= 0) {
				Lyrics.Line line = lines.get(idx);
				long next = idx + 1 < lines.size() ? lines.get(idx + 1).timeMs() : Long.MAX_VALUE;
				long hide = (long) (cfg.behavior.hideAfterSec * 1000);
				boolean stale = hide > 0 && pos - line.timeMs() > hide && next - line.timeMs() > hide;
				if (!line.text().isBlank() && !stale) {
					targetId = np.key() + "#" + idx;
					targetText = line.text();
				}
			}
		}
		if (targetText == null && editing && s.show) {
			targetId = "placeholder";
			targetText = PLACEHOLDER_LYRIC;
		}

		// ---- the important bit: a new line REPLACES the old one (old fades out, new fades in)
		String curId = cur == null ? null : cur.id;
		if (!java.util.Objects.equals(curId, targetId)) {
			if (cur != null) {
				cur.leaveAt = now;
				prev = cur;
			}
			cur = targetId == null ? null : new Shown(targetId, targetText, now);
		}
		if (prev != null && now - prev.leaveAt > Math.max(1, s.fadeOutMs)) prev = null;

		double px = fbH / 1080.0 * s.scale;
		double maxW = Gfx.clamp(s.maxWidth, 0.1, 1) * fbW;
		double ax = s.x * fbW, ay = s.y * fbH;
		boolean vanilla = FontManager.isMinecraft(s.font);
		int ver = LyricsOverlayClient.configVersion();

		lyricsBox = null;
		if (prev != null) {
			double t = (now - prev.leaveAt) / (double) Math.max(1, s.fadeOutMs);
			drawLine(g, mc, s, prev.text, ax, ay, px, maxW, vanilla, ver, 1 - t, false, gs, false);
		}
		if (cur != null) {
			double t = s.fadeInMs <= 0 ? 1 : (now - cur.since) / (double) s.fadeInMs;
			drawLine(g, mc, s, cur.text, ax, ay, px, maxW, vanilla, ver, Math.min(1, t), true, gs, true);
		}
		if (lyricsBox == null && editing) {
			lyricsBox = new double[]{(ax - 150 * px) / gs, (ay - 25 * px) / gs, 300 * px / gs, 50 * px / gs};
		}
		trimCache();
	}

	/**
	 * @param k        animation progress 0..1 (1 = fully visible)
	 * @param entering true for the incoming line, false for the outgoing one
	 */
	private void drawLine(GuiGraphics g, Minecraft mc, Config.Lyrics s, String text, double ax, double ay, double px,
						  double maxW, boolean vanilla, int ver, double k, boolean entering, double gs, boolean recordBox) {
		k = Gfx.clamp(k, 0, 1);
		double e = entering ? easeOutBack(k, s.animation.equals("pop")) : easeOut(k);
		double alpha = s.animation.equals("none") ? 1 : easeOut(k);
		double dy = 0, scale = 1, wipe = 1;
		double slide = 22 * px;
		switch (s.animation) {
			case "slide_up" -> dy = entering ? (1 - e) * slide : -(1 - e) * slide;
			case "slide_down" -> dy = entering ? -(1 - e) * slide : (1 - e) * slide;
			case "pop" -> scale = entering ? 0.7 + 0.3 * e : 1 + 0.08 * (1 - e);
			case "zoom" -> scale = entering ? 1.35 - 0.35 * e : 0.85 + 0.15 * e;
			case "wipe" -> {
				if (entering) {
					wipe = e;
					alpha = Math.min(1, k * 3);
				}
			}
			case "none" -> alpha = k >= 1 || entering ? 1 : 0;
			default -> {
			}
		}
		if (alpha <= 0.01) return;

		if (vanilla) {
			drawVanillaLine(g, mc, s, text, ax, ay + dy, px, maxW, scale, alpha, gs, recordBox);
			return;
		}

		GpuImage img = lineTexture(text, s, px, maxW, ver);
		if (img == null) return;
		double ox = switch (s.align) {
			case "left" -> 0;
			case "right" -> -img.width;
			default -> -img.width / 2.0;
		};
		double oy = -img.height / 2.0;
		g.pose().pushMatrix();
		g.pose().translate((float) ax, (float) (ay + dy));
		g.pose().scale((float) scale);
		g.pose().translate((float) ox, (float) oy);
		int a = (int) Math.round(alpha * 255);
		int drawW = (int) Math.max(1, Math.round(img.width * wipe));
		g.blit(RenderPipelines.GUI_TEXTURED, img.id, 0, 0, 0f, 0f, drawW, img.height, img.width, img.height, (a << 24) | 0xFFFFFF);
		g.pose().popMatrix();
		if (recordBox) {
			lyricsBox = new double[]{(ax + ox) / gs, (ay + oy) / gs, img.width / gs, img.height / gs};
		}
	}

	private GpuImage lineTexture(String text, Config.Lyrics s, double px, double maxW, int ver) {
		String key = ver + "|" + Math.round(px * 1000) + "|" + Math.round(maxW) + "|" + text;
		Job job = lineCache.get(key);
		if (job == null) {
			job = new Job();
			Config.Lyrics style = LyricsOverlayClient.config.copy().lyrics;
			job.future = CompletableFuture.supplyAsync(() -> GpuImage.toNative(LyricImageRenderer.render(text, style, px, maxW)), worker);
			lineCache.put(key, job);
		}
		if (job.gpu == null && job.future.isDone()) {
			NativeImage ni = job.future.getNow(null);
			if (ni != null) job.gpu = GpuImage.upload(ni);
			else job.future = CompletableFuture.completedFuture(null);
		}
		return job.gpu;
	}

	private void trimCache() {
		if (lineCache.size() <= 10) return;
		Iterator<Map.Entry<String, Job>> it = lineCache.entrySet().iterator();
		while (lineCache.size() > 8 && it.hasNext()) {
			Job j = it.next().getValue();
			if (j.gpu != null) {
				j.gpu.release();
			} else if (!j.future.isDone()) {
				continue;
			} else {
				NativeImage ni = j.future.getNow(null);
				if (ni != null) ni.close();
			}
			it.remove();
		}
	}

	/** Lyric line using the game's own font (pixel look, like vanilla). */
	private void drawVanillaLine(GuiGraphics g, Minecraft mc, Config.Lyrics s, String text, double ax, double ay, double px,
								 double maxW, double animScale, double alpha, double gs, boolean recordBox) {
		Font font = mc.font;
		double unit = Math.max(0.5, s.fontSize * px / 9.0); // pixels per font unit
		MutableComponent comp = Component.literal(s.uppercase ? text.toUpperCase(Locale.ROOT) : text);
		if (s.bold) comp = comp.withStyle(ChatFormatting.BOLD);
		if (s.italic) comp = comp.withStyle(ChatFormatting.ITALIC);
		List<FormattedCharSequence> lines = font.split(comp, (int) Math.max(40, maxW / unit));
		if (lines.isEmpty()) return;
		int lineH = 10;
		int blockW = 0;
		for (FormattedCharSequence l : lines) blockW = Math.max(blockW, font.width(l));
		int blockH = lines.size() * lineH - 1;

		double ox = switch (s.align) {
			case "left" -> 0;
			case "right" -> -blockW;
			default -> -blockW / 2.0;
		};
		double oy = -blockH / 2.0;

		g.pose().pushMatrix();
		g.pose().translate((float) ax, (float) ay);
		g.pose().scale((float) (unit * animScale));
		g.pose().translate((float) ox, (float) oy);

		int a = (int) Math.round(alpha * 255);
		if (s.background) {
			double padU = s.bgPadding * px / unit;
			int bg = Gfx.argb(s.bgColor, s.bgOpacity * alpha);
			g.fill((int) Math.floor(-padU), (int) Math.floor(-padU), (int) Math.ceil(blockW + padU), (int) Math.ceil(blockH + padU), bg);
		}
		int main = (Gfx.argb(s.color, s.opacity * alpha));
		for (int i = 0; i < lines.size(); i++) {
			FormattedCharSequence l = lines.get(i);
			double lx = switch (s.align) {
				case "left" -> 0;
				case "right" -> blockW - font.width(l);
				default -> (blockW - font.width(l)) / 2.0;
			};
			double ly = i * lineH;
			if (s.glow) {
				double r = Math.max(0.6, s.glowSize * px / unit * 0.55);
				int ga = (int) Gfx.clamp(255 * 0.11 * s.glowStrength * alpha, 0, 255);
				int gc = (ga << 24) | (Gfx.argb(s.glowColor, 1) & 0xFFFFFF);
				if (ga > 2) {
					for (double ring : new double[]{0.33, 0.66, 1.0}) {
						for (int d = 0; d < 8; d++) {
							double ang = d * Math.PI / 4 + ring;
							offsetString(g, font, l, lx + Math.cos(ang) * r * ring, ly + Math.sin(ang) * r * ring, gc);
						}
					}
				}
			}
			if (s.shadow) {
				double o = s.shadowOffset * px / unit;
				offsetString(g, font, l, lx + o, ly + o, Gfx.argb(s.shadowColor, 0.8 * alpha));
			}
			if (s.outline) {
				double o = Math.max(0.5, s.outlineWidth * px / unit * 0.5);
				int oc = Gfx.argb(s.outlineColor, alpha);
				for (int d = 0; d < 8; d++) {
					double ang = d * Math.PI / 4;
					offsetString(g, font, l, lx + Math.cos(ang) * o, ly + Math.sin(ang) * o, oc);
				}
			}
			if ((main >>> 24) > 3) offsetString(g, font, l, lx, ly, main);
		}
		g.pose().popMatrix();
		if (recordBox && a > 0) {
			double sc = unit * animScale;
			lyricsBox = new double[]{(ax + ox * sc) / gs, (ay + oy * sc) / gs, blockW * sc / gs, blockH * sc / gs};
		}
	}

	private static void offsetString(GuiGraphics g, Font font, FormattedCharSequence l, double x, double y, int color) {
		if ((color >>> 24) <= 3) return;
		g.pose().pushMatrix();
		g.pose().translate((float) x, (float) y);
		g.drawString(font, l, 0, 0, color, false);
		g.pose().popMatrix();
	}

	// =====================================================================================
	// Card
	// =====================================================================================

	private void renderCard(GuiGraphics g, Minecraft mc, Config cfg, NowPlaying np, boolean editing,
							int fbW, int fbH, double gs, double dt) {
		Config.Card c = cfg.card;
		CardRenderer.Info info;
		long pos;
		if (np != null) {
			pos = np.positionNow();
			info = new CardRenderer.Info(np.title(), np.artist(), np.album(), np.sourceName(), pos, np.durMs(), np.playing());
		} else if (editing) {
			pos = 42_000;
			info = new CardRenderer.Info("Song Title", "Artist Name", "Album", "Spotify", pos, 200_000, true);
		} else {
			pos = 0;
			info = null;
		}

		boolean visible = c.show && info != null && (editing || !(c.hideWhenPaused && np != null && !np.playing()));
		cardAlpha = Gfx.clamp(cardAlpha + (visible ? dt : -dt) * 4.0, 0, 1);
		if (np != null) loadCoverIfNeeded(np);

		cardBox = null;
		if (info == null && cardShown == null) return;

		double px = fbH / 1080.0 * c.scale;
		if (info != null) {
			BufferedImage cov = np != null ? cover : null;
			String key = LyricsOverlayClient.configVersion() + "|" + Math.round(px * 1000) + "|" + info.title() + "|" + info.artist()
					+ "|" + info.album() + "|" + (info.posMs() / 1000) + "|" + info.durMs() + "|" + System.identityHashCode(cov);
			if (!key.equals(cardKey) && (cardPending == null || cardPending.isDone())) {
				cardKey = key;
				Config.Card style = LyricsOverlayClient.config.copy().card;
				CardRenderer.Info snap = info;
				cardPending = CompletableFuture.supplyAsync(() -> {
					CardRenderer.Result r = CardRenderer.render(snap, cov, style, px);
					return new CardBuilt(null, GpuImage.toNative(r.image()), r);
				}, worker);
			}
		}
		if (cardPending != null && cardPending.isDone()) {
			CardBuilt b = cardPending.getNow(null);
			cardPending = null;
			if (b != null) {
				if (cardShown != null && cardShown.gpu() != null) cardShown.gpu().release();
				cardShown = new CardBuilt(GpuImage.upload(b.pendingNative()), null, b.layout());
			}
		}
		if (cardShown == null || cardShown.gpu() == null || cardAlpha <= 0.01) return;

		GpuImage img = cardShown.gpu();
		CardRenderer.Result L = cardShown.layout();
		double x = Math.round(c.x * fbW), y = Math.round(c.y * fbH);
		int a = (int) Math.round(easeOut(cardAlpha) * 255);
		g.pose().pushMatrix();
		g.pose().translate((float) x, (float) y);
		g.blit(RenderPipelines.GUI_TEXTURED, img.id, 0, 0, 0f, 0f, img.width, img.height, img.width, img.height, (a << 24) | 0xFFFFFF);

		// live progress fill (smooth, every frame)
		if (L.barW() > 0 && info != null) {
			double frac = info.durMs() > 0 ? Gfx.clamp(pos / (double) info.durMs(), 0, 1) : 0;
			int x1 = (int) Math.round(L.barX()), y1 = (int) Math.round(L.barY());
			int x2 = (int) Math.round(L.barX() + L.barW() * frac), y2 = (int) Math.round(L.barY() + L.barH());
			if (x2 > x1) g.fill(x1, y1, x2, Math.max(y1 + 1, y2), Gfx.argb(c.accentColor, a / 255.0));
		}

		// texts drawn with the Minecraft font
		Font font = mc.font;
		for (CardRenderer.TextItem t : L.texts()) {
			double unit = Math.max(0.5, t.sizePx() / 9.0);
			int maxU = (int) Math.floor(t.maxW() / unit);
			String str = t.text();
			if (font.width(str) > maxU) {
				str = font.plainSubstrByWidth(str, Math.max(0, maxU - font.width("..."))) + "...";
			}
			double tx = t.rightAlign() ? t.x() - font.width(str) * unit : t.x();
			double ty = t.y() + (t.sizePx() - 8 * unit) / 2;
			int col = ((int) Math.round(((t.argb() >>> 24) & 0xFF) * a / 255.0) << 24) | (t.argb() & 0xFFFFFF);
			if ((col >>> 24) <= 3) continue;
			g.pose().pushMatrix();
			g.pose().translate((float) tx, (float) ty);
			g.pose().scale((float) unit);
			g.drawString(font, str, 0, 0, col, t.shadow());
			g.pose().popMatrix();
		}
		g.pose().popMatrix();
		cardBox = new double[]{x / gs, y / gs, img.width / gs, img.height / gs};
	}

	private void loadCoverIfNeeded(NowPlaying np) {
		String path = np.coverPath();
		String key = np.key() + "|" + path;
		if (key.equals(coverKey)) return;
		coverKey = key;
		if (path == null || path.isEmpty()) {
			cover = null;
			return;
		}
		worker.submit(() -> {
			try {
				File f = new File(path);
				BufferedImage raw = f.exists() ? ImageIO.read(f) : null;
				if (raw == null) {
					if (key.equals(coverKey)) cover = null;
					return;
				}
				int max = 256;
				double s = Math.min(1.0, max / (double) Math.max(raw.getWidth(), raw.getHeight()));
				int w = Math.max(1, (int) (raw.getWidth() * s)), h = Math.max(1, (int) (raw.getHeight() * s));
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				Gfx.hints(g);
				g.drawImage(raw, 0, 0, w, h, null);
				g.dispose();
				if (key.equals(coverKey)) cover = img;
			} catch (Exception e) {
				if (key.equals(coverKey)) cover = null;
			}
		});
	}

	/** Called when settings change so everything gets re-rendered. */
	public void invalidate() {
		cardKey = "";
	}

	// =====================================================================================

	private static double easeOut(double t) {
		t = Gfx.clamp(t, 0, 1);
		return 1 - Math.pow(1 - t, 3);
	}

	private static double easeOutBack(double t, boolean back) {
		t = Gfx.clamp(t, 0, 1);
		if (!back) return easeOut(t);
		double c1 = 1.70158, c3 = c1 + 1;
		return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
	}

	public void shutdown() {
		worker.shutdownNow();
	}
}

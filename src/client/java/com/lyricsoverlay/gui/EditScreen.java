package com.lyricsoverlay.gui;

import com.lyricsoverlay.Config;
import com.lyricsoverlay.LyricsOverlayClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game layout editor: drag the lyrics / card around, mouse-wheel to resize.
 * Everything else (fonts, colours, effects...) is on the browser settings page.
 */
public class EditScreen extends Screen {
	private static volatile boolean open;

	private enum Target {NONE, LYRICS, CARD}

	private Target drag = Target.NONE;
	private double grabDX, grabDY;
	private boolean wasDown;
	private boolean snappedX, snappedY;
	private final List<Button> buttons = new ArrayList<>();
	private Button cardBgBtn, lyricsBgBtn, cardShowBtn, lyricsShowBtn;

	public EditScreen() {
		super(Component.literal("Lyrics Overlay - Edit layout"));
	}

	public static boolean isOpen() {
		return open;
	}

	@Override
	protected void init() {
		open = true;
		buttons.clear();
		Config c = LyricsOverlayClient.config;
		int bw = 92, bh = 20, gap = 4;
		int row1 = this.height - 2 * bh - 10, row2 = this.height - bh - 6;
		int cols = 5;
		int startX = this.width / 2 - (cols * bw + (cols - 1) * gap) / 2;

		lyricsShowBtn = add(startX, row1, bw, bh, label("Lyrics", c.lyrics.show), b -> {
			c.lyrics.show = !c.lyrics.show;
			changed();
		});
		add(startX + (bw + gap), row1, bw / 2 - 1, bh, "Lyrics -", b -> scale(Target.LYRICS, 1 / 1.08));
		add(startX + (bw + gap) + bw / 2 + 1, row1, bw / 2 - 1, bh, "Lyrics +", b -> scale(Target.LYRICS, 1.08));
		lyricsBgBtn = add(startX + 2 * (bw + gap), row1, bw, bh, label("Lyrics BG", c.lyrics.background), b -> {
			c.lyrics.background = !c.lyrics.background;
			changed();
		});
		add(startX + 3 * (bw + gap), row1, bw, bh, "Center lyrics", b -> {
			c.lyrics.x = 0.5;
			changed();
		});
		add(startX + 4 * (bw + gap), row1, bw, bh, "All settings...", b -> LyricsOverlayClient.openSettingsPage());

		cardShowBtn = add(startX, row2, bw, bh, label("Card", c.card.show), b -> {
			c.card.show = !c.card.show;
			changed();
		});
		add(startX + (bw + gap), row2, bw / 2 - 1, bh, "Card -", b -> scale(Target.CARD, 1 / 1.08));
		add(startX + (bw + gap) + bw / 2 + 1, row2, bw / 2 - 1, bh, "Card +", b -> scale(Target.CARD, 1.08));
		cardBgBtn = add(startX + 2 * (bw + gap), row2, bw, bh, label("Card BG", c.card.background), b -> {
			c.card.background = !c.card.background;
			changed();
		});
		add(startX + 3 * (bw + gap), row2, bw, bh, "Reset layout", b -> {
			Config d = new Config();
			c.lyrics.x = d.lyrics.x;
			c.lyrics.y = d.lyrics.y;
			c.lyrics.scale = d.lyrics.scale;
			c.card.x = d.card.x;
			c.card.y = d.card.y;
			c.card.scale = d.card.scale;
			changed();
		});
		add(startX + 4 * (bw + gap), row2, bw, bh, "Done", b -> this.onClose());
	}

	private Button add(int x, int y, int w, int h, String text, Button.OnPress onPress) {
		Button b = Button.builder(Component.literal(text), onPress).bounds(x, y, w, h).build();
		buttons.add(b);
		return this.addRenderableWidget(b);
	}

	private static String label(String name, boolean on) {
		return name + ": " + (on ? "ON" : "OFF");
	}

	private void changed() {
		Config c = LyricsOverlayClient.config;
		if (lyricsShowBtn != null) lyricsShowBtn.setMessage(Component.literal(label("Lyrics", c.lyrics.show)));
		if (cardShowBtn != null) cardShowBtn.setMessage(Component.literal(label("Card", c.card.show)));
		if (lyricsBgBtn != null) lyricsBgBtn.setMessage(Component.literal(label("Lyrics BG", c.lyrics.background)));
		if (cardBgBtn != null) cardBgBtn.setMessage(Component.literal(label("Card BG", c.card.background)));
		LyricsOverlayClient.markConfigChanged(false);
	}

	private void scale(Target t, double f) {
		Config c = LyricsOverlayClient.config;
		if (t == Target.LYRICS) c.lyrics.scale = clamp(c.lyrics.scale * f, 0.25, 5);
		else c.card.scale = clamp(c.card.scale * f, 0.25, 5);
		changed();
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// no blur / dirt: you need to see the game while placing things
		g.fill(0, 0, this.width, this.height, 0x30000000);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		handleDrag(mouseX, mouseY);
		super.render(g, mouseX, mouseY, delta);

		double[] lb = LyricsOverlayClient.overlay.lyricsBox;
		double[] cb = LyricsOverlayClient.overlay.cardBox;
		Target hover = hit(mouseX, mouseY);

		if (snappedX) g.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x80FF7A30);
		if (snappedY) g.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x80FF7A30);

		if (lb != null) box(g, lb, drag == Target.LYRICS || hover == Target.LYRICS, "Lyrics");
		if (cb != null) box(g, cb, drag == Target.CARD || hover == Target.CARD, "Now playing");

		g.drawCenteredString(this.font, "Drag to move  •  Scroll on an element to resize  •  Esc / Done to save",
				this.width / 2, 8, 0xFFFFFFFF);
		g.drawCenteredString(this.font, "Fonts, colours, glow, animations: \"All settings...\" (opens in your browser)",
				this.width / 2, 20, 0xFFB0B0B0);
	}

	private void box(GuiGraphics g, double[] b, boolean hot, String name) {
		int x1 = (int) Math.floor(b[0]) - 2, y1 = (int) Math.floor(b[1]) - 2;
		int x2 = (int) Math.ceil(b[0] + b[2]) + 2, y2 = (int) Math.ceil(b[1] + b[3]) + 2;
		int col = hot ? 0xFFFF7A30 : 0xAAFFFFFF;
		g.fill(x1, y1, x2, y1 + 1, col);
		g.fill(x1, y2 - 1, x2, y2, col);
		g.fill(x1, y1, x1 + 1, y2, col);
		g.fill(x2 - 1, y1, x2, y2, col);
		if (hot) g.fill(x1, y1, x2, y2, 0x18FF7A30);
		int ty = y1 - 10 < 30 ? y2 + 2 : y1 - 10;
		g.drawString(this.font, name, x1, ty, col, true);
	}

	private Target hit(double mx, double my) {
		double[] cb = LyricsOverlayClient.overlay.cardBox;
		double[] lb = LyricsOverlayClient.overlay.lyricsBox;
		if (in(cb, mx, my)) return Target.CARD;
		if (in(lb, mx, my)) return Target.LYRICS;
		return Target.NONE;
	}

	private static boolean in(double[] b, double mx, double my) {
		return b != null && mx >= b[0] - 3 && mx <= b[0] + b[2] + 3 && my >= b[1] - 3 && my <= b[1] + b[3] + 3;
	}

	private boolean overButton(double mx, double my) {
		for (Button b : buttons) if (b.isMouseOver(mx, my)) return true;
		return false;
	}

	/** Polls the mouse button each frame (keeps this independent of Minecraft's input-event classes). */
	private void handleDrag(int mx, int my) {
		boolean down = this.minecraft != null && this.minecraft.mouseHandler.isLeftPressed();
		Config c = LyricsOverlayClient.config;
		if (down && !wasDown && !overButton(mx, my)) {
			drag = hit(mx, my);
			if (drag == Target.LYRICS) {
				grabDX = mx - c.lyrics.x * this.width;
				grabDY = my - c.lyrics.y * this.height;
			} else if (drag == Target.CARD) {
				grabDX = mx - c.card.x * this.width;
				grabDY = my - c.card.y * this.height;
			}
		}
		snappedX = snappedY = false;
		if (down && drag != Target.NONE) {
			double nx = (mx - grabDX) / this.width, ny = (my - grabDY) / this.height;
			if (drag == Target.LYRICS) {
				if (Math.abs(nx - 0.5) < 0.012) {
					nx = 0.5;
					snappedX = true;
				}
				if (Math.abs(ny - 0.5) < 0.012) {
					ny = 0.5;
					snappedY = true;
				}
				c.lyrics.x = clamp(nx, 0, 1);
				c.lyrics.y = clamp(ny, 0, 1);
			} else {
				c.card.x = clamp(nx, 0, 0.98);
				c.card.y = clamp(ny, 0, 0.98);
			}
		}
		if (!down && wasDown && drag != Target.NONE) {
			drag = Target.NONE;
			LyricsOverlayClient.markConfigChanged(false);
		}
		wasDown = down;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		Target t = hit(mouseX, mouseY);
		if (t != Target.NONE && scrollY != 0) {
			scale(t, scrollY > 0 ? 1.06 : 1 / 1.06);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		open = false;
		LyricsOverlayClient.markConfigChanged(true);
		super.removed();
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}

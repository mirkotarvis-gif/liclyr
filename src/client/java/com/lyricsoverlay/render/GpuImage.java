package com.lyricsoverlay.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

/** A BufferedImage uploaded as a Minecraft texture. */
public final class GpuImage {
	private static final AtomicInteger COUNTER = new AtomicInteger();

	public final Identifier id;
	public final int width, height;
	private boolean released;

	private GpuImage(Identifier id, int w, int h) {
		this.id = id;
		this.width = w;
		this.height = h;
	}

	/** Converts on any thread (CPU memory only). */
	public static NativeImage toNative(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		NativeImage ni = new NativeImage(w, h, false);
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			img.getRGB(0, y, w, 1, row, 0, w);
			for (int x = 0; x < w; x++) ni.setPixel(x, y, row[x]);
		}
		return ni;
	}

	/** Must be called on the render thread. Takes ownership of {@code ni}. */
	public static GpuImage upload(NativeImage ni) {
		int n = COUNTER.incrementAndGet();
		Identifier id = Identifier.fromNamespaceAndPath("lyricsoverlay", "dynamic/" + n);
		DynamicTexture tex = new DynamicTexture(() -> "lyricsoverlay:" + n, ni);
		Minecraft.getInstance().getTextureManager().register(id, tex);
		return new GpuImage(id, ni.getWidth(), ni.getHeight());
	}

	/** Must be called on the render thread. */
	public void release() {
		if (released) return;
		released = true;
		Minecraft.getInstance().getTextureManager().release(id);
	}
}

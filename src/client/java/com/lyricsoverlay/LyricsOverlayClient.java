package com.lyricsoverlay;

import com.lyricsoverlay.gui.EditScreen;
import com.lyricsoverlay.lyrics.LyricsService;
import com.lyricsoverlay.media.MediaWatcher;
import com.lyricsoverlay.media.NowPlaying;
import com.lyricsoverlay.render.FontManager;
import com.lyricsoverlay.render.Overlay;
import com.lyricsoverlay.web.SettingsServer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class LyricsOverlayClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("LyricsOverlay");

	public static volatile Config config = new Config();
	private static final AtomicInteger CONFIG_VERSION = new AtomicInteger();

	public static MediaWatcher media;
	public static LyricsService lyrics;
	public static Overlay overlay;
	public static SettingsServer web;

	private static volatile boolean openEditor;

	@Override
	public void onInitializeClient() {
		config = Config.load();
		FontManager.init(Config.dir().resolve("fonts"));

		media = new MediaWatcher();
		lyrics = new LyricsService();
		overlay = new Overlay();
		web = new SettingsServer();

		media.start();
		web.start(config.behavior.settingsPort);

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lyricsoverlay", "overlay"), overlay::render);

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			NowPlaying np = media.current();
			if (np != null) lyrics.update(np);
			if (openEditor) {
				openEditor = false;
				if (mc.level != null) mc.setScreen(new EditScreen());
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> shutdown());
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				media.stop();
			} catch (Throwable ignored) {
			}
		}, "LyricsOverlay-Shutdown"));

		registerCommands();
		LOG.info("Lyrics Overlay ready. Type /lyrics in game.");
	}

	private static void shutdown() {
		media.stop();
		lyrics.shutdown();
		web.stop();
		overlay.shutdown();
		config.save();
	}

	// ------------------------------------------------------------------ commands

	private static void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("lyrics")
						.executes(ctx -> help(ctx.getSource()))
						.then(ClientCommandManager.literal("edit").executes(ctx -> {
							requestEditor();
							return 1;
						}))
						.then(ClientCommandManager.literal("settings").executes(ctx -> {
							openSettingsPage();
							msg(ctx.getSource(), "Opening settings in your browser: " + web.url());
							return 1;
						}))
						.then(ClientCommandManager.literal("toggle").executes(ctx -> {
							config.enabled = !config.enabled;
							markConfigChanged(true);
							msg(ctx.getSource(), "Lyrics Overlay " + (config.enabled ? "ON" : "OFF"));
							return 1;
						}))
						.then(ClientCommandManager.literal("reload").executes(ctx -> {
							lyrics.reload(media.current());
							msg(ctx.getSource(), "Searching lyrics again...");
							return 1;
						}))
						.then(ClientCommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
						.then(ClientCommandManager.literal("offset")
								.executes(ctx -> {
									msg(ctx.getSource(), "Lyric offset: " + config.behavior.offsetMs
											+ " ms (positive = earlier). Use /lyrics offset <ms>");
									return 1;
								})
								.then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(-10000, 10000))
										.executes(ctx -> {
											config.behavior.offsetMs = IntegerArgumentType.getInteger(ctx, "ms");
											markConfigChanged(true);
											msg(ctx.getSource(), "Lyric offset set to " + config.behavior.offsetMs + " ms");
											return 1;
										})))
		));
	}

	private static int help(FabricClientCommandSource src) {
		msg(src, "§6Lyrics Overlay§r commands:");
		msg(src, " §e/lyrics edit§r - drag & resize the lyrics and the card");
		msg(src, " §e/lyrics settings§r - fonts, colours, glow, animations (browser)");
		msg(src, " §e/lyrics toggle§r - turn the overlay on/off");
		msg(src, " §e/lyrics offset <ms>§r - lyrics too late? try 300. too early? try -300");
		msg(src, " §e/lyrics reload§r - search lyrics again");
		msg(src, " §e/lyrics status§r - what the mod currently sees");
		return 1;
	}

	private static int status(FabricClientCommandSource src) {
		NowPlaying np = media.current();
		LyricsService.Result r = lyrics.current();
		msg(src, "Media: " + media.status());
		if (np != null) {
			msg(src, "Song: " + np.artist() + " - " + np.title() + " (" + np.sourceName() + ")");
			msg(src, "Lyrics: " + describe(r.status()) + (r.matched().isEmpty() ? "" : " [" + r.matched() + "]"));
		}
		msg(src, "Settings page: " + web.url());
		return 1;
	}

	private static String describe(LyricsService.Status s) {
		return switch (s) {
			case NONE -> "-";
			case LOADING -> "searching...";
			case SYNCED -> "found (synced)";
			case PLAIN_ONLY -> "only unsynced lyrics exist for this song, can't time them";
			case INSTRUMENTAL -> "instrumental";
			case NOT_FOUND -> "not found on lrclib.net";
			case ERROR -> "network error (check your internet / firewall)";
		};
	}

	private static void msg(FabricClientCommandSource src, String text) {
		src.sendFeedback(Component.literal(text));
	}

	// ------------------------------------------------------------------ helpers used by GUI / web

	public static int configVersion() {
		return CONFIG_VERSION.get();
	}

	public static void markConfigChanged(boolean save) {
		CONFIG_VERSION.incrementAndGet();
		if (overlay != null) overlay.invalidate();
		if (save) config.save();
	}

	public static void applyConfig(Config c) {
		config = c;
		markConfigChanged(true);
	}

	public static void requestEditor() {
		openEditor = true;
	}

	public static void openSettingsPage() {
		String url = web.url();
		if (url == null) return;
		openExternal(url);
	}

	public static void openFolder(Path p) {
		openExternal(p.toAbsolutePath().toString());
	}

	private static void openExternal(String target) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase();
			ProcessBuilder pb;
			if (os.contains("win")) {
				pb = target.startsWith("http")
						? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target)
						: new ProcessBuilder("explorer.exe", target);
			} else if (os.contains("mac")) {
				pb = new ProcessBuilder("open", target);
			} else {
				pb = new ProcessBuilder("xdg-open", target);
			}
			pb.start();
		} catch (Exception e) {
			LOG.warn("Could not open {}", target, e);
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("Open this in your browser: " + target)));
			}
		}
	}
}

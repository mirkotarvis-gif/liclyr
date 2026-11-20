# Lyrics Overlay (Fabric 1.21.11, client-side)

Shows the lyrics of whatever song is playing on your PC (Spotify app, YouTube or anything in a browser, Apple Music) while you play.

- **One line at a time.** When the next line starts, the old one animates out and the new one animates in, so lines never pile up.
- **A "now playing" card** with the cover, title, artist and a progress bar. It sits in the top-left by default.
- **Everything is customizable:** font (any Windows font, the Minecraft font, or your own .ttf), size, colours, gradient, glow, outline, shadow, background on or off, animation style, position and size.

Lyrics come from [lrclib.net](https://lrclib.net), which is free and needs no account. The current song is read from Windows' media controls, the same info as the media popup that appears when you press a volume key.

## Install

1. Double-click **`BUILD-AND-INSTALL.bat`**.
   - If you don't have Java 21 it offers to install it. Say **Y**, then run the .bat again.
   - The first build downloads Minecraft and Fabric, which takes 2–5 minutes.
   - At the end it offers to copy the mod into `.minecraft\mods`.
2. Make sure **Fabric API** for 1.21.11 is also in your `mods` folder.
3. Start Minecraft 1.21.11 with Fabric and play a song.

## Use

| Command | What it does |
|---|---|
| `/lyrics edit` | Drag the lyrics or the card around, scroll the mouse wheel on them to resize, and turn the backgrounds on or off |
| `/lyrics settings` | Opens the full settings page in your browser: fonts, colours, glow, outline, animations, presets. Changes show up in game instantly. |
| `/lyrics offset 300` | Use this if lyrics come in late (negative numbers if they come in early) |
| `/lyrics toggle` | Turns the overlay on or off |
| `/lyrics reload` | Searches for the lyrics again |
| `/lyrics status` | Shows what the mod detects, which helps if lyrics don't appear |

**Custom fonts:** click "Open fonts folder" on the settings page, drop `.ttf` files in, then reload the page.

## Notes

- Song detection is **Windows 10/11 only**.
- YouTube titles get cleaned up automatically, but odd video titles can still miss. Songs that only have unsynced lyrics on LRCLIB can't be timed, so nothing shows for those (`/lyrics status` tells you).
- Settings are saved in `.minecraft\config\lyricsoverlay\config.json`.
- The settings page only listens on your own computer (127.0.0.1).

## Building without the .bat

`gradlew build` with JDK 21 installed. The jar ends up in `build/libs/`. You can also push this folder to GitHub, and the included workflow builds the jar for you (look under Actions → artifacts).

package com.lyricsoverlay.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Time-synced lyrics (parsed from LRC). */
public final class Lyrics {
	public record Line(long timeMs, String text) {
	}

	public static final Lyrics EMPTY = new Lyrics(List.of());

	private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
	// word-level timing tags used by "enhanced" LRC: <00:12.34>
	private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

	private final List<Line> lines;

	private Lyrics(List<Line> lines) {
		this.lines = lines;
	}

	public List<Line> lines() {
		return lines;
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	public static Lyrics parseLrc(String lrc) {
		if (lrc == null || lrc.isBlank()) return EMPTY;
		long offset = 0;
		Matcher om = OFFSET.matcher(lrc);
		if (om.find()) {
			try {
				offset = Long.parseLong(om.group(1));
			} catch (NumberFormatException ignored) {
			}
		}
		List<Line> out = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher m = TIME.matcher(raw);
			List<Long> times = new ArrayList<>();
			int end = 0;
			while (m.find() && m.start() == end) {
				long min = Long.parseLong(m.group(1));
				long sec = Long.parseLong(m.group(2));
				long frac = 0;
				if (m.group(3) != null) {
					String f = m.group(3);
					frac = switch (f.length()) {
						case 1 -> Long.parseLong(f) * 100;
						case 2 -> Long.parseLong(f) * 10;
						default -> Long.parseLong(f.substring(0, 3));
					};
				}
				times.add(min * 60000 + sec * 1000 + frac);
				end = m.end();
			}
			if (times.isEmpty()) continue;
			String text = WORD_TAG.matcher(raw.substring(end)).replaceAll("").trim();
			for (long t : times) out.add(new Line(Math.max(0, t - offset), text));
		}
		out.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
		return new Lyrics(Collections.unmodifiableList(out));
	}

	/** Index of the line active at {@code posMs}, or -1 before the first line. */
	public int indexAt(long posMs) {
		int lo = 0, hi = lines.size() - 1, ans = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (lines.get(mid).timeMs() <= posMs) {
				ans = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return ans;
	}
}

package com.lyricsoverlay.lyrics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns messy titles (especially YouTube ones like
 * "Artist - Song (Official Music Video) [HD]") into clean artist/title guesses.
 */
public final class TitleCleaner {
	public record Query(String artist, String title) {
	}

	private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[【「『{][^)\\]】」』}]*[)\\]】」』}]");
	private static final Pattern JUNK_WORDS = Pattern.compile(
			"(?i)\\b(official|video|audio|lyrics?|lyric video|visuali[sz]er|music video|mv|m/v|hd|hq|4k|1080p|720p|"
					+ "remaster(ed)?|explicit|clean|clip|videoclip|full song|sped up|slowed|reverb|nightcore|"
					+ "текст|кліп|клип|офіційн|официальн|премьера|прем'єра)\\b");
	private static final Pattern FEAT = Pattern.compile("(?i)\\s*[(\\[]?\\s*(feat\\.?|ft\\.?|featuring|prod\\.?( by)?)\\s+[^)\\]]*[)\\]]?");
	private static final Pattern DASH = Pattern.compile("\\s+[-–—|]\\s+");

	private TitleCleaner() {
	}

	public static List<Query> queries(String artist, String title, String album, boolean fromBrowser) {
		Set<Query> out = new LinkedHashSet<>();
		String a = cleanArtist(artist);
		String t = title == null ? "" : title.trim();

		// Pipe suffixes: "Song | Album Name | Label"
		int pipe = t.indexOf(" | ");
		if (pipe > 0) t = t.substring(0, pipe);

		String noJunk = stripJunkBrackets(t);
		String noFeat = FEAT.matcher(noJunk).replaceAll("").trim();

		// "Artist - Title" inside the title (YouTube style)
		String[] parts = DASH.split(noJunk, 2);
		if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
			String left = parts[0].trim(), right = parts[1].trim();
			String rightNoFeat = FEAT.matcher(right).replaceAll("").trim();
			out.add(new Query(left, rightNoFeat));
			out.add(new Query(left, right));
			if (!a.isEmpty()) out.add(new Query(a, rightNoFeat));
			// some uploads use "Title - Artist"
			out.add(new Query(right, left));
		}

		if (!a.isEmpty()) {
			out.add(new Query(a, noJunk));
			if (!noFeat.equals(noJunk)) out.add(new Query(a, noFeat));
			String first = firstArtist(a);
			if (!first.equals(a)) out.add(new Query(first, noFeat));
		}
		// last resort: title only
		out.add(new Query("", noFeat));

		List<Query> list = new ArrayList<>();
		for (Query q : out) {
			if (!q.title().isBlank()) list.add(new Query(q.artist().trim(), q.title().trim()));
		}
		return list;
	}

	static String stripJunkBrackets(String s) {
		StringBuilder sb = new StringBuilder();
		var m = BRACKETS.matcher(s);
		int last = 0;
		while (m.find()) {
			String inside = m.group();
			sb.append(s, last, m.start());
			if (!JUNK_WORDS.matcher(inside).find()) sb.append(inside); // keep things like "(Remix)" or "(Acoustic)"
			last = m.end();
		}
		sb.append(s.substring(last));
		String r = sb.toString().replaceAll("\\s{2,}", " ").trim();
		// trailing junk without brackets: "Song Official Video"
		r = r.replaceAll("(?i)\\s+(official\\s+)?(music\\s+)?(lyric\\s+)?(video|audio)$", "").trim();
		return r.isEmpty() ? s.trim() : r;
	}

	static String cleanArtist(String artist) {
		if (artist == null) return "";
		String a = artist.trim();
		a = a.replaceAll("(?i)\\s*-\\s*topic$", "");
		a = a.replaceAll("(?i)vevo$", "");
		a = a.replaceAll("(?i)\\s*(official|музыка|music)\\s*$", "");
		return a.trim();
	}

	static String firstArtist(String a) {
		String[] split = a.split("(?i)\\s*(,|&|\\bx\\b|\\band\\b|\\bfeat\\.?|\\bft\\.?|;|/)\\s*");
		return split.length > 0 && !split[0].isBlank() ? split[0].trim() : a;
	}

	/** Loose similarity 0..1 used to rank search results. */
	public static double similarity(String x, String y) {
		String a = norm(x), b = norm(y);
		if (a.isEmpty() || b.isEmpty()) return 0;
		if (a.equals(b)) return 1;
		if (a.contains(b) || b.contains(a)) return 0.85;
		Set<String> wa = new java.util.HashSet<>(java.util.Arrays.asList(a.split(" ")));
		Set<String> wb = new java.util.HashSet<>(java.util.Arrays.asList(b.split(" ")));
		long common = wa.stream().filter(wb::contains).count();
		return (double) common / Math.max(wa.size(), wb.size());
	}

	private static String norm(String s) {
		if (s == null) return "";
		return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
	}
}

/*
 * Persistent Bank — RuneLite plugin
 *
 * Append-only per-account wealth history. Each time the plugin writes a
 * snapshot it appends one point (epochMs,valueGp) to
 * <snapshotDir>/history/<accountHash>.csv. Crucially, the value is whatever
 * the account was worth AT THAT MOMENT, priced with the GE prices that were
 * live then — so the series reflects real historical wealth, including price
 * movement, not a re-pricing of today's holdings against old dates.
 *
 * CSV (not JSON) on purpose: the file is hot-path append-only and can grow to
 * thousands of lines; two longs per line keeps it tiny and trivially parsed.
 */
package com.persistentbank;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class WealthHistory
{
	private WealthHistory() { }

	/** One observation: total value {@code v} (gp) at wall-clock {@code t} (epoch ms). */
	static final class Point
	{
		final long t;
		final long v;

		Point(long t, long v)
		{
			this.t = t;
			this.v = v;
		}
	}

	private static Path historyDir(Path snapshotDir)
	{
		return snapshotDir.resolve("history");
	}

	/** Append one point for an account. Best-effort; never throws. */
	static void append(Path snapshotDir, long accountHash, long t, long v)
	{
		if (snapshotDir == null)
		{
			return;
		}
		try
		{
			Path d = historyDir(snapshotDir);
			Files.createDirectories(d);
			Path f = d.resolve(accountHash + ".csv");
			Files.writeString(f, t + "," + v + "\n",
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (Exception ignored)
		{
			// History is non-critical; losing a point must never disrupt a write.
		}
	}

	/** Load all points for one account, ascending by time. Never throws;
	 *  returns empty on any error and skips malformed lines. */
	static List<Point> load(Path snapshotDir, long accountHash)
	{
		List<Point> out = new ArrayList<>();
		if (snapshotDir == null)
		{
			return out;
		}
		Path f = historyDir(snapshotDir).resolve(accountHash + ".csv");
		if (!Files.isRegularFile(f))
		{
			return out;
		}
		try
		{
			for (String line : Files.readAllLines(f))
			{
				int comma = line.indexOf(',');
				if (comma <= 0)
				{
					continue;
				}
				try
				{
					long t = Long.parseLong(line.substring(0, comma).trim());
					long v = Long.parseLong(line.substring(comma + 1).trim());
					if (v > 0)
					{
						out.add(new Point(t, v));
					}
				}
				catch (NumberFormatException ignored)
				{
					// skip junk line
				}
			}
		}
		catch (IOException ignored)
		{
			// unreadable file — return whatever parsed
		}
		out.sort((a, b) -> Long.compare(a.t, b.t));
		return out;
	}

	/** Value of one account at time {@code t}: the last observation at or
	 *  before {@code t} (forward-fill). Returns 0 if the account had no data
	 *  yet at that time. {@code pts} must be ascending by time. */
	private static long valueAt(List<Point> pts, long t)
	{
		int lo = 0, hi = pts.size() - 1, ans = -1;
		while (lo <= hi)
		{
			int mid = (lo + hi) >>> 1;
			if (pts.get(mid).t <= t)
			{
				ans = mid;
				lo = mid + 1;
			}
			else
			{
				hi = mid - 1;
			}
		}
		// Backward-fill: before an account's first record, assume its earliest
		// known value (wealth doesn't appear from nothing) so a combined total
		// never ramps up from zero as accounts are captured at different times.
		return pts.get(ans < 0 ? 0 : ans).v;
	}

	/** Build the summed series for {@code selected} accounts across
	 *  [{@code startMs}, {@code nowMs}]. Each account is forward-filled so the
	 *  combined total is correct even though accounts update at different
	 *  times. The line begins at {@code startMs} when there is earlier data to
	 *  carry forward, otherwise at the first real point inside the window.
	 *  Always terminates at {@code nowMs} with the current sum. */
	static List<Point> buildCombined(java.util.Map<Long, List<Point>> histories,
									 Set<Long> selected, long startMs, long nowMs)
	{
		TreeSet<Long> inWindow = new TreeSet<>();
		boolean anyBefore = false;
		for (Long h : selected)
		{
			List<Point> pts = histories.get(h);
			if (pts == null)
			{
				continue;
			}
			for (Point p : pts)
			{
				if (p.t < startMs)
				{
					anyBefore = true;
				}
				else if (p.t <= nowMs)
				{
					inWindow.add(p.t);
				}
			}
		}

		List<Point> out = new ArrayList<>();
		if (inWindow.isEmpty() && !anyBefore)
		{
			return out; // no data in or before the window
		}

		long effStart = anyBefore ? startMs : inWindow.first();

		TreeSet<Long> timeline = new TreeSet<>(inWindow);
		timeline.add(effStart);
		timeline.add(nowMs);

		for (Long t : timeline)
		{
			if (t < effStart || t > nowMs)
			{
				continue;
			}
			long sum = 0L;
			for (Long h : selected)
			{
				List<Point> pts = histories.get(h);
				if (pts != null && !pts.isEmpty())
				{
					sum += valueAt(pts, t);
				}
			}
			out.add(new Point(t, sum));
		}
		return out;
	}

	/** Reduce a series to at most {@code maxPoints} by uniform index bucketing,
	 *  always keeping the first and last point. Keeps long windows (1Y / All)
	 *  cheap to draw without distorting the shape. */
	/** Delete all saved history for every account. The live snapshots (current
	 *  totals) are untouched; only the chart's time-series is wiped. Best-effort;
	 *  never throws. */
	static void clear(Path snapshotDir)
	{
		if (snapshotDir == null)
		{
			return;
		}
		Path d = historyDir(snapshotDir);
		if (!Files.isDirectory(d))
		{
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(d, "*.csv"))
		{
			for (Path p : stream)
			{
				try
				{
					Files.deleteIfExists(p);
				}
				catch (Exception ignored)
				{
					// skip a file we can't remove
				}
			}
		}
		catch (Exception ignored)
		{
			// directory-level failure — nothing more to do
		}
	}

	/** Drop single downward spikes that immediately recover — a point well below
	 *  both of its neighbours. On a wealth line that's a capture artifact (a write
	 *  that briefly missed a section), never a real change; a genuine drop keeps a
	 *  low neighbour and is preserved. */
	static List<Point> filterSpikes(List<Point> in)
	{
		int n = in.size();
		if (n < 3)
		{
			return in;
		}
		List<Point> out = new ArrayList<>(n);
		out.add(in.get(0));
		for (int i = 1; i < n - 1; i++)
		{
			long cur = in.get(i).v;
			long lowNeighbour = Math.min(in.get(i - 1).v, in.get(i + 1).v);
			if (cur < lowNeighbour * 85L / 100L)
			{
				continue; // dip-and-recover artifact — skip
			}
			out.add(in.get(i));
		}
		out.add(in.get(n - 1));
		return out;
	}

	static List<Point> downsample(List<Point> in, int maxPoints)
	{
		int n = in.size();
		if (maxPoints < 2 || n <= maxPoints)
		{
			return in;
		}
		List<Point> out = new ArrayList<>(maxPoints);
		out.add(in.get(0));
		double step = (double) (n - 1) / (maxPoints - 1);
		for (int i = 1; i < maxPoints - 1; i++)
		{
			out.add(in.get((int) Math.round(i * step)));
		}
		out.add(in.get(n - 1));
		return out;
	}
}

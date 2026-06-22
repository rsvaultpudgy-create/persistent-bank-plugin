/*
 * Persistent Bank — RuneLite plugin
 *
 * A compact wealth-over-time chart drawn in the style of a TradingView "mini"
 * preview: a single smoothed area line, a gradient fill fading into the
 * background, minimal time labels along the bottom, and a hover crosshair that
 * reads out the value and date under the cursor. The line is green when the
 * value rose across the visible window and red when it fell.
 *
 * The component is purely a renderer: it is handed a prepared, summed,
 * downsampled series plus the window bounds and draws it. All series assembly
 * lives in WealthHistory; all selection/period logic lives in WealthPanel.
 */
package com.persistentbank;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.GeneralPath;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.swing.JPanel;

import net.runelite.client.ui.FontManager;

class WealthChart extends JPanel
{
	private static final Color UP = new Color(0x26, 0xA6, 0x9A);   // TradingView teal-green
	private static final Color DOWN = new Color(0xEF, 0x53, 0x50); // red
	private static final Color MUTED = new Color(0x80, 0x80, 0x80);
	private static final long MIN_SPAN = 50_000_000L;
	private static final long[] SPAN_BANDS = {
		50_000_000L, 100_000_000L, 200_000_000L, 500_000_000L,
		1_000_000_000L, 2_000_000_000L, 5_000_000_000L, 10_000_000_000L,
		20_000_000_000L, 50_000_000_000L, 100_000_000_000L
	};

	private List<WealthHistory.Point> series = Collections.emptyList();
	private long startMs;
	private long endMs;
	private boolean up = true;
	private int hoverIndex = -1;
	private String emptyMessage = "Collecting data…";

	WealthChart()
	{
		setOpaque(false);
		setPreferredSize(new Dimension(100, 168));
		MouseAdapter ma = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				updateHover(e.getX());
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				updateHover(e.getX());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (hoverIndex != -1)
				{
					hoverIndex = -1;
					repaint();
				}
			}
		};
		addMouseListener(ma);
		addMouseMotionListener(ma);
	}

	/** Hand the chart a new series and window. The series must be ascending
	 *  by time and already downsampled. */
	void setSeries(List<WealthHistory.Point> s, long startMs, long endMs)
	{
		this.series = (s == null) ? Collections.emptyList() : s;
		this.startMs = startMs;
		this.endMs = endMs;
		this.hoverIndex = -1;
		if (this.series.size() >= 2)
		{
			this.up = this.series.get(this.series.size() - 1).v >= this.series.get(0).v;
		}
		repaint();
	}

	void setEmptyMessage(String m)
	{
		this.emptyMessage = m;
		repaint();
	}

	private Insets plotInsets()
	{
		return new Insets(8, 6, 18, 6);
	}

	private void updateHover(int mx)
	{
		if (series.size() < 2)
		{
			return;
		}
		Insets in = plotInsets();
		int pw = getWidth() - in.left - in.right;
		if (pw <= 0)
		{
			return;
		}
		long span = Math.max(1L, endMs - startMs);
		int best = -1;
		double bestDx = Double.MAX_VALUE;
		for (int i = 0; i < series.size(); i++)
		{
			double px = in.left + (series.get(i).t - startMs) / (double) span * pw;
			double dx = Math.abs(px - mx);
			if (dx < bestDx)
			{
				bestDx = dx;
				best = i;
			}
		}
		if (best != hoverIndex)
		{
			hoverIndex = best;
			repaint();
		}
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		super.paintComponent(g0);
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int w = getWidth();
		int h = getHeight();
		Insets in = plotInsets();
		int px0 = in.left;
		int py0 = in.top;
		int pw = w - in.left - in.right;
		int ph = h - in.top - in.bottom;

		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();

		if (series.isEmpty() || pw <= 0 || ph <= 0)
		{
			g.setColor(MUTED);
			int tw = fm.stringWidth(emptyMessage);
			g.drawString(emptyMessage, Math.max(0, (w - tw) / 2), h / 2);
			g.dispose();
			return;
		}

		long minV = Long.MAX_VALUE;
		long maxV = Long.MIN_VALUE;
		for (WealthHistory.Point p : series)
		{
			minV = Math.min(minV, p.v);
			maxV = Math.max(maxV, p.v);
		}
		// Give a single flat value some breathing room so it sits mid-plot.
		// Y-axis: keep a minimum visible range so a few-hundred-k wiggle on a big
		// total doesn't look like a 100% swing, and only widen to a "nice" band
		// (50M, 100M, 200M, 500M, 1B, 2B, ...) when the day's range actually grows.
		long dataRange = Math.max(0L, maxV - minV);
		long vSpan = niceSpan(Math.max(MIN_SPAN, dataRange));
		double center = (minV + maxV) / 2.0;
		double lo = Math.max(0.0, center - vSpan / 2.0);
		double hi = lo + vSpan;
		long span = Math.max(1L, endMs - startMs);

		Color line = up ? UP : DOWN;

		// y-axis reference lines + value labels (drawn behind the series)
		drawYAxis(g, fm, minV, maxV, lo, hi, px0, py0, pw, ph, w);

		// time-axis labels
		g.setColor(MUTED);
		SimpleDateFormat fmt = axisFormat(span);
		int labels = Math.max(2, Math.min(5, pw / 55));
		for (int i = 0; i < labels; i++)
		{
			long t = startMs + (long) ((double) i / (labels - 1) * span);
			String txt = fmt.format(new Date(t));
			int tw = fm.stringWidth(txt);
			double x = px0 + (t - startMs) / (double) span * pw;
			int tx = (int) Math.round(x - tw / 2.0);
			tx = Math.max(0, Math.min(w - tw, tx));
			g.drawString(txt, tx, h - 5);
		}

		double[] xs = new double[series.size()];
		double[] ys = new double[series.size()];
		for (int i = 0; i < series.size(); i++)
		{
			WealthHistory.Point p = series.get(i);
			xs[i] = px0 + (p.t - startMs) / (double) span * pw;
			ys[i] = py0 + (1.0 - (p.v - lo) / (hi - lo)) * ph;
		}

		if (series.size() == 1)
		{
			// Just populated: a small green marker + stub line, not a spike.
			int cx = (int) Math.round(xs[0]);
			int cy = (int) Math.round(ys[0]);
			g.setColor(line);
			g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(cx - 7, cy, cx + 7, cy);
			g.fillOval(cx - 3, cy - 3, 6, 6);
			g.dispose();
			return;
		}

		GeneralPath path = new GeneralPath();
		for (int i = 0; i < series.size(); i++)
		{
			if (i == 0)
			{
				path.moveTo(xs[i], ys[i]);
			}
			else
			{
				path.lineTo(xs[i], ys[i]);
			}
		}

		// subtle fill so the chart reads as a line, not a filled bar
		GeneralPath fill = new GeneralPath(path);
		fill.lineTo(xs[xs.length - 1], py0 + ph);
		fill.lineTo(xs[0], py0 + ph);
		fill.closePath();
		g.setPaint(new GradientPaint(
			0, py0, new Color(line.getRed(), line.getGreen(), line.getBlue(), 38),
			0, py0 + ph, new Color(line.getRed(), line.getGreen(), line.getBlue(), 0)));
		g.fill(fill);

		g.setColor(line);
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(path);

		if (hoverIndex >= 0 && hoverIndex < series.size())
		{
			int hx = (int) Math.round(xs[hoverIndex]);
			int hy = (int) Math.round(ys[hoverIndex]);
			g.setColor(new Color(0xAA, 0xAA, 0xAA, 110));
			g.setStroke(new BasicStroke(1f));
			g.drawLine(hx, py0, hx, py0 + ph);
			g.setColor(line);
			g.fillOval(hx - 3, hy - 3, 6, 6);

			WealthHistory.Point p = series.get(hoverIndex);
			drawReadout(g, WealthPanel.formatGp(p.v),
				sdf("MMM d, HH:mm 'UTC'").format(new Date(p.t)), hx, py0, w);
		}

		g.dispose();
	}

	private void drawYAxis(Graphics2D g, FontMetrics fm, long minV, long maxV,
						   double lo, double hi, int px0, int py0, int pw, int ph, int w)
	{
		long[] vals = {(long) hi, (long) ((lo + hi) / 2.0), (long) lo};
		for (long v : vals)
		{
			int y = (int) Math.round(py0 + (1.0 - (v - lo) / (hi - lo)) * ph);
			g.setColor(new Color(0x55, 0x55, 0x55, 70));
			g.drawLine(px0, y, px0 + pw, y);

			String txt = WealthPanel.formatGp(v);
			int tw = fm.stringWidth(txt);
			int ly = y - 2;
			if (ly < py0 + fm.getAscent())
			{
				ly = py0 + fm.getAscent();
			}
			g.setColor(new Color(0x9A, 0x9A, 0x9A));
			g.drawString(txt, w - tw - 1, ly);
		}
	}

	private void drawReadout(Graphics2D g, String value, String when, int anchorX, int top, int w)
	{
		FontMetrics fm = g.getFontMetrics();
		int pad = 4;
		int boxW = Math.max(fm.stringWidth(value), fm.stringWidth(when)) + pad * 2;
		int boxH = fm.getHeight() * 2 + pad * 2;
		int x = anchorX + 8;
		if (x + boxW > w)
		{
			x = anchorX - 8 - boxW;
		}
		x = Math.max(0, x);
		int y = top + 2;
		g.setColor(new Color(0x1A, 0x1A, 0x1A, 235));
		g.fillRoundRect(x, y, boxW, boxH, 6, 6);
		g.setColor(new Color(0x55, 0x55, 0x55));
		g.drawRoundRect(x, y, boxW, boxH, 6, 6);
		g.setColor(up ? UP : DOWN);
		g.drawString(value, x + pad, y + pad + fm.getAscent());
		g.setColor(new Color(0xBB, 0xBB, 0xBB));
		g.drawString(when, x + pad, y + pad + fm.getAscent() + fm.getHeight());
	}

	private static SimpleDateFormat sdf(String pattern)
	{
		SimpleDateFormat f = new SimpleDateFormat(pattern);
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
		return f;
	}

	private static long niceSpan(long required)
	{
		for (long b : SPAN_BANDS)
		{
			if (b >= required)
			{
				return b;
			}
		}
		long s = SPAN_BANDS[SPAN_BANDS.length - 1];
		while (s < required)
		{
			s *= 2L;
		}
		return s;
	}

	private static SimpleDateFormat axisFormat(long spanMs)
	{
		long day = 86_400_000L;
		if (spanMs <= 2 * day)
		{
			return sdf("HH:mm");
		}
		if (spanMs <= 60 * day)
		{
			return sdf("MMM d");
		}
		if (spanMs <= 730 * day)
		{
			return sdf("MMM ''yy");
		}
		return sdf("yyyy");
	}
}

/*
 * Persistent Bank — RuneLite plugin
 *
 * Side-panel UI: a running wealth total across every Jagex account seen on
 * this machine, a per-account breakdown, and a wealth-over-time chart.
 *
 * Selection model: clicking "Total wealth" charts the combined value of all
 * accounts; clicking individual account rows toggles them into a summed
 * subset. A period dropdown (1D … All) sets the chart window. The chart line
 * is green when the value rose across the window and red when it fell.
 *
 * Threading: update() may be called from any thread; it loads history off the
 * caller's thread, then bounces all Swing work onto the EDT.
 */
package com.persistentbank;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ImageIcon;
import javax.swing.border.EmptyBorder;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;

class WealthPanel extends PluginPanel
{
	private static final Color VALUE_COLOR = ColorScheme.BRAND_ORANGE;
	// Wealth-tier text colours: <10m white, 10m-999.99m green, 1b-9.99b blue, 10b+ purple.
	private static final Color TIER_WHITE  = new Color(235, 235, 235);
	private static final Color TIER_GREEN  = new Color(80, 215, 95);
	private static final Color TIER_BLUE   = new Color(90, 165, 255);
	private static final Color TIER_PURPLE = new Color(185, 120, 255);
	private static final long DAY = 86_400_000L;
	private static final String[] PERIOD_LABELS = {"1D", "1W", "1M", "6M", "1Y", "All"};
	private static final long[] PERIOD_MS = {DAY, 7 * DAY, 30 * DAY, 182 * DAY, 365 * DAY, 0L};

	private final JLabel titleLabel;
	private final JLabel totalValueLabel;
	private final JLabel totalCaptionLabel;
	private final JPanel header;
	private final JPanel accountsContainer;
	private final JLabel statusLabel;
	private final WealthChart chart;
	private final JComboBox<String> periodBox;

	private final Runnable onRefresh;
	private final Runnable onOpenFolder;
	private final Path snapshotDir;

	private final Color defaultTitleColor;
	private final Set<Long> selected = new LinkedHashSet<>();
	private final Map<Long, JPanel> rowPanels = new HashMap<>();
	private Map<Long, List<WealthHistory.Point>> historyCache = new HashMap<>();
	private Map<Long, Long> liveTotals = new HashMap<>();

	WealthPanel(Runnable onRefresh, Runnable onOpenFolder, Path snapshotDir)
	{
		super();
		this.onRefresh = onRefresh;
		this.onOpenFolder = onOpenFolder;
		this.snapshotDir = snapshotDir;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout(0, 8));

		// ---- header (click = chart the combined total) ----------------------

		header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_HOVER_COLOR),
			new EmptyBorder(0, 0, 8, 0)));

		titleLabel = new JLabel("Total wealth");
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		defaultTitleColor = titleLabel.getForeground();

		totalValueLabel = new JLabel("—");
		totalValueLabel.setFont(FontManager.getRunescapeBoldFont());
		totalValueLabel.setForeground(VALUE_COLOR);
		totalValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		totalCaptionLabel = new JLabel(" ");
		totalCaptionLabel.setFont(FontManager.getRunescapeSmallFont());
		totalCaptionLabel.setForeground(Color.GRAY);

		JPanel headerTop = new JPanel(new BorderLayout());
		headerTop.setOpaque(false);
		headerTop.add(titleLabel, BorderLayout.WEST);
		headerTop.add(totalValueLabel, BorderLayout.EAST);
		header.add(headerTop, BorderLayout.NORTH);
		header.add(totalCaptionLabel, BorderLayout.SOUTH);

		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setToolTipText("Chart the combined wealth of all accounts");
		for (Component c : new Component[]{header, headerTop, titleLabel, totalValueLabel, totalCaptionLabel})
		{
			addClick(c, this::selectTotal);
		}

		// ---- top button row (support + contact) -----------------------------

		JPanel topButtons = new JPanel();
		topButtons.setLayout(new BoxLayout(topButtons, BoxLayout.X_AXIS));
		topButtons.add(Box.createHorizontalGlue());
		topButtons.add(supportButton());
		topButtons.add(Box.createRigidArea(new Dimension(4, 0)));
		topButtons.add(emailButton());

		JPanel north = new JPanel(new BorderLayout(0, 6));
		north.add(topButtons, BorderLayout.NORTH);
		north.add(header, BorderLayout.CENTER);
		add(north, BorderLayout.NORTH);

		// ---- accounts list --------------------------------------------------

		accountsContainer = new JPanel();
		accountsContainer.setLayout(new BoxLayout(accountsContainer, BoxLayout.Y_AXIS));

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.add(accountsContainer, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(wrapper);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		// ---- footer + chart -------------------------------------------------

		statusLabel = new JLabel(" ");
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(Color.GRAY);
		statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		JButton refreshButton = new JButton("Refresh");
		refreshButton.setFocusPainted(false);
		refreshButton.addActionListener(e -> onRefresh.run());
		JButton folderButton = new JButton("Folder");
		folderButton.setFocusPainted(false);
		folderButton.addActionListener(e -> onOpenFolder.run());
		buttons.add(refreshButton);
		buttons.add(folderButton);

		JPanel footer = new JPanel(new BorderLayout(0, 4));
		footer.add(statusLabel, BorderLayout.NORTH);
		footer.add(buttons, BorderLayout.SOUTH);

		chart = new WealthChart();
		JPopupMenu chartMenu = new JPopupMenu();
		JMenuItem clearItem = new JMenuItem("Clear history");
		clearItem.addActionListener(e -> confirmClearHistory());
		chartMenu.add(clearItem);
		chart.setComponentPopupMenu(chartMenu);
		periodBox = new JComboBox<>(PERIOD_LABELS);
		periodBox.setSelectedIndex(0);
		periodBox.setFocusable(false);
		periodBox.addActionListener(e -> rebuildSeries());

		JLabel chartTitle = new JLabel("History");
		chartTitle.setFont(FontManager.getRunescapeSmallFont());
		chartTitle.setForeground(Color.GRAY);

		JPanel chartHeader = new JPanel(new BorderLayout());
		chartHeader.add(chartTitle, BorderLayout.WEST);
		chartHeader.add(periodBox, BorderLayout.EAST);

		JPanel chartSection = new JPanel(new BorderLayout(0, 4));
		chartSection.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.DARK_GRAY_HOVER_COLOR),
			new EmptyBorder(8, 0, 0, 0)));
		chartSection.add(chartHeader, BorderLayout.NORTH);
		chartSection.add(chart, BorderLayout.CENTER);

		JPanel bottom = new JPanel(new BorderLayout(0, 10));
		bottom.add(footer, BorderLayout.NORTH);
		bottom.add(chartSection, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);

		applySelectionStyles();
	}

	// ---- data entry point ---------------------------------------------------

	/** Replace the panel contents with a new set of summaries. Loads each
	 *  account's history off the calling thread, then renders on the EDT. */
	void update(List<SnapshotReader.AccountSummary> summariesIn)
	{
		final List<SnapshotReader.AccountSummary> summaries = new ArrayList<>(summariesIn);
		final Map<Long, List<WealthHistory.Point>> hist = new HashMap<>();
		for (SnapshotReader.AccountSummary s : summaries)
		{
			hist.put(s.accountHash, WealthHistory.load(snapshotDir, s.accountHash));
		}
		SwingUtilities.invokeLater(() ->
		{
			this.historyCache = hist;
			doUpdate(summaries);
		});
	}

	private void doUpdate(List<SnapshotReader.AccountSummary> summaries)
	{
		long grandTotal = 0L;
		int incomplete = 0;
		liveTotals = new HashMap<>();
		for (SnapshotReader.AccountSummary s : summaries)
		{
			if (s.complete)
			{
				grandTotal += s.totalValueGp;
				liveTotals.put(s.accountHash, s.totalValueGp);
			}
			else
			{
				incomplete++;
			}
		}
		totalValueLabel.setText(grandTotal > 0L || summaries.isEmpty() ? formatGp(grandTotal) : "—");
		totalValueLabel.setForeground(colorForValue(grandTotal));
		String caption = summaries.size() == 1 ? "1 account" : summaries.size() + " accounts";
		if (incomplete > 0)
		{
			caption += " · " + incomplete + " need bank opened";
		}
		totalCaptionLabel.setText(caption);

		// Drop selections for accounts that no longer exist.
		selected.retainAll(historyCache.keySet());

		accountsContainer.removeAll();
		rowPanels.clear();

		if (summaries.isEmpty())
		{
			JLabel empty = new JLabel("No snapshots yet.");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(Color.GRAY);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			empty.setBorder(new EmptyBorder(8, 2, 0, 2));
			accountsContainer.add(empty);
		}
		else
		{
			summaries.sort(Comparator
				.comparing((SnapshotReader.AccountSummary s) -> !s.complete)
				.thenComparing(Comparator.comparingLong((SnapshotReader.AccountSummary s) -> s.totalValueGp).reversed()));
			for (SnapshotReader.AccountSummary s : summaries)
			{
				JPanel row = buildRow(s);
				rowPanels.put(s.accountHash, row);
				accountsContainer.add(row);
				accountsContainer.add(Box.createVerticalStrut(4));
			}
		}

		statusLabel.setText("Updated " + formatRelative(System.currentTimeMillis()));
		applySelectionStyles();
		accountsContainer.revalidate();
		accountsContainer.repaint();
		rebuildSeries();
	}

	// ---- selection ----------------------------------------------------------

	private void selectTotal()
	{
		selected.clear();
		applySelectionStyles();
		rebuildSeries();
	}

	private void toggleAccount(long hash)
	{
		if (!selected.remove(hash))
		{
			selected.add(hash);
		}
		applySelectionStyles();
		rebuildSeries();
	}

	private void applySelectionStyles()
	{
		boolean total = selected.isEmpty();
		titleLabel.setForeground(total ? VALUE_COLOR : defaultTitleColor);
		for (Map.Entry<Long, JPanel> e : rowPanels.entrySet())
		{
			boolean sel = selected.contains(e.getKey());
			JPanel row = e.getValue();
			row.setBackground(sel ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			row.setBorder(sel
				? BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, 3, 0, 0, VALUE_COLOR),
					new EmptyBorder(6, 5, 6, 8))
				: new EmptyBorder(6, 8, 6, 8));
		}
	}

	private void confirmClearHistory()
	{
		int res = JOptionPane.showConfirmDialog(this,
			"Clear the saved wealth history for all accounts?\n"
				+ "This only wipes the chart's data — your current totals are unaffected.",
			"Clear history", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (res == JOptionPane.YES_OPTION)
		{
			WealthHistory.clear(snapshotDir);
			historyCache = new HashMap<>();
			rebuildSeries();
		}
	}

	private void rebuildSeries()
	{
		long now = System.currentTimeMillis();
		// Epoch ms counts from 1970-01-01 00:00 UTC, so flooring to whole days
		// gives 00:00 UTC of the current day with no timezone math.
		long startOfDay = (now / DAY) * DAY;
		long endOfDay = startOfDay + DAY;

		int idx = Math.max(0, periodBox.getSelectedIndex());
		long axisStart;
		long axisEnd = endOfDay;
		switch (idx)
		{
			case 0:  axisStart = startOfDay;             break; // 1D  today 00:00-24:00 UTC
			case 1:  axisStart = startOfDay - 6 * DAY;   break; // 1W
			case 2:  axisStart = startOfDay - 29 * DAY;  break; // 1M
			case 3:  axisStart = startOfDay - 181 * DAY; break; // 6M
			case 4:  axisStart = startOfDay - 364 * DAY; break; // 1Y
			default: axisStart = 0L;                     break; // All
		}

		Set<Long> sel = selected.isEmpty() ? historyCache.keySet() : selected;
		List<WealthHistory.Point> combined =
			new ArrayList<>(WealthHistory.buildCombined(historyCache, sel, axisStart, now));

		// Anchor the most-recent point to the live displayed total (a complete
		// calculation), so the chart's current value always matches the number
		// shown above — never a partial value that slipped into the history file.
		long liveNow = 0L;
		if (selected.isEmpty())
		{
			for (Long v : liveTotals.values())
			{
				liveNow += v;
			}
		}
		else
		{
			for (Long h : selected)
			{
				Long v = liveTotals.get(h);
				if (v != null)
				{
					liveNow += v;
				}
			}
		}
		if (liveNow > 0L)
		{
			if (!combined.isEmpty() && combined.get(combined.size() - 1).t >= now - 2000)
			{
				combined.set(combined.size() - 1, new WealthHistory.Point(now, liveNow));
			}
			else
			{
				combined.add(new WealthHistory.Point(now, liveNow));
			}
		}

		combined = WealthHistory.filterSpikes(combined);
		combined = WealthHistory.downsample(combined, 400);

		if (idx >= 5)
		{
			axisStart = combined.isEmpty() ? startOfDay : (combined.get(0).t / DAY) * DAY;
		}

		chart.setEmptyMessage(historyCache.isEmpty()
			? "Collecting data…"
			: "Not enough history yet for this range");
		chart.setSeries(combined, axisStart, axisEnd);
	}

	// ---- row building -------------------------------------------------------

	private JPanel buildRow(SnapshotReader.AccountSummary s)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Click to chart this account (click again to remove)");

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.WEST;

		String name = s.displayName == null || s.displayName.isEmpty() ? "(unnamed)" : s.displayName;
		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(s.complete ? colorForValue(s.totalValueGp) : Color.WHITE);

		JLabel valueLabel = new JLabel(s.complete ? formatGp(s.totalValueGp) : "—");
		valueLabel.setFont(FontManager.getRunescapeBoldFont());
		valueLabel.setForeground(s.complete ? colorForValue(s.totalValueGp) : Color.GRAY);
		valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		JLabel timeLabel = new JLabel(s.complete ? formatRelative(s.lastUpdated) : "Incomplete — open your bank in-game");
		timeLabel.setFont(FontManager.getRunescapeSmallFont());
		timeLabel.setForeground(Color.GRAY);

		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1.0;
		c.fill = GridBagConstraints.HORIZONTAL;
		row.add(nameLabel, c);

		c.gridx = 1;
		c.gridy = 0;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.EAST;
		row.add(valueLabel, c);

		c.gridx = 0;
		c.gridy = 1;
		c.gridwidth = 2;
		c.weightx = 1.0;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;
		row.add(timeLabel, c);

		final long hash = s.accountHash;
		for (Component cc : new Component[]{row, nameLabel, valueLabel, timeLabel})
		{
			addClick(cc, () -> toggleAccount(hash));
		}
		return row;
	}

	private static void addClick(Component c, Runnable r)
	{
		c.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				r.run();
			}
		});
	}

	// ---- top buttons --------------------------------------------------------

	private JButton supportButton()
	{
		BufferedImage img = null;
		try
		{
			img = ImageUtil.loadImageResource(WealthPanel.class, "support.png");
		}
		catch (Exception ignored)
		{
			// fall back to a heart glyph below
		}
		JButton b;
		if (img != null)
		{
			b = new JButton(new ImageIcon(img.getScaledInstance(22, 22, Image.SCALE_SMOOTH)));
		}
		else
		{
			b = new JButton("♥");
			b.setForeground(new Color(0xC8, 0x5A, 0xE0));
		}
		SwingUtil.removeButtonDecorations(b);
		b.setFocusable(false);
		b.setToolTipText("Support development and projects - Borealiseternal.com");
		Dimension d = new Dimension(28, 28);
		b.setPreferredSize(d);
		b.setMinimumSize(d);
		b.setMaximumSize(d);
		b.addActionListener(e -> LinkBrowser.browse("https://borealiseternal.com/"));
		return b;
	}

	private JButton emailButton()
	{
		JButton b = new JButton(mailIcon());
		SwingUtil.removeButtonDecorations(b);
		b.setFocusable(false);
		b.setToolTipText("Contact the developer - Borealiseternal.com");
		Dimension d = new Dimension(28, 28);
		b.setPreferredSize(d);
		b.setMinimumSize(d);
		b.setMaximumSize(d);
		b.addActionListener(e -> LinkBrowser.browse("https://borealiseternal.com/contact.html"));
		return b;
	}

	private static ImageIcon mailIcon()
	{
		int s = 22;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xC8, 0xDD, 0xE9));
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int x0 = 2, y0 = 6, w = s - 4, h = 11;
		g.drawRoundRect(x0, y0, w, h, 3, 3);
		g.drawLine(x0, y0, x0 + w / 2, y0 + h / 2);
		g.drawLine(x0 + w, y0, x0 + w / 2, y0 + h / 2);
		g.dispose();
		return new ImageIcon(img);
	}

	// ---- formatting helpers -------------------------------------------------

	/** Text colour for a wealth figure by magnitude tier. */
	static Color colorForValue(long gp)
	{
		if (gp >= 10_000_000_000L) return TIER_PURPLE; // 10b+
		if (gp >= 1_000_000_000L) return TIER_BLUE;    // 1b - 9.99b
		if (gp >= 10_000_000L) return TIER_GREEN;      // 10m - 999.99m
		return TIER_WHITE;                             // up to 9,999,999
	}
	
	static String formatGp(long gp)
	{
		if (gp <= 0) return "0 gp";
		if (gp < 1_000) return gp + " gp";
		if (gp < 10_000) return String.format("%.2fK", gp / 1_000.0);
		if (gp < 1_000_000) return String.format("%.1fK", gp / 1_000.0);
		if (gp < 10_000_000) return String.format("%.2fM", gp / 1_000_000.0);
		if (gp < 1_000_000_000L) return String.format("%.1fM", gp / 1_000_000.0);
		return String.format("%.2fB", gp / 1_000_000_000.0);
	}

	static String formatRelative(long epochMs)
	{
		if (epochMs <= 0) return "—";
		long delta = System.currentTimeMillis() - epochMs;
		if (delta < 0) delta = 0;
		if (delta < 30_000) return "just now";
		if (delta < 60_000) return (delta / 1000) + "s ago";
		if (delta < 3_600_000) return (delta / 60_000) + "m ago";
		if (delta < 86_400_000) return (delta / 3_600_000) + "h ago";
		return (delta / 86_400_000) + "d ago";
	}

	static boolean openInFileBrowser(Path folder)
	{
		if (folder == null) return false;
		try
		{
			if (!Desktop.isDesktopSupported()) return false;
			Desktop.getDesktop().open(folder.toFile());
			return true;
		}
		catch (Exception e)
		{
			return false;
		}
	}
}

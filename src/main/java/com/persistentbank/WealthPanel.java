/*
 * Persistent Bank — RuneLite plugin
 *
 * Side-panel UI that shows a running wealth total across every Jagex account
 * the user has logged in with on this machine, along with a per-account
 * breakdown. Data source is the on-disk snapshot folder: the panel never
 * queries the live client, never calls ItemManager, and never iterates item
 * lists. All values are precomputed at snapshot-write time and embedded in
 * the JSON — the panel just reads and renders.
 *
 * Threading: update() is safe to call from any thread; it always bounces
 * the actual Swing work onto the EDT via SwingUtilities.invokeLater.
 */
package com.persistentbank;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class WealthPanel extends PluginPanel
{
	/** Colour used for the wealth numbers on each row and in the header.
	 *  Orange matches the RuneLite brand accent, which the sidebar already
	 *  uses elsewhere, so the panel looks native. */
	private static final Color VALUE_COLOR = ColorScheme.BRAND_ORANGE;

	private final JLabel totalValueLabel;
	private final JLabel totalCaptionLabel;
	private final JPanel accountsContainer;
	private final JLabel statusLabel;

	private final Runnable onRefresh;
	private final Runnable onOpenFolder;

	/** @param onRefresh callback fired when the user clicks Refresh — the
	 *                   plugin should re-scan disk and call update().
	 *  @param onOpenFolder callback fired when the user clicks Folder — the
	 *                      plugin should open the snapshot directory in the
	 *                      OS file browser. */
	WealthPanel(Runnable onRefresh, Runnable onOpenFolder)
	{
		super();
		this.onRefresh = onRefresh;
		this.onOpenFolder = onOpenFolder;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout(0, 8));

		// ---- header ---------------------------------------------------------

		JPanel header = new JPanel(new BorderLayout());
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_HOVER_COLOR),
			new EmptyBorder(0, 0, 8, 0)
		));

		JLabel titleLabel = new JLabel("Total wealth");
		titleLabel.setFont(FontManager.getRunescapeBoldFont());

		totalValueLabel = new JLabel("—");
		totalValueLabel.setFont(FontManager.getRunescapeBoldFont());
		totalValueLabel.setForeground(VALUE_COLOR);
		totalValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		totalCaptionLabel = new JLabel(" ");
		totalCaptionLabel.setFont(FontManager.getRunescapeSmallFont());
		totalCaptionLabel.setForeground(Color.GRAY);

		JPanel headerTop = new JPanel(new BorderLayout());
		headerTop.add(titleLabel, BorderLayout.WEST);
		headerTop.add(totalValueLabel, BorderLayout.EAST);

		header.add(headerTop, BorderLayout.NORTH);
		header.add(totalCaptionLabel, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);

		// ---- accounts list --------------------------------------------------

		accountsContainer = new JPanel();
		accountsContainer.setLayout(new BoxLayout(accountsContainer, BoxLayout.Y_AXIS));

		// Outer wrapper so the BoxLayout doesn't stretch rows vertically
		// when there are only a couple of accounts.
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.add(accountsContainer, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(wrapper);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		// ---- footer ---------------------------------------------------------

		JPanel footer = new JPanel(new BorderLayout(0, 4));

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

		footer.add(statusLabel, BorderLayout.NORTH);
		footer.add(buttons, BorderLayout.SOUTH);
		add(footer, BorderLayout.SOUTH);
	}

	/** Replace the contents of the panel with a new set of summaries. Safe
	 *  to call from any thread — all UI mutation is bounced to the EDT. */
	void update(List<SnapshotReader.AccountSummary> summariesIn)
	{
		// Defensive copy so we don't iterate a list the caller might still
		// be mutating on another thread.
		final List<SnapshotReader.AccountSummary> summaries = new ArrayList<>(summariesIn);
		SwingUtilities.invokeLater(() -> doUpdate(summaries));
	}

	private void doUpdate(List<SnapshotReader.AccountSummary> summaries)
	{
		long grandTotal = 0L;
		for (SnapshotReader.AccountSummary s : summaries)
		{
			grandTotal += s.totalValueGp;
		}
		totalValueLabel.setText(formatGp(grandTotal));
		totalCaptionLabel.setText(summaries.size() == 1
			? "1 account"
			: summaries.size() + " accounts");

		accountsContainer.removeAll();

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
			// Highest-wealth account first — matches the way loot trackers
			// and wealth trackers typically order rows.
			summaries.sort(Comparator.<SnapshotReader.AccountSummary>comparingLong(s -> s.totalValueGp).reversed());
			for (SnapshotReader.AccountSummary s : summaries)
			{
				accountsContainer.add(buildRow(s));
				accountsContainer.add(Box.createVerticalStrut(4));
			}
		}

		statusLabel.setText("Updated " + formatRelative(System.currentTimeMillis()));

		accountsContainer.revalidate();
		accountsContainer.repaint();
	}

	private static JPanel buildRow(SnapshotReader.AccountSummary s)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		// BoxLayout respects maximum size on the cross-axis; without this
		// cap each row stretches the full panel width, which is what we
		// want — rows should fill horizontally but stay row-height tall.
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.WEST;

		String name = s.displayName == null || s.displayName.isEmpty()
			? "(unnamed)"
			: s.displayName;
		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());

		JLabel valueLabel = new JLabel(formatGp(s.totalValueGp));
		valueLabel.setFont(FontManager.getRunescapeBoldFont());
		valueLabel.setForeground(VALUE_COLOR);
		valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		JLabel timeLabel = new JLabel(formatRelative(s.lastUpdated));
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

		return row;
	}

	// ---- helpers ------------------------------------------------------------

	/** Compact gp formatting: "123", "12.3K", "1.2M", "3.45B". Never returns
	 *  the raw integer for sub-1K values because that's more readable to the
	 *  eye in the list context. Negative / zero values render as "0 gp". */
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

	/** Human-friendly relative time ("just now", "5m ago", "3h ago", "2d ago").
	 *  Falls back to "—" for invalid timestamps. */
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

	/** Best-effort "open this folder in the OS file browser". Returns true
	 *  on success. Used by the Folder button's action handler in the plugin
	 *  so the UI stays decoupled from Desktop/AWT details. */
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

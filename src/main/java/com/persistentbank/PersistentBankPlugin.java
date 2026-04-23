/*
 * Persistent Bank — RuneLite plugin
 *
 * Snapshots the player's bank / inventory / equipment / seed vault / GE slots
 * to disk on a rate-limited cadence. Another application (RSVault) watches the
 * output folder and aggregates snapshots across every Jagex account the user
 * has logged into.
 *
 * Compliance posture: strictly observational. We read from Client, we write
 * JSON to disk, we do nothing that changes game behavior. No input synthesis,
 * no packet modification, no automation.
 *
 * Rate limiter: per accountHash we track lastWriteMs. An event fires, we mark
 * the account dirty. If (now - lastWriteMs) >= cooldown, we write immediately;
 * otherwise we schedule a one-shot flush for (lastWriteMs + cooldown). A
 * scheduled flush re-checks dirty and writes only if still needed.
 *
 * First-ever snapshot of an account bypasses the cooldown (so the user sees
 * the account appear in RSVault on first login rather than waiting 15 min).
 *
 * On logout: if flushOnLogout() is on and the account is dirty, flush now.
 */
package com.persistentbank;

import com.google.inject.Provides;
import com.google.gson.Gson;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Persistent Bank",
	description = "Snapshots bank/inventory/equipment/GE/seed vault to disk for RSVault.",
	tags = {"bank", "inventory", "export", "rsvault", "snapshot"}
)
public class PersistentBankPlugin extends Plugin
{
	/** Equipment slot names in the order RuneLite exposes them on the
	 *  equipment container. Index matches
	 *  net.runelite.api.kit.KitType.ordinal(). */
	private static final String[] EQUIPMENT_SLOTS = {
		"HEAD", "CAPE", "AMULET", "WEAPON", "TORSO", "SHIELD",
		"ARMS", "LEGS", "HAIR", "HANDS", "BOOTS", "JAW",
		"RING", "AMMO"
	};

	/** Grace window after first-sight during which every event flushes
	 *  immediately, bypassing the cooldown. Lets the login burst land
	 *  (GE events fire before the player has spawned, inventory/equipment
	 *  arrive moments later, display name resolves last). 30s is enough
	 *  to cover a cold login without meaningfully loosening the rate
	 *  limiter. */
	private static final long FIRST_WRITE_GRACE_MS = 30_000L;

	/** Number of game ticks to keep retrying the initial-container sync
	 *  after a LOGGED_IN transition. A tick is ~0.6s, so 100 gives us
	 *  ~60 seconds to cover the window where the client is "in" but
	 *  either accountHash or the INVENTORY/EQUIPMENT containers haven't
	 *  been populated yet. Once both are captured we stop early. */
	private static final int INITIAL_SYNC_TICKS = 100;

	@Inject private Client client;
	@Inject private Gson gson;
	@Inject private PersistentBankConfig config;
	@Inject private ScheduledExecutorService executor;
	@Inject private ItemManager itemManager;
	@Inject private ClientToolbar clientToolbar;

	private SnapshotWriter writer;
	private SnapshotReader reader;
	private Path snapshotDir;

	/** Side panel. Created on startUp, reused across refresh cycles. */
	private WealthPanel panel;

	/** Toolbar icon that opens the wealth panel. Held onto so we can
	 *  unregister in shutDown. */
	private NavigationButton navButton;

	/** One mutable state block per Jagex account. Keyed by accountHash. */
	private final Map<Long, AccountState> accounts = new HashMap<>();

	/** Countdown of game ticks remaining for the post-login initial sync.
	 *  Set to INITIAL_SYNC_TICKS when LOGGED_IN fires; decremented on every
	 *  GameTick. Reset to 0 (i.e. stop retrying) once inventory & equipment
	 *  have both been captured. */
	private int initialSyncTicksRemaining;

	// ---- Guice binding -----------------------------------------------------

	@Provides
	PersistentBankConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(PersistentBankConfig.class);
	}

	// ---- plugin lifecycle --------------------------------------------------

	@Override
	protected void startUp()
	{
		writer = new SnapshotWriter(gson);
		reader = new SnapshotReader();
		snapshotDir = resolveSnapshotDir();

		// Wealth panel. The panel itself is UI-only; the plugin owns the
		// disk-scan and open-folder callbacks because they want access to
		// snapshotDir / the executor thread.
		panel = new WealthPanel(this::refreshPanel, this::openSnapshotFolder);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		NavigationButton.NavigationButtonBuilder b = NavigationButton.builder()
			.tooltip("Persistent Bank")
			.priority(7)
			.panel(panel);
		if (icon != null)
		{
			b.icon(icon);
		}
		navButton = b.build();
		clientToolbar.addNavigation(navButton);

		// Kick off an initial disk-scan so the panel shows cached wealth
		// from previous sessions the moment the user opens it. Runs off the
		// EDT / client thread so it doesn't stall startup.
		executor.submit(this::refreshPanel);

		log.info("Persistent Bank started. Snapshot dir: {}", snapshotDir);
	}

	@Override
	protected void shutDown()
	{
		// Flush any pending dirty accounts on plugin disable.
		synchronized (accounts)
		{
			for (AccountState s : accounts.values())
			{
				cancelPending(s);
				if (s.dirty)
				{
					flush(s, "shutdown");
				}
			}
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		log.info("Persistent Bank stopped.");
	}

	private Path resolveSnapshotDir()
	{
		String override = config.snapshotDirOverride();
		if (override != null && !override.trim().isEmpty())
		{
			return Paths.get(override.trim());
		}
		return RuneLite.RUNELITE_DIR.toPath().resolve("persistent-bank");
	}

	// ---- event handlers ----------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState gs = e.getGameState();
		if (gs == GameState.LOGIN_SCREEN || gs == GameState.HOPPING)
		{
			// Flush-on-logout, if enabled. We flush across all tracked
			// accounts because HOPPING can drop account context before the
			// next GameState event fires.
			if (!config.flushOnLogout())
			{
				return;
			}
			synchronized (accounts)
			{
				for (AccountState s : accounts.values())
				{
					cancelPending(s);
					if (s.dirty)
					{
						flush(s, "logout");
					}
				}
			}
		}
		else if (gs == GameState.LOGGED_IN)
		{
			// Belt-and-braces initial sync. Live ItemContainerChanged events
			// for INVENTORY / EQUIPMENT fire on container CHANGES, which
			// means RuneLite will not emit them on login if a container is
			// unchanged-empty (e.g. a fresh account with nothing worn and
			// an empty backpack). On top of that, the LOGGED_IN transition
			// can race ahead of both accountHash resolution and the
			// inventory/equipment packets, so a single-shot read here
			// sometimes returns null. We arm a short retry window
			// (INITIAL_SYNC_TICKS game ticks) and re-attempt on each tick
			// until both sections are captured, so empty-but-real
			// containers still land on disk.
			if (config.verboseLogging())
			{
				log.info("Persistent Bank: GameState -> LOGGED_IN, arming initial sync for {} ticks (accountHash={})",
					INITIAL_SYNC_TICKS, client.getAccountHash());
			}
			initialSyncTicksRemaining = INITIAL_SYNC_TICKS;
			syncInitialContainers();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (initialSyncTicksRemaining <= 0)
		{
			return;
		}
		initialSyncTicksRemaining--;
		syncInitialContainers();
	}

	private void syncInitialContainers()
	{
		AccountState s = stateForCurrentAccount();
		if (s == null)
		{
			if (config.verboseLogging() && initialSyncTicksRemaining == 0)
			{
				log.info("Persistent Bank: initial sync exhausted without resolving account (accountHash={})",
					client.getAccountHash());
			}
			return;
		}
		boolean changed = false;

		if (config.snapshotInventory() && s.inventory == null)
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				s.inventory = toContainerItems(inv);
				s.inventoryUpdatedAt = System.currentTimeMillis();
				changed = true;
				if (config.verboseLogging())
				{
					log.info("Persistent Bank: initial sync captured INVENTORY ({} items)",
						s.inventory.size());
				}
			}
		}
		if (config.snapshotEquipment() && s.equipment == null)
		{
			ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
			if (eq != null)
			{
				s.equipment = toEquipmentItems(eq);
				s.equipmentUpdatedAt = System.currentTimeMillis();
				changed = true;
				if (config.verboseLogging())
				{
					log.info("Persistent Bank: initial sync captured EQUIPMENT ({} items)",
						s.equipment.size());
				}
			}
		}
		// Bank and seed vault only populate when the player actually opens
		// them, so we intentionally don't poll for those here — there's
		// nothing to read yet.

		if (changed)
		{
			s.touch();
			maybeFlush(s);
		}

		// Early-stop: once both sections are populated there's nothing
		// more for onGameTick to do, so kill the retry window.
		if ((!config.snapshotInventory() || s.inventory != null)
			&& (!config.snapshotEquipment() || s.equipment != null))
		{
			initialSyncTicksRemaining = 0;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		int id = e.getContainerId();
		ItemContainer c = e.getItemContainer();
		if (config.verboseLogging())
		{
			log.info("Persistent Bank: ItemContainerChanged id={} size={}",
				id, c == null || c.getItems() == null ? -1 : c.getItems().length);
		}

		if (id == InventoryID.BANK.getId())
		{
			if (!config.snapshotBank()) return;
			AccountState s = stateForCurrentAccount();
			if (s == null) return;
			s.bank = toContainerItems(c);
			s.bankUpdatedAt = System.currentTimeMillis();
			s.touch();
			maybeFlush(s);
		}
		else if (id == InventoryID.INVENTORY.getId())
		{
			if (!config.snapshotInventory()) return;
			AccountState s = stateForCurrentAccount();
			if (s == null) return;
			s.inventory = toContainerItems(c);
			s.inventoryUpdatedAt = System.currentTimeMillis();
			s.touch();
			maybeFlush(s);
		}
		else if (id == InventoryID.EQUIPMENT.getId())
		{
			if (!config.snapshotEquipment()) return;
			AccountState s = stateForCurrentAccount();
			if (s == null) return;
			s.equipment = toEquipmentItems(c);
			s.equipmentUpdatedAt = System.currentTimeMillis();
			s.touch();
			maybeFlush(s);
		}
		else if (id == InventoryID.SEED_VAULT.getId())
		{
			if (!config.snapshotSeedVault()) return;
			AccountState s = stateForCurrentAccount();
			if (s == null) return;
			s.seedVault = toContainerItems(c);
			s.seedVaultUpdatedAt = System.currentTimeMillis();
			s.touch();
			maybeFlush(s);
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		if (!config.snapshotGrandExchange()) return;
		AccountState s = stateForCurrentAccount();
		if (s == null) return;
		// Rebuild the full 8-slot view on every GE change. Cheaper than
		// tracking deltas and keeps the JSON authoritative.
		s.grandExchange = readAllGeSlots();
		s.geUpdatedAt = System.currentTimeMillis();
		s.touch();
		maybeFlush(s);
	}

	// ---- state lookup ------------------------------------------------------

	/** Returns the AccountState for the currently-logged-in account,
	 *  creating one on first sight. Returns null if no account is
	 *  logged in (accountHash < 0). */
	private AccountState stateForCurrentAccount()
	{
		long hash = client.getAccountHash();
		if (hash <= 0)
		{
			return null;
		}
		AccountState s;
		synchronized (accounts)
		{
			s = accounts.computeIfAbsent(hash, AccountState::new);
		}
		// Refresh display name every time — it's cheap and cheap to keep
		// current across name changes.
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			s.displayName = client.getLocalPlayer().getName();
		}
		return s;
	}

	// ---- container -> plain data ------------------------------------------

	private List<AccountState.ContainerItem> toContainerItems(ItemContainer c)
	{
		List<AccountState.ContainerItem> out = new ArrayList<>();
		if (c == null) return out;
		Item[] items = c.getItems();
		if (items == null) return out;
		for (Item it : items)
		{
			int id = it.getId();
			int qty = it.getQuantity();
			if (id <= 0 || qty <= 0)
			{
				continue; // empty slot
			}
			if (isPlaceholder(id))
			{
				// Bank placeholder slot — the game shows "0" on it and
				// it represents an item that *used* to be here. Skipping
				// these matches what the player actually owns.
				continue;
			}
			// RuneLite exposes noted items as a different itemId than the
			// unnoted form. We keep the raw id and set noted=false; the
			// reader can look up "is this a noted variant?" against its
			// own item mapping if needed.
			out.add(new AccountState.ContainerItem(id, qty, false));
		}
		return out;
	}

	/**
	 * True if the given itemId is a bank-placeholder variant. Placeholders
	 * have a non-(-1) {@code placeholderTemplateId} on their
	 * {@link ItemComposition}; real items return -1. This is the canonical
	 * RuneLite check for the "slot shows 0, kept as a shortcut for later"
	 * state. Wrapped in a try/catch so a transient lookup failure during
	 * the login burst never blows up the snapshot path.
	 */
	private boolean isPlaceholder(int itemId)
	{
		try
		{
			ItemComposition comp = itemManager.getItemComposition(itemId);
			return comp != null && comp.getPlaceholderTemplateId() != -1;
		}
		catch (Exception e)
		{
			return false;
		}
	}

	private List<AccountState.EquipmentItem> toEquipmentItems(ItemContainer c)
	{
		List<AccountState.EquipmentItem> out = new ArrayList<>();
		if (c == null) return out;
		Item[] items = c.getItems();
		if (items == null) return out;
		for (int i = 0; i < items.length; i++)
		{
			Item it = items[i];
			if (it == null) continue;
			int id = it.getId();
			int qty = it.getQuantity();
			if (id <= 0 || qty <= 0) continue;
			String slot = (i < EQUIPMENT_SLOTS.length) ? EQUIPMENT_SLOTS[i] : String.valueOf(i);
			out.add(new AccountState.EquipmentItem(id, qty, slot));
		}
		return out;
	}

	private List<AccountState.GeSlot> readAllGeSlots()
	{
		List<AccountState.GeSlot> out = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return out;
		}
		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer o = offers[i];
			if (o == null || o.getState() == GrandExchangeOfferState.EMPTY)
			{
				out.add(AccountState.GeSlot.empty(i));
				continue;
			}
			int itemId = o.getItemId();
			int totalQty = o.getTotalQuantity();
			int tradedQty = o.getQuantitySold();
			int pricePer = o.getPrice();
			int spent = o.getSpent();
			String state = o.getState() == null ? "UNKNOWN" : o.getState().name();
			// itemId of 0 can slip through as a transient state; treat as empty.
			if (itemId <= 0)
			{
				out.add(AccountState.GeSlot.empty(i));
				continue;
			}
			out.add(new AccountState.GeSlot(
				i, state, itemId, totalQty, tradedQty, pricePer, spent
			));
		}
		return out;
	}

	// ---- rate limiter ------------------------------------------------------

	/**
	 * Decide whether to flush now or defer. Called after any container event.
	 * Must be invoked from the client thread (RuneLite event dispatch).
	 */
	private void maybeFlush(AccountState s)
	{
		long now = System.currentTimeMillis();
		long cooldownMs = Math.max(1, config.minMinutesBetweenWrites()) * 60_000L;

		// First-ever snapshot for this account: write immediately and open
		// the grace window. Stamp firstSeenAtMs BEFORE flush() so follow-up
		// events on the same login burst recognize we're still in the window.
		if (!s.hasWrittenOnce && config.firstSnapshotImmediate())
		{
			cancelPending(s);
			s.firstSeenAtMs = now;
			flush(s, "first-sight");
			return;
		}

		// Grace window: during the first N seconds after first-sight, every
		// event flushes immediately. This catches the login-burst sequence
		// where GE fires first (before player spawn), then inventory /
		// equipment / seed vault arrive, and finally the display name
		// becomes readable. Without this, those later events get parked
		// behind the 15-minute cooldown and the first file on disk is
		// missing bank/inventory/equipment plus the display name.
		if (s.hasWrittenOnce
			&& s.firstSeenAtMs > 0
			&& (now - s.firstSeenAtMs) < FIRST_WRITE_GRACE_MS)
		{
			cancelPending(s);
			flush(s, "first-burst");
			return;
		}

		long elapsed = now - s.lastWriteMs;
		if (elapsed >= cooldownMs)
		{
			cancelPending(s);
			flush(s, "cooldown-elapsed");
			return;
		}

		// Too soon. Make sure a deferred flush is queued so changes don't
		// get lost if nothing else fires for a while.
		if (s.pendingFlush == null || s.pendingFlush.isDone())
		{
			long delay = cooldownMs - elapsed;
			final AccountState captured = s;
			s.pendingFlush = executor.schedule(
				() -> deferredFlush(captured),
				delay,
				TimeUnit.MILLISECONDS);
			if (config.verboseLogging())
			{
				log.info("Persistent Bank: deferring flush for acct {} by {}ms", s.accountHash, delay);
			}
		}
	}

	private void deferredFlush(AccountState s)
	{
		// Re-check dirty: a successful flush between the scheduling and now
		// would have cleared it.
		if (s.dirty)
		{
			flush(s, "deferred");
		}
	}

	private void cancelPending(AccountState s)
	{
		if (s.pendingFlush != null)
		{
			s.pendingFlush.cancel(false);
			s.pendingFlush = null;
		}
	}

	private void flush(AccountState s, String reason)
	{
		try
		{
			long now = System.currentTimeMillis();

			// Recompute wealth totals using current GE prices. Cheap
			// (hashmap lookups inside ItemManager) and only runs at write
			// time, which is already rate-limited to once per 15 minutes
			// per account — so even on weak PCs the cost is negligible.
			// Done here (not in SnapshotWriter) so the writer stays a pure
			// serializer and doesn't pull an ItemManager dependency.
			recomputeValues(s);

			writer.write(snapshotDir, s, now);
			s.lastWriteMs = now;
			s.dirty = false;
			s.hasWrittenOnce = true;
			if (config.verboseLogging())
			{
				log.info("Persistent Bank: wrote acct {} ({}) totalValueGp={}",
					s.accountHash, reason, s.totalValueGp);
			}

			// Let the wealth panel re-read the folder. Runs off the EDT
			// (SnapshotReader is safe on any thread; WealthPanel.update()
			// bounces the actual UI mutation to the EDT internally).
			if (panel != null)
			{
				executor.submit(this::refreshPanel);
			}
		}
		catch (Exception e)
		{
			log.warn("Persistent Bank: failed to write snapshot for {} ({}): {}",
				s.accountHash, reason, e.getMessage());
		}
	}

	// ---- wealth computation ------------------------------------------------

	/**
	 * Fill in the *ValueGp fields on {@code s} using current GE prices. Any
	 * price-lookup failure for a single item is swallowed — a missing price
	 * just contributes zero to the total, which is the right behaviour for
	 * items that aren't tradeable or aren't in the price cache yet.
	 */
	private void recomputeValues(AccountState s)
	{
		s.bankValueGp      = containerValue(s.bank);
		s.inventoryValueGp = containerValue(s.inventory);
		s.equipmentValueGp = equipmentValue(s.equipment);
		s.seedVaultValueGp = containerValue(s.seedVault);
		s.totalValueGp =
			s.bankValueGp + s.inventoryValueGp
			+ s.equipmentValueGp + s.seedVaultValueGp;
	}

	private long containerValue(List<AccountState.ContainerItem> items)
	{
		if (items == null || items.isEmpty()) return 0L;
		long total = 0L;
		for (AccountState.ContainerItem it : items)
		{
			total += priceFor(it.id) * (long) it.qty;
		}
		return total;
	}

	private long equipmentValue(List<AccountState.EquipmentItem> items)
	{
		if (items == null || items.isEmpty()) return 0L;
		long total = 0L;
		for (AccountState.EquipmentItem it : items)
		{
			total += priceFor(it.id) * (long) it.qty;
		}
		return total;
	}

	/** Safe GE-price lookup. Returns 0 for untradeables or any price the
	 *  ItemManager can't resolve right now — we'd rather undercount than
	 *  crash the write path. */
	private long priceFor(int itemId)
	{
		if (itemId <= 0) return 0L;
		try
		{
			int p = itemManager.getItemPrice(itemId);
			return Math.max(0, p);
		}
		catch (Exception e)
		{
			return 0L;
		}
	}

	// ---- panel callbacks ---------------------------------------------------

	/** Re-read the snapshot folder and push the results into the panel.
	 *  Called both on the Refresh button and after every successful write.
	 *  Safe to call from any thread; if invoked on the EDT we hop to the
	 *  executor so disk I/O never blocks the UI. */
	private void refreshPanel()
	{
		if (panel == null) return;
		Runnable job = () ->
		{
			if (reader == null || snapshotDir == null || panel == null) return;
			List<SnapshotReader.AccountSummary> summaries = reader.readAll(snapshotDir);
			if (panel != null)
			{
				panel.update(summaries);
			}
		};
		if (javax.swing.SwingUtilities.isEventDispatchThread())
		{
			executor.submit(job);
		}
		else
		{
			job.run();
		}
	}

	/** Open the snapshot folder in the OS file browser. Delegated from the
	 *  Folder button in the wealth panel. */
	private void openSnapshotFolder()
	{
		try
		{
			java.nio.file.Files.createDirectories(snapshotDir);
		}
		catch (Exception ignored)
		{
			// If we can't create it we can't open it — WealthPanel will
			// just silently no-op below.
		}
		WealthPanel.openInFileBrowser(snapshotDir);
	}
}

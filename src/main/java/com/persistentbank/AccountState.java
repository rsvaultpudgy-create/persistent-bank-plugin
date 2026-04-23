/*
 * Persistent Bank — RuneLite plugin
 *
 * In-memory mutable state for one Jagex account. The plugin keeps a map of
 * accountHash -> AccountState so different accounts on the same launcher
 * don't clobber each other during account hopping.
 *
 * This is a plain data holder; serialization and the rate-limiter live in
 * SnapshotWriter and PersistentBankPlugin respectively.
 */
package com.persistentbank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

class AccountState
{
	/** Stable Jagex account identifier. Key of the per-account map. */
	final long accountHash;

	/** In-game display name captured at last snapshot. Used as a UX hint
	 *  for the RSVault rename flow — the accountHash remains the source
	 *  of truth. */
	String displayName;

	/** Per-section last-observed data. Each is nullable: null means
	 *  "the plugin hasn't observed this container yet since the client
	 *  started". We never clobber a populated section with null. */
	List<ContainerItem> bank;
	long bankUpdatedAt;

	List<ContainerItem> inventory;
	long inventoryUpdatedAt;

	List<EquipmentItem> equipment;
	long equipmentUpdatedAt;

	List<ContainerItem> seedVault;
	long seedVaultUpdatedAt;

	List<GeSlot> grandExchange;
	long geUpdatedAt;

	/** Rate-limiter state. */
	long lastWriteMs;
	boolean dirty;

	/** Reference to a pending deferred-flush so we can cancel/coalesce. */
	ScheduledFuture<?> pendingFlush;

	/** Have we ever successfully written this account to disk? Used so we
	 *  can short-circuit the cooldown for brand-new accounts (first-snapshot
	 *  immediate). */
	boolean hasWrittenOnce;

	/** Wall-clock ms at which this account was first seen (i.e. the moment
	 *  we fired the first-sight flush). Used to open a short grace window
	 *  during which every event flushes immediately, so the login burst
	 *  (GE -> inventory -> equipment -> seed vault, plus the display name
	 *  showing up once the player spawns) all land on disk instead of
	 *  getting swallowed by the rate limiter. */
	long firstSeenAtMs;

	/** Per-section GE-value totals, recomputed at every write using the
	 *  current ItemManager price map. These are denormalized into the JSON
	 *  so the Wealth panel (and any external reader) can show a wealth
	 *  breakdown without iterating items or doing its own price lookups.
	 *  Zero means "no data yet" — consumers should treat a missing section
	 *  the same as a zero-valued section. */
	long bankValueGp;
	long inventoryValueGp;
	long equipmentValueGp;
	long seedVaultValueGp;

	/** Sum of the four section values above. Convenience for the panel's
	 *  grand-total row and for external readers that don't want to sum
	 *  the sections themselves. */
	long totalValueGp;

	AccountState(long accountHash)
	{
		this.accountHash = accountHash;
	}

	/** Mark a section as dirty. Caller decides whether to flush now or defer. */
	void touch() { this.dirty = true; }

	// ---- plain-data value types --------------------------------------------

	static class ContainerItem
	{
		final int id;
		final int qty;
		final boolean noted;

		ContainerItem(int id, int qty, boolean noted)
		{
			this.id = id;
			this.qty = qty;
			this.noted = noted;
		}
	}

	static class EquipmentItem extends ContainerItem
	{
		final String slot;

		EquipmentItem(int id, int qty, String slot)
		{
			super(id, qty, false);
			this.slot = slot;
		}
	}

	static class GeSlot
	{
		final int slot;
		final String state;
		final Integer itemId;
		final Integer qtySought;
		final Integer qtyTraded;
		final Integer pricePer;
		final Integer spent;

		GeSlot(int slot, String state, Integer itemId, Integer qtySought,
			   Integer qtyTraded, Integer pricePer, Integer spent)
		{
			this.slot = slot;
			this.state = state;
			this.itemId = itemId;
			this.qtySought = qtySought;
			this.qtyTraded = qtyTraded;
			this.pricePer = pricePer;
			this.spent = spent;
		}

		static GeSlot empty(int slot)
		{
			return new GeSlot(slot, "EMPTY", null, null, null, null, null);
		}
	}

	/** Convenience: copy a source list, keeping it detached from any live
	 *  game object we might have read from. */
	static <T> List<T> snapshotList(List<T> src)
	{
		return src == null ? null : new ArrayList<>(src);
	}
}

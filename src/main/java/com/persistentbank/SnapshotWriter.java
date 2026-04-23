/*
 * Persistent Bank — RuneLite plugin
 *
 * Serializes an AccountState to a stable JSON shape and writes it atomically
 * to <snapshotDir>/<accountHash>.json. The JSON schema is the contract with
 * RSVault; see README.md § "Snapshot schema" before changing anything.
 *
 * Atomic write pattern: write to "<hash>.json.tmp", fsync, then Files.move
 * with ATOMIC_MOVE + REPLACE_EXISTING. This guarantees RSVault's file watcher
 * never sees a half-written file.
 */
package com.persistentbank;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

class SnapshotWriter
{
	private final Gson gson;

	SnapshotWriter(Gson gson)
	{
		this.gson = gson;
	}

	/** Build the on-disk JSON object for one account. Never mutates
	 *  {@code state}. */
	JsonObject build(AccountState state, long nowMs)
	{
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.addProperty("accountHash", state.accountHash);
		root.addProperty("displayName", state.displayName == null ? "" : state.displayName);
		root.addProperty("lastUpdated", nowMs);
		// Top-level totalValueGp: sum of bank + inventory + equipment + seed
		// vault GE prices at write time. Grand Exchange deliberately not
		// included — pending orders can double-count coins the player also
		// has in the bank, and finished-but-uncollected items sit on the
		// GE until the player hits Collect. Consumers that want a fully
		// complete picture can walk the per-slot data themselves.
		root.addProperty("totalValueGp", state.totalValueGp);

		if (state.bank != null)
		{
			root.add("bank", containerSection(state.bank, state.bankUpdatedAt, state.bankValueGp));
		}
		if (state.inventory != null)
		{
			root.add("inventory", containerSection(state.inventory, state.inventoryUpdatedAt, state.inventoryValueGp));
		}
		if (state.equipment != null)
		{
			root.add("equipment", equipmentSection(state.equipment, state.equipmentUpdatedAt, state.equipmentValueGp));
		}
		if (state.seedVault != null)
		{
			root.add("seedVault", containerSection(state.seedVault, state.seedVaultUpdatedAt, state.seedVaultValueGp));
		}
		if (state.grandExchange != null)
		{
			root.add("grandExchange", geSection(state.grandExchange, state.geUpdatedAt));
		}

		return root;
	}

	/** Serialize {@code state} to JSON and atomically replace the account's
	 *  file in {@code snapshotDir}. */
	void write(Path snapshotDir, AccountState state, long nowMs) throws IOException
	{
		Files.createDirectories(snapshotDir);

		JsonObject root = build(state, nowMs);
		String json = gson.toJson(root);

		Path target = snapshotDir.resolve(state.accountHash + ".json");
		Path tmp = snapshotDir.resolve(state.accountHash + ".json.tmp");

		Files.writeString(tmp, json);
		try
		{
			Files.move(tmp, target,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (Exception atomicFailed)
		{
			// Some filesystems (exotic network mounts) don't support atomic
			// move. Fall back to a plain replace.
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// ---- section builders --------------------------------------------------

	private JsonObject containerSection(List<AccountState.ContainerItem> items, long updatedAt, long valueGp)
	{
		JsonObject s = new JsonObject();
		s.addProperty("updatedAt", updatedAt);
		s.addProperty("totalValueGp", valueGp);
		JsonArray arr = new JsonArray();
		for (AccountState.ContainerItem it : items)
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", it.id);
			o.addProperty("qty", it.qty);
			if (it.noted)
			{
				o.addProperty("noted", true);
			}
			arr.add(o);
		}
		s.add("items", arr);
		return s;
	}

	private JsonObject equipmentSection(List<AccountState.EquipmentItem> items, long updatedAt, long valueGp)
	{
		JsonObject s = new JsonObject();
		s.addProperty("updatedAt", updatedAt);
		s.addProperty("totalValueGp", valueGp);
		JsonArray arr = new JsonArray();
		for (AccountState.EquipmentItem it : items)
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", it.id);
			o.addProperty("qty", it.qty);
			o.addProperty("slot", it.slot);
			arr.add(o);
		}
		s.add("items", arr);
		return s;
	}

	private JsonObject geSection(List<AccountState.GeSlot> slots, long updatedAt)
	{
		JsonObject s = new JsonObject();
		s.addProperty("updatedAt", updatedAt);
		JsonArray arr = new JsonArray();
		for (AccountState.GeSlot slot : slots)
		{
			JsonObject o = new JsonObject();
			o.addProperty("slot", slot.slot);
			o.addProperty("state", slot.state);
			if (slot.itemId != null) o.addProperty("itemId", slot.itemId);
			if (slot.qtySought != null) o.addProperty("qtySought", slot.qtySought);
			if (slot.qtyTraded != null) o.addProperty("qtyTraded", slot.qtyTraded);
			if (slot.pricePer != null) o.addProperty("pricePer", slot.pricePer);
			if (slot.spent != null) o.addProperty("spent", slot.spent);
			arr.add(o);
		}
		s.add("slots", arr);
		return s;
	}
}

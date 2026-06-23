/*
 * Persistent Bank — RuneLite plugin
 *
 * Reads the snapshot folder on disk and returns lightweight per-account
 * summaries for the Wealth panel. We intentionally do NOT reparse item lists
 * or recompute prices here — the values embedded by SnapshotWriter at
 * write-time are the source of truth. That keeps the panel's refresh path
 * trivial (read a few small JSON files, no price lookups, no item iteration)
 * so it stays cheap even on weak PCs with many accounts.
 */
package com.persistentbank;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class SnapshotReader
{
	/** Lightweight view of one account's on-disk snapshot, suitable for
	 *  rendering in the Wealth panel. Full item data stays on disk. */
	static class AccountSummary
	{
		long accountHash;
		String displayName;
		long lastUpdated;
		long totalValueGp;
		long bankValueGp;
		long inventoryValueGp;
		long equipmentValueGp;
		long seedVaultValueGp;
		long geValueGp;
		boolean complete;
	}

	/** Scan {@code snapshotDir} for *.json files and parse each into an
	 *  AccountSummary. Swallows per-file parse errors so one corrupted
	 *  snapshot doesn't hide the rest. Never throws; returns an empty list
	 *  if the directory is missing or unreadable. Safe to call from a
	 *  background thread — does not touch any RuneLite client state. */
	List<AccountSummary> readAll(Path snapshotDir)
	{
		List<AccountSummary> out = new ArrayList<>();
		if (snapshotDir == null || !Files.isDirectory(snapshotDir))
		{
			return out;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotDir, "*.json"))
		{
			for (Path p : stream)
			{
				try
				{
					AccountSummary s = readOne(p);
					if (s != null)
					{
						out.add(s);
					}
				}
				catch (Exception ignored)
				{
					// Corrupt / partial / foreign file — skip silently.
				}
			}
		}
		catch (IOException ignored)
		{
			// Directory-level failure; return whatever we got (likely empty).
		}
		return out;
	}

	private AccountSummary readOne(Path p) throws IOException
	{
		String raw = Files.readString(p);
		// Use the instance-style JsonParser API (pre-2.8.6 Gson) since
		// RuneLite bundles an older Gson than the one that added the
		// static JsonParser.parseString(). Both APIs return a JsonElement;
		// the instance form is just slightly more ceremony.
		@SuppressWarnings("deprecation")
		JsonElement parsed = new JsonParser().parse(raw);
		if (!parsed.isJsonObject())
		{
			return null;
		}
		JsonObject root = parsed.getAsJsonObject();

		AccountSummary s = new AccountSummary();
		s.accountHash = asLong(root, "accountHash");
		s.displayName = asString(root, "displayName", "");
		s.lastUpdated = asLong(root, "lastUpdated");
		s.totalValueGp = asLong(root, "totalValueGp");
		s.bankValueGp = sectionValue(root, "bank");
		s.inventoryValueGp = sectionValue(root, "inventory");
		s.equipmentValueGp = sectionValue(root, "equipment");
		s.seedVaultValueGp = sectionValue(root, "seedVault");
		s.geValueGp = sectionValue(root, "grandExchange");
		s.complete = !root.has("complete") || asBool(root, "complete");

		// Fallback: older snapshots pre-dating the wealth-panel work don't
		// have a top-level totalValueGp. Derive from sections so those
		// accounts still show a sensible grand total without waiting for
		// the user to log in again.
		if (s.totalValueGp <= 0)
		{
			s.totalValueGp =
				s.bankValueGp + s.inventoryValueGp
				+ s.equipmentValueGp + s.seedVaultValueGp
				+ s.geValueGp;
		}

		if (s.accountHash == -1L)
		{
			// Sentinel for "no account logged in" — not a real snapshot.
			// Anything else (including negative-signed Jagex hashes, which
			// are valid 64-bit identifiers whose high bit happens to be set)
			// must be accepted, otherwise the panel silently drops roughly
			// half of all accounts.
			return null;
		}
		return s;
	}

	private static long sectionValue(JsonObject root, String section)
	{
		if (!root.has(section) || !root.get(section).isJsonObject())
		{
			return 0L;
		}
		return asLong(root.getAsJsonObject(section), "totalValueGp");
	}

	private static long asLong(JsonObject o, String key)
	{
		if (o == null || !o.has(key))
		{
			return 0L;
		}
		try
		{
			return o.get(key).getAsLong();
		}
		catch (Exception e)
		{
			return 0L;
		}
	}

	private static boolean asBool(JsonObject o, String key)
	{
		if (o == null || !o.has(key))
		{
			return true;
		}
		try
		{
			return o.get(key).getAsBoolean();
		}
		catch (Exception e)
		{
			return true;
		}
	}
	
	private static String asString(JsonObject o, String key, String fallback)
	{
		if (o == null || !o.has(key))
		{
			return fallback;
		}
		try
		{
			return o.get(key).getAsString();
		}
		catch (Exception e)
		{
			return fallback;
		}
	}

	/** Seed an in-memory {@link AccountState} from this account's last
	 *  on-disk snapshot. Only the "open-on-demand" sections (bank, seed
	 *  vault, Grand Exchange) are restored: those don't get re-read every
	 *  login, so without this a flush fired before the player re-opens them
	 *  would overwrite the saved file with empty sections. Inventory and
	 *  equipment are deliberately left alone — the plugin re-reads those live
	 *  on every login. No-op when there is no prior file or it can't be
	 *  parsed. Safe on any thread; touches no client state. */
	void hydrate(Path snapshotDir, AccountState s)
	{
		if (snapshotDir == null || s == null)
		{
			return;
		}
		Path p = snapshotDir.resolve(s.accountHash + ".json");
		if (!Files.isRegularFile(p))
		{
			return;
		}
		try
		{
			String raw = Files.readString(p);
			@SuppressWarnings("deprecation")
			JsonElement parsed = new JsonParser().parse(raw);
			if (!parsed.isJsonObject())
			{
				return;
			}
			JsonObject root = parsed.getAsJsonObject();

			String dn = asString(root, "displayName", "");
			if (!dn.isEmpty())
			{
				s.displayName = dn;
			}

			JsonObject bank = sectionObj(root, "bank");
			if (bank != null)
			{
				List<AccountState.ContainerItem> items = parseContainerItems(bank);
				if (items != null && !items.isEmpty())
				{
					s.bank = items;
					s.bankUpdatedAt = asLong(bank, "updatedAt");
					s.bankValueGp = asLong(bank, "totalValueGp");
				}
			}

			JsonObject sv = sectionObj(root, "seedVault");
			if (sv != null)
			{
				List<AccountState.ContainerItem> items = parseContainerItems(sv);
				if (items != null && !items.isEmpty())
				{
					s.seedVault = items;
					s.seedVaultUpdatedAt = asLong(sv, "updatedAt");
					s.seedVaultValueGp = asLong(sv, "totalValueGp");
				}
			}

			JsonObject ge = sectionObj(root, "grandExchange");
			if (ge != null)
			{
				List<AccountState.GeSlot> slots = parseGeSlots(ge);
				if (slots != null)
				{
					s.grandExchange = slots;
					s.geUpdatedAt = asLong(ge, "updatedAt");
					s.geValueGp = asLong(ge, "totalValueGp");
				}
			}
		}
		catch (Exception ignored)
		{
			// Corrupt / partial snapshot — start clean rather than fail.
		}
	}

	private static JsonObject sectionObj(JsonObject root, String name)
	{
		if (root == null || !root.has(name) || !root.get(name).isJsonObject())
		{
			return null;
		}
		return root.getAsJsonObject(name);
	}

	private static List<AccountState.ContainerItem> parseContainerItems(JsonObject section)
	{
		if (!section.has("items") || !section.get("items").isJsonArray())
		{
			return null;
		}
		List<AccountState.ContainerItem> out = new ArrayList<>();
		for (JsonElement el : section.getAsJsonArray("items"))
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			int id = (int) asLong(o, "id");
			int qty = (int) asLong(o, "qty");
			if (id <= 0 || qty <= 0)
			{
				continue;
			}
			boolean noted = o.has("noted") && !o.get("noted").isJsonNull() && o.get("noted").getAsBoolean();
			out.add(new AccountState.ContainerItem(id, qty, noted));
		}
		return out;
	}

	private static List<AccountState.GeSlot> parseGeSlots(JsonObject section)
	{
		if (!section.has("slots") || !section.get("slots").isJsonArray())
		{
			return null;
		}
		List<AccountState.GeSlot> out = new ArrayList<>();
		for (JsonElement el : section.getAsJsonArray("slots"))
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			int slot = (int) asLong(o, "slot");
			String state = asString(o, "state", "EMPTY");
			out.add(new AccountState.GeSlot(
				slot, state,
				asIntOrNull(o, "itemId"),
				asIntOrNull(o, "qtySought"),
				asIntOrNull(o, "qtyTraded"),
				asIntOrNull(o, "pricePer"),
				asIntOrNull(o, "spent")));
		}
		return out;
	}

	private static Integer asIntOrNull(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || o.get(key).isJsonNull())
		{
			return null;
		}
		try
		{
			return o.get(key).getAsInt();
		}
		catch (Exception e)
		{
			return null;
		}
	}
}

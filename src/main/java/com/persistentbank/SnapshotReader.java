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

		// Fallback: older snapshots pre-dating the wealth-panel work don't
		// have a top-level totalValueGp. Derive from sections so those
		// accounts still show a sensible grand total without waiting for
		// the user to log in again.
		if (s.totalValueGp <= 0)
		{
			s.totalValueGp =
				s.bankValueGp + s.inventoryValueGp
				+ s.equipmentValueGp + s.seedVaultValueGp;
		}

		if (s.accountHash <= 0)
		{
			// Not a valid snapshot file.
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
}

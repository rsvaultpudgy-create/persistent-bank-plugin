/*
 * Persistent Bank — RuneLite plugin
 *
 * Config surface exposed in the RuneLite sidebar. All options default to ON
 * (snapshot everything) because the intent of installing the plugin is to
 * have a complete picture; individual sections are toggleable for people
 * who want to narrow the footprint.
 */
package com.persistentbank;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("persistentbank")
public interface PersistentBankConfig extends Config
{
	@ConfigSection(
		name = "What to snapshot",
		description = "Pick which containers are included in each snapshot.",
		position = 0
	)
	String sectionWhat = "what";

	@ConfigSection(
		name = "Snapshot cadence",
		description = "How often snapshots get written to disk.",
		position = 1
	)
	String sectionCadence = "cadence";

	@ConfigSection(
		name = "Advanced",
		description = "Paths and debug options.",
		position = 2
	)
	String sectionAdvanced = "advanced";

	// ---- What to snapshot --------------------------------------------------

	@ConfigItem(
		keyName = "snapshotBank",
		name = "Bank",
		description = "Include bank contents in snapshots.",
		section = sectionWhat,
		position = 0
	)
	default boolean snapshotBank() { return true; }

	@ConfigItem(
		keyName = "snapshotInventory",
		name = "Inventory",
		description = "Include the 28-slot inventory in snapshots.",
		section = sectionWhat,
		position = 1
	)
	default boolean snapshotInventory() { return true; }

	@ConfigItem(
		keyName = "snapshotEquipment",
		name = "Equipment",
		description = "Include currently-worn gear in snapshots.",
		section = sectionWhat,
		position = 2
	)
	default boolean snapshotEquipment() { return true; }

	@ConfigItem(
		keyName = "snapshotSeedVault",
		name = "Seed vault",
		description = "Include the farming guild seed vault in snapshots.",
		section = sectionWhat,
		position = 3
	)
	default boolean snapshotSeedVault() { return true; }

	@ConfigItem(
		keyName = "snapshotGrandExchange",
		name = "Grand Exchange",
		description = "Include open/completed GE slots in snapshots.",
		section = sectionWhat,
		position = 4
	)
	default boolean snapshotGrandExchange() { return true; }

	// ---- Cadence -----------------------------------------------------------

	@Range(min = 1, max = 120)
	@ConfigItem(
		keyName = "minMinutesBetweenWrites",
		name = "Minimum minutes between snapshots",
		description =
			"Rate-limit writes per account. A change triggers an immediate write if this much time " +
				"has passed since the last one; otherwise the write is deferred until the cooldown expires.",
		section = sectionCadence,
		position = 0
	)
	default int minMinutesBetweenWrites() { return 15; }

	@ConfigItem(
		keyName = "flushOnLogout",
		name = "Flush on logout",
		description =
			"When you log out, flush any unwritten changes immediately instead of waiting for the " +
				"cooldown. Recommended ON so short sessions still produce a snapshot.",
		section = sectionCadence,
		position = 1
	)
	default boolean flushOnLogout() { return true; }

	@ConfigItem(
		keyName = "firstSnapshotImmediate",
		name = "First snapshot immediate",
		description =
			"When the plugin sees a new account for the first time, write a snapshot immediately " +
				"instead of waiting for the cooldown.",
		section = sectionCadence,
		position = 2
	)
	default boolean firstSnapshotImmediate() { return true; }

	// ---- Advanced ----------------------------------------------------------

	@ConfigItem(
		keyName = "snapshotDirOverride",
		name = "Snapshot folder (override)",
		description =
			"Leave blank to use the default: <RuneLite dir>/persistent-bank. Set to an absolute path to " +
				"write elsewhere (e.g. a folder RSVault is watching).",
		section = sectionAdvanced,
		position = 0
	)
	default String snapshotDirOverride() { return ""; }

	@ConfigItem(
		keyName = "verboseLogging",
		name = "Verbose logging",
		description = "Log every write to the RuneLite log. Useful when diagnosing why a snapshot isn't landing.",
		section = sectionAdvanced,
		position = 1
	)
	default boolean verboseLogging() { return false; }
}

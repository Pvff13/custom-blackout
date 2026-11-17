package com.customblackout;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(CustomBlackoutConfig.GROUP)
public interface CustomBlackoutConfig extends Config
{
	String GROUP = "customblackout";

	@ConfigSection(name = "Combat", description = "Black out while fighting chosen NPCs", position = 0)
	String combat = "combat";

	@ConfigSection(name = "Skilling", description = "Black out while training chosen skills", position = 1)
	String skilling = "skilling";

	@ConfigSection(name = "Blackout", description = "How the blackout looks", position = 2)
	String blackout = "blackout";

	// ---------------- Combat ----------------

	@ConfigItem(
		keyName = "combatEnabled",
		name = "Enable combat blackout",
		description = "Black out while in combat with one of the NPCs below",
		section = combat,
		position = 0
	)
	default boolean combatEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "npcNames",
		name = "NPC names",
		description = "Comma separated NPC names. * is a wildcard, e.g. 'Gemstone crab, *dragon, Sand crab'. Case doesn't matter.",
		section = combat,
		position = 1
	)
	default String npcNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = "countAttackers",
		name = "Count NPCs attacking me",
		description = "Also black out when a listed NPC is attacking you, even if you aren't targeting it",
		section = combat,
		position = 2
	)
	default boolean countAttackers()
	{
		return true;
	}

	@Range(min = 0, max = 50)
	@Units(Units.TICKS)
	@ConfigItem(
		keyName = "combatGraceTicks",
		name = "Combat end delay",
		description = "Game ticks after combat stops before the blackout lifts (smooths over brief target loss)",
		section = combat,
		position = 3
	)
	default int combatGraceTicks()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "showTarget",
		name = "Show target",
		description = "Cut a hole in the blackout around the NPC you're fighting",
		section = combat,
		position = 4
	)
	default boolean showTarget()
	{
		return false;
	}

	@Range(min = 0, max = 99)
	@ConfigItem(
		keyName = "liftBelowHp",
		name = "Lift below HP",
		description = "Lift the blackout while your hitpoints are at or below this. 0 = off.",
		section = combat,
		position = 5
	)
	default int liftBelowHp()
	{
		return 0;
	}

	// ---------------- Skilling ----------------

	@ConfigItem(
		keyName = "skillingEnabled",
		name = "Enable skilling blackout",
		description = "Black out while training one of the skills below",
		section = skilling,
		position = 0
	)
	default boolean skillingEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skills",
		name = "Skills",
		description = "Comma separated skill names, e.g. 'Woodcutting, Fishing, Mining'. Combat skills work too.",
		section = skilling,
		position = 1
	)
	default String skills()
	{
		return "";
	}

	@Range(min = 1, max = 100)
	@Units(Units.TICKS)
	@ConfigItem(
		keyName = "xpGraceTicks",
		name = "Linger",
		description = "Stay blacked out this many ticks after an XP drop in a chosen skill (raise it for slow actions like low-level woodcutting)",
		section = skilling,
		position = 2
	)
	default int xpGraceTicks()
	{
		return 5;
	}


	// ---------------- Blackout ----------------

	@ConfigItem(
		keyName = "blackoutColor",
		name = "Blackout color",
		description = "Color of the blackout (opacity is fixed at 200/255)",
		section = blackout,
		position = 0
	)
	default Color blackoutColor()
	{
		return Color.BLACK;
	}

	@ConfigItem(
		keyName = "drawBelowWidgets",
		name = "Draw below widgets",
		description = "Keep the inventory, chat and other interfaces visible on top of the blackout",
		section = blackout,
		position = 1
	)
	default boolean drawBelowWidgets()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPlayer",
		name = "Show player",
		description = "Cut a hole in the blackout around your own character",
		section = blackout,
		position = 2
	)
	default boolean showPlayer()
	{
		return false;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "padding",
		name = "Hole padding",
		description = "Extra pixels around the target/player holes",
		section = blackout,
		position = 3
	)
	default int padding()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "pauseHotkey",
		name = "Pause hotkey",
		description = "Toggles the blackout off/on until pressed again",
		section = blackout,
		position = 4
	)
	default Keybind pauseHotkey()
	{
		return Keybind.NOT_SET;
	}
}

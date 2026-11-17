package com.customblackout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.client.util.WildcardMatcher;

/**
 * One user-made blackout trigger. Flat on purpose: every type's parameters live here so the
 * whole list serialises to one JSON string in config, and the editor only shows the fields
 * the chosen type uses.
 */
public class Trigger
{
	// ---------------- saved fields ----------------
	String name = "New trigger";
	boolean enabled = true;
	boolean collapsed = false;
	Type type = Type.GAME_MESSAGE;
	Effect effect = Effect.START;
	/**
	 * Events: ticks the effect lasts after firing (0 = until a Stop trigger).
	 * Conditions: ticks the effect lingers after the condition stops being true.
	 */
	int holdTicks = 0;

	String text = "";
	boolean regex = false;
	ChatFilter chatFilter = ChatFilter.ANY;

	Skill skill = Skill.HITPOINTS;
	LevelType levelType = LevelType.BOOSTED;
	Compare compare = Compare.AT_MOST;
	int value = 1;

	boolean includeAttackers = true;
	boolean anyAnimation = false;

	SpawnKind spawnKind = SpawnKind.NPC;
	boolean despawn = false;

	InventoryMode inventoryMode = InventoryMode.FULL;

	int x = 3222;
	int y = 3218;
	int plane = 0;
	int radius = 5;

	// ---------------- runtime state (not saved) ----------------
	transient int lastActiveTick = Integer.MIN_VALUE / 2;
	/** Event + START + holdTicks 0: on until a Stop event clears it. */
	transient boolean latched;
	private transient String cachedKey;
	private transient List<Object> cachedMatchers;

	boolean isEvent()
	{
		return type.event;
	}

	/** Matches a single piece of text (chat line, overhead text, notification). */
	boolean matchesWhole(String s)
	{
		return matchesAny(s, false);
	}

	/** Matches a name against the comma-separated name list. */
	boolean matchesName(String s)
	{
		return matchesAny(s, true);
	}

	private boolean matchesAny(String s, boolean commaList)
	{
		if (s == null)
		{
			return false;
		}
		s = s.replace((char) 160, ' ');
		List<Object> matchers = matchers(commaList);
		if (matchers.isEmpty())
		{
			return false;
		}
		for (Object m : matchers)
		{
			if (m instanceof Pattern ? ((Pattern) m).matcher(s).find() : WildcardMatcher.matches((String) m, s))
			{
				return true;
			}
		}
		return false;
	}

	private List<Object> matchers(boolean commaList)
	{
		String key = regex + "|" + commaList + "|" + text;
		if (key.equals(cachedKey))
		{
			return cachedMatchers;
		}
		List<Object> list = new ArrayList<>();
		if (regex)
		{
			try
			{
				if (!text.isEmpty())
				{
					list.add(Pattern.compile(text, Pattern.CASE_INSENSITIVE));
				}
			}
			catch (PatternSyntaxException ignored)
			{
				// invalid regex never matches; the editor shows it red
			}
		}
		else if (commaList)
		{
			for (String part : text.split(","))
			{
				String p = part.trim();
				if (!p.isEmpty())
				{
					list.add(p);
				}
			}
		}
		else if (!text.trim().isEmpty())
		{
			list.add(text.trim());
		}
		cachedMatchers = list;
		cachedKey = key;
		return list;
	}

	List<Integer> ids()
	{
		List<Integer> ids = new ArrayList<>();
		for (String part : text.split(","))
		{
			try
			{
				ids.add(Integer.parseInt(part.trim()));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return ids;
	}

	// ---------------- enums ----------------

	@Getter
	@AllArgsConstructor
	enum Type
	{
		// events
		GAME_MESSAGE("Chat message", true, "A chat line matches (game messages, player chat, ...)"),
		NOTIFICATION("Notification fired", true, "Any plugin fires a RuneLite notification that matches"),
		XP_DROP("XP drop", true, "You gain XP in one of the listed skills"),
		SOUND("Sound played", true, "A sound effect with one of the listed ids plays"),
		SPAWNED("Spawned / despawned", true, "An NPC, object, ground item or player with a matching name appears or disappears"),
		OVERHEAD_TEXT("Overhead text", true, "Anyone's overhead text matches"),
		// conditions
		NPC_COMBAT("In combat with NPC", false, "While you fight (or are attacked by) a matching NPC"),
		SKILLING("Training skill", false, "While you are training one of the listed skills"),
		STAT("Stat level", false, "While a skill level compares to a value"),
		INVENTORY("Inventory", false, "While your inventory is full / empty / holds an item count"),
		LOCATION("Location", false, "While you are within a radius of a tile"),
		ANIMATION("Animating", false, "While your character plays one of the listed animations (or any animation, if ticked)"),
		IDLE("Idle", false, "While you have been idle (not animating or moving) for the hold-off ticks");

		private final String label;
		private final boolean event;
		private final String tooltip;

		@Override
		public String toString()
		{
			return label;
		}
	}

	@AllArgsConstructor
	enum Effect
	{
		START("Black out"),
		STOP("Lift blackout");

		private final String label;

		@Override
		public String toString()
		{
			return label;
		}
	}

	@AllArgsConstructor
	enum ChatFilter
	{
		ANY("Any chat"),
		GAME("Game messages"),
		PLAYER("Player chat");

		private final String label;

		boolean accepts(ChatMessageType t)
		{
			switch (this)
			{
				case GAME:
					return t == ChatMessageType.GAMEMESSAGE || t == ChatMessageType.SPAM || t == ChatMessageType.ENGINE
						|| t == ChatMessageType.CONSOLE || t == ChatMessageType.MESBOX || t == ChatMessageType.DIALOG
						|| t == ChatMessageType.BROADCAST || t == ChatMessageType.NPC_SAY;
				case PLAYER:
					return t == ChatMessageType.PUBLICCHAT || t == ChatMessageType.MODCHAT || t == ChatMessageType.PRIVATECHAT
						|| t == ChatMessageType.MODPRIVATECHAT || t == ChatMessageType.FRIENDSCHAT || t == ChatMessageType.CLAN_CHAT
						|| t == ChatMessageType.CLAN_GUEST_CHAT || t == ChatMessageType.CLAN_GIM_CHAT || t == ChatMessageType.AUTOTYPER
						|| t == ChatMessageType.MODAUTOTYPER;
				default:
					return true;
			}
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@AllArgsConstructor
	enum LevelType
	{
		BOOSTED("Current level"),
		REAL("Real level"),
		BOOST("Boost (current - real)");

		private final String label;

		@Override
		public String toString()
		{
			return label;
		}
	}

	@AllArgsConstructor
	enum Compare
	{
		AT_MOST("at most"),
		AT_LEAST("at least"),
		EQUAL("exactly");

		private final String label;

		boolean test(int actual, int target)
		{
			switch (this)
			{
				case AT_MOST:
					return actual <= target;
				case AT_LEAST:
					return actual >= target;
				default:
					return actual == target;
			}
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@AllArgsConstructor
	enum SpawnKind
	{
		NPC("NPC"),
		OBJECT("Object"),
		ITEM("Ground item"),
		PLAYER("Player");

		private final String label;

		@Override
		public String toString()
		{
			return label;
		}
	}

	@AllArgsConstructor
	enum InventoryMode
	{
		FULL("Full"),
		EMPTY("Empty"),
		ITEM_COUNT("Item count");

		private final String label;

		@Override
		public String toString()
		{
			return label;
		}
	}
}

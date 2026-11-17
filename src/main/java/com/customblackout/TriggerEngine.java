package com.customblackout;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NotificationFired;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Runs the user's custom triggers. Events fire from the event bus; conditions are re-checked
 * every game tick (both on the client thread). The overlay asks {@link #isBlackout()} /
 * {@link #isSuppressed()} every frame.
 */
@Slf4j
@Singleton
class TriggerEngine
{
	static final String TRIGGERS_KEY = "triggers";
	private static final int INVENTORY_ID = 93;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Getter
	private final List<Trigger> triggers = new CopyOnWriteArrayList<>();

	@Getter
	private volatile int tick;
	@Getter
	private volatile Actor combatTarget;

	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> lastXpTick = new EnumMap<>(Skill.class);
	private int lastBusyTick;

	/** For the editor: current animation and the last non-idle one seen. */
	@Getter
	private volatile int currentAnimation = -1;
	@Getter
	private volatile int lastAnimation = -1;

	// ---------------- persistence ----------------

	void load()
	{
		triggers.clear();
		String json = configManager.getConfiguration(CustomBlackoutConfig.GROUP, TRIGGERS_KEY);
		triggers.addAll(parse(json));
	}

	void save()
	{
		configManager.setConfiguration(CustomBlackoutConfig.GROUP, TRIGGERS_KEY, toJson(triggers));
	}

	String toJson(List<Trigger> list)
	{
		return gson.toJson(list);
	}

	/** @throws JsonParseException on bad input when called for an import */
	List<Trigger> parseStrict(String json)
	{
		List<Trigger> list = gson.fromJson(json, new TypeToken<List<Trigger>>()
		{
		}.getType());
		List<Trigger> out = new ArrayList<>();
		if (list != null)
		{
			for (Trigger t : list)
			{
				if (t != null)
				{
					sanitize(t);
					out.add(t);
				}
			}
		}
		return out;
	}

	private List<Trigger> parse(String json)
	{
		if (json == null || json.isEmpty())
		{
			return Collections.emptyList();
		}
		try
		{
			return parseStrict(json);
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			log.warn("Could not read Custom Blackout triggers", ex);
			return Collections.emptyList();
		}
	}

	private static void sanitize(Trigger t)
	{
		Trigger d = new Trigger();
		if (t.name == null) t.name = d.name;
		if (t.type == null) t.type = d.type;
		if (t.effect == null) t.effect = d.effect;
		if (t.text == null) t.text = "";
		if (t.chatFilter == null) t.chatFilter = d.chatFilter;
		if (t.skill == null) t.skill = d.skill;
		if (t.levelType == null) t.levelType = d.levelType;
		if (t.compare == null) t.compare = d.compare;
		if (t.spawnKind == null) t.spawnKind = d.spawnKind;
		if (t.inventoryMode == null) t.inventoryMode = d.inventoryMode;
		t.lastActiveTick = Integer.MIN_VALUE / 2;
		t.latched = false;
	}

	// ---------------- state ----------------

	void reset()
	{
		lastXp.clear();
		lastXpTick.clear();
		combatTarget = null;
		lastBusyTick = tick;
		for (Trigger t : triggers)
		{
			t.lastActiveTick = Integer.MIN_VALUE / 2;
			t.latched = false;
		}
	}

	/** Whether this trigger is currently applying its effect. */
	boolean isActive(Trigger t)
	{
		if (!t.enabled)
		{
			return false;
		}
		if (t.isEvent() && t.effect == Trigger.Effect.START && t.holdTicks == 0)
		{
			return t.latched;
		}
		int since = tick - t.lastActiveTick;
		return t.isEvent() ? since < Math.max(1, t.holdTicks) : since <= t.holdTicks;
	}

	boolean isBlackout()
	{
		for (Trigger t : triggers)
		{
			if (t.effect == Trigger.Effect.START && isActive(t))
			{
				return true;
			}
		}
		return false;
	}

	boolean isSuppressed()
	{
		for (Trigger t : triggers)
		{
			if (t.effect == Trigger.Effect.STOP && isActive(t))
			{
				return true;
			}
		}
		return false;
	}

	private void fire(Trigger t)
	{
		t.lastActiveTick = tick;
		if (t.effect == Trigger.Effect.START)
		{
			if (t.holdTicks == 0)
			{
				t.latched = true;
			}
		}
		else
		{
			// A Stop event ends every latched "until stopped" blackout
			for (Trigger other : triggers)
			{
				other.latched = false;
			}
		}
	}

	private void fireMatching(Trigger.Type type, Predicate<Trigger> test)
	{
		for (Trigger t : triggers)
		{
			if (t.enabled && t.type == type && test.test(t))
			{
				fire(t);
			}
		}
	}

	// ---------------- per-tick conditions ----------------

	void onTick(Player me)
	{
		tick++;
		if (me == null)
		{
			combatTarget = null;
			return;
		}
		int anim = me.getAnimation();
		currentAnimation = anim;
		if (anim != -1)
		{
			lastAnimation = anim;
		}
		boolean animating = anim != -1;
		boolean moving = me.getPoseAnimation() != me.getIdlePoseAnimation();
		if (animating || moving)
		{
			lastBusyTick = tick;
		}

		Actor target = null;
		for (Trigger t : triggers)
		{
			if (!t.enabled || t.isEvent())
			{
				continue;
			}
			boolean on;
			switch (t.type)
			{
				case NPC_COMBAT:
				{
					NPC npc = findCombatTarget(client, me, n -> t.matchesName(n.getName()), t.includeAttackers);
					on = npc != null;
					if (on && t.effect == Trigger.Effect.START)
					{
						target = npc;
					}
					break;
				}
				case SKILLING:
					on = isSkilling(t);
					break;
				case STAT:
					on = t.compare.test(level(t), t.value);
					break;
				case INVENTORY:
					on = checkInventory(t);
					break;
				case LOCATION:
				{
					WorldPoint wp = me.getWorldLocation();
					on = wp != null && wp.getPlane() == t.plane
						&& Math.max(Math.abs(wp.getX() - t.x), Math.abs(wp.getY() - t.y)) <= t.radius;
					break;
				}
				case ANIMATION:
				{
					on = anim != -1 && (t.anyAnimation || t.ids().contains(anim));
					break;
				}
				case IDLE:
					on = tick - lastBusyTick >= Math.max(1, t.value);
					break;
				default:
					on = false;
			}
			if (on)
			{
				t.lastActiveTick = tick;
			}
		}
		if (target != null)
		{
			combatTarget = target;
		}
		else if (!isBlackout())
		{
			combatTarget = null;
		}
	}

	static NPC findCombatTarget(Client client, Player me, Predicate<NPC> matches, boolean includeAttackers)
	{
		Actor interacting = me.getInteracting();
		if (interacting instanceof NPC && !((NPC) interacting).isDead() && matches.test((NPC) interacting))
		{
			return (NPC) interacting;
		}
		if (includeAttackers)
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv != null)
			{
				for (NPC npc : wv.npcs())
				{
					if (npc != null && npc.getInteracting() == me && !npc.isDead() && matches.test(npc))
					{
						return npc;
					}
				}
			}
		}
		return null;
	}

	private boolean isSkilling(Trigger t)
	{
		int best = Integer.MAX_VALUE;
		for (Skill s : parseSkills(t.text))
		{
			Integer last = lastXpTick.get(s);
			if (last != null)
			{
				best = Math.min(best, tick - last);
			}
		}
		return best == 0; // linger ticks keep it on between XP drops
	}

	private int level(Trigger t)
	{
		int boosted = client.getBoostedSkillLevel(t.skill);
		int real = client.getRealSkillLevel(t.skill);
		switch (t.levelType)
		{
			case REAL:
				return real;
			case BOOST:
				return boosted - real;
			default:
				return boosted;
		}
	}

	private boolean checkInventory(Trigger t)
	{
		ItemContainer inv = client.getItemContainer(INVENTORY_ID);
		int used = 0;
		int matching = 0;
		if (inv != null)
		{
			for (Item item : inv.getItems())
			{
				if (item.getId() < 0 || item.getQuantity() <= 0)
				{
					continue;
				}
				used++;
				if (t.inventoryMode == Trigger.InventoryMode.ITEM_COUNT
					&& t.matchesName(itemManager.getItemComposition(item.getId()).getName()))
				{
					matching += item.getQuantity();
				}
			}
		}
		switch (t.inventoryMode)
		{
			case FULL:
				return used >= 28;
			case EMPTY:
				return used == 0;
			default:
				return t.compare.test(matching, t.value);
		}
	}

	static Set<Skill> parseSkills(String text)
	{
		Set<Skill> parsed = EnumSet.noneOf(Skill.class);
		for (String part : text.split(","))
		{
			String s = part.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
			if (s.isEmpty())
			{
				continue;
			}
			if (s.equals("RC") || s.equals("RUNECRAFTING"))
			{
				s = "RUNECRAFT";
			}
			else if (s.equals("HP"))
			{
				s = "HITPOINTS";
			}
			try
			{
				parsed.add(Skill.valueOf(s));
			}
			catch (IllegalArgumentException ignored)
			{
			}
		}
		return parsed;
	}

	// ---------------- events ----------------

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		Skill skill = e.getSkill();
		Integer previous = lastXp.put(skill, e.getXp());
		if (previous == null || e.getXp() <= previous)
		{
			return; // first value after login is the client loading stats
		}
		lastXpTick.put(skill, tick);
		fireMatching(Trigger.Type.XP_DROP, t -> parseSkills(t.text).contains(skill));
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		String msg = Text.removeTags(e.getMessage());
		fireMatching(Trigger.Type.GAME_MESSAGE, t -> t.chatFilter.accepts(e.getType()) && t.matchesWhole(msg));
	}

	@Subscribe
	public void onNotificationFired(NotificationFired e)
	{
		String msg = Text.removeTags(e.getMessage());
		fireMatching(Trigger.Type.NOTIFICATION, t -> t.matchesWhole(msg));
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged e)
	{
		String msg = Text.removeTags(e.getOverheadText());
		fireMatching(Trigger.Type.OVERHEAD_TEXT, t -> t.matchesWhole(msg));
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed e)
	{
		int id = e.getSoundId();
		fireMatching(Trigger.Type.SOUND, t -> t.ids().contains(id));
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed e)
	{
		int id = e.getSoundId();
		fireMatching(Trigger.Type.SOUND, t -> t.ids().contains(id));
	}

	private void spawn(Trigger.SpawnKind kind, boolean despawn, String name)
	{
		if (name == null)
		{
			return;
		}
		fireMatching(Trigger.Type.SPAWNED, t -> t.spawnKind == kind && t.despawn == despawn && t.matchesName(name));
	}

	private boolean wantsSpawn(Trigger.SpawnKind kind)
	{
		for (Trigger t : triggers)
		{
			if (t.enabled && t.type == Trigger.Type.SPAWNED && t.spawnKind == kind)
			{
				return true;
			}
		}
		return false;
	}

	private String objectName(int id)
	{
		ObjectComposition def = client.getObjectDefinition(id);
		return def == null ? null : def.getName();
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		spawn(Trigger.SpawnKind.NPC, false, e.getNpc().getName());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		spawn(Trigger.SpawnKind.NPC, true, e.getNpc().getName());
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned e)
	{
		spawn(Trigger.SpawnKind.PLAYER, false, e.getPlayer().getName());
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned e)
	{
		spawn(Trigger.SpawnKind.PLAYER, true, e.getPlayer().getName());
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		if (wantsSpawn(Trigger.SpawnKind.OBJECT))
		{
			spawn(Trigger.SpawnKind.OBJECT, false, objectName(e.getGameObject().getId()));
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		if (wantsSpawn(Trigger.SpawnKind.OBJECT))
		{
			spawn(Trigger.SpawnKind.OBJECT, true, objectName(e.getGameObject().getId()));
		}
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e)
	{
		if (wantsSpawn(Trigger.SpawnKind.OBJECT))
		{
			spawn(Trigger.SpawnKind.OBJECT, false, objectName(e.getWallObject().getId()));
		}
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e)
	{
		if (wantsSpawn(Trigger.SpawnKind.OBJECT))
		{
			spawn(Trigger.SpawnKind.OBJECT, true, objectName(e.getWallObject().getId()));
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned e)
	{
		if (wantsSpawn(Trigger.SpawnKind.ITEM))
		{
			spawn(Trigger.SpawnKind.ITEM, false, itemManager.getItemComposition(e.getItem().getId()).getName());
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned e)
	{
		if (wantsSpawn(Trigger.SpawnKind.ITEM))
		{
			spawn(Trigger.SpawnKind.ITEM, true, itemManager.getItemComposition(e.getItem().getId()).getName());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState s = e.getGameState();
		if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING || s == GameState.CONNECTION_LOST)
		{
			reset();
		}
	}
}

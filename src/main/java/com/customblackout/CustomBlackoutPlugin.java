package com.customblackout;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.WildcardMatcher;

@Slf4j
@PluginDescriptor(
	name = "Custom Blackout",
	description = "Blacks out the screen while fighting chosen NPCs or training chosen skills",
	tags = {"blackout", "afk", "combat", "skilling", "screen"}
)
public class CustomBlackoutPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private CustomBlackoutConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private CustomBlackoutOverlay overlay;

	@Inject
	private TriggerEngine engine;

	@Inject
	private EventBus eventBus;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	private CustomBlackoutPanel panel;
	private NavigationButton navButton;

	/** NPC we're fighting via the quick combat setting. */
	private volatile Actor combatTarget;

	private volatile boolean combatActive;
	private volatile boolean skillingActive;
	@Getter
	private volatile boolean paused;

	private List<String> npcPatterns = new ArrayList<>();
	private Set<Skill> skills = EnumSet.noneOf(Skill.class);

	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private int tick;
	private int lastCombatTick = Integer.MIN_VALUE / 2;
	private int lastXpTick = Integer.MIN_VALUE / 2;

	private final HotkeyListener pauseListener = new HotkeyListener(() -> config.pauseHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			paused = !paused;
		}
	};

	@Provides
	CustomBlackoutConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CustomBlackoutConfig.class);
	}

	@Override
	protected void startUp()
	{
		parseConfig();
		resetState();
		engine.load();
		engine.reset();
		eventBus.register(engine);
		overlay.updateLayer();
		overlayManager.add(overlay);
		keyManager.registerKeyListener(pauseListener);

		panel = new CustomBlackoutPanel(this, engine, client, clientThread);
		navButton = NavigationButton.builder()
			.tooltip("Custom Blackout")
			.icon(icon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	private static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(1, 1, 14, 14);
		g.setColor(new Color(220, 220, 220));
		g.drawRect(1, 1, 13, 13);
		g.fillOval(5, 5, 6, 6);
		g.dispose();
		return img;
	}

	/** Target hole: the quick-setting target, else a custom combat trigger's target. */
	Actor getCombatTarget()
	{
		Actor t = combatTarget;
		return t != null ? t : engine.getCombatTarget();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		keyManager.unregisterKeyListener(pauseListener);
		eventBus.unregister(engine);
		clientToolbar.removeNavigation(navButton);
		panel.shutDown();
		panel = null;
		navButton = null;
		resetState();
	}

	/** Read by the overlay every frame. */
	boolean isBlackoutActive()
	{
		if (paused || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		if (engine.isSuppressed())
		{
			return false;
		}
		if (!combatActive && !skillingActive && !engine.isBlackout())
		{
			return false;
		}
		int liftHp = config.liftBelowHp();
		return liftHp <= 0 || client.getBoostedSkillLevel(Skill.HITPOINTS) > liftHp;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tick++;
		Player me = client.getLocalPlayer();
		engine.onTick(me);
		if (me == null)
		{
			combatActive = skillingActive = false;
			combatTarget = null;
			return;
		}

		updateCombat(me);
		updateSkilling(me);
	}

	private void updateCombat(Player me)
	{
		if (!config.combatEnabled() || npcPatterns.isEmpty())
		{
			combatActive = false;
			combatTarget = null;
			return;
		}

		NPC target = TriggerEngine.findCombatTarget(client, me, this::isFightable, config.countAttackers());
		if (target != null)
		{
			lastCombatTick = tick;
			combatTarget = target;
		}
		combatActive = tick - lastCombatTick <= config.combatGraceTicks();
		if (!combatActive)
		{
			combatTarget = null;
		}
	}

	private boolean isFightable(NPC npc)
	{
		String name = npc.getName();
		if (name == null)
		{
			return false;
		}
		name = name.replace((char) 160, ' ');
		for (String pattern : npcPatterns)
		{
			if (WildcardMatcher.matches(pattern, name))
			{
				return true;
			}
		}
		return false;
	}

	private void updateSkilling(Player me)
	{
		if (!config.skillingEnabled() || skills.isEmpty())
		{
			skillingActive = false;
			return;
		}
		int sinceXp = tick - lastXpTick;
		skillingActive = sinceXp <= config.xpGraceTicks();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		Integer previous = lastXp.put(skill, event.getXp());
		// The first StatChanged per skill after login is the client loading stats, not a real XP drop
		if (previous != null && event.getXp() > previous && skills.contains(skill))
		{
			lastXpTick = tick;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			resetState();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CustomBlackoutConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (TriggerEngine.TRIGGERS_KEY.equals(event.getKey()))
		{
			return;
		}
		parseConfig();
		if ("drawBelowWidgets".equals(event.getKey()))
		{
			// Layer changes only take effect after re-adding the overlay
			overlayManager.remove(overlay);
			overlay.updateLayer();
			overlayManager.add(overlay);
		}
	}

	private void resetState()
	{
		lastXp.clear();
		combatActive = false;
		skillingActive = false;
		combatTarget = null;
		lastCombatTick = Integer.MIN_VALUE / 2;
		lastXpTick = Integer.MIN_VALUE / 2;
	}

	private void parseConfig()
	{
		List<String> patterns = new ArrayList<>();
		for (String part : config.npcNames().split(","))
		{
			String p = part.trim();
			if (!p.isEmpty())
			{
				patterns.add(p);
			}
		}
		npcPatterns = patterns;

		skills = TriggerEngine.parseSkills(config.skills());
	}
}

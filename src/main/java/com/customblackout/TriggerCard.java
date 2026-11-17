package com.customblackout;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** Editor for one trigger in the side panel. */
class TriggerCard extends JPanel
{
	private static final Color ACTIVE_START = new Color(60, 160, 60);
	private static final Color ACTIVE_STOP = new Color(200, 140, 40);

	private final Trigger trigger;
	private final CustomBlackoutPanel panel;
	private final JPanel body = new JPanel();
	private final JButton collapse = new JButton();
	private final JLabel status = new JLabel();
	private JLabel animLabel;

	TriggerCard(Trigger trigger, CustomBlackoutPanel panel)
	{
		this.trigger = trigger;
		this.panel = panel;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, ColorScheme.DARKER_GRAY_COLOR));

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		JCheckBox enabled = new JCheckBox();
		enabled.setOpaque(false);
		enabled.setSelected(trigger.enabled);
		enabled.setToolTipText("Enabled");
		enabled.addActionListener(e ->
		{
			trigger.enabled = enabled.isSelected();
			panel.changed();
		});

		JTextField name = new JTextField(trigger.name);
		onText(name, s -> trigger.name = s);

		JPanel buttons = new JPanel(new GridLayout(1, 0, 2, 0));
		buttons.setOpaque(false);
		collapse.setToolTipText("Show / hide settings");
		collapse.addActionListener(e ->
		{
			trigger.collapsed = !trigger.collapsed;
			refreshCollapse();
			panel.changed();
		});
		JButton up = smallButton("▲", "Move up", () -> panel.move(trigger, -1));
		JButton down = smallButton("▼", "Move down", () -> panel.move(trigger, 1));
		JButton delete = smallButton("✕", "Delete", () -> panel.delete(trigger));
		buttons.add(collapse);
		buttons.add(up);
		buttons.add(down);
		buttons.add(delete);

		header.add(enabled, BorderLayout.WEST);
		header.add(name, BorderLayout.CENTER);
		header.add(buttons, BorderLayout.EAST);

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 4));
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(header, BorderLayout.NORTH);
		top.add(status, BorderLayout.SOUTH);

		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));

		add(top, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		buildBody();
		refreshCollapse();
	}

	private void refreshCollapse()
	{
		collapse.setText(trigger.collapsed ? "+" : "−");
		body.setVisible(!trigger.collapsed);
		revalidate();
	}

	/** Called by the panel's refresh timer. */
	void updateStatus(boolean active, int currentAnim, int lastAnim)
	{
		if (animLabel != null)
		{
			animLabel.setText("now " + (currentAnim == -1 ? "idle" : currentAnim) + ", last " + (lastAnim == -1 ? "none" : lastAnim));
		}
		Color c = !active ? ColorScheme.DARKER_GRAY_COLOR
			: trigger.effect == Trigger.Effect.START ? ACTIVE_START : ACTIVE_STOP;
		setBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, c));
		String what = trigger.type.getLabel() + " → " + trigger.effect;
		status.setText(active ? what + " (active)" : what);
		status.setForeground(active ? c.brighter() : Color.GRAY);
	}

	private void addLastAnimation()
	{
		int id = panel.lastAnimation();
		if (id == -1 || trigger.ids().contains(id))
		{
			return;
		}
		trigger.text = trigger.text.trim().isEmpty() ? String.valueOf(id) : trigger.text.trim() + ", " + id;
		panel.changed();
		buildBody();
	}

	private void buildBody()
	{
		body.removeAll();
		animLabel = null;
		Trigger t = trigger;

		JComboBox<Trigger.Type> type = new JComboBox<>(Trigger.Type.values());
		type.setSelectedItem(t.type);
		type.setToolTipText(t.type.getTooltip());
		type.addActionListener(e ->
		{
			Trigger.Type picked = (Trigger.Type) type.getSelectedItem();
			if (picked == null || picked == t.type)
			{
				return;
			}
			t.type = picked;
			t.holdTicks = defaultHold(picked);
			t.latched = false;
			panel.changed();
			buildBody();
		});
		row("Trigger", type);

		JComboBox<Trigger.Effect> effect = new JComboBox<>(Trigger.Effect.values());
		effect.setSelectedItem(t.effect);
		effect.setToolTipText("Black out: turns the blackout on. Lift blackout: forces it off, overriding everything else.");
		effect.addActionListener(e ->
		{
			t.effect = (Trigger.Effect) effect.getSelectedItem();
			t.latched = false;
			panel.changed();
		});
		row("Effect", effect);

		switch (t.type)
		{
			case GAME_MESSAGE:
				row("Chat", combo(Trigger.ChatFilter.values(), t.chatFilter, v -> t.chatFilter = v));
				textRows("Message", "Whole line must match; * is a wildcard, e.g. *you catch a shark*");
				break;
			case NOTIFICATION:
				textRows("Message", "Notification text; * is a wildcard");
				break;
			case OVERHEAD_TEXT:
				textRows("Text", "Overhead text; * is a wildcard");
				break;
			case XP_DROP:
				row("Skills", text("Comma separated, e.g. Mining, Smithing"));
				break;
			case SOUND:
				row("Sound ids", text("Comma separated sound effect ids"));
				break;
			case SPAWNED:
				row("Kind", combo(Trigger.SpawnKind.values(), t.spawnKind, v -> t.spawnKind = v));
				row("When", combo(new String[]{"Spawns", "Despawns"}, t.despawn ? "Despawns" : "Spawns",
					v -> t.despawn = "Despawns".equals(v)));
				textRows("Names", "Comma separated names; * is a wildcard");
				break;
			case NPC_COMBAT:
				textRows("NPC names", "Comma separated names; * is a wildcard, e.g. *dragon");
				row("", check("Count NPCs attacking me", t.includeAttackers, v -> t.includeAttackers = v));
				break;
			case SKILLING:
				row("Skills", text("Comma separated, e.g. Woodcutting, Fishing"));
				break;
			case STAT:
				row("Skill", combo(Skill.values(), t.skill, v -> t.skill = v));
				row("Level", combo(Trigger.LevelType.values(), t.levelType, v -> t.levelType = v));
				row("Is", combo(Trigger.Compare.values(), t.compare, v -> t.compare = v));
				row("Value", spinner(t.value, -99, 200, v -> t.value = v, null));
				break;
			case INVENTORY:
				JComboBox<Trigger.InventoryMode> mode = combo(Trigger.InventoryMode.values(), t.inventoryMode, v ->
				{
					t.inventoryMode = v;
					buildBody();
				});
				row("Inventory is", mode);
				if (t.inventoryMode == Trigger.InventoryMode.ITEM_COUNT)
				{
					textRows("Item names", "Comma separated; * is a wildcard. Counts are summed.");
					row("Count is", combo(Trigger.Compare.values(), t.compare, v -> t.compare = v));
					row("Value", spinner(t.value, 0, Integer.MAX_VALUE, v -> t.value = v, null));
				}
				break;
			case LOCATION:
				row("X", spinner(t.x, 0, 20000, v -> t.x = v, null));
				row("Y", spinner(t.y, 0, 20000, v -> t.y = v, null));
				row("Plane", spinner(t.plane, 0, 3, v -> t.plane = v, null));
				row("Radius", spinner(t.radius, 0, 200, v -> t.radius = v, "Tiles from the point (square)"));
				JButton here = new JButton("Use my location");
				here.addActionListener(e -> panel.fillLocation(t, this::buildBody));
				row("", here);
				break;
			case ANIMATION:
				row("Anim ids", text("Comma separated animation ids, e.g. 879, 877"));
				row("", check("Any animation (ignore ids)", t.anyAnimation, v -> t.anyAnimation = v));
				animLabel = new JLabel();
				animLabel.setFont(FontManager.getRunescapeSmallFont());
				JButton useLast = new JButton("Add last animation id");
				useLast.setToolTipText("Adds the last animation your character played to the id list");
				useLast.addActionListener(e -> addLastAnimation());
				row("Yours", animLabel);
				row("", useLast);
				break;
			case IDLE:
				row("Idle ticks", spinner(t.value, 1, 10000, v -> t.value = v, "Ticks without animating or moving"));
				break;
			default:
				break;
		}

		String holdTip = t.isEvent()
			? "How long the effect lasts after it fires. 0 = until a 'Lift blackout' event (for Black out)."
			: "Keep the effect this many ticks after the condition stops being true.";
		row(t.isEvent() ? "Lasts (ticks)" : "Linger (ticks)", spinner(t.holdTicks, 0, 100000, v -> t.holdTicks = v, holdTip));

		body.revalidate();
		body.repaint();
	}

	private static int defaultHold(Trigger.Type type)
	{
		switch (type)
		{
			case NPC_COMBAT:
				return 2;
			case SKILLING:
				return 5;
			default:
				return 0;
		}
	}

	// ---------------- small builders ----------------

	private void textRows(String label, String tip)
	{
		row(label, text(tip));
		row("", check("Regex (contains, case-insensitive)", trigger.regex, v ->
		{
			trigger.regex = v;
			buildBody();
		}));
	}

	private JTextField text(String tip)
	{
		JTextField f = new JTextField(trigger.text);
		f.setToolTipText(tip);
		onText(f, s ->
		{
			trigger.text = s;
			validateRegex(f);
		});
		validateRegex(f);
		return f;
	}

	private void validateRegex(JTextField f)
	{
		boolean bad = false;
		if (trigger.regex && !trigger.text.isEmpty())
		{
			try
			{
				Pattern.compile(trigger.text);
			}
			catch (PatternSyntaxException ex)
			{
				bad = true;
			}
		}
		f.setForeground(bad ? Color.RED : null);
	}

	private void onText(JTextField f, Consumer<String> setter)
	{
		f.getDocument().addDocumentListener(new DocumentListener()
		{
			private void update()
			{
				setter.accept(f.getText());
				panel.changed();
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				update();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				update();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				update();
			}
		});
	}

	private <T> JComboBox<T> combo(T[] values, T selected, Consumer<T> setter)
	{
		JComboBox<T> box = new JComboBox<>(values);
		box.setSelectedItem(selected);
		box.addActionListener(e ->
		{
			@SuppressWarnings("unchecked")
			T v = (T) box.getSelectedItem();
			if (v != null)
			{
				setter.accept(v);
				panel.changed();
			}
		});
		return box;
	}

	private JCheckBox check(String label, boolean value, Consumer<Boolean> setter)
	{
		JCheckBox box = new JCheckBox(label, value);
		box.setOpaque(false);
		box.addActionListener(e ->
		{
			setter.accept(box.isSelected());
			panel.changed();
		});
		return box;
	}

	private JSpinner spinner(int value, int min, int max, IntConsumer setter, String tip)
	{
		JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, 1));
		s.setToolTipText(tip);
		s.addChangeListener(e ->
		{
			setter.accept((Integer) s.getValue());
			panel.changed();
		});
		return s;
	}

	private JButton smallButton(String text, String tip, Runnable action)
	{
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.setMargin(new java.awt.Insets(0, 3, 0, 3));
		b.setFocusable(false);
		b.addActionListener(e -> action.run());
		return b;
	}

	private void row(String label, JComponent field)
	{
		JPanel r = new JPanel(new BorderLayout(4, 0));
		r.setOpaque(false);
		r.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		JLabel l = new JLabel(label);
		l.setPreferredSize(new Dimension(78, 20));
		l.setFont(FontManager.getRunescapeSmallFont());
		r.add(l, BorderLayout.WEST);
		r.add(field, BorderLayout.CENTER);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
		body.add(r);
		body.add(Box.createVerticalStrut(1));
	}
}

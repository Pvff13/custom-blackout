package com.customblackout;

import com.google.gson.JsonParseException;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** Side panel listing the custom triggers, Watchdog-style. */
class CustomBlackoutPanel extends PluginPanel
{
	private final CustomBlackoutPlugin plugin;
	private final TriggerEngine engine;
	private final Client client;
	private final ClientThread clientThread;

	private final JLabel status = new JLabel();
	private final JPanel list = new JPanel();
	private final List<TriggerCard> cards = new ArrayList<>();
	private final Timer refresh;

	CustomBlackoutPanel(CustomBlackoutPlugin plugin, TriggerEngine engine, Client client, ClientThread clientThread)
	{
		this.plugin = plugin;
		this.engine = engine;
		this.client = client;
		this.clientThread = clientThread;

		setLayout(new BorderLayout(0, 6));
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel top = new JPanel(new BorderLayout(0, 6));
		JLabel title = new JLabel("Custom Blackout triggers");
		title.setFont(FontManager.getRunescapeBoldFont());
		status.setFont(FontManager.getRunescapeSmallFont());

		JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
		JButton add = new JButton("Add");
		add.setToolTipText("Add a new trigger");
		add.addActionListener(e -> add(new Trigger()));
		JButton export = new JButton("Export");
		export.setToolTipText("Copy all triggers to the clipboard");
		export.addActionListener(e -> exportTriggers());
		JButton imp = new JButton("Import");
		imp.setToolTipText("Add triggers from the clipboard");
		imp.addActionListener(e -> importTriggers());
		buttons.add(add);
		buttons.add(imp);
		buttons.add(export);

		JLabel help = new JLabel("<html>Each trigger either blacks out the screen or lifts it. "
			+ "<b>Lift</b> always wins. Events (chat, XP drop, sound...) last for the set ticks, "
			+ "or with 0 until a Lift event. Conditions (combat, location...) apply while true.</html>");
		help.setFont(FontManager.getRunescapeSmallFont());
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		title.setAlignmentX(LEFT_ALIGNMENT);
		status.setAlignmentX(LEFT_ALIGNMENT);
		help.setAlignmentX(LEFT_ALIGNMENT);
		header.add(title);
		header.add(Box.createVerticalStrut(2));
		header.add(status);
		header.add(Box.createVerticalStrut(4));
		header.add(help);

		top.add(header, BorderLayout.NORTH);
		top.add(buttons, BorderLayout.SOUTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));

		add(top, BorderLayout.NORTH);
		add(list, BorderLayout.CENTER);

		rebuild();
		refresh = new Timer(300, e -> updateStatus());
	}

	@Override
	public void onActivate()
	{
		updateStatus();
		refresh.start();
	}

	@Override
	public void onDeactivate()
	{
		refresh.stop();
	}

	void shutDown()
	{
		refresh.stop();
	}

	void rebuild()
	{
		list.removeAll();
		cards.clear();
		for (Trigger t : engine.getTriggers())
		{
			TriggerCard card = new TriggerCard(t, this);
			card.setAlignmentX(LEFT_ALIGNMENT);
			cards.add(card);
			list.add(card);
			list.add(Box.createVerticalStrut(6));
		}
		if (cards.isEmpty())
		{
			JLabel empty = new JLabel("No triggers yet - press Add.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			list.add(empty);
		}
		updateStatus();
		list.revalidate();
		list.repaint();
	}

	private void updateStatus()
	{
		boolean on = plugin.isBlackoutActive();
		String text = plugin.isPaused() ? "Blackout: paused (hotkey)" : on ? "Blackout: ON" : "Blackout: off";
		status.setText(text);
		status.setForeground(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		List<Trigger> triggers = engine.getTriggers();
		for (int i = 0; i < cards.size() && i < triggers.size(); i++)
		{
			cards.get(i).updateStatus(engine.isActive(triggers.get(i)), engine.getCurrentAnimation(), engine.getLastAnimation());
		}
	}

	// ---------------- actions from cards ----------------

	int lastAnimation()
	{
		return engine.getLastAnimation();
	}

	void changed()
	{
		engine.save();
	}

	void add(Trigger t)
	{
		engine.getTriggers().add(t);
		changed();
		rebuild();
	}

	void delete(Trigger t)
	{
		int ok = JOptionPane.showConfirmDialog(this, "Delete trigger '" + t.name + "'?", "Custom Blackout",
			JOptionPane.YES_NO_OPTION);
		if (ok == JOptionPane.YES_OPTION)
		{
			engine.getTriggers().remove(t);
			changed();
			rebuild();
		}
	}

	void move(Trigger t, int dir)
	{
		List<Trigger> triggers = engine.getTriggers();
		int i = triggers.indexOf(t);
		int j = i + dir;
		if (i < 0 || j < 0 || j >= triggers.size())
		{
			return;
		}
		List<Trigger> copy = new ArrayList<>(triggers);
		Collections.swap(copy, i, j);
		triggers.clear();
		triggers.addAll(copy);
		changed();
		rebuild();
	}

	void fillLocation(Trigger t, Runnable after)
	{
		clientThread.invoke(() ->
		{
			Player me = client.getLocalPlayer();
			if (me == null)
			{
				return;
			}
			WorldPoint wp = WorldPoint.fromLocalInstance(client, me.getLocalLocation());
			SwingUtilities.invokeLater(() ->
			{
				t.x = wp.getX();
				t.y = wp.getY();
				t.plane = wp.getPlane();
				changed();
				after.run();
			});
		});
	}

	private void exportTriggers()
	{
		String json = engine.toJson(engine.getTriggers());
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(json), null);
		JOptionPane.showMessageDialog(this, engine.getTriggers().size() + " trigger(s) copied to the clipboard.",
			"Custom Blackout", JOptionPane.INFORMATION_MESSAGE);
	}

	private void importTriggers()
	{
		try
		{
			String json = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			List<Trigger> imported = engine.parseStrict(json.trim());
			if (imported.isEmpty())
			{
				throw new JsonParseException("no triggers");
			}
			engine.getTriggers().addAll(imported);
			changed();
			rebuild();
			JOptionPane.showMessageDialog(this, "Imported " + imported.size() + " trigger(s).",
				"Custom Blackout", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "The clipboard doesn't hold exported Custom Blackout triggers.",
				"Custom Blackout", JOptionPane.WARNING_MESSAGE);
		}
	}
}

package com.customblackout;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Same technique as Spotlight's blackout: fill the whole game area with the blackout
 * colour, minus padded rectangles ("holes") around anything that should stay visible.
 */
class CustomBlackoutOverlay extends Overlay
{
	private static final int OPACITY = 200;

	private final Client client;
	private final CustomBlackoutPlugin plugin;
	private final CustomBlackoutConfig config;

	@Inject
	CustomBlackoutOverlay(Client client, CustomBlackoutPlugin plugin, CustomBlackoutConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(OverlayPriority.HIGHEST);
		setDragTargetable(false);
		updateLayer();
	}

	void updateLayer()
	{
		setLayer(config.drawBelowWidgets() ? OverlayLayer.UNDER_WIDGETS : OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isBlackoutActive())
		{
			return null;
		}

		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();
		Area area = new Area(new Rectangle(0, 0, width, height));

		int padding = config.padding();
		if (config.showTarget())
		{
			cutHole(area, plugin.getCombatTarget(), padding);
		}
		if (config.showPlayer())
		{
			cutHole(area, client.getLocalPlayer(), padding);
		}

		Color c = config.blackoutColor();
		graphics.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), OPACITY));
		graphics.fill(area);
		return new Dimension(width, height);
	}

	private static void cutHole(Area area, Actor actor, int padding)
	{
		if (actor == null)
		{
			return;
		}
		Shape hull = actor.getConvexHull();
		if (hull == null)
		{
			return;
		}
		Rectangle b = hull.getBounds();
		area.subtract(new Area(new Rectangle(b.x - padding, b.y - padding, b.width + padding * 2, b.height + padding * 2)));
	}
}

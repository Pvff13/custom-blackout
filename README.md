# Custom Blackout

Blacks out the game screen while you're doing something you chose — fighting certain NPCs, training certain skills, or anything matched by your own custom triggers — and lifts it as soon as you stop.

The blackout is drawn at a fixed opacity of 200/255. You can change its colour, but not its transparency.

## Quick settings (plugin config)

For the two most common cases you don't need any triggers:

**Combat**
- **NPC names** – comma separated, `*` is a wildcard (e.g. `Gemstone crab, *dragon`). Case doesn't matter.
- **Count NPCs attacking me** – also black out when a listed NPC is attacking you.
- **Combat end delay** – ticks to wait after combat stops before lifting (default 2).
- **Show target** – cuts a hole in the blackout around the NPC you're fighting.
- **Lift below HP** – lifts the blackout while your hitpoints are at or below this (0 = off).

**Skilling**
- **Skills** – comma separated skill names (e.g. `Woodcutting, Fishing`). `RC` and `HP` work as shorthands.
- **Linger** – ticks to stay blacked out after each XP drop (default 5). Raise it for slow actions.

**Blackout**
- **Blackout color** – colour only; opacity is fixed at 200.
- **Draw below widgets** – keep inventory, chat and other interfaces visible on top.
- **Show player** – cuts a hole around your own character.
- **Hole padding** – extra pixels around the holes.
- **Pause hotkey** – turns the blackout off until pressed again.

## Custom triggers (side panel)

Open the **Custom Blackout** panel from the sidebar (black square icon) and press **Add**. You can make as many triggers as you like.

Each trigger has an **Effect**:
- **Black out** – turns the blackout on.
- **Lift blackout** – forces it off. A Lift trigger always wins over everything else.

### Event triggers
These fire once when something happens. **Lasts (ticks)** sets how long the effect stays on. With 0, a Black out event stays on until a Lift event fires.

| Trigger | Fires when |
|---|---|
| Chat message | A chat line matches (filter: any chat, game messages, player chat) |
| Notification fired | Any plugin fires a RuneLite notification that matches |
| XP drop | You gain XP in one of the listed skills |
| Sound played | A sound effect with one of the listed ids plays |
| Spawned / despawned | An NPC, object, ground item or player with a matching name appears or disappears |
| Overhead text | Anyone's overhead text matches |

### Condition triggers
These apply while they're true. **Linger (ticks)** keeps the effect on for a while after they stop being true.

| Trigger | Active while |
|---|---|
| In combat with NPC | You're fighting (or being attacked by) a matching NPC |
| Training skill | You get XP drops in a listed skill (use Linger to bridge the gaps) |
| Stat level | A skill's current level, real level or boost is at most / at least / exactly a value |
| Inventory | Your inventory is full, empty, or holds a number of a named item |
| Location | You're within a radius of a tile (**Use my location** fills it in) |
| Animating | Your character plays one of the listed animation ids, or any animation if **Any animation** is ticked |
| Idle | You haven't animated or moved for a number of ticks |

**Finding animation ids:** the Animating card shows your current and last animation id live. Do the activity, then press **Add last animation id**. With no ids and **Any animation** unticked, the trigger never fires.

### Text matching
Name and message fields use `*` as a wildcard and must match the whole text (e.g. `*you catch a shark*`). Name fields take a comma-separated list. Tick **Regex** for a case-insensitive regular expression instead (matches anywhere in the text); bad regex turns red.

### Managing triggers
- Tick the box on the left to enable or disable a trigger, and rename it in the header.
- ▲ / ▼ reorder, − / + collapse, ✕ deletes (asks first).
- A card's left edge turns green while it's blacking out and orange while it's lifting. The top of the panel shows whether the blackout is on.
- **Export** copies all triggers to the clipboard as JSON; **Import** adds triggers from the clipboard, so setups can be shared.

Triggers are saved in your RuneLite config and survive restarts and reloads.

## Tip: use animations for skilling blackouts

For a specific skilling activity, an **Animating** trigger with that activity's animation ids is usually the best choice. It works better than a Training skill or XP drop trigger because:

- **It lifts the moment you stop.** XP-based triggers need a Linger to bridge the gaps between drops, so they keep the screen black for those extra ticks after you've stopped. An animation stops when you do.
- **It stays on through slow actions.** Low-level woodcutting or mining can go a long time between XP drops. The animation keeps playing the whole time, so there's no flicker and no need for a long Linger.
- **It only matches that activity.** A skill can gain XP from many things (e.g. Hitpoints XP from any combat). An animation id belongs to one action, such as chopping with a particular axe.
- **It doesn't need XP at all.** It also works for activities where XP comes late or not at all.

**Setting one up:**
1. Add a trigger, set it to **Animating**.
2. Do the activity for a moment, then press **Add last animation id**.
3. If you use different tools (e.g. bronze vs. dragon axe), repeat with each one. Each tool has its own animation id.
4. Set **Linger** to 1–3 ticks. Many activities have a brief idle frame between actions (picking up the next item, re-clicking a spot), and a small linger stops the blackout from flickering there.

Use **Training skill** instead when an activity has no steady animation, e.g. Agility, where you're mostly running between obstacles.

## Example setups

- **AFK woodcutting:** Animating → your axe's chop animation id (Add last animation id), Linger 2.
- **Safety lift:** Stat level → Hitpoints, Current level, at most 20 → Lift blackout.
- **Black out until the inventory fills:** Chat message `*you catch a shark*` → Black out, Lasts 0; plus Chat message `*inventory is too full*` → Lift blackout.

Note: a Black out event with Lasts 0 is only switched off by a Lift **event**. A Lift **condition** hides the blackout only while it's true, and the blackout comes back afterwards.

## How the blackout is drawn

Same technique as the Spotlight plugin: an overlay fills the whole game canvas with the blackout colour, minus padded rectangles ("holes") around anything that should stay visible.

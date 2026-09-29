package net.shurui.shuruisutilities.client.gui.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestObjective;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.client.ClientWaypoints;
import net.shurui.dev.sdu.waypoint.Waypoint;
import net.shurui.dev.sdu.waypoint.WaypointMark;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.racing.client.RaceClientState;

/**
 * SU's quest tracker HUD. It REPLACES DragonMineZ's own persistent tracked-quest panel with the commissioned quest
 * banner ({@link QuestArt}, drawn uniformly scaled), which is the panel's own title, and a row per ACTIVE MARKER
 * beneath it, anchored directly under the coordinate lines Xaero's Minimap draws below its map. No text header is
 * drawn over the banner: the banner already carries the "quest info" label, so a separate line repeating it was
 * redundant and has been removed.
 *
 * <h2>What it shows</h2>
 * Two kinds of entry, in this order.
 *
 * <p>A single QUEST entry for the mission the player is TRACKING: its name, then its outstanding objectives
 * indented beneath it, in DMZ's own phrasing and with its own progress counts, so the panel agrees with the
 * journal. Tracking is one at a time (DMZ stores a single {@code trackedQuestId}), so switching the tracked quest
 * REPLACES this entry rather than adding a second: the previously tracked mission stops being shown. With nothing
 * explicitly tracked, the first accepted, still-incomplete quest stands in so a freshly accepted mission is not
 * silent. Which objectives are outstanding follows DMZ exactly (a parallel quest lists all of them, a sequential
 * one lists the one it is on), with COORDINATE objectives dropped: the marker and the compass already answer "go
 * here" better than a line of numbers, which would also spoil it. The tracked quest wears its marker's distance.
 *
 * <p>A MARKER entry for everything else sdu has synced ({@code ClientWaypoints.all()}, the same set
 * {@code CompassOverlay} draws): airdrops, dimensional tears, tasks, sign-ups, manual pins. Each is one row: the
 * marker's pin, its name, and a distance suffix "(220m)" (one block = one metre, horizontal), or the destination
 * for a {@code travel} marker. Quest pins themselves are NOT drawn as rows, since their quest is already an entry.
 *
 * <h2>Anchoring</h2>
 * When {@link XaeroMinimapAnchor} holds a fresh under-the-map capture, the banner's right edge aligns to the
 * minimap's right edge and the header top sits just below Xaero's coordinate block. Otherwise (Xaero absent,
 * hidden, toggled off, or the capture expired) a configurable top-right fallback that clears a default minimap is
 * used. The block is variable height (a row per marker), so nothing downstream assumes a fixed size.
 *
 * <h2>Suppression + toggle</h2>
 * A Forge-bus {@link RenderGuiOverlayEvent.Pre} handler cancels DMZ's {@code dragonminez:tracked_quest_hud} overlay
 * by its registered id (no eager classload of DMZ's HUD class). Both the suppression and our draw are gated on
 * {@link SUConfig#customQuestTracker}; with it off, DMZ's tracker is left alone and we draw nothing. Client-only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QuestTrackerOverlay
{
    /** Our overlay id, registered in {@code SUClientMenus}. */
    public static final String OVERLAY_ID = "quest_tracker_hud";

    /** DMZ's tracked-quest overlay, registered as {@code registerAbove(PLAYER_HEALTH, "tracked_quest_hud", ...)}. */
    private static final ResourceLocation DMZ_TRACKED_QUEST_HUD =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "tracked_quest_hud");

    // A top-level marker row: icon at the left, name, then a distance/destination suffix.
    private static final int ROW_LEFT = 4;          // banner-local X of the icon
    private static final int ICON_SIZE = 8;
    private static final int ICON_TEXT_GAP = 4;
    private static final int ROW_HEIGHT = 11;
    private static final int MARKER_TEXT_COLOUR = 0xFFFFFFFF;
    private static final int SUFFIX_COLOUR = 0xFFBFC7D5; // the "(220m)" / "(Namek)" suffix, slightly dimmer

    // The same two colours as RGB, for Style.withColor, which takes no alpha channel. A row is built as a styled
    // component so it can be wrapped as one unit, and these are what keep the two halves visually distinct once
    // the plain drawString colour argument no longer decides it.
    private static final int MARKER_TEXT_RGB = MARKER_TEXT_COLOUR & 0x00FFFFFF;
    private static final int SUFFIX_RGB = SUFFIX_COLOUR & 0x00FFFFFF;

    // Neutral icon colour for a marker with no type ({@link WaypointMark#NONE}, whose colour is 0).
    private static final int ICON_NONE_COLOUR = 0xFFCFD6E4;

    // The commissioned pin art is authored at 50x50 (red/blue/yellow/purple/green); blitted down into ICON_SIZE.
    private static final int MARK_TEX_SIZE = 50;

    private static final String DISTANCE_FORMAT = "(%dm)";

    // Uniform banner scale bounds. The banner is scaled to sit the same width as Xaero's minimap (or the fallback
    // width), so a tiny or a giant minimap cannot produce a 2px sliver or an oversized bar. 1.0 is the authored
    // 354px width, already "huge" per the owner, so we never scale UP past it; 0.35 keeps the art legible at ~124px
    // wide by ~20px tall. Only the ART scales: the header and rows below stay at normal font size.
    private static final float MIN_BANNER_SCALE = 0.35F;
    private static final float MAX_BANNER_SCALE = 1.0F;

    // Gaps and clamps.
    private static final int GAP_BELOW_BANNER = 3;  // banner bottom -> first marker row
    private static final int GAP_BELOW_COORDS = 4;  // Xaero coord block bottom -> banner top

    /** How far an objective line is indented past its quest's own text, so the two read as parent and child. */
    private static final int OBJECTIVE_INDENT = 6;
    private static final int EDGE_MARGIN = 2;       // keep the banner off the very left edge

    /**
     * Smallest text column we will wrap into, in pixels. The wrap width comes from the banner, and the banner
     * scales down with Xaero's minimap, so without a floor a small minimap would wrap to one word per line.
     */
    private static final int MIN_TEXT_WIDTH = 70;

    /** Never let the panel run off the bottom of the screen; this is the only thing that caps the row count. */
    private static final int BOTTOM_MARGIN = 4;

    private QuestTrackerOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event)
    {
        if (!SUConfig.customQuestTracker && !RaceClientState.isSessionActive())
            return;
        if (DMZ_TRACKED_QUEST_HUD.equals(event.getOverlay().id()))
            event.setCanceled(true);
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        if (!SUConfig.customQuestTracker)
            return;
        if (RaceClientState.isSessionActive())
            return; // the race HUD owns the screen during a race
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.options.renderDebug)
            return;

        try
        {
            String currentDim = mc.player.level().dimension().location().toString();

            // The active marker set the server already synced. A non-travel marker only shows in its own dimension
            // (matching CompassOverlay); a travel marker is kept anywhere, since it deliberately points elsewhere.
            List<Waypoint> markers = new ArrayList<>();
            for (Waypoint w : ClientWaypoints.all())
            {
                // A notice has no dimension at all: it is a task's progress or a raid's sign-up state, which is
                // true wherever the player is standing, so it is never filtered by dimension.
                if (w.isNotice() || w.travel || currentDim.equals(w.dim))
                    markers.add(w);
            }
            if (markers.isEmpty())
                return;

            double px = mc.player.getX();
            double pz = mc.player.getZ();
            // Nearest first; positionless (travel) markers sort to the end.
            markers.sort(Comparator.comparingDouble(w -> sortKey(w, px, pz)));

            // The panel is built as ENTRIES, not as one row per marker.
            //
            // A quest is one entry: its name, then its outstanding objectives underneath it. That is what a player
            // actually wants from a tracker, and it is why the quest markers are not drawn as rows of their own any
            // more: a quest with three objectives used to arrive as three unrelated rows with no indication they
            // belonged together, or (worse) as three rows all captioned with the quest's own title.
            //
            // COORDINATE objectives are deliberately never listed. "Go to x, y, z" is the one objective the marker
            // and the compass already answer better than a line of text can, and spelling the numbers out is both
            // noise and a spoiler.
            Font font = mc.font;
            LazyOptional<StatsData> cap = StatsProvider.get(StatsCapability.INSTANCE, mc.player);
            StatsData stats = cap.resolve().orElse(null);
            PlayerQuestData pqd = stats == null ? null : stats.getPlayerQuestData();

            List<Entry> entries = new ArrayList<>();
            List<Entry> questEntries = questEntries(pqd);
            entries.addAll(questEntries);
            for (Waypoint w : markers)
            {
                if (isQuestMarker(w))
                {
                    // The quest's own marker is folded into its entry above: its distance goes on the header row of
                    // the first quest entry, which is the one the player is tracking. Only a POSITIONED marker has
                    // anything worth folding in; a notice's suffix is its progress count, which is already the
                    // objective line under that same header.
                    if (!w.isNotice() && !questEntries.isEmpty() && questEntries.get(0).suffix == null)
                        questEntries.get(0).suffix = suffixFor(w, mc, currentDim);
                    continue;
                }
                entries.add(new Entry(w.mark, w.name, suffixFor(w, mc, currentDim), List.of()));
            }
            if (entries.isEmpty())
                return;

            Anchor anchor = resolveAnchor(screenWidth, screenHeight);
            int bannerX = anchor.x;
            float scale = anchor.scale;
            int bannerY = anchor.topY;

            // The bare banner, uniformly scaled, at the anchor's own X. It is the panel's title, so no text header
            // is drawn over it.
            //
            // The banner USED to slide left whenever a row was wider than it, which kept the text on screen but
            // dragged the art off its alignment with the minimap every time a long quest name appeared. The art
            // now never moves; a row too wide for the column wraps instead, which is the same information in the
            // same place.
            QuestArt.banner(g, bannerX, bannerY, scale);

            int rightLimit = screenWidth - EDGE_MARGIN;
            int textX = bannerX + ROW_LEFT + ICON_SIZE + ICON_TEXT_GAP;
            // Wrap to the banner's own width so the block reads as one panel, floored so a small minimap cannot
            // squeeze the column down to one word a line, and never past the screen edge.
            int textRoom = Math.max(MIN_TEXT_WIDTH,
                    bannerX + QuestArt.scaledW(scale) - textX);
            textRoom = Math.max(16, Math.min(textRoom, rightLimit - textX));
            int bottomLimit = screenHeight - BOTTOM_MARGIN;

            // Rows: one per marker, [pin] name (distance). No objective sub-rows.
            int y = bannerY + QuestArt.scaledH(scale) + GAP_BELOW_BANNER;
            boolean truncated = false;

            for (Entry entry : entries)
            {
                // The only cap is the screen itself. There used to be a flat eight-line ceiling, which is what hid
                // a player's later quests behind a "..." even with room to spare below.
                if (y + ROW_HEIGHT > bottomLimit) { truncated = true; break; }

                // icon: the entry's own commissioned pin, or the coloured exclamation glyph for a markless one
                int iconX = bannerX + ROW_LEFT;
                int iconY = y + (ROW_HEIGHT - ICON_SIZE) / 2;
                if (entry.mark != null && entry.mark.hasPin())
                {
                    // Uniform down-blit of the 50x50 pin into the row's icon box: same factor on both axes, no smear.
                    g.blit(entry.mark.texture, iconX, iconY, ICON_SIZE, ICON_SIZE,
                            0.0F, 0.0F, MARK_TEX_SIZE, MARK_TEX_SIZE, MARK_TEX_SIZE, MARK_TEX_SIZE);
                }
                else
                {
                    int iconColour = entry.mark != null && entry.mark.color != 0 ? entry.mark.color : ICON_NONE_COLOUR;
                    drawExclamation(g, iconX, iconY, ICON_SIZE, iconColour);
                }

                // The name and its suffix are ONE piece of information, so they are wrapped together as one
                // component rather than laid out separately. Font.split breaks on spaces, so a name never gets
                // cut mid-word and the suffix is never orphaned from the row it belongs to. Nothing is dropped:
                // whatever a row needs, it takes in extra lines, in the same text column, which still reads as a
                // single entry.
                //
                // The suffix keeps its dimmer colour through the wrap because it is a styled SIBLING, not a
                // separate draw: split() carries per-character style into each FormattedCharSequence, so the
                // colour survives being broken across lines.
                int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
                net.minecraft.network.chat.MutableComponent row = net.minecraft.network.chat.Component
                        .literal(entry.label)
                        .withStyle(s2 -> s2.withColor(MARKER_TEXT_RGB));
                if (entry.suffix != null)
                {
                    row = row.append(net.minecraft.network.chat.Component.literal(" " + entry.suffix)
                            .withStyle(s2 -> s2.withColor(SUFFIX_RGB)));
                }

                for (net.minecraft.util.FormattedCharSequence line : font.split(row, textRoom))
                {
                    if (y + ROW_HEIGHT > bottomLimit) { truncated = true; break; }
                    g.drawString(font, line, textX, textY, MARKER_TEXT_COLOUR, true);
                    y += ROW_HEIGHT;
                    textY += ROW_HEIGHT;
                }
                if (truncated)
                    break;

                // Objectives sit UNDER their quest, indented past the pin and drawn in the dimmer suffix colour, so
                // the panel reads as a quest with its work beneath it rather than as a flat list of equal rows.
                for (String objective : entry.objectives)
                {
                    net.minecraft.network.chat.MutableComponent line = net.minecraft.network.chat.Component
                            .literal(objective)
                            .withStyle(s2 -> s2.withColor(SUFFIX_RGB));
                    for (net.minecraft.util.FormattedCharSequence part
                            : font.split(line, Math.max(16, textRoom - OBJECTIVE_INDENT)))
                    {
                        if (y + ROW_HEIGHT > bottomLimit) { truncated = true; break; }
                        g.drawString(font, part, textX + OBJECTIVE_INDENT, textY, SUFFIX_COLOUR, true);
                        y += ROW_HEIGHT;
                        textY += ROW_HEIGHT;
                    }
                    if (truncated)
                        break;
                }
                if (truncated)
                    break;
            }

            if (truncated)
                g.drawString(font, "...", bannerX + ROW_LEFT, y, SUFFIX_COLOUR, true);
        }
        catch (Throwable ignored)
        {
            // A draw fault must never take down the HUD pass; DMZ's tracker is already suppressed, so we draw nothing.
        }
    }


    /**
     * One thing the panel lists: a quest with its outstanding objectives, or a single marker row.
     *
     * <p>Mutable in one field only, {@code suffix}, because the distance shown on a quest's header row comes from
     * that quest's world marker, which is discovered while walking the markers AFTER the quest entries are built.
     */
    private static final class Entry
    {
        final WaypointMark mark;
        final String label;
        String suffix;
        final List<String> objectives;

        Entry(WaypointMark mark, String label, String suffix, List<String> objectives)
        {
            this.mark = mark;
            this.label = label;
            this.suffix = suffix;
            this.objectives = objectives;
        }
    }

    /**
     * Is this marker the world pin for a DMZ QUEST, as opposed to a tear, an airdrop, a task or a sign-up?
     *
     * <p>Decided on the pin KIND, which is the only thing that distinguishes them: MAIN and SIDE are the two the
     * quest tracker produces (story and everything else questlike), while EVENT, AIRDROP and TASK belong to other
     * systems that legitimately want a row of their own. This matters beyond tidiness: every one of those used to
     * be captioned with the tracked quest's title, because the old row builder assumed any quest-flagged marker was
     * the quest.
     */
    private static boolean isQuestMarker(Waypoint w)
    {
        // NOTICES count too, and leaving them out is what put "Defeat Zombie (9/19)" on the panel twice: once as its
        // quest's objective line and again as a row of its own. sdu emits a positionless notice for a quest whose
        // current objective has nowhere to point (kill this many, collect that many) and a positioned marker for one
        // that does, and BOTH are the quest, which now has an entry of its own either way.
        return w.mark == WaypointMark.MAIN || w.mark == WaypointMark.SIDE;
    }

    /**
     * The single entry for the tracked mission, carrying its outstanding objectives, or empty when the player is on
     * no quest at all.
     *
     * <p>Tracking is one at a time: DMZ stores a single {@code trackedQuestId}, so the panel shows exactly that
     * quest and switching it REPLACES the entry rather than stacking a second one. The tracked mission used to be
     * listed alongside every OTHER accepted quest, which is why a player who switched their tracked side mission
     * saw both the old and the new one at once; now only the tracked one is drawn. With nothing explicitly tracked,
     * {@link #pickQuest} falls back to the first accepted, still-incomplete quest so a freshly accepted mission is
     * not silent.
     *
     * <p>Objective selection mirrors DMZ's own tracker so the two never disagree about what is being asked of the
     * player: a parallel quest lists every incomplete objective, a sequential one lists only the objective it is
     * currently on. COORDINATE objectives are dropped, since the marker and compass already show that and printing
     * the numbers is a spoiler. A quest whose remaining objectives are ALL coordinates therefore lists nothing
     * under it, which is correct: the pin is the instruction.
     */
    private static List<Entry> questEntries(PlayerQuestData pqd)
    {
        List<Entry> out = new ArrayList<>();
        if (pqd == null)
            return out;
        try
        {
            String id = pickQuest(pqd);
            if (id == null)
                return out;
            Quest quest = QuestRegistry.getClientQuest(id);
            if (quest == null)
                return out;
            out.add(new Entry(markFor(quest), questTitle(id, quest), null, objectiveLines(pqd, id, quest)));
        }
        catch (Throwable ignored)
        {
            // A DMZ API shift must not empty the whole panel: the marker rows below still draw.
        }
        return out;
    }

    /** The outstanding objective lines for one quest, coordinates excluded. */
    private static List<String> objectiveLines(PlayerQuestData pqd, String questId, Quest quest)
    {
        List<String> lines = new ArrayList<>();
        try
        {
            List<QuestObjective> objectives = quest.getObjectives();
            if (objectives == null)
                return lines;
            boolean parallel = quest.isParallelObjectives();
            for (int i = 0; i < objectives.size(); i++)
            {
                QuestObjective objective = objectives.get(i);
                if (objective == null)
                    continue;
                int progress = pqd.getObjectiveProgress(questId, i);
                int required = quest.getObjectiveRequired(pqd, questId, i);
                if (progress >= required)
                    continue;
                // A sequential quest is only ever working on this one, so stop after it whether or not it is shown.
                // Keyless there is no compass or beacon (both private), so the location objective is listed
                // instead: the quest's location stays public. With the key it is left to the marker, as before.
                if (objective.getType() != QuestObjective.ObjectiveType.COORDS
                        || !net.shurui.dev.sdu.api.ClientGate.key())
                    lines.add(objectiveText(objective, progress, required));
                if (!parallel)
                    break;
            }
        }
        catch (Throwable ignored)
        {
            // Leave whatever was gathered; a quest with no listable objectives is still a valid entry.
        }
        return lines;
    }

    /** "- kill 3 Saibamen (1/3)", using DMZ's own phrasing so the wording matches its journal. */
    private static String objectiveText(QuestObjective objective, int progress, int required)
    {
        String described;
        try
        {
            described = com.dragonminez.common.quest.QuestTextFormatter.describeObjective(objective).getString();
        }
        catch (Throwable ignored)
        {
            described = objective.getType().name().toLowerCase(java.util.Locale.ROOT);
        }
        // The count is omitted for a one-shot objective, where "(0/1)" says nothing the line does not.
        return required > 1 ? "- " + described + " (" + progress + "/" + required + ")" : "- " + described;
    }

    /** The pin a quest's entry wears: the story pin for a saga quest, the side pin for everything else. */
    private static WaypointMark markFor(Quest quest)
    {
        try
        {
            String type = String.valueOf(quest.getType());
            return "SAGA".equalsIgnoreCase(type) ? WaypointMark.MAIN : WaypointMark.SIDE;
        }
        catch (Throwable ignored)
        {
            return WaypointMark.SIDE;
        }
    }

    /** Sort weight: horizontal distance squared, or +infinity for a positionless travel marker so it sinks last. */
    private static double sortKey(Waypoint w, double px, double pz)
    {
        // Notices sort just above travel markers: both are positionless, but a task the player is actively on is
        // more use at a glance than a reminder that an objective is in another world.
        if (w.isNotice())
            return Double.MAX_VALUE / 2.0;
        if (w.travel)
            return Double.MAX_VALUE;
        double dx = w.x - px;
        double dz = w.z - pz;
        return dx * dx + dz * dz;
    }

    /** The row suffix: "(220m)" for a positioned marker, "(DimName)" for a travel marker, or null to omit. */
    private static String suffixFor(Waypoint w, Minecraft mc, String currentDim)
    {
        // A notice carries its own trailing text (a count, a state) because it has no distance to show.
        if (w.isNotice())
            return "(" + w.note + ")";
        if (w.travel)
            return "(" + dimensionName(w.dim) + ")";
        double dx = w.x - mc.player.getX();
        double dz = w.z - mc.player.getZ();
        int metres = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        return String.format(DISTANCE_FORMAT, metres);
    }

    /** Banner top-left X, banner top Y, and the uniform banner scale for one frame. */
    private static final class Anchor
    {
        final int x;
        final int topY;
        final float scale;

        Anchor(int x, int topY, float scale)
        {
            this.x = x;
            this.topY = topY;
            this.scale = scale;
        }
    }

    /**
     * Where and how big to draw the panel. When Xaero has a fresh under-the-map capture, the banner is LEFT-aligned to
     * the minimap's left edge, scaled so its width matches the minimap ("same width as xaeros"), and its top sits just
     * under the coordinate block. Otherwise a configurable top-right fallback of a configured width is used.
     *
     * <p>Xaero's captured geometry is in its OWN scaled space, not GUI space (Xaero draws its minimap inside a pose
     * scaled by {@code 1 / mapScale}). The tracker draws in ordinary GUI space, so the captured values are converted
     * with {@link XaeroMinimapAnchor}'s GUI space getters ({@link XaeroMinimapAnchor#guiLeft()},
     * {@link XaeroMinimapAnchor#guiWidth()}, {@link XaeroMinimapAnchor#guiCoordBottom(int)}), which divide out
     * {@code mapScale}. This is why the panel used to go invisible when the GUI scale changed: it was positioned with
     * Xaero-scaled coordinates while it drew in GUI space, so the two only agreed at one scale. Converting instead of
     * scaling the whole pose keeps the FONT at normal readable size whatever Xaero's minimap scale is; only the banner
     * ART scales, to the minimap's true on screen width.</p>
     *
     * <p>The banner scale is clamped to [{@link #MIN_BANNER_SCALE}, {@link #MAX_BANNER_SCALE}] so a silly minimap size
     * cannot make a sliver or a giant. As a final safety net, both X and Y are clamped so the banner stays fully
     * within the screen: a bad or stale capture can never push the panel off screen and make it invisible again.</p>
     */
    private static Anchor resolveAnchor(int screenWidth, int screenHeight)
    {
        int bannerX;
        int topY;
        float scale;
        if (XaeroMinimapAnchor.valid())
        {
            // GUI space values: Xaero's scaled capture divided out by mapScale.
            scale = clampScale(XaeroMinimapAnchor.guiWidth() / (float) QuestArt.SHEET_W);
            bannerX = XaeroMinimapAnchor.guiLeft();
            topY = XaeroMinimapAnchor.guiCoordBottom(SUConfig.questTrackerCoordLines) + GAP_BELOW_COORDS;
        }
        else
        {
            // Fallback: pure GUI space from config, no Xaero scale involved (mapScale effectively 1.0), so it can
            // never inherit a stale minimap scale.
            scale = clampScale(SUConfig.questTrackerFallbackWidth / (float) QuestArt.SHEET_W);
            bannerX = screenWidth - QuestArt.scaledW(scale) - SUConfig.questTrackerFallbackRightMargin;
            topY = SUConfig.questTrackerFallbackTopY;
        }

        // Final safety net: keep the whole banner on screen at every GUI scale and minimap size.
        int bannerW = QuestArt.scaledW(scale);
        int bannerH = QuestArt.scaledH(scale);
        int maxX = screenWidth - bannerW - EDGE_MARGIN;
        if (bannerX > maxX)
            bannerX = maxX;
        if (bannerX < EDGE_MARGIN)
            bannerX = EDGE_MARGIN;
        int maxY = screenHeight - bannerH - EDGE_MARGIN;
        if (topY > maxY)
            topY = maxY;
        if (topY < EDGE_MARGIN)
            topY = EDGE_MARGIN;
        return new Anchor(bannerX, topY, scale);
    }

    private static float clampScale(float scale)
    {
        if (scale < MIN_BANNER_SCALE)
            return MIN_BANNER_SCALE;
        if (scale > MAX_BANNER_SCALE)
            return MAX_BANNER_SCALE;
        return scale;
    }

    /** A chunky exclamation mark (stem + dot) drawn from fills in {@code colour}, so it needs no texture. */
    private static void drawExclamation(GuiGraphics g, int x, int y, int size, int colour)
    {
        int w = Math.max(2, size / 4);
        int cx = x + size / 2 - w / 2;
        int stemBottom = y + (size * 3) / 5;
        g.fill(cx, y, cx + w, stemBottom, colour);
        int dotTop = y + (size * 3) / 4;
        g.fill(cx, dotTop, cx + w, dotTop + w, colour);
    }

    /** Tracked quest if accepted and incomplete, else the first accepted, still-incomplete quest (mirrors WaypointTracker). */
    private static String pickQuest(PlayerQuestData pqd)
    {
        try
        {
            java.util.Set<String> accepted = pqd.getAcceptedQuestIds();
            if (accepted == null || accepted.isEmpty())
                return null;
            String tracked = pqd.getTrackedQuestId();
            if (tracked != null && !tracked.isBlank() && accepted.contains(tracked) && !pqd.isQuestCompleted(tracked))
                return tracked;
            for (String id : accepted)
                if (!pqd.isQuestCompleted(id))
                    return id;
        }
        catch (Throwable ignored)
        {
            // fall through
        }
        return null;
    }

    /** The tracked quest's display name, localised/readable the same way DMZ's tracker does. */
    private static String questTitle(String questId, Quest quest)
    {
        try
        {
            String title = quest.getTitle();
            if (title != null && !title.isBlank())
                return com.dragonminez.client.util.LocalizationUtil.localizedOrReadableText(title);
        }
        catch (Throwable ignored)
        {
            // fall through to the id
        }
        return questId;
    }

    private static final java.util.Map<String, String> DIM_NAME_CACHE = new java.util.HashMap<>();

    /** A readable name for a dimension id: a {@code dimension.<ns>.<path>} lang key if present, else the prettified path. */
    private static String dimensionName(String dimId)
    {
        if (dimId == null || dimId.isBlank())
            return "?";
        String cached = DIM_NAME_CACHE.get(dimId);
        if (cached != null)
            return cached;
        String name = resolveDimensionName(dimId);
        DIM_NAME_CACHE.put(dimId, name);
        return name;
    }

    private static String resolveDimensionName(String dimId)
    {
        ResourceLocation loc = ResourceLocation.tryParse(dimId);
        String path = loc == null ? dimId : loc.getPath();
        if (loc != null)
        {
            String key = "dimension." + loc.getNamespace() + "." + path;
            if (I18n.exists(key))
                return I18n.get(key);
        }
        StringBuilder sb = new StringBuilder(path.length());
        boolean upper = true;
        for (int i = 0; i < path.length(); i++)
        {
            char c = path.charAt(i);
            if (c == '_' || c == '/')
            {
                sb.append(' ');
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }
}

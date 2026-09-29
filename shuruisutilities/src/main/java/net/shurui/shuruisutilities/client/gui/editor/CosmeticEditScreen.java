package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAccessoryStyle;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticEditorServer;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * One cosmetic definition, laid out so the fields an admin needs come first and the rest is clearly optional.
 *
 * <p>Three sections, in the order an admin actually fills them:
 * <ul>
 *   <li>Basics: the name, the slot and the model. Enough to make a working cosmetic.</li>
 *   <li>Qualities: which of Normal, Super and Magic a copy may be, the Magic effect pool, and the Super counters
 *       (shown only once Super is allowed).</li>
 *   <li>Trading and extras: whether it can leave its owner, plus the fine model placement.</li>
 * </ul>
 *
 * <p>{@code meta} arrives in the order {@code CosmeticEditorServer.sendOne} writes it and is sent back in the
 * same order on save, so the two halves are one contract. Indices 0 to {@code META_OTHERS - 1} are the record;
 * everything past that is the ids of the other cosmetics, which feeds the copy-from dropdown. The save adds two
 * more fields (the default effect pool, then the event) after the record, which is why the two constants differ.
 *
 * <p>Index 4 is the ELIGIBILITY SET, which qualities a copy may be minted at, drawn as one toggle per quality.
 *
 * <p>The MODEL fields are dropdowns limited to the item tag {@code dmz_ragnarok:cosmetic_models}, sent as
 * {@code m} rows. Only items we deliberately added to that tag are offerable, so a cosmetic can never point at a
 * vanilla or other-mod item. A saved value not in the list is still shown (marked) rather than wiped.
 *
 * <p>The ID IS NOT EDITABLE. It is the key every ownership row and every equipped slot references, so renaming
 * one here would silently orphan every player who owns it. Changing an id is deliberately a delete plus a new
 * entry.
 */
public class CosmeticEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] TABS = {
            "gui.dmz_ragnarok.core.cosmetics.tab_basics",
            "gui.dmz_ragnarok.core.cosmetics.tab_qualities",
            "gui.dmz_ragnarok.core.cosmetics.tab_extras" };

    /** The fixed set of vanilla body parts a cosmetic may hang off. Blank (added by the dropdown) is the default. */
    private static final List<String> BONE_KEYS = List.of("head", "body", "right_arm", "left_arm", "right_leg",
            "left_leg");

    private final List<String> meta;
    private final List<List<String>> rows;
    private final List<String> otherIds = new ArrayList<>();
    private final List<String> modelItemIds = new ArrayList<>();
    private final List<String> modelItemLabels = new ArrayList<>();
    private final List<String> poolIds = new ArrayList<>();
    private final List<String> poolLabels = new ArrayList<>();

    private int tab;

    // The working copy. Edited in place by the field setters, sent back whole on save.
    private String displayName;
    private String description;
    private String slotKey;
    /** The eligibility SET, one flag per quality. Not a single value: see CosmeticDef.allowedQualities. */
    private final Set<CosmeticQuality> qualities = new LinkedHashSet<>();
    private String defaultPoolId;
    private boolean enabled;
    private boolean tradeable;
    private String modelId;
    private String modelSelfId;
    private String modelPreviewId;
    private String attachBone;
    private final String[] offset = new String[3];
    private final String[] offsetSelf = new String[3];
    private final String[] rotation = new String[3];
    private String scale;
    private boolean hideHelmet;
    private boolean hidesHair;
    private String rarity;
    /** The event (collection) picked from the dropdown of existing events. */
    private String event;
    /** A brand new event name typed in the box. When non-blank it wins over {@link #event} on save. */
    private String newEvent = "";
    /** The existing event names the server sent, for the dropdown. */
    private final List<String> eventOptions = new ArrayList<>();
    /** Mount movement, "flying" or "ground". Shown and saved only for a mount-slot cosmetic. */
    private String mountMovement = "ground";
    /** Mount drive speed in blocks per tick, as text. Shown and saved only for a mount-slot cosmetic. */
    private String mountSpeed = "0.35";
    /** Accessory render style (hand / float / carried). Shown and saved only for an accessory-slot cosmetic. */
    private String accessoryStyle = CosmeticAccessoryStyle.CARRIED.key;
    /** The lowest Patreon tier whose active patrons get this cosmetic, or blank when it is not a Patreon cosmetic. */
    private String patreonTier = "";
    /** The fixed Patreon ladder the server sent, ids and display names in parallel, lowest first. */
    private final List<String> patreonTierIds = new ArrayList<>();
    private final List<String> patreonTierLabels = new ArrayList<>();

    private String copyFrom = "";
    private EditBox trackerBox;

    /** Set when a Save was blocked by a missing field; drawn under the fields so the admin sees why. */
    private String saveNotice = "";

    public CosmeticEditScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.cosmetics.edit_title"), UI_W, UI_H, null);
        this.meta = meta;
        this.rows = rows;
        this.displayName = at(1, "");
        this.description = at(2, "");
        this.slotKey = at(3, CosmeticSlot.HEAD.key);
        this.qualities.addAll(CosmeticQuality.split(at(4, CosmeticQuality.NORMAL.key)));
        this.enabled = Boolean.parseBoolean(at(5, "true"));
        this.tradeable = Boolean.parseBoolean(at(6, "false"));
        this.modelId = at(7, "");
        this.modelSelfId = at(8, "");
        this.modelPreviewId = at(9, "");
        this.attachBone = at(10, "");
        for (int i = 0; i < 3; i++)
            offset[i] = at(11 + i, "0");
        for (int i = 0; i < 3; i++)
            offsetSelf[i] = at(14 + i, "0");
        for (int i = 0; i < 3; i++)
            rotation[i] = at(17 + i, "0");
        this.scale = at(20, "1");
        this.hideHelmet = Boolean.parseBoolean(at(21, "false"));
        this.rarity = at(22, "");
        // META_OTHERS, not META_FIXED. The two differ by one, and reading the tail from the save constant used
        // to drop the FIRST id out of the copy-from dropdown with nothing anywhere saying so.
        for (int i = CosmeticEditorServer.META_OTHERS; i < meta.size(); i++)
            otherIds.add(meta.get(i));
        this.defaultPoolId = readPoolRow();
        this.event = readEventRow();
        this.hidesHair = readHideHairRow();
        readModelAndPoolOptions();
        readEventOptions();
        readMountRow();
        this.accessoryStyle = readAccessoryStyleRow();
        readPatreonRows();
    }

    /** The {@code pt} row (the gate) and the {@code pto} rows (the ladder). Absent on an older server: blank. */
    private void readPatreonRows()
    {
        for (List<String> row : rows)
        {
            if (row.size() > 1 && CosmeticEditorServer.ROW_PATREON.equals(row.get(0)))
                this.patreonTier = row.get(1);
            else if (row.size() > 1 && CosmeticEditorServer.ROW_PATREONOPT.equals(row.get(0)))
            {
                patreonTierIds.add(row.get(1));
                patreonTierLabels.add(row.size() > 2 && !row.get(2).isBlank() ? row.get(2) : row.get(1));
            }
        }
    }

    /** The accessory-style row is {@code [as, styleKey]}. Absent on an older server, which reads as carried. */
    private String readAccessoryStyleRow()
    {
        for (List<String> row : rows)
            if (row.size() > 1 && CosmeticEditorServer.ROW_ACCSTYLE.equals(row.get(0)))
                return CosmeticAccessoryStyle.byKey(row.get(1)).key;
        return CosmeticAccessoryStyle.CARRIED.key;
    }

    /** The mount row is {@code [mo, flyingBool, speed]}. Absent on an older server, which reads as a ground default. */
    private void readMountRow()
    {
        for (List<String> row : rows)
            if (row.size() > 2 && CosmeticEditorServer.ROW_MOUNT.equals(row.get(0)))
            {
                this.mountMovement = Boolean.parseBoolean(row.get(1)) ? "flying" : "ground";
                this.mountSpeed = row.get(2);
                return;
            }
    }

    /** The pool row is {@code [p, poolIdOrBlank]}. Absent on an older server, which reads as blank. */
    private String readPoolRow()
    {
        for (List<String> row : rows)
            if (row.size() > 1 && CosmeticEditorServer.ROW_POOL.equals(row.get(0)))
                return row.get(1);
        return "";
    }

    /** The event row is {@code [ev, eventOrBlank]}. Absent on an older server, which reads as blank. */
    private String readEventRow()
    {
        for (List<String> row : rows)
            if (row.size() > 1 && CosmeticEditorServer.ROW_EVENT.equals(row.get(0)))
                return row.get(1);
        return "";
    }

    /** The hide-hair row is {@code [hh, trueOrFalse]}. Absent on an older server, which reads as false. */
    private boolean readHideHairRow()
    {
        for (List<String> row : rows)
            if (row.size() > 1 && CosmeticEditorServer.ROW_HIDEHAIR.equals(row.get(0)))
                return Boolean.parseBoolean(row.get(1));
        return false;
    }

    /** The {@code evo} rows: one existing event name each, for the dropdown of events to pick from. */
    private void readEventOptions()
    {
        for (List<String> row : rows)
            if (row.size() > 1 && CosmeticEditorServer.ROW_EVENTOPT.equals(row.get(0))
                    && !row.get(1).isBlank())
                eventOptions.add(row.get(1));
    }

    /**
     * Read the model-option rows ({@code m}) and pool-option rows ({@code po}) the server appended, and resolve
     * each model item id to a friendly display name (and it is what the dropdown searches on). The display name
     * comes from the CLIENT's own registry, so it is localised and shows the real item name whenever the item is
     * installed; a missing item falls back to its raw id.
     */
    private void readModelAndPoolOptions()
    {
        for (List<String> row : rows)
        {
            if (row.isEmpty())
                continue;
            if (CosmeticEditorServer.ROW_MODEL.equals(row.get(0)) && row.size() > 1)
            {
                String itemId = row.get(1);
                modelItemIds.add(itemId);
                modelItemLabels.add(itemLabel(itemId));
            }
            else if (CosmeticEditorServer.ROW_POOLOPT.equals(row.get(0)) && row.size() > 1)
            {
                poolIds.add(row.get(1));
                poolLabels.add(row.size() > 2 && !row.get(2).isBlank() ? row.get(2) : row.get(1));
            }
        }
    }

    /** The installed item's display name, or the raw id when the item is not present on this client. */
    private static String itemLabel(String itemId)
    {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl != null && ForgeRegistries.ITEMS.containsKey(rl))
        {
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item != null)
                return new ItemStack(item).getHoverName().getString();
        }
        return itemId;
    }

    private String at(int index, String fallback)
    {
        return meta.size() > index ? meta.get(index) : fallback;
    }

    private String id()
    {
        return at(0, "");
    }

    /** Only the tracker rows, in the order the server sent them; the index IS the server-side index. */
    private List<List<String>> trackerRows()
    {
        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows)
            if (!row.isEmpty() && CosmeticEditorServer.ROW_TRACKER.equals(row.get(0)))
                out.add(row);
        return out;
    }

    private List<String> commandRows()
    {
        List<String> out = new ArrayList<>();
        for (List<String> row : rows)
            if (row.size() > 1 && CosmeticEditorServer.ROW_COMMAND.equals(row.get(0)))
                out.add(row.get(1));
        return out;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        // The record's name goes TOP LEFT rather than centred under the logo, per the suite-wide "named entity
        // to top left" rule; centred, it collides with the tab row.
        rowY = buildNamedTabHeader(id(), trAll(TABS), tab, i ->
        {
            applyFields();
            tab = i;
            saveNotice = "";
            rebuildWidgets();
        });

        switch (tab)
        {
        case 0 -> basics();
        case 1 -> qualities();
        default -> extras();
        }

        if (!saveNotice.isBlank())
            label(saveNotice, 14, footerY() - ROW_H, 0xFFE0704A);

        btn(UI_W / 2 - 104, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W / 2 + 4, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.act("cosmetics_admin", "back"));
    }

    private void basics()
    {
        tf(tr("gui.dmz_ragnarok.core.cosmetics.field_name"), displayName, v -> displayName = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_name"));
        df(tr("gui.dmz_ragnarok.core.cosmetics.field_slot"), CosmeticSlot.activeKeys(), slotKey,
                v -> slotKey = v.isBlank() ? slotKey : v);
        // Only the ACTIVE slots are offered. BODY is declared in the enum and switched off, and going through
        // activeKeys() here is what makes turning it on later a one-boolean change with no editor edit.
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_slot"));
        // The MODEL is a dropdown limited to our own cosmetic items (the item tag). Only items we added are
        // offered, so a cosmetic can never be pointed at a vanilla or other-mod item. Empty tag shows a clear
        // "nothing added yet" line rather than an empty dropdown.
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_model"), modelItemIds, modelItemLabels, modelId,
                v -> modelId = v, tr("gui.dmz_ragnarok.core.cosmetics.no_models"));
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_model"));
        // The EVENT (collection): a dropdown of the events already in use, plus a box to type a brand new one. The
        // typed value wins on save (see save()), so an admin naming a new event does not have to pick "(none)"
        // first. dfNamed keeps the current value even when it is off the sent list, so a live event is never wiped.
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_event"), eventOptions, eventOptions, event,
                v -> event = v, tr("gui.dmz_ragnarok.core.cosmetics.no_events"));
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_event"));
        newEventBox(tr("gui.dmz_ragnarok.core.cosmetics.field_event_new"));
        // Movement is only meaningful on a mount, so the dropdown and speed field appear only for a mount-slot
        // cosmetic. Everything else the record carries is slot-agnostic; drawing these on a hat would confuse.
        if (CosmeticSlot.MOUNT.key.equals(slotKey))
            mountControls();
        // The accessory style (hand / float / carried) is only meaningful on an accessory, so its dropdown appears
        // only for an accessory-slot cosmetic, the same way the mount controls appear only for a mount.
        if (CosmeticSlot.ACCESSORY.key.equals(slotKey))
            accessoryControls();
        label(tr("gui.dmz_ragnarok.core.cosmetics.basics_note"), 14, rowY + 4, 0xFFB0B0B0);
    }

    /** The accessory-style dropdown (Hand / Float / Carried). Drawn only for an accessory-slot cosmetic. */
    private void accessoryControls()
    {
        List<String> values = CosmeticAccessoryStyle.keys();
        List<String> labels = new ArrayList<>(values.size());
        for (String v : values)
            labels.add(tr(CosmeticAccessoryStyle.byKey(v).langKey()));
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_accstyle"), values, labels, accessoryStyle,
                v -> accessoryStyle = v.isBlank() ? accessoryStyle : v,
                tr("gui.dmz_ragnarok.core.cosmetics.field_accstyle"));
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_accstyle"));
    }

    /** The mount movement dropdown (Flying / Ground) and the speed field. Drawn only for a mount-slot cosmetic. */
    private void mountControls()
    {
        List<String> values = List.of("ground", "flying");
        List<String> labels = List.of(tr("gui.dmz_ragnarok.core.cosmetics.mount_ground"),
                tr("gui.dmz_ragnarok.core.cosmetics.mount_flying"));
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_mount_movement"), values, labels, mountMovement,
                v -> mountMovement = v.isBlank() ? mountMovement : v,
                tr("gui.dmz_ragnarok.core.cosmetics.field_mount_movement"));
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_mount_movement"));
        tf(tr("gui.dmz_ragnarok.core.cosmetics.field_mount_speed"), mountSpeed, v -> mountSpeed = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_mount_speed"));
    }

    /** A labelled box, empty by default, that captures a brand new event name. Read back on {@link #applyFields}. */
    private void newEventBox(String labelText)
    {
        label(labelText, 14, rowY + 2);
        EditBox b = rawField(fieldColX(), rowY + 1, fieldColW(), newEvent, v -> newEvent = v);
        b.setHint(Component.translatable("gui.dmz_ragnarok.core.cosmetics.event_new_hint"));
        b.setMaxLength(48);
        rowY += ROW_H;
    }

    private void qualities()
    {
        // A MULTI-SELECT, one toggle per quality, because this is an eligibility set rather than a single value:
        // an item can be allowed to exist as Normal and as Magic without being either right now. Built from
        // CosmeticQuality.values() so a quality added later appears here with no edit.
        for (CosmeticQuality q : CosmeticQuality.values())
        {
            final CosmeticQuality quality = q;
            bf(tr("gui.dmz_ragnarok.core.cosmetics.field_quality", tr(q.langKey())), qualities.contains(q), () ->
            {
                // The last remaining quality cannot be unticked. An empty set is normalised back to Normal only
                // on the way in, so allowing it here would show three unticked boxes and save something else.
                if (!qualities.contains(quality))
                    qualities.add(quality);
                else if (qualities.size() > 1)
                    qualities.remove(quality);
            });
        }
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_quality"));
        // The Magic effect pool, as a dropdown of the pools the server sent. Only meaningful when Magic is
        // allowed, but shown regardless so an admin can set it up before widening eligibility.
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_pool"), poolIds, poolLabels, defaultPoolId,
                v -> defaultPoolId = v, tr("gui.dmz_ragnarok.core.cosmetics.no_pools"));
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_pool"));
        rowY += 4;
        // The Super counters live here, and are only reachable once Super is allowed: a counter on a copy that can
        // never be Super would never show.
        if (!qualities.contains(CosmeticQuality.SUPER))
        {
            label(tr("gui.dmz_ragnarok.core.cosmetics.tracker_quality_note"), 14, rowY, 0xFFB0B0B0);
            return;
        }
        trackers();
    }

    private void trackers()
    {
        List<List<String>> trackerList = trackerRows();
        label(tr("gui.dmz_ragnarok.core.cosmetics.trackers_header"), 14, rowY, 0xFFB0B0B0);
        rowY += ROW_H;
        for (int i = 0; i < trackerList.size(); i++)
        {
            List<String> row = trackerList.get(i);
            final int index = i;
            String tid = row.size() > 1 ? row.get(1) : "";
            String label = row.size() > 2 ? row.get(2) : tid;
            String goal = row.size() > 3 ? row.get(3) : "";
            String target = row.size() > 4 ? row.get(4) : "";
            int delW = 20;
            int delX = rowControlRight() - delW;
            rowBtn(14, rowY, delX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal(label),
                    () -> EditorScreens.act("cosmetics_admin", "opentracker", id(), Integer.toString(index)))
                    .color(0xFFF6E27A)
                    .right(Component.literal(goal + (target.isBlank() ? "" : " " + target)), 0xFFB0B0B0);
            btn(delX, rowY, delW, GuiTheme.ROW_HEIGHT, Component.literal("X"),
                    () -> EditorScreens.act("cosmetics_admin", "deltracker", id(), Integer.toString(index)));
            rowY += GuiTheme.ROW_HEIGHT;
        }
        rowY += 4;
        label(tr("gui.dmz_ragnarok.core.cosmetics.tracker_add"), 14, rowY + 2, 0xFFB0B0B0);
        trackerBox = field(fieldColX(), rowY + 1, fieldColW() - 32, "");
        trackerBox.setHint(Component.translatable("gui.dmz_ragnarok.core.cosmetics.tracker_hint"));
        trackerBox.setMaxLength(32);
        btn(fieldColX() + fieldColW() - 28, rowY, 28, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.core.btn.add"), () ->
                {
                    String v = trackerBox.getValue().trim();
                    if (!v.isBlank())
                        EditorScreens.act("cosmetics_admin", "addtracker", id(), v);
                });
        rowY += ROW_H + 4;
        List<String> cmds = commandRows();
        label(tr("gui.dmz_ragnarok.core.cosmetics.grant_commands", cmds.size()), 14, rowY, 0xFFB0B0B0);
    }

    private void extras()
    {
        bf(tr("gui.dmz_ragnarok.core.cosmetics.field_enabled"), enabled, () -> enabled = !enabled);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_enabled"));
        // The Patreon tier gate: "(none)" for an ordinary cosmetic, else the lowest tier whose active patrons get it.
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_patreon_tier"), patreonTierIds, patreonTierLabels,
                patreonTier, v -> patreonTier = v, tr("gui.dmz_ragnarok.core.cosmetics.field_patreon_tier"));
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_patreon_tier"));
        bf(tr("gui.dmz_ragnarok.core.cosmetics.field_tradeable"), tradeable, () -> tradeable = !tradeable);
        // The single most consequential field on the record gets the longest hover help in the editor, and it
        // says out loud what ticking it means for real money. See CosmeticDef.tradeable.
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_tradeable"));
        tf(tr("gui.dmz_ragnarok.core.cosmetics.field_desc"), description, v -> description = v);
        tf(tr("gui.dmz_ragnarok.core.cosmetics.field_rarity"), rarity, v -> rarity = v);
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_model_self"), modelItemIds, modelItemLabels, modelSelfId,
                v -> modelSelfId = v, tr("gui.dmz_ragnarok.core.cosmetics.no_models"));
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_model_preview"), modelItemIds, modelItemLabels,
                modelPreviewId, v -> modelPreviewId = v, tr("gui.dmz_ragnarok.core.cosmetics.no_models"));
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetics.field_bone"), BONE_KEYS, boneLabels(), attachBone,
                v -> attachBone = v, tr("gui.dmz_ragnarok.core.cosmetics.field_bone"));
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_bone"));
        triple(tr("gui.dmz_ragnarok.core.cosmetics.field_offset"), offset);
        triple(tr("gui.dmz_ragnarok.core.cosmetics.field_offset_self"), offsetSelf);
        triple(tr("gui.dmz_ragnarok.core.cosmetics.field_rotation"), rotation);
        tf(tr("gui.dmz_ragnarok.core.cosmetics.field_scale"), scale, v -> scale = v);
        bf(tr("gui.dmz_ragnarok.core.cosmetics.field_hide_helmet"), hideHelmet, () -> hideHelmet = !hideHelmet);
        bf(tr("gui.dmz_ragnarok.core.cosmetics.field_hides_hair"), hidesHair, () -> hidesHair = !hidesHair);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_hides_hair"));
        if (!otherIds.isEmpty())
        {
            df(tr("gui.dmz_ragnarok.core.cosmetics.field_copyfrom"), otherIds, copyFrom, v -> copyFrom = v);
            tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_copyfrom"));
            btn(fieldColX(), rowY, fieldColW(), GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.core.cosmetics.copy_now"), () ->
                    {
                        applyFields();
                        if (!copyFrom.isBlank())
                            EditorScreens.act("cosmetics_admin", "copyfrom", copyFrom, id());
                    });
            rowY += ROW_H;
        }
        label(tr("gui.dmz_ragnarok.core.cosmetics.model_note"), 14, rowY + 4, 0xFFB0B0B0);
    }

    /** The friendly labels for {@link #BONE_KEYS}, parallel and same length. */
    private List<String> boneLabels()
    {
        List<String> out = new ArrayList<>(BONE_KEYS.size());
        for (String k : BONE_KEYS)
            out.add(tr("gui.dmz_ragnarok.core.cosmetics.bone." + k));
        return out;
    }

    /** Three small boxes on one row, for an x y z triple. Uses rawField so applyFields still reads them back. */
    private void triple(String labelText, String[] target)
    {
        label(labelText, 14, rowY + 2, 0xFFB0B0B0);
        int w = Math.max(20, (fieldColW() - 8) / 3);
        for (int i = 0; i < 3; i++)
        {
            final int index = i;
            rawField(fieldColX() + i * (w + 4), rowY + 1, w, target[i], v -> target[index] = v);
        }
        rowY += ROW_H;
    }

    /** Send every field back in the order the server reads them, the default effect pool last. */
    private void save()
    {
        applyFields();
        // Tell the admin what is missing rather than saving a nameless entry that reads as its id everywhere. A
        // blank model is fine (the wardrobe draws the name), so only the name is required.
        if (displayName == null || displayName.isBlank())
        {
            saveNotice = tr("gui.dmz_ragnarok.core.cosmetics.save_needs_name");
            tab = 0;
            rebuildWidgets();
            return;
        }
        saveNotice = "";
        List<String> args = new ArrayList<>();
        args.add(id());
        args.add(displayName);
        args.add(description);
        args.add(slotKey);
        args.add(CosmeticQuality.join(qualities));
        args.add(Boolean.toString(enabled));
        args.add(Boolean.toString(tradeable));
        args.add(modelId);
        args.add(modelSelfId);
        args.add(modelPreviewId);
        args.add(attachBone);
        for (int i = 0; i < 3; i++)
            args.add(offset[i]);
        for (int i = 0; i < 3; i++)
            args.add(offsetSelf[i]);
        for (int i = 0; i < 3; i++)
            args.add(rotation[i]);
        args.add(scale);
        args.add(Boolean.toString(hideHelmet));
        args.add(rarity);
        args.add(defaultPoolId == null ? "" : defaultPoolId);
        // A newly typed event name wins over the dropdown pick, so naming a new one does not need "(none)" chosen
        // first. Trimmed here; the server trims again and normalise() has the last word.
        String finalEvent = newEvent != null && !newEvent.isBlank() ? newEvent.trim()
                : (event == null ? "" : event);
        args.add(finalEvent);
        // The mount movement, appended last so it never moves the fields above. Always sent; the server applies it
        // only to a mount-slot cosmetic and ignores it otherwise, so a non-mount save carries harmless defaults.
        args.add(Boolean.toString("flying".equals(mountMovement)));
        args.add(mountSpeed == null ? "" : mountSpeed);
        // accessoryStyle is appended after hidesHair below, at the very end of the tail, so its index never shifts
        // the fields before it. Always sent; the server applies it only to an accessory-slot cosmetic.
        // hidesHair is appended LAST, after the event and after the mount fields the mount work appends, so it
        // lands at save index 27 and never moves the tail. The server reads it defensively. HEAD-only in effect.
        args.add(Boolean.toString(hidesHair));
        // accessoryStyle appended at the VERY END of the tail (save index 28), after hidesHair, so its index never
        // shifts anything before it. Always sent; the server applies it only to an accessory-slot cosmetic.
        args.add(CosmeticAccessoryStyle.byKey(accessoryStyle).key);
        // The Patreon tier gate at save index 29, after the accessory style, blank for none.
        args.add(patreonTier == null ? "" : patreonTier);
        EditorScreens.act("cosmetics_admin", "save", args);
    }
}

package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.shuruis_dmz_dungeons.block.CustomDrop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

// the ONE CustomDrop list editor body, shared by the advanced spawner's Drops tab and the per-floor crate Loot
// tab, so there is a single drop-row implementation (item-id picker + count range + pick-weight percent + remove,
// plus Add and Add-held buttons). Lives in the gui package so it can call the FieldEditScreen / BaseEditScreen
// toolkit's protected row helpers on the host screen and advance its running rowY cursor, exactly like a section
// the host laid out by hand. The host owns the list's scroll offset (so different screens keep their own) and
// passes it in with a setter.
public final class DropListEditor {

    // item id dropdown source, cached once (every registered item id, sorted). shared by every drop row.
    private static List<String> ITEM_IDS;

    private DropListEditor() {
    }

    // build the column captions, the scrollable drop rows and the Add / Add-held buttons for `drops` onto `screen`,
    // starting at the host's current rowY and advancing it. `scroll` is the host's stored top index; `setScroll` is
    // how the host records a new one (usually v -> { this.field = v; rebuildWidgets(); }).
    public static void build(FieldEditScreen screen, List<CustomDrop> drops, int scroll, IntConsumer setScroll) {
        screen.label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.col_item"), 14, screen.rowY);
        screen.label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.col_min"), 168, screen.rowY);
        screen.label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.col_max"), 196, screen.rowY);
        screen.label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.col_pct"), 226, screen.rowY);
        screen.rowY += 10;

        int listTop = screen.rowY;
        // Reserve 16px so the list stops above the Add / Add-held row (2px gap + 12px buttons), and clears the
        // spawner host's scroll band whose bottom is 2px above GuiTheme.contentBottom.
        int maxRows = screen.rowsThatFit(listTop, FieldEditScreen.ROW_H, 16);
        int count = drops.size();
        int s = Math.max(0, Math.min(scroll, Math.max(0, count - maxRows)));
        int end = Math.min(count, s + maxRows);
        for (int i = s; i < end; i++) {
            dropRow(screen, drops, drops.get(i), i);
        }
        screen.scrollList(12, screen.uiWidth, listTop, FieldEditScreen.ROW_H, maxRows, count, s, setScroll);

        int addY = listTop + maxRows * FieldEditScreen.ROW_H + 2;
        screen.btn(14, addY, 120, 12, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.add_drop"), () -> {
            screen.applyFields();
            drops.add(new CustomDrop());
            setScroll.accept(Math.max(0, drops.size() - maxRows));
            screen.rebuild();
        });
        // copy the main-hand item as a drop, keeping ALL its NBT (enchants, names, modded data)
        screen.btn(140, addY, 148, 12, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.add_held"), () -> {
            var player = Minecraft.getInstance().player;
            ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
            if (held.isEmpty()) {
                return;
            }
            screen.applyFields();
            CustomDrop d = new CustomDrop();
            d.itemId = String.valueOf(ForgeRegistries.ITEMS.getKey(held.getItem()));
            d.minCount = d.maxCount = held.getCount();
            d.chance = 100.0f;
            if (held.hasTag()) {
                d.nbt = held.getTag().toString();
            }
            drops.add(d);
            setScroll.accept(Math.max(0, drops.size() - maxRows));
            screen.rebuild();
        });
        screen.tooltip(140, addY, 148, 12, I18n.get("gui.dmz_ragnarok.dungeons.spawner.add_held_tip"));
    }

    // one editable drop row: item id, count range, % pick-weight, remove button (plus the NBT marker if it carries one)
    private static void dropRow(FieldEditScreen screen, List<CustomDrop> drops, CustomDrop d, int index) {
        int y = screen.rowY;
        screen.dfAt(14, y, 148, itemIds(), d.itemId, v -> d.itemId = v.trim());
        screen.rawField(166, y + 1, 24, Integer.toString(d.minCount),
                v -> d.minCount = Math.max(0, FieldEditScreen.parseI(v, d.minCount)));
        screen.rawField(194, y + 1, 24, Integer.toString(d.maxCount),
                v -> d.maxCount = Math.max(0, FieldEditScreen.parseI(v, d.maxCount)));
        screen.rawField(222, y + 1, 30, fmt(d.chance), v -> d.chance = clampChance(parseFloat(v, d.chance)));
        boolean hasNbt = d.nbt != null && !d.nbt.isBlank();
        if (hasNbt) {
            screen.label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.nbt_marker"), 248, y + 2);
        }
        screen.btn(256, y, 12, 11, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.drop_remove"), () -> {
            screen.applyFields();
            drops.remove(index);
            screen.rebuild();
        });
        screen.tooltip(14, y, 148, FieldEditScreen.ROW_H,
                (hasNbt ? I18n.get("gui.dmz_ragnarok.dungeons.spawner.drop_row_nbt") : "")
                        + I18n.get("gui.dmz_ragnarok.dungeons.spawner.drop_row_tip"));
        screen.rowY += FieldEditScreen.ROW_H;
    }

    private static List<String> itemIds() {
        if (ITEM_IDS == null) {
            List<String> ids = new ArrayList<>();
            for (var key : ForgeRegistries.ITEMS.getKeys()) {
                ids.add(key.toString());
            }
            ids.sort(String::compareTo);
            ITEM_IDS = ids;
        }
        return ITEM_IDS;
    }

    private static float clampChance(float v) {
        return Math.max(0.0f, Math.min(100.0f, v));
    }

    private static float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String fmt(float v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Float.toString(v);
    }
}

package net.shurui.dev.sdu.client.gui.shenron;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.sdu.client.GameItems;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.SduHubScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineColorConfig;
import net.shurui.dev.sdu.shenron.ShrineModels;
import net.shurui.dev.sdu.shenron.ShrineRequiredItem;
import net.shurui.dev.sdu.shenron.ShrineWish;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Admin GUI to configure the fully-custom Shenron-shrine feature (SDU's own, independent of DMZ's wish
 * system). Tabs: a global {@code Wishes} list (add/edit/delete, each editing a {@link ShrineWish} via
 * {@link ShrineWishEditScreen}), then one tab per {@link ShrineColor} for that colour's summon
 * requirements, display-entity model/scale, wish offering (empty = all) and summon settings.
 *
 * <p>Seeded from one JSON bundle pushed by the server ({@code OpenShrineConfigPacket}); all edits stay in
 * memory until Save, which re-serializes the bundle and streams it back via
 * {@link DmzNet#sendLargeToServer} under the {@code "shrineconfig"} kind. The server re-gates, validates and
 * replaces its live model, so edits take effect on the next summon without a restart.
 */
public class ShrineConfigScreen extends FieldEditScreen {

    private static final Gson GSON = new Gson();
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTIONS = {
            "gui.dmz_ragnarok.npc.shrineconfig.tab.wishes",
            "gui.dmz_ragnarok.npc.shrine.color.blue", "gui.dmz_ragnarok.npc.shrine.color.gold",
            "gui.dmz_ragnarok.npc.shrine.color.green", "gui.dmz_ragnarok.npc.shrine.color.red",
    };

    private final List<ShrineWish> wishes;
    private final Map<ShrineColor, ShrineColorConfig> colors;
    private int section;
    // Vertical scroll for the (potentially tall) section content uses the inherited SagaBaseScreen band.

    private ShrineConfigScreen(List<ShrineWish> wishes, Map<ShrineColor, ShrineColorConfig> colors) {
        super(Component.translatable("gui.dmz_ragnarok.npc.shrineconfig.title"), UI_W, UI_H, null);
        this.wishes = wishes;
        this.colors = colors;
    }

    /** Open the screen on the client, seeded from the server's shrine-config JSON bundle. */
    public static void open(String bundleJson) {
        List<ShrineWish> wishes = new ArrayList<>();
        Map<ShrineColor, ShrineColorConfig> colors = new EnumMap<>(ShrineColor.class);
        try {
            JsonObject root = GSON.fromJson(bundleJson, JsonObject.class);
            if (root != null) {
                if (root.has("wishes") && root.get("wishes").isJsonArray()) {
                    for (var el : root.getAsJsonArray("wishes")) {
                        if (el.isJsonObject()) {
                            wishes.add(ShrineWish.fromJson(el.getAsJsonObject()));
                        }
                    }
                }
                if (root.has("colors") && root.get("colors").isJsonObject()) {
                    JsonObject c = root.getAsJsonObject("colors");
                    for (ShrineColor col : ShrineColor.values()) {
                        if (c.has(col.key()) && c.get(col.key()).isJsonObject()) {
                            colors.put(col, ShrineColorConfig.fromJson(c.getAsJsonObject(col.key())));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (ShrineColor col : ShrineColor.values()) {
            colors.computeIfAbsent(col, k -> ShrineColorConfig.seedDefault());
        }
        Minecraft.getInstance().setScreen(new ShrineConfigScreen(wishes, colors));
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        int belowTabs = buildTabHeader(tr("gui.dmz_ragnarok.npc.shrineconfig.subtitle"), trAll(SECTIONS), section, this::selectSection);
        int contentTop = belowTabs;
        int contentBottom = UI_H - 30;

        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        buildSection();
        finishScrollBand(contentTop, contentBottom, rowY);

        commitBtn(UI_W / 2 - 118, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.save"),
                () -> { applyFields(); save(); });
        btn(UI_W / 2 + 8, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new SduHubScreen()));
    }

    private void selectSection(int i) {
        applyFields();
        section = i;
        scroll = 0;
        rebuildWidgets();
    }

    private void buildSection() {
        if (section == 0) {
            buildWishes();
        } else {
            buildColor(ShrineColor.values()[section - 1]);
        }
    }

    private void buildWishes() {
        label("§e" + tr("gui.dmz_ragnarok.npc.shrineconfig.hdr_wishes"), 14, rowY);
        rowY += ROW_H + 2;
        for (int i = 0; i < wishes.size(); i++) {
            final ShrineWish w = wishes.get(i);
            String name = w.name == null || w.name.isBlank() ? w.id : w.name;
            label("§b" + name + " §7(" + (w.id == null ? "" : w.id) + ")", 14, rowY + 3);
            int rowTop = rowY;
            // Edit + delete are pulled in to the shared reserved column on the narrower canvas so neither runs
            // under the content band's scrollbar; the X is right-aligned exactly like every other list's delete.
            btn(214, rowTop, 44, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"),
                    () -> { applyFields(); minecraft.setScreen(new ShrineWishEditScreen(this, w)); });
            iconBtnRight(rowControlRight(), rowTop, ROW_H, Component.translatable("gui.dmz_ragnarok.npc.btn.x"),
                    () -> { applyFields(); wishes.remove(w); rebuildWidgets(); });
            rowY += ROW_H + 2;
        }
        if (wishes.isEmpty()) {
            label("§7" + tr("gui.dmz_ragnarok.npc.shrineconfig.no_wishes"), 14, rowY + 2);
            rowY += ROW_H;
        }
        rowY += 2;
        commitBtn(14, rowY, 140, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.shrineconfig.add_wish"),
                () -> { applyFields(); wishes.add(new ShrineWish("wish_" + (wishes.size() + 1), "", "", new ArrayList<>())); rebuildWidgets(); });
        rowY += ROW_H + 2;
    }

    private void buildColor(ShrineColor color) {
        final ShrineColorConfig cfg = colors.get(color);
        label("§e" + tr("gui.dmz_ragnarok.npc.shrineconfig.hdr_display"), 14, rowY);
        rowY += ROW_H + 2;
        df(tr("gui.dmz_ragnarok.npc.shrineconfig.model_geo"), ShrineModels.geoOptions(), cfg.modelGeo, v -> cfg.modelGeo = v.trim());
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_model_geo"));
        df(tr("gui.dmz_ragnarok.npc.shrineconfig.model_texture"), ShrineModels.textureOptions(), cfg.modelTexture, v -> cfg.modelTexture = v.trim());
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_model_texture"));
        tf(tr("gui.dmz_ragnarok.npc.shrineconfig.entity_scale"), dbl(cfg.entityScale),
                v -> cfg.entityScale = (float) parseD(v, cfg.entityScale));
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_entity_scale"));

        rowY += 4;
        label("§e" + tr("gui.dmz_ragnarok.npc.shrineconfig.hdr_summon"), 14, rowY);
        rowY += ROW_H + 2;
        bf(tr("gui.dmz_ragnarok.npc.shrineconfig.darken_sky"), cfg.darkenSky, () -> cfg.darkenSky = !cfg.darkenSky);
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_darken_sky"));
        tf(tr("gui.dmz_ragnarok.npc.shrineconfig.summon_duration"), intStr(cfg.summonDurationTicks),
                v -> cfg.summonDurationTicks = Math.max(1, parseI(v, cfg.summonDurationTicks)));
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_summon_duration"));

        rowY += 4;
        label("§e" + tr("gui.dmz_ragnarok.npc.shrineconfig.hdr_wish_ids"), 14, rowY);
        rowY += ROW_H + 2;
        dfMulti(tr("gui.dmz_ragnarok.npc.shrineconfig.wish_ids"), allWishIds(), cfg.wishIds,
                out -> replaceList(cfg.wishIds, out));
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_wish_ids"));

        rowY += 4;
        label("§e" + tr("gui.dmz_ragnarok.npc.shrineconfig.hdr_required"), 14, rowY);
        rowY += ROW_H + 2;
        for (int i = 0; i < cfg.requiredItems.size(); i++) {
            final ShrineRequiredItem r = cfg.requiredItems.get(i);
            int dropRow = rowY;
            // The dropdown gives up its last 13px so the delete can sit between it and the scrollbar column. At the
            // old x=325 this button was off the 300-wide panel entirely; parked in the gutter instead it would draw
            // but never take a click, because the band tests the scroll thumb before any button.
            df(tr("gui.dmz_ragnarok.npc.shrineconfig.item_n", i + 1), itemIds(), r.item, v -> r.item = v, 13);
            iconBtnAt(rowControlRight() - 11, dropRow, 11, Component.translatable("gui.dmz_ragnarok.npc.btn.x"),
                    () -> { applyFields(); cfg.requiredItems.remove(r); rebuildWidgets(); });
            tf(tr("gui.dmz_ragnarok.npc.shrineconfig.count"), intStr(r.count), v -> r.count = Math.max(1, parseI(v, r.count)));
        }
        commitBtn(14, rowY + 1, 140, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.shrineconfig.add_item"),
                () -> { applyFields(); cfg.requiredItems.add(new ShrineRequiredItem("minecraft:nether_star", 1)); rebuildWidgets(); });
        rowY += ROW_H + 4;
    }

    private List<String> allWishIds() {
        List<String> out = new ArrayList<>();
        for (ShrineWish w : wishes) {
            if (w.id != null && !w.id.isBlank()) {
                out.add(w.id);
            }
        }
        return out;
    }

    private static List<String> itemIds() {
        List<String> out = new ArrayList<>();
        for (var r : GameItems.itemIds()) {
            out.add(r.toString());
        }
        return out;
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonArray wa = new JsonArray();
        for (ShrineWish w : wishes) {
            wa.add(w.toJson());
        }
        root.add("wishes", wa);
        JsonObject co = new JsonObject();
        for (ShrineColor c : ShrineColor.values()) {
            co.add(c.key(), colors.getOrDefault(c, ShrineColorConfig.seedDefault()).toJson());
        }
        root.add("colors", co);
        DmzNet.sendLargeToServer("shrineconfig", GSON.toJson(root));
    }

}

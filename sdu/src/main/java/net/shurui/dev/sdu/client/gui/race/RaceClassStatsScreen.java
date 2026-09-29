package net.shurui.dev.sdu.client.gui.race;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.GsonHelper;
import net.shurui.dev.sdu.client.GeneratedLang;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.race.RaceData;

/** Create/edit one character class within a race: its name plus base stats, scaling, regen & TP. */
public class RaceClassStatsScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTIONS = {"gui.dmz_ragnarok.npc.class_edit.tab.base", "gui.dmz_ragnarok.npc.class_edit.tab.scaling", "gui.dmz_ragnarok.npc.class_edit.tab.regen", "gui.dmz_ragnarok.npc.class_edit.tab.passive"};

    private final RaceData race;
    private String className;
    private final RaceData.ClassStats cs;
    private int section;

    public RaceClassStatsScreen(Screen parent, RaceData race, String className) {
        super(Component.translatable("gui.dmz_ragnarok.npc.raceclassstats.edit_class"), UI_W, UI_H, parent);
        this.race = race;
        this.className = className;
        this.cs = race.classes.get(className);
    }

    /** Rename this class's key in the race, keeping its stats. No-op on blank/duplicate names. */
    private void rename(String raw) {
        String name = net.shurui.dev.sdu.util.SduIds.sanitize(raw);
        if (name.isBlank() || name.equals(className) || race.classes.containsKey(name)) {
            return;
        }
        race.classes.remove(className);
        race.classes.put(name, cs);
        className = name;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        if (cs == null) {
            btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::back);
            return;
        }
        rowY = buildTabHeader(tr("gui.dmz_ragnarok.npc.class_edit.subtitle", className), trAll(SECTIONS), section, this::selectSection);
        tf( tr("gui.dmz_ragnarok.npc.raceclassstats.class_name"), className, this::rename);
        tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_this_class_s_id_with"));
        // The field above is the ID. These two are what a PLAYER sees, and without them a custom class showed the
        // raw lang key on the character screen and had no blurb at all. Pre-filled from the class config, falling
        // back to whatever is already in the lang overlay so a class named before these fields existed still shows
        // its name here rather than looking unset.
        tf( tr("gui.dmz_ragnarok.npc.raceclassstats.display_name"),
                !cs.displayName.isBlank() ? cs.displayName : GeneratedLang.className(className),
                v -> cs.displayName = v.trim());
        tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_shown_on_the_charact"));
        tf( tr("gui.dmz_ragnarok.npc.raceclassstats.class_desc"),
                !cs.description.isBlank() ? cs.description : GeneratedLang.classDesc(className),
                v -> cs.description = v.trim());
        tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_the_blurb_under_the"));
        buildSection();

        // Commit on the way out; if a stat field rejected its text, stay put so the admin sees the banner
        // rather than carrying the old value back to the race screen unnoticed.
        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> {
            applyFields();
            if (hasFieldWarning()) {
                rebuildWidgets();
                return;
            }
            back();
        });
    }

    private void selectSection(int i) {
        applyFields();
        section = i;
        rebuildWidgets();
    }

    private void buildSection() {
        switch (section) {
            case 0 -> {
                statField(tr("gui.dmz_ragnarok.npc.raceclassstats.str"), cs.str, v -> cs.str = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_starting_strength_fo"));
                statField(tr("gui.dmz_ragnarok.npc.raceclassstats.skp"), cs.skp, v -> cs.skp = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_starting_skill_power"));
                statField(tr("gui.dmz_ragnarok.npc.raceclassstats.res"), cs.res, v -> cs.res = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_starting_resistance"));
                statField(tr("gui.dmz_ragnarok.npc.raceclassstats.vit"), cs.vit, v -> cs.vit = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_starting_vitality_he"));
                statField(tr("gui.dmz_ragnarok.npc.raceclassstats.pwr"), cs.pwr, v -> cs.pwr = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_starting_power_ki_da"));
                statField(tr("gui.dmz_ragnarok.npc.raceclassstats.ene"), cs.ene, v -> cs.ene = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_starting_energy_max"));
            }
            case 1 -> {
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.str_scaling"), cs.strScaling, v -> cs.strScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_how_much_each_streng"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.skp_scaling"), cs.skpScaling, v -> cs.skpScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_growth_multiplier_pe"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.stm_scaling"), cs.stmScaling, v -> cs.stmScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_growth_multiplier_pe_2"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.def_scaling"), cs.defScaling, v -> cs.defScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_growth_multiplier_pe_3"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.vit_scaling"), cs.vitScaling, v -> cs.vitScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_growth_multiplier_pe_4"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.pwr_scaling"), cs.pwrScaling, v -> cs.pwrScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_growth_multiplier_pe_5"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.ene_scaling"), cs.eneScaling, v -> cs.eneScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_growth_multiplier_pe_6"));
            }
            case 2 -> {
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.base_hp_5s"), cs.baseHp5, v -> cs.baseHp5 = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_health_regenerated_e"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.hp_5s_vit_scaling"), cs.hp5VitScaling, v -> cs.hp5VitScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_extra_hp_5s_per_poin"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.base_ep_5s"), cs.baseEp5, v -> cs.baseEp5 = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_ki_energy_regenerate"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.ep_5s_ene_scaling"), cs.ep5EneScaling, v -> cs.ep5EneScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_extra_ki_5s_per_poin"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.base_sp_5s"), cs.baseSp5, v -> cs.baseSp5 = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_stamina_regenerated"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.sp_5s_stm_scaling"), cs.sp5StmScaling, v -> cs.sp5StmScaling = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_extra_stamina_5s_per"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.tp_cost_x"), cs.tpCostMultiplier, v -> cs.tpCostMultiplier = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_multiplier_on_traini"));
                scaleField(tr("gui.dmz_ragnarok.npc.raceclassstats.tp_gain_x"), cs.tpGainMultiplier, v -> cs.tpGainMultiplier = v);
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_multiplier_on_traini_2"));
            }
            case 3 -> {
                bf( tr("gui.dmz_ragnarok.npc.raceclassstats.passive_enabled"), passiveEnabled(), () -> cs.passive.addProperty("enabled", !passiveEnabled()));
                tip( tr("gui.dmz_ragnarok.npc.raceclassstats.t_whether_this_class_s"));
                // The fixed menu. Every entry maps onto a hook DragonMineZ already calls, which is why they are
                // these and not a freer list: an effect with no hook behind it would do nothing however it was
                // configured. 0 means "no effect" throughout, so a blank passive changes nothing.
                for (String key : net.shurui.dev.sdu.passive.SduClassPassive.MENU) {
                    scaleField(tr("gui.dmz_ragnarok.npc.classpassive." + key), passiveValue(key),
                            v -> setPassiveValue(key, v));
                    tip( tr("gui.dmz_ragnarok.npc.classpassive.t_" + key));
                }
            }
            default -> { }
        }
    }

    /** One configured passive number, 0 when unset. */
    private double passiveValue(String key) {
        if (cs.passive == null || !cs.passive.has("values") || !cs.passive.get("values").isJsonObject()) {
            return 0.0;
        }
        return GsonHelper.getAsDouble(cs.passive.getAsJsonObject("values"), key, 0.0);
    }

    /**
     * Write a passive number, REMOVING it at 0.
     *
     * <p>Removing rather than storing a zero keeps the config to what an admin actually set, and matters for more
     * than tidiness: DMZ reads these with a per-key fallback, so a stored 0 and an absent key are only equivalent
     * while every fallback happens to be 0. Leaving the key out lets the fallback mean what it says.
     */
    private void setPassiveValue(String key, double value) {
        if (cs.passive == null) {
            cs.passive = new com.google.gson.JsonObject();
        }
        com.google.gson.JsonObject values =
                cs.passive.has("values") && cs.passive.get("values").isJsonObject()
                        ? cs.passive.getAsJsonObject("values")
                        : new com.google.gson.JsonObject();
        if (value == 0.0) {
            values.remove(key);
        } else {
            values.addProperty(key, value);
        }
        cs.passive.add("values", values);
    }

    private boolean passiveEnabled() {
        return cs.passive != null && GsonHelper.getAsBoolean(cs.passive, "enabled", true);
    }

    /**
     * A whole-number stat row. The visible parser keeps the last good value AND arms the on-screen warning
     * when the typed text isn't a whole number, so a fat-fingered "5o" no longer looks like a silent revert.
     */
    private void statField(String label, int current, java.util.function.IntConsumer setter) {
        tf(label, intStr(current), v -> setter.accept(parseIntField(label, v, current)));
    }

    /** A decimal row (scaling / regen / TP multiplier), same visible-failure handling as {@link #statField}. */
    private void scaleField(String label, double current, java.util.function.DoubleConsumer setter) {
        tf(label, dbl(current), v -> setter.accept(parseDoubleField(label, v, current)));
    }
}

package net.shurui.dev.sdu.client.gui.radial;

import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.client.gui.radial.nodes.CategoryNode;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * sdu replacement for DMZ's {@code MoreFormsNode} in base radial slot 1. Same name/icon as the stock "Extra
 * Forms" wheel, but it adds an intermediate <b>type</b> tier the base game lacks: opening it shows one
 * {@link FormTypeNode} per non-(super/legendary/android) form type present for the player's race, and each
 * type node expands into that type's unlocked groups/forms.
 *
 * <p>The super/legendary/android types are deliberately excluded here because they belong to the Super wheel
 * (base slot 0); the split mirrors DMZ's own {@code RadialForms.moreForms} predicate. This is a
 * <b>locked-preview</b> wheel: {@link FormTypeNode} expands into <em>every</em> form of each group, greying
 * out the ones the player hasn't unlocked (non-interactive) rather than hiding them, so the section shows
 * even when nothing is unlocked. The server still re-validates any transform, so no unowned form can be
 * entered from here.
 */
public final class SduExtraFormsCategory extends CategoryNode {

    public SduExtraFormsCategory() {
        // Reuse DMZ's key + icon so the slot stays named/looks like the stock "Extra Forms" node.
        super(Component.translatable("gui.dragonminez.radial.extraforms"), icon("godforms"));
    }

    /**
     * Keep the slot on the wheel even with nothing to put in it, when the player has asked for that.
     *
     * <p>{@code CategoryNode.visible} is true only while at least one CHILD is visible, so the Extra Forms slot
     * disappears entirely for a race that has no non-super form types. That is the right default (an empty ring is
     * a dead click) but it means the wheel is laid out differently from one race to the next, and someone who has
     * learned where the slot sits finds it moved. The opt-in trades an occasional empty ring for a wheel that is
     * always in the same order.
     *
     * <p>Client config, because this is purely about what is drawn on one player's own screen.
     */
    @Override
    public boolean visible(StatsData stats) {
        return net.shurui.dev.sdu.client.ClientConfig.alwaysShowExtraFormsTab || super.visible(stats);
    }

    @Override
    protected List<RadialNode> buildChildren(StatsData stats) {
        String race = stats.getCharacter().getRaceName();
        // Seed custom form types from the already-synced DMZ form configs BEFORE building the wheel, so a
        // custom formType containing a reserved substring (e.g. saiyangod / truegod) routes to its OWN skill
        // via TransformationsHelperMixin - correcting its locked/unlocked determination and icon - regardless
        // of whether sdu's own meta packet has arrived yet.
        seedCustomFormTypesFromConfig(race);
        // Bucket the race's form groups by lowercased type, preserving discovery order per type.
        Map<String, List<String>> byType = new LinkedHashMap<>();
        Map<String, FormConfig> groups = ConfigManager.getAllFormsForRace(race);
        if (groups != null) {
            for (String group : groups.keySet()) {
                FormConfig config = ConfigManager.getFormGroup(race, group);
                if (config == null) {
                    continue;
                }
                String type = config.getFormType() != null
                        ? config.getFormType().toLowerCase(Locale.ROOT) : "";
                // Same partition as DMZ's moreForms wheel: super/legendary/android belong to the Super wheel.
                if (type.isEmpty()
                        || type.contains("super") || type.contains("legendary") || type.contains("android")) {
                    continue;
                }
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(group);
            }
        }

        List<String> types = new ArrayList<>(byType.keySet());
        // Default ordering (used until the player reorders): known stock non-super types first
        // (god, kaioken, ultimate), then any custom types alphabetically. A saved order layered on top by
        // RadialOrdering below takes precedence.
        types.sort(Comparator
                .comparingInt(SduExtraFormsCategory::stockRank)
                .thenComparing(t -> t, String.CASE_INSENSITIVE_ORDER));

        List<RadialNode> out = new ArrayList<>();
        for (String type : types) {
            FormTypeNode node = new FormTypeNode(race, type, byType.get(type));
            // In preview mode a type is visible as long as it has any resolvable group, so this keeps the
            // section showing even when nothing of that type is unlocked (its forms just render greyed).
            if (node.visible(stats)) {
                out.add(node);
            }
        }
        // New top-level TYPE ring: no legacy order to inherit, so it gets its own stable key through DMZ's
        // store. FormTypeNode.orderKey() ("formtype:<type>") is what the reorder UI persists under this key.
        return RadialOrdering.orderAndCap("extraforms:types", out);
    }

    /**
     * Register every non-stock {@code formType} used by the player's currently-loaded (synced) form groups as
     * a custom type in {@link net.shurui.dev.sdu.form.CustomFormTypes}. This is the robustness fix (audit B):
     * DMZ's substring routing sends a custom type containing {@code god}/{@code super}/{@code legendary}/
     * {@code android} to the stock skill unless {@code CustomFormTypes} knows it's custom - and that set was
     * previously seeded only from sdu's own meta packet (fragile, rejoin-only). Seeding from the synced
     * configs here makes routing (and therefore locked/unlocked determination + icons) correct at build time,
     * independent of the meta packet. Stock ids are filtered by {@code CustomFormTypes.register} itself.
     */
    private static void seedCustomFormTypesFromConfig(String race) {
        try {
            Map<String, FormConfig> groups = race != null && !race.isEmpty()
                    ? ConfigManager.getAllFormsForRace(race) : null;
            if (groups != null && !groups.isEmpty()) {
                for (FormConfig config : groups.values()) {
                    if (config != null && config.getFormType() != null) {
                        net.shurui.dev.sdu.form.CustomFormTypes.register(config.getFormType());
                    }
                }
            }
            // Also union in every other race's types, so a custom type on a group we won't build for this race
            // is still routed correctly wherever DMZ consults it (ascension gating, skills screen, ...).
            seedFromAllForms();
        } catch (Throwable ignored) {
            // Defensive: routing degrades to DMZ's stock behaviour, exactly as before this seed existed.
        }
    }

    /** Union of every loaded group's formType across all races (also the fallback when the race lookup is empty). */
    private static void seedFromAllForms() {
        try {
            var all = ConfigManager.getAllForms();
            if (all == null) {
                return;
            }
            for (Map<String, FormConfig> byGroup : all.values()) {
                if (byGroup == null) {
                    continue;
                }
                for (FormConfig config : byGroup.values()) {
                    if (config != null && config.getFormType() != null) {
                        net.shurui.dev.sdu.form.CustomFormTypes.register(config.getFormType());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int stockRank(String type) {
        if (type.contains("god")) {
            return 0;
        }
        if (type.contains("kaioken")) {
            return 1;
        }
        if (type.contains("ultimate")) {
            return 2;
        }
        return 3;
    }
}

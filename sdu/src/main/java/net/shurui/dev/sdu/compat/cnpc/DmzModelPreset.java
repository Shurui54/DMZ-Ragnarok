package net.shurui.dev.sdu.compat.cnpc;

import java.util.ArrayList;
import java.util.List;

/**
 * A one-click DMZ model preset for the {@code DMZ NPC} tab (feature 4.6): geo model, animation set,
 * state-&gt;clip mapping and texture that reproduce a DMZ "saga" NPC. {@link CnpcGeckoBridge#applyDmzModel}
 * feeds it into the NPC's {@link com.goodbird.cnpcgeckoaddon.data.CustomModelData} + skin.
 *
 * @param geo         DMZ geo resource location (e.g. {@code dragonminez:geo/entity/sagas/saga_raditz.geo.json})
 * @param hurt        hurt clip name (may be empty; {@code saga_base} has no dedicated hurt clip)
 * @param texture     applied as the NPC skin
 */
public record DmzModelPreset(String id, String displayName, String geo, String animation,
                             String idle, String walk, String attack, String hurt, String texture) {

    /**
     * Display names in {@link DmzModelPresets#all()} order, the picker list. Returns a <em>mutable</em>
     * {@link ArrayList}: Custom NPCs' {@code GuiStringSelection.init()} sorts in place, which throws on an
     * immutable list ({@code Stream.toList()}).
     */
    public static List<String> displayNames(List<DmzModelPreset> presets) {
        List<String> names = new ArrayList<>(presets.size());
        for (DmzModelPreset p : presets) {
            names.add(p.displayName());
        }
        return names;
    }
}

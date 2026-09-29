package net.shurui.dev.sdu.compat.cnpc;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzCompat;

/**
 * Entry point + dependency gate for the DMZ x Custom NPCs compat layer.
 *
 * <p>Soft dependency on both the CustomNPCs-Unofficial 1.20.1 port ({@code customnpcs}) and the
 * CNPC-Gecko-Addon ({@code cnpcgeckoaddon}). If either is absent SDU still loads; the DMZ-NPC tab, model/
 * animation feeding, skin bridge and saga-clone spawning just disable themselves with one warning.
 *
 * <p>Every CNPC/addon symbol lives in {@code compat.cnpc} and is only reached after a dep check passes, so
 * classloading is deferred. DMZ access still routes through {@link DmzCompat}.
 *
 * <p>Features (see PROMPT_dmz_customnpc_compat.md): 4.1 DMZ NPC editor tab; 4.2 append DMZ GeckoLib anims;
 * 4.3 DMZ move sets; 4.4 AI level (AITier); 4.5 player/URL skins; 4.6 model preset picker; 4.7 sagas spawn
 * CNPC clones.
 */
public final class DmzCnpcCompat {

    /** CustomNPCs-Unofficial 1.20.1 port: the {@code noppes.npcs.*} stack. */
    public static final String CUSTOMNPCS_MODID = "customnpcs";
    /** CNPC-Gecko-Addon: {@code com.goodbird.cnpcgeckoaddon.*}, supplies the GeckoLib NPC model. */
    public static final String CNPC_GECKO_MODID = "cnpcgeckoaddon";

    private static boolean initialised = false;
    private static boolean cnpcPresent = false;
    private static boolean geckoAddonPresent = false;

    private DmzCnpcCompat() {
    }

    /** CNPC port installed. Features needing only the NPC entity gate on this. */
    public static boolean cnpcAvailable() {
        return cnpcPresent;
    }

    /** Gecko addon installed. Model/animation feeding (4.2/4.6) gates on this. */
    public static boolean geckoAddonAvailable() {
        return geckoAddonPresent;
    }

    /** DMZ + CNPC port + Gecko addon all present: the full bridge is usable. */
    public static boolean fullyAvailable() {
        return DmzCompat.isLoaded() && cnpcPresent && geckoAddonPresent;
    }

    /**
     * Resolve dependency presence. Safe to call unconditionally from mod construction: only records which
     * optional mods loaded and logs the mode. Hooks register lazily behind {@link #cnpcAvailable()}/
     * {@link #geckoAddonAvailable()}, so a missing dep is a no-op.
     */
    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        cnpcPresent = ModList.get().isLoaded(CUSTOMNPCS_MODID);
        geckoAddonPresent = ModList.get().isLoaded(CNPC_GECKO_MODID);

        if (!cnpcPresent) {
            DmzNpc.LOGGER.info("[{}] Custom NPCs ('{}') not present - DMZ x Custom NPCs bridge disabled.",
                    DmzNpc.MODID, CUSTOMNPCS_MODID);
            return;
        }
        if (!geckoAddonPresent) {
            DmzNpc.LOGGER.warn("[{}] Custom NPCs present but CNPC-Gecko-Addon ('{}') is not - DMZ model/animation "
                    + "and saga-clone features are disabled; the rest of SDU is unaffected.",
                    DmzNpc.MODID, CNPC_GECKO_MODID);
            return;
        }

        // Put Custom NPCs onto SDU's own player-shaped models as they load. Registered HERE, after both deps
        // are confirmed present, so the handler class (which references CNPC and addon types) only classloads
        // when they exist.
        MinecraftForge.EVENT_BUS.register(CnpcCitizenModels.class);

        DmzNpc.LOGGER.info("[{}] DMZ x Custom NPCs bridge enabled (customnpcs + cnpcgeckoaddon + dragonminez={}).",
                DmzNpc.MODID, DmzCompat.isLoaded());
    }
}

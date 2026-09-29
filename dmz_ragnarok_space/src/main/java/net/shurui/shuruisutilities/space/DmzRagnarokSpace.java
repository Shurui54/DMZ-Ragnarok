package net.shurui.shuruisutilities.space;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;

// DMZ Ragnarok: Space module.
//
// Its own @Mod container in the core-plus-modules split: this loads either as its own jar or as one of the
// mods declared by the fat jar. Either way it depends on core (dmz_ragnarok, mandatory, loads AFTER it) and
// registers ALL of its content into the dmz_ragnarok namespace, so an existing world is unchanged whether the
// Space content arrives via its own jar or the fat jar.
//
// Space keeps ALL of its dimension / dimension_type / biome / noise JSON, worldgen, damage types, assets and the
// planet region seed data IN CORE (dmz_ragnarok), on purpose: level.dat and the WorldgenShim copy worldgen from
// the core mod file, and PlanetRegionSeeder reads it too, so those must stay byte-identical in the core jar. This
// module ships only the Space Java (and its own mixin config + refmap).
@Mod(DmzRagnarokSpace.CONTAINER_ID)
public class DmzRagnarokSpace {

    // The FORGE mod-container id. This is the id in mods.toml / @Mod / @Mod.EventBusSubscriber, and what
    // "is this module present" checks look for (net.shurui.dev.sdu.api.ModulePresence.SPACE). DISTINCT from MODID.
    public static final String CONTAINER_ID = "dmz_ragnarok_space";

    // The REGISTRY / ASSET / DATA namespace. Stays "dmz_ragnarok" so this module's entities, items, dimensions and
    // assets keep the exact ids they have on disk today. NEVER change this value.
    public static final String MODID = "dmz_ragnarok";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DmzRagnarokSpace() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Space entity types (still in the dmz_ragnarok namespace), attached to THIS module's mod bus. Attribute
        // creation + renderer registration ride the mod bus too, via the @Mod.EventBusSubscriber classes in this
        // tree (now under modid dmz_ragnarok_space), so they fire on this container's bus.
        PlanetDefenderEntities.ENTITY_TYPES.register(modEventBus);
        PlanetGarrisonDefenderEntities.ENTITY_TYPES.register(modEventBus);
        PlanetSaiyanGarrisonEntities.ENTITY_TYPES.register(modEventBus);
        PlanetSaiyanTownEntities.ENTITY_TYPES.register(modEventBus);
        SuperBallEntities.ENTITY_TYPES.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        // Install the core SpaceHook provider so guilds can read planet claims and the raid arena without naming the
        // Space classes. With Space absent (a modular set without it) the hook stays unset and a guild planet raid
        // refuses to start, like a rift without Dungeons. The @SUModule classes in this tree are discovered by SU's
        // ModuleLauncher across all jars (ModList.getAllScanData), so they do not depend on this hook being set.
        SpaceHookImpl.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[{}] common setup - DMZ Ragnarok Space module loading", CONTAINER_ID);
        // Register the Space packets on core's shared SU channel (dmz_ragnarok:fe-network) with the SAME explicit
        // ids they carried in core (49, 50, 52, 53, 54, 58, 61). NetworkUtils keys each message by an explicit id and
        // dedups on a shared static set, so core's other ids never shift and the wire is identical fat vs modular.
        event.enqueueWork(DmzRagnarokSpace::registerSpaceNetworkMessages);
    }

    private static void registerSpaceNetworkMessages() {
        // space planet-select opener: query the current tracked planet (clear=false) or stop tracking it (clear=true).
        NetworkUtils.registerClientToServer(49, PacketSpaceCourse.class,
                PacketSpaceCourse::encode, PacketSpaceCourse::decode, PacketSpaceCourse::handler);
        // space layout sync: the client re-derives and draws every space body from the same layout inputs the server uses.
        NetworkUtils.registerServerToClient(50, PacketSpaceLayoutSync.class,
                PacketSpaceLayoutSync::encode, PacketSpaceLayoutSync::decode, PacketSpaceLayoutSync::handler);
        // planet DOOM sequence phase marker: the visual half of a planet bust.
        NetworkUtils.registerServerToClient(52, PacketPlanetDoom.class,
                PacketPlanetDoom::encode, PacketPlanetDoom::decode, PacketPlanetDoom::handler);
        // planet-info GUI: keypress asks (C2S), server pushes the authoritative view (S2C).
        NetworkUtils.registerClientToServer(53, PacketPlanetInfoRequest.class,
                PacketPlanetInfoRequest::encode, PacketPlanetInfoRequest::decode, PacketPlanetInfoRequest::handler);
        NetworkUtils.registerServerToClient(54, PacketPlanetInfoGui.class,
                PacketPlanetInfoGui::encode, PacketPlanetInfoGui::decode, PacketPlanetInfoGui::handler);
        // planet-clash cinematic marker: reframes DMZ's clash camera onto the clash point.
        NetworkUtils.registerServerToClient(58, PacketPlanetClashCam.class,
                PacketPlanetClashCam::encode, PacketPlanetClashCam::decode, PacketPlanetClashCam::handler);
        // space-pod autopilot sync: the controlling client integrates the pod toward the synced target.
        NetworkUtils.registerServerToClient(61, PacketSpaceAutopilotSync.class,
                PacketSpaceAutopilotSync::encode, PacketSpaceAutopilotSync::decode, PacketSpaceAutopilotSync::handler);
    }
}

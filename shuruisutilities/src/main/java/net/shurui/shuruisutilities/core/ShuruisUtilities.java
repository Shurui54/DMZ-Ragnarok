package net.shurui.shuruisutilities.core;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Level;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.commons.BuildInfo;
import net.shurui.shuruisutilities.commons.events.RegisterPacketEvent;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.commons.network.packets.Packet01SelectionUpdate;
import net.shurui.shuruisutilities.commons.network.packets.Packet03PlayerPermissions;
import net.shurui.shuruisutilities.commons.network.packets.Packet05Noclip;
import net.shurui.shuruisutilities.compat.BaublesCompat;
import net.shurui.shuruisutilities.compat.HelpFixer;
import net.shurui.shuruisutilities.compat.worldedit.WEIntegration;
import net.shurui.shuruisutilities.core.commands.CommandSUInfo;
import net.shurui.shuruisutilities.core.commands.CommandSUWorldInfo;
import net.shurui.shuruisutilities.core.commands.CommandSuReload;
import net.shurui.shuruisutilities.core.commands.CommandSuSettings;
import net.shurui.shuruisutilities.core.commands.CommandTest;
import net.shurui.shuruisutilities.core.commands.CommandUuid;
import net.shurui.shuruisutilities.core.commands.registration.SUCommandManager;
import net.shurui.shuruisutilities.core.config.ConfigBase;
import net.shurui.shuruisutilities.core.environment.Environment;
import net.shurui.shuruisutilities.core.misc.BlockModListFile;
import net.shurui.shuruisutilities.core.misc.CommandPermissionManager;
import net.shurui.shuruisutilities.core.misc.Packet0HandshakeHandler;
import net.shurui.shuruisutilities.core.misc.RespawnHandler;
import net.shurui.shuruisutilities.core.misc.TaskRegistry;
import net.shurui.shuruisutilities.core.misc.TeleportHelper;
import net.shurui.shuruisutilities.core.misc.Translator;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule.Instance;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.protection.ProtectionPerms;
import net.shurui.shuruisutilities.util.CommandUtils;
import net.shurui.shuruisutilities.util.CommandUtils.CommandInfo;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.ServerUtil;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerAboutToStartEvent;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartedEvent;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartingEvent;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStoppedEvent;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStoppingEvent;
import net.shurui.shuruisutilities.util.events.player.PlayerPositionEventFactory;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.util.questioner.Questioner;
import net.shurui.shuruisutilities.util.selections.CommandDeselect;
import net.shurui.shuruisutilities.util.selections.CommandExpand;
import net.shurui.shuruisutilities.util.selections.CommandExpandY;
import net.shurui.shuruisutilities.util.selections.CommandPos1;
import net.shurui.shuruisutilities.util.selections.CommandPos2;
import net.shurui.shuruisutilities.util.selections.CommandWand;
import net.shurui.shuruisutilities.util.selections.SelectionHandler;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;

// @Mod removed in slice 1: this class is now constructed by net.shurui.dev.ragnarok.DmzRagnarok, the
// single container. The Mod import and the @Mod.EventBusSubscriber below are kept (event-bus
// annotations are slice 2); MODID, MOD_CONTAINER caching and all constructor logic are unchanged.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public class ShuruisUtilities
{

    public static final String MODID = "dmz_ragnarok";
    public static final int CURRENT_MODULE_VERSION =1;

    public static final String SU_DIRECTORY = "ShuruisUtilities";
    @Instance
    public static ShuruisUtilities instance;
    public static IEventBus modMain;
    public static ModContainer MOD_CONTAINER;

    public static Random rnd = new Random();

    public static final String PERM = "su";
    public static final String PERM_CORE = PERM + ".core";
    public static final String PERM_INFO = PERM_CORE + ".info";
    public static final String PERM_RELOAD = PERM_CORE + ".reload";
    public static final String PERM_VERSIONINFO = PERM_CORE + ".versioninfo";

    /* ShuruisUtilities core submodules */

    protected static ConfigBase configManager;

    protected static ModuleLauncher moduleLauncher;

    protected static TaskRegistry tasks;

    protected static PlayerPositionEventFactory factory;

    protected static TeleportHelper teleportHelper;

    protected static Questioner questioner;

    protected static SUCommandManager commandManager;

    // prestige forge-bus handler (registered at preInit when Prestige is on).
    private static net.shurui.shuruisutilities.prestige.PrestigeEvents prestigeEvents;

    private static File suDirectory;

    // private static File moduleDirectory;

    private static File jarLocation;

    protected static boolean debugMode = true;

    protected static boolean safeMode = false;

    protected static boolean logCommandsToConsole;

    private RespawnHandler respawnHandler;

    private SelectionHandler selectionHandler;

    public static boolean isCubicChunksInstalled = false;

    public ShuruisUtilities()
    {
        LoggingHandler.init();
        LoggingHandler.sulog.info("ShuruisUtilitiesInt");
        instance = this;
        // Set mod as server only
        MOD_CONTAINER = ModLoadingContext.get().getActiveContainer();
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY,
                        (a, b) -> true));
        modMain = FMLJavaModLoadingContext.get().getModEventBus();
        // Custom menu types for the DMZ-styled chest GUIs (crate/trade/permissions).
        net.shurui.shuruisutilities.client.gui.SUMenus.REGISTER.register(modMain);
        // Colored portal fill blocks + the end-portal block entity.
        net.shurui.shuruisutilities.block.SUBlocks.REGISTER.register(modMain);
        // crate blocks before the block entity types, so the type's block list resolves.
        net.shurui.shuruisutilities.crate.block.SuCrateBlocks.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.crate.block.SuCrateBlocks.ITEMS.register(modMain);
        net.shurui.shuruisutilities.block.SUBlockEntities.REGISTER.register(modMain);
        // Rideable hoverbikes: entity type, four spawn items, per-variant rev sounds, creative tab.
        net.shurui.shuruisutilities.hoverbike.HoverbikeEntities.REGISTER.register(modMain);
        net.shurui.shuruisutilities.hoverbike.HoverbikeItems.REGISTER.register(modMain);
        net.shurui.shuruisutilities.hoverbike.HoverbikeSounds.REGISTER.register(modMain);
        // Dragon ball bag: a Curios-equippable 7-slot pouch (one per star) that dragon balls route into on pickup.
        // Its menu type registers via SUMenus above; this is just the item. Rules live in DragonBallInventoryHandler
        // (forge bus, registered at preInit) plus the Slot.mayPlace containment mixin.
        net.shurui.shuruisutilities.dragonballbag.DragonBallBagItems.REGISTER.register(modMain);
        // Senzu bean bag: a Curios-equippable 9-slot bean pouch (the ex-placeholder "senzubag" given real behaviour).
        // Its menu type registers via SUMenus above; this is just the item. Two keybinds (open GUI / pull one bean)
        // drive it, both server-authoritative through packets 56 and 57.
        net.shurui.shuruisutilities.senzu.bag.SenzuBagItems.REGISTER.register(modMain);
        net.shurui.shuruisutilities.runes.RuneItems.REGISTER.register(modMain);
        net.shurui.shuruisutilities.runes.RuneRecipes.REGISTER.register(modMain);
        net.shurui.shuruisutilities.runes.RuneBenchRegistry.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.runes.RuneBenchRegistry.ITEMS.register(modMain);
        net.shurui.shuruisutilities.runes.RuneBenchRegistry.MENUS.register(modMain);
        // Admin "Deletion Wand": item registration + its forge-bus deletion handler.
        net.shurui.shuruisutilities.deletionwand.DeletionWandItems.REGISTER.register(modMain);
        // Data-driven "rgnpc" GeckoLib display NPC: one entity type that renders any of the installed models
        // (model/texture/scale synced per entity). Attributes + renderer register on the mod bus via
        // @Mod.EventBusSubscriber (RgNpcEntities / RgNpcClientEvents); the pre-rename id remap is a forge-bus
        // subscriber (RgNpcEntities.Remap).
        net.shurui.shuruisutilities.ragnarok.RgNpcEntities.ENTITY_TYPES.register(modMain);
        // The same characters on a DragonMineZ saga chassis, so one picked in an editor fights and animates
        // instead of standing there. The display NPC above stays for decoration; see RgNpcFighterEntity.
        net.shurui.shuruisutilities.ragnarok.RgNpcFighterEntities.ENTITY_TYPES.register(modMain);
        // Same model table, published to sdu's Custom NPCs model editor so a Custom NPC can wear a ragnarok
        // character (geo + its texture + its baked hitbox) picked by name instead of by raw geo path.
        net.shurui.shuruisutilities.ragnarok.RgNpcCnpcCatalog.install();
        // Unified content: the 233 shared items and the five creative tabs (gems_souls, dragon_balls,
        // equipment, consumables, blocks_misc), owned by SU. SU's own hoverbikes go into equipment and the
        // deletion wand into blocks_misc directly (see ContentTabs); sibling addons insert into these tabs.
        net.shurui.shuruisutilities.content.ContentItems.ITEMS.register(modMain);
        net.shurui.shuruisutilities.content.ContentTabs.CREATIVE_MODE_TABS.register(modMain);
        // Bundled Halloween cosmetic art items. Registered unconditionally (a registry must match on both ends of
        // a connection); the key tier and the Cosmetics switch gate wearing and spawning, not existence.
        net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticContentItems.ITEMS.register(modMain);
        // The one cosmetic mount entity type, rig picked by a synched mount id (like the hoverbike's variant).
        // Registered unconditionally so it exists on both ends of a connection; the renderer registers on the mod
        // bus (CosmeticMountClientEvents) and summoning is gated at the summon call, not at registration.
        net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountEntities.ENTITY_TYPES.register(modMain);
        // The one cosmetic pet entity type, rig picked by a synched pet id (like the mount). Registered
        // unconditionally so it exists on both ends of a connection; the renderer registers on the mod bus
        // (CosmeticPetClientEvents) and spawning is gated at the spawn call, not at registration.
        net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetEntities.ENTITY_TYPES.register(modMain);
        // Per-mount SFX (summon, dismiss, idle, move): a mount replaces a vehicle, so these play in the vehicle's
        // place. Registered unconditionally alongside the entity type, for the same both-ends reason.
        net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountSounds.REGISTER.register(modMain);
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.deletionwand.DeletionWandHandler());
        // Wish-tracking swap set: block + item registration.
        net.shurui.shuruisutilities.corrupted.CorruptedBalls.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.corrupted.CorruptedBalls.ITEMS.register(modMain);
        net.shurui.shuruisutilities.combat.DashCooldownEffect.EFFECTS.register(modMain);
        // The bounded generator's codec. Registered like any other content, but note it is APPEND ONLY: a world
        // that has loaded a dimension using it names it in level.dat for ever.
        net.shurui.shuruisutilities.worldgen.SuChunkGenerators.REGISTER.register(modMain);
        // Marks a victim as standing in Haze Shenron's gas; drives the purple screen overlay client-side.
        net.shurui.shuruisutilities.dragons.PollutedEffect.EFFECTS.register(modMain);
        // Marks a victim as standing in Nuova Shenron's heat; drives the orange screen shimmer.
        net.shurui.shuruisutilities.dragons.ScorchedEffect.EFFECTS.register(modMain);
        // The Angel's soulbound staff.
        net.shurui.shuruisutilities.god.AngelItems.REGISTER.register(modMain);
        net.shurui.shuruisutilities.ritual.ShenronIdol.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.ritual.ShenronIdol.ITEMS.register(modMain);
        net.shurui.shuruisutilities.ritual.ShenronIdol.BLOCK_ENTITIES.register(modMain);
        net.shurui.shuruisutilities.corrupted.ShadowDragonSounds.REGISTER.register(modMain);
        // The corrupted cinematic's custom shadow shenron prop entity.
        net.shurui.shuruisutilities.corrupted.ShadowShenronEntities.REGISTER.register(modMain);
        // Tamed saibaman companion: a vanilla TamableAnimal wearing DragonMineZ's saibaman art. Attributes +
        // renderer register on the mod bus (SaibamanPetEntities / SaibamanPetClientBusEvents); stats live in
        // SaibamanPet.toml via the @SUModule ModuleSaibamanPet and are edited from the admin hub.
        net.shurui.shuruisutilities.saibaman.SaibamanPetEntities.ENTITY_TYPES.register(modMain);
        // Mini clone technique companion: a vanilla TamableAnimal summoned by su_mini_clone. Attributes register on
        // the mod bus (MiniCloneEntities); the technique itself is registered by DragonTechniqueBridge, its cast seam
        // is the dispatcher mixin, and the per-owner registry is MiniCloneRegistry's @EventBusSubscriber. There is no
        // unlock path in code on purpose: the move is handed out as a saga TECHNIQUE reward, which reaches it through
        // PredefinedTechniques.REGISTRY like any other grantable technique.
        net.shurui.shuruisutilities.clone.MiniCloneEntities.ENTITY_TYPES.register(modMain);
        // The saibaman seed crop: seed item, ground crop block, and the crop's tick-counter block-entity type. The
        // seed is slotted into the CONSUMABLES creative tab first (like the senzu seeds) so it lands after the
        // ContentItems placeholders; the crop grows on rocky_dirt and is harvested into a tamed pet via spawnTamed.
        net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.slotIntoTabs();
        net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.ITEMS.register(modMain);
        net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.BLOCK_ENTITIES.register(modMain);
        // Senzu-bean farming: bean/seed items, blank + eight typed pots, and the shared pot block-entity type.
        // Touching SenzuRegistry.ITEMS runs its static block first (registerBeans/Seeds/Pots), which also slots each
        // item into the right ContentItems creative-tab list, so the three DeferredRegisters are fully populated
        // before they attach to the mod bus here. The golden-totem death handler + config live in SenzuModule (an
        // @SUModule the module launcher auto-registers on the Forge bus).
        net.shurui.shuruisutilities.senzu.SenzuRegistry.ITEMS.register(modMain);
        net.shurui.shuruisutilities.armor.RagnarokGiItems.ITEMS.register(modMain);
        net.shurui.shuruisutilities.senzu.SenzuRegistry.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.senzu.SenzuRegistry.BLOCK_ENTITIES.register(modMain);
        // Auction house: the placeable auction block + its BlockItem. Touching AuctionRegistry.BLOCKS runs its static
        // block, which slots the BlockItem into the ContentItems BLOCKS_MISC creative-tab list before the tab is built.
        // The auction logic/config/command live in the AuctionModule (@SUModule, auto-registered on the Forge bus).
        net.shurui.shuruisutilities.auction.AuctionRegistry.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.auction.AuctionRegistry.ITEMS.register(modMain);
        // Katchin material line: twelve blocks + their BlockItems (katchin ore/deepslate ore/block, plus the three
        // katchi katchin colours), and the tool/template items (ten tools, no sword, plus two smithing templates).
        // Blocks go into the blocks_misc creative tab and tools into equipment (see ContentTabs). The two tool tiers
        // are folded into the tier sorting registry in preInit; the 3x3 hammer break and the client colour property
        // are Forge/mod-bus @EventBusSubscribers (HammerBreakHandler / KatchinClientEvents).
        net.shurui.shuruisutilities.katchin.KatchinBlocks.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.katchin.KatchinBlocks.ITEMS.register(modMain);
        net.shurui.shuruisutilities.katchin.KatchinItems.ITEMS.register(modMain);
        // Decorative sci-fi space consoles: four horizontal-facing metal props (console, compact console, hologram
        // projector, module column) with emissive glow maps. Their BlockItems go into the blocks_misc creative tab.
        net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks.ITEMS.register(modMain);
        // Guild planet raids (phase 3): the defending-clone driver entity. One SU-owned DBSagasEntity subclass that
        // fights with a copied member's stats; a client puppet renders it with that member's true appearance.
        // Attributes + renderer register on the mod bus (GuildRaidCloneEntities / GuildRaidCloneClientEvents).
        net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntities.ENTITY_TYPES.register(modMain);
        // The four guild claim-upgrade consumables (+1/+5/+10/+25 chunks). Slotted into the CONSUMABLES creative
        // tab by ContentTabs; their effect is gated behind the same Guilds module checks as the /guild commands.
        net.shurui.shuruisutilities.guilds.ClaimUpgradeItems.ITEMS.register(modMain);
        // The suite's own particle types: a soft round dot in any colour, at any size, for any lifetime, with any
        // gravity, all four independent (vanilla ties scale to lifetime and darkens the colour). Two types, one
        // translucent and one additive. A particle type is a COMMON registry, so this attaches on both sides; the
        // client-only provider is RgParticleClientBusEvents.
        net.shurui.shuruisutilities.particle.RgParticles.REGISTER.register(modMain);

        // Guild gravity chamber: a guild-owned training block (gravity + range GUI, sparring menu). Touching its BLOCKS
        // register runs the static block that slots its BlockItem into the blocks_misc creative tab. Its sparring dummy
        // is an SU-owned DBSagasEntity (renderer via ChamberSparClientEvents), scaled from a chosen member's stats.
        net.shurui.shuruisutilities.gravitychamber.GuildGravityChamberBlocks.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.gravitychamber.GuildGravityChamberBlocks.ITEMS.register(modMain);
        net.shurui.shuruisutilities.gravitychamber.GuildGravityChamberBlocks.BLOCK_ENTITIES.register(modMain);
        net.shurui.shuruisutilities.gravitychamber.ChamberSparEntities.ENTITY_TYPES.register(modMain);

        // Space entity types (planet_defender, planet_garrison_defender, planet_garrison_saiyan,
        // planet_saiyan_citizen, planet_saiyan_trader, super_ball) are registered by the Space module's own @Mod
        // container (net.shurui.shuruisutilities.space.DmzRagnarokSpace), on ITS mod bus, still in the dmz_ragnarok
        // namespace. With Space absent (a modular set without it) they simply do not register here.
        // Trunks' time machine: a rideable, flying SU vehicle that enables space travel like the space pod (deployed
        // from the shared "hoverbike" curios slot + toggle), and a decorative for-show block. Entity type, block +
        // BlockItem here; the chip item rides HoverbikeItems.REGISTER; the block-entity type is in SUBlockEntities;
        // renderers + input feeding are client-side (TimeMachineClientEvents).
        net.shurui.shuruisutilities.timemachine.TimeMachineEntities.ENTITY_TYPES.register(modMain);
        net.shurui.shuruisutilities.timemachine.TimeMachineBlocks.BLOCKS.register(modMain);
        net.shurui.shuruisutilities.timemachine.TimeMachineBlocks.ITEMS.register(modMain);
        // Z orbs: the z_orb entity type registers UNCONDITIONALLY (both suite outputs, key present or not) so client
        // and server always agree it exists. All behaviour is the private key's (via ZOrbHooks); keyless, a summoned
        // orb discards itself on its first tick. The renderer is bound client-side in ZOrbClientEvents.
        net.shurui.shuruisutilities.zorb.ZOrbEntities.ENTITY_TYPES.register(modMain);
        // Racing (Mario-Kart-style races on hoverbikes): the item box / ki orb / Saibaman entities, the boost pad /
        // item spawner / finish line blocks + their block items, the item spawner block entity, the track wand, and
        // the twelve race.* sound events all register UNCONDITIONALLY (both suite outputs, key present or not) so
        // client and server always agree these ids exist. All behaviour is the private key's (via RaceHooks);
        // keyless, a summoned race entity discards itself on its first tick and the blocks are inert markers. The
        // entity renderers are bound client-side in RaceClientEvents.
        net.shurui.shuruisutilities.racing.RaceRegistries.register(modMain);
        // Space bodies (planets, stars, black holes) are NOT entities: they are re-derived from the position hash and
        // drawn directly by the client (see SpaceDimensionEffects / SpaceBodyRenderer), so there is no entity type to
        // register here. Asteroids are real blocks. The hazards are re-derived server-side too (SpaceHazardModule).
        tasks = new TaskRegistry();

        // SU is now shipped inside the merged dmz_ragnarok container, so its jar is registered under that modId,
        // not "shuruisutilities". Look up the container to feed BuildInfo the jar path (getBuildInfo is null-safe).
        List<IModInfo> mods = ModList.get().getMods();
        for (IModInfo mod : mods)
        {
            if (mod.getModId().equals("dmz_ragnarok"))
            {
                jarLocation = mod.getOwningFile().getFile().getFilePath().toFile();
                break;
            }
        }
        BuildInfo.getBuildInfo(jarLocation);

        initConfiguration();
        Environment.check();
        // Let the event engine's bundle import store event NPC regions (template + eventOnly) from JSON, without
        // the key naming SU. No-op when nothing imports; only ever adds regions, never mutates an existing one.
        net.shurui.shuruisutilities.npcregion.NpcRegionImport.register();
        MinecraftForge.EVENT_BUS.register(this);
        modMain.addListener(this::preInit);
        modMain.addListener(this::postLoad);
        moduleLauncher = new ModuleLauncher();
        NetworkUtils.init();

        // S-phase slot markers (S0): each marker names the batch that owns the registration line(s) right below it.
        // A batch edits only its own marked block and keeps the blank lines around it, so parallel worktrees merge
        // cleanly. Comments only; no behaviour hangs on them.
        // [slot S2] the permission engine bootstrap goes here, before moduleLauncher.init().
        net.shurui.shuruisutilities.permissions.PermissionsBootstrap.init();

        // [slot S18a] the command execution gates (permission guard, map teleport, spam guard). They used to be built
        // with the Commands module (and ran keyless too, since the teardown never unregistered them); core owns them
        // now, so they stay whatever the key holds. Built here, where moduleLauncher.init() used to build them.
        new net.shurui.shuruisutilities.commands.util.CommandGateHandler();

        // [slot S18b] /character (character slots are public): registered by core itself since the Commands module
        // moved into the Ragnarok Key.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.character.CharacterCommandRegistrar());

        // Load submodules
        moduleLauncher.init();
    }

    public void preInit(FMLCommonSetupEvent event)
    {
        LoggingHandler.sulog.info("ShuruisUtilities CommonSetup");
        LoggingHandler.sulog.info(String.format("Running ShuruisUtilities %s (%s)-%s", BuildInfo.getCurrentVersion(), BuildInfo.getBuildHash(), BuildInfo.getBuildType()));
        // Mute vanilla's spammy "moved too quickly!"/"moved wrongly!" movement-check warnings that SU's fast
        // hoverbikes trigger constantly. Log4j2 filter mirrored on sdu's DmzLogAddonMuteFilter; idempotent.
        net.shurui.shuruisutilities.util.MovementWarningMute.tryInstall();
        // Mute two noisy vanilla console lines: LivingEntity "Named entity died" (per custom-named death) and
        // PersistentEntitySectionManager "duplicated UUID" (from vanilla/DMZ saga transform discard-then-add,
        // not suite code). Same log4j2 filter mechanism as above; idempotent and fail-safe.
        net.shurui.shuruisutilities.util.VanillaLogSpamMute.tryInstall();
        // Register the space-pod autopilot route chunk-loading validation callback exactly once, so a forced route
        // window that outlived an abnormal shutdown is dropped on world load instead of pinning chunks forever (a
        // resuming autopilot re-forces a fresh window on its next drive tick). Must be set during common setup, before
        // any level loads its forced chunks.
        net.shurui.shuruisutilities.world.space.SpaceRouteTickets.registerLoadingCallback();
        // The core SpaceHook provider is now installed by the Space module's own @Mod constructor
        // (net.shurui.shuruisutilities.space.DmzRagnarokSpace). With Space absent the hook stays unset and a guild
        // planet raid refuses to start, like a rift without Dungeons.
        // Fold the katchin and katchi katchin tool tiers into the tier sorting registry (and gete itself, which DMZ
        // ships unsorted) so the needs_gete_tool / needs_katchin_tool harvest gates actually work. Runs on both
        // physical sides here in common setup, well before the registry is sorted at world load.
        net.shurui.shuruisutilities.katchin.KatchinTiers.registerSorting();
        // What clients are told about the key follows what the key really installed, never the KeyFeatures marks
        // alone (any jar can mark): each SU feature id is answered by its installed hook, and the client key flag also
        // needs the core SU hooks installed. See KeyAnswers.
        net.shurui.shuruisutilities.api.key.KeyAnswers.register();
    	// Handle submodules parents, must be called after mod loading
        moduleLauncher.handleModuleParents();
        if (safeMode) {
            LoggingHandler.sulog.warn("You are running SU in safe mode. Please only do so if requested to by the ShuruisUtilities team.");
        }
        ShuruisUtilities.getConfigManager().bakeAllRegisteredConfigs(false);
        // Set up logger level
        toggleDebug();

        // Register core submodules
        factory = new PlayerPositionEventFactory();
        teleportHelper = new TeleportHelper();
        questioner = new Questioner();
        respawnHandler = new RespawnHandler();
        selectionHandler = new SelectionHandler();
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.compat.CompatXaeroMinimap());

        // [slot S19b] prestige events: the PUBLIC half (the earned TP bonus, the client syncs, the name colour) runs on
        // every server; the Ragnarok Key adds the private half (ledger seed, floor, kit backfill, NPC right click).
        if (net.shurui.shuruisutilities.core.config.Features.enabled(net.shurui.shuruisutilities.core.config.Features.PRESTIGE))
        {
            prestigeEvents = new net.shurui.shuruisutilities.prestige.PrestigeEvents();
            MinecraftForge.EVENT_BUS.register(prestigeEvents);
        }

        // Re-assert above-cap /dmzstats overrides after DMZ re-clamps on login / clone / dimension change (per
        // character; slot switch re-asserts inline in CharacterSlots). Core to the character system, not gated.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.character.StatCapReassertHandler());

        if (ModuleLauncher.getModuleList().contains(WEIntegration.weModule))
        {
            WEIntegration.instance.postLoad();
        }

        // register packets in COMMON setup so BOTH client and dedicated server get the ids. doing it in
        // ServerAboutToStartEvent registered them server-side only, so on a real dedicated server the client
        // didn't know the ids and every GUI packet was silently dropped (looked fine in singleplayer only).
        event.enqueueWork(this::registerNetworkMessages);

        // Register the KeepPartialInventory gamerule on the mod thread (GameRules' static registry is not
        // thread-safe). GraveEventHandler drives the feature off the forge bus via @Mod.EventBusSubscriber.
        event.enqueueWork(net.shurui.shuruisutilities.grave.KeepPartialInventory::register);
        // terrainRegen: the global switch for combat damage repairing itself. Registered here for the same
        // reason as the rule above, GameRules' static registry is not thread safe outside the mod thread.
        event.enqueueWork(net.shurui.shuruisutilities.regen.TerrainRegenRule::register);

        // [slot S20] corrupted balls and the shadow dragon boss handlers. PUBLIC on every server (S20): the corrupted
        // cycle is the shadow dragon race's unlock path. Only its editor (hub row, packets 45/46) is in the key.
        // Wish-tracking: forge-bus interaction handler + DMZ summon counter (gated behind isModLoaded).
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.corrupted.CorruptedBallHandler());
        // Shadow dragon encounter: death/kill-credit tracking + restart pip restore.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.corrupted.ShadowDragonForgeHandler());

        net.shurui.shuruisutilities.compat.dmz.WishTrackingCompat.init();
        // Dragon-ball wish rituals: the potara-pose countdown closer and the Super Saiyan God charge ritual scanner.
        // Both are throttled server-tick handlers that return immediately when nothing is in flight; the DMZ access
        // inside them is guarded, so they are harmless with DMZ absent. The per-wish counter is driven by
        // MixinDmzGrantWish, and the SSG purchase is gated by MixinDmzSsgPurchaseGate.
        // Combat damage repair: captures blast destroyed blocks and pays them back a bounded number per tick.
        // Inert unless the terrainRegen gamerule is on, so registering it unconditionally costs nothing.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.regen.TerrainRegenHandler());
        // /terrainregen: the operator's view of the repair engine, which is otherwise entirely invisible in game.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.regen.TerrainRegenCommand());

        // [slot S11] /subackups stays here (owner Q4: keep today's keyless surface).
        // The multiworld engine (saved multiworld dimensions loaded at SU server start, the named-world lookup, the
        // logout parking) runs keyless as it always did: public space, respawn and the smp portals stand on it. The
        // MultiworldV2 admin module (/mw, /mwtp) is in the Ragnarok Key.
        net.shurui.shuruisutilities.multiworld.v2.MultiworldEngine.register();
        // /subackups: what the permission and store snapshots are costing, and a way to clear the excess out.
        // Registered unconditionally for the same reason: a server with a backup problem is exactly the one whose
        // modules an operator may have switched off, so this must not be one of the things that goes with them.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.core.backup.BackupCommand());

        // [slot S12] Regions and protection: the Regions and Protection modules are in the Ragnarok Key, but the saved
        // regions and their flag answers stay live on every server (public terrain regen, the world-flag mixins and
        // the DMZ ki-grief hook read them, as they did keyless before the move), and the hand-built dimension guard
        // (Planet Vegeta, SUConfig ProtectedBuildDimensions) protects a public dimension, so core runs both.
        net.shurui.shuruisutilities.regions.RegionEngine.register();
        // (ServerEventHandler's constructor puts the guard on the Forge bus, as when the Protection module built it.)
        new net.shurui.shuruisutilities.protection.BuildDimensionProtection();

        // [slot S13] The anti-farm kill-TP scaling (multi-kill diminishing returns on every mob kill, plus the per-NPC
        // region level falloff, which answers 1.0 when there are no NPC regions). The NpcRegions module that used to
        // build it is in the Ragnarok Key; the multi-kill half outlived the keyless teardown and shapes public TP
        // gain, so core keeps it on every server. (ServerEventHandler's constructor puts it on the Forge bus.)
        new net.shurui.shuruisutilities.npcregion.KillTpModifier();

        // Dash and the melee clash it can produce. Both are inert until a player actually dashes, so this costs
        // one map lookup per level tick when nobody is using them.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.combat.CombatTickHandler());
        // Role energy (malice / destruction / angelic): refills the bars and pushes each player their own. Stays core
        // because the shadow dragon's MALICE bar is public; the god roles are gated inside EnergyManager.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.energy.EnergySync());
        // Shadow dragon signature moves, registered into DMZ's technique registry once a server starts.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.compat.dmz.DragonTechniqueBridge());
        // Drives every running shadow dragon effect, and cleans them up when the server stops.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.dragons.DragonEffectTicker());
        // Makes each live shadow dragon boss use its own slot's signature move.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.dragons.DragonBossController());
        // Keeps Nuova's flames presentation-only, so vanilla fire damage never stacks on the move's own figure.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.dragons.DragonBurnGuard());
        // (The Angel's parry, the role-state guard, the role technique sync and the staff's ki weapon damage are the
        // Ragnarok Key's: feature roles registers them.)
        // Keeps the Angel's staff from ever persisting (drops, tosses, respawn, logout, containers). The summon itself
        // is the key's; this hygiene stays so a keyless server never keeps a stray staff.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.god.AngelStaffGuard());
        // Applies a move's payload when its own DMZ projectile lands.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.dragons.DragonProjectileHit());
        // Sends the charge pose the moment one of our techniques starts charging.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.dragons.MoveChargeTracker());
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.ritual.SsgRitualManager());
        // Keep the wishes for our three hardcoded dragons (Apophis, Super Shenron, Cerulean Shenron) alive after
        // every datapack reload / login, now that the external dragonballs pack that carried them is gone. Gated
        // behind isModLoaded internally.
        net.shurui.shuruisutilities.compat.dmz.SuWishCompat.init();
        // (The Super Saiyan 5 wish's command and the potara pose countdown are the Ragnarok Key's: feature rituals.)
        // The "knowledge of Super Saiyan God" wish, which is what unlocks the SSG charge ritual. Same delivery
        // shape as the SSJ5 wish: a command DMZ's CommandWish entry runs.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.ritual.SsgKnowledgeCommand());
        // The three Super Shenron wishes delivered as commands DMZ's wish entry runs: a fortune in zeni, a stat
        // redistribution, and a permanent +25 to the ki release ceiling. ReleaseBoostEvents resyncs the release
        // bonus to each client on login so the radial node draws the ceiling the server enforces.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.wish.ZeniWishCommand());
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.wish.StatRelocateWishCommand());
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.wish.ReleaseBoostCommand());
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.wish.ReleaseBoostEvents());
        // The keyless Super Shenron power wish (100,000,000 TP and +25% on each stat). Its row is on the list only
        // when the Ragnarok Key's rituals are absent; the command refuses on a keyed server.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.wish.SuperPowerWishCommand());
        // Refuse placing a Super dragon ball within a few blocks of another so the oversized models never overlap.
        // Gated behind isModLoaded internally.
        net.shurui.shuruisutilities.compat.dmz.SuDragonBallPlacementCompat.init();
        // Rebuild DMZ's per-server party roster and scoreboard team on arrival from the party pointer the player
        // already carries in their vaulted StatsData, so parties survive a shard hop (gated behind isModLoaded).
        net.shurui.shuruisutilities.compat.dmz.DmzPartyArrival.init();
        // Optional Curios integration for keep-inventory (gated behind isModLoaded internally).
        net.shurui.shuruisutilities.compat.curios.CuriosCompat.init();
        // Dragon ball bag rules: pickup routing into the equipped bag, the one-dragon-at-a-time refusal, item-frame
        // containment, and drop-on-death / drop-on-logout. GUI-container containment is the Slot.mayPlace mixin.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.dragonballbag.DragonBallInventoryHandler());
        // Right-clicking a dragon radar reports which dimensions that set's balls are in, and how many sit in a
        // grave totem. Server-side only, no packet: the radar HUD has no way to show a dimension, and since a player
        // can now die anywhere and leave balls in a totem there, a bare bearing is no longer enough to find them.
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.compat.dmz.RadarDimensionReport());
        // Optional Cosmetic Armor Reworked integration for keep-inventory (gated behind isModLoaded internally).
        net.shurui.shuruisutilities.compat.cosarmor.CosArmorCompat.init();
        // Optional Sophisticated Backpacks integration: carry a backpack's contents across a shard hop, since the
        // items live in a per-server store keyed by UUID and never in the player's NBT (gated internally).
        net.shurui.shuruisutilities.compat.sophisticatedbackpacks.SophisticatedBackpacksCompat.init();
        // Eject any dragon ball that predates the backpack containment rules back to the player when a backpack GUI is
        // opened. New balls are refused by MixinSophisticatedInventoryHandler; this handles ones already inside from
        // before (gated behind isModLoaded internally).
        net.shurui.shuruisutilities.compat.sophisticatedbackpacks.BackpackDragonBallEject.init();
        // Container-agnostic backstop for the dragon ball whitelist: whenever any container is opened, return any ball
        // sitting in a non-whitelisted slot to the opener. Catches inventories the insertion hooks cannot enumerate.
        net.shurui.shuruisutilities.dragonballbag.DragonBallContainerEject.init();
    }

    public void postLoad(FMLLoadCompleteEvent e)
    {
        LoggingHandler.sulog.info("ShuruisUtilities LoadCompleteEvent");
        commandManager = new SUCommandManager();
        isCubicChunksInstalled = ModList.get().isLoaded("cubicchunks");
    }

    private void initConfiguration()
    {
        suDirectory = new File(FMLPaths.GAMEDIR.get().toFile(), SU_DIRECTORY);
        suDirectory.mkdirs();

        // Record whether main.toml already exists BEFORE Forge opens (and would create) it. This is the only
        // reliable signal that tells an existing player's file, which a one-time config migration may move
        // forward, from a brand-new standalone install, which must keep the code defaults untouched. Captured
        // here in the constructor because Forge reads and creates the file later in the load lifecycle. The name
        // matches SUModConfig ("main" + ".toml") under getSUDirectory().
        SUConfig.noteMainConfigPreexisted(new File(suDirectory, "main.toml").exists());

        configManager = new ConfigBase();

        // Batch M removed the operator module switchboard: a module's presence is decided by installing its jar and
        // a private feature by the Ragnarok Key, so there is no per-feature on/off file to read or consolidate any
        // more. Both the legacy ShuruisUtilities/Modules.cfg and config/dmz_ragnarok/modules.cfg are left on disk,
        // ignored. Every SU module is enabled (subject only to the key allow-list in PublicContent).
        configManager.registerSpecs(new SUConfig());
        // [slot S19a] SaibamanPet.toml: the public pet's stats, loaded and baked on every server (the SaibamanPets
        // editor module is in the Ragnarok Key and no longer owns the file).
        configManager.registerSpecs(new net.shurui.shuruisutilities.saibaman.SaibamanPetConfigLoader());
    }

    private void toggleDebug()
    {
        if (isDebug())
            LoggingHandler.setLevel(Level.DEBUG);
        else
            LoggingHandler.setLevel(Level.INFO);
    }

    private void registerNetworkMessages()
    {
        LoggingHandler.sulog.info("ShuruisUtilities registering network Packets");
        // Load network packages
        NetworkUtils.registerClientToServer(0, Packet0HandshakeHandler.class, Packet0HandshakeHandler::encode, Packet0HandshakeHandler::decode,
                Packet0HandshakeHandler::handler);
        NetworkUtils.registerServerToClient(1, Packet01SelectionUpdate.class, Packet01SelectionUpdate::encode, Packet01SelectionUpdate::decode,
                Packet01SelectionUpdate::handler);
        // NetworkUtils.registerServerToClient(2, Packet2Reach.class, Packet2Reach::decode); old times
        NetworkUtils.registerServerToClient(3, Packet03PlayerPermissions.class, Packet03PlayerPermissions::encode, Packet03PlayerPermissions::decode,
                Packet03PlayerPermissions::handler);
        // NetworkUtils.registerServerToClient(2, Packet4Economy.class, Packet4Economy::decode); old times
        NetworkUtils.registerServerToClient(5, Packet05Noclip.class, Packet05Noclip::encode, Packet05Noclip::decode, Packet05Noclip::handler);
        NetworkUtils.registerServerToClient(11, net.shurui.shuruisutilities.guilds.network.PacketGuildGui.class,
                net.shurui.shuruisutilities.guilds.network.PacketGuildGui::encode,
                net.shurui.shuruisutilities.guilds.network.PacketGuildGui::decode,
                net.shurui.shuruisutilities.guilds.network.PacketGuildGui::handler);
        NetworkUtils.registerServerToClient(12, net.shurui.shuruisutilities.guilds.network.PacketGuildClaims.class,
                net.shurui.shuruisutilities.guilds.network.PacketGuildClaims::encode,
                net.shurui.shuruisutilities.guilds.network.PacketGuildClaims::decode,
                net.shurui.shuruisutilities.guilds.network.PacketGuildClaims::handler);
        NetworkUtils.registerServerToClient(13, net.shurui.shuruisutilities.ranks.PacketRankSync.class,
                net.shurui.shuruisutilities.ranks.PacketRankSync::encode,
                net.shurui.shuruisutilities.ranks.PacketRankSync::decode,
                net.shurui.shuruisutilities.ranks.PacketRankSync::handler);
        NetworkUtils.registerServerToClient(14, net.shurui.shuruisutilities.permissions.gui.PacketPermGui.class,
                net.shurui.shuruisutilities.permissions.gui.PacketPermGui::encode,
                net.shurui.shuruisutilities.permissions.gui.PacketPermGui::decode,
                net.shurui.shuruisutilities.permissions.gui.PacketPermGui::handler);
        NetworkUtils.registerClientToServer(15, net.shurui.shuruisutilities.permissions.gui.PacketPermAction.class,
                net.shurui.shuruisutilities.permissions.gui.PacketPermAction::encode,
                net.shurui.shuruisutilities.permissions.gui.PacketPermAction::decode,
                net.shurui.shuruisutilities.permissions.gui.PacketPermAction::handler);
        NetworkUtils.registerServerToClient(20, net.shurui.shuruisutilities.hub.PacketOpenHub.class,
                net.shurui.shuruisutilities.hub.PacketOpenHub::encode,
                net.shurui.shuruisutilities.hub.PacketOpenHub::decode,
                net.shurui.shuruisutilities.hub.PacketOpenHub::handler);
        NetworkUtils.registerClientToServer(21, net.shurui.shuruisutilities.hub.PacketOpenEditor.class,
                net.shurui.shuruisutilities.hub.PacketOpenEditor::encode,
                net.shurui.shuruisutilities.hub.PacketOpenEditor::decode,
                net.shurui.shuruisutilities.hub.PacketOpenEditor::handler);
        NetworkUtils.registerServerToClient(22, net.shurui.shuruisutilities.hub.PacketEditorData.class,
                net.shurui.shuruisutilities.hub.PacketEditorData::encode,
                net.shurui.shuruisutilities.hub.PacketEditorData::decode,
                net.shurui.shuruisutilities.hub.PacketEditorData::handler);
        NetworkUtils.registerClientToServer(23, net.shurui.shuruisutilities.hub.PacketEditorAction.class,
                net.shurui.shuruisutilities.hub.PacketEditorAction::encode,
                net.shurui.shuruisutilities.hub.PacketEditorAction::decode,
                net.shurui.shuruisutilities.hub.PacketEditorAction::handler);
        NetworkUtils.registerServerToClient(24, net.shurui.shuruisutilities.tablist.PacketTabListBanner.class,
                net.shurui.shuruisutilities.tablist.PacketTabListBanner::encode,
                net.shurui.shuruisutilities.tablist.PacketTabListBanner::decode,
                net.shurui.shuruisutilities.tablist.PacketTabListBanner::handler);
        NetworkUtils.registerServerToClient(25, net.shurui.shuruisutilities.ranks.PacketRankAssets.class,
                net.shurui.shuruisutilities.ranks.PacketRankAssets::encode,
                net.shurui.shuruisutilities.ranks.PacketRankAssets::decode,
                net.shurui.shuruisutilities.ranks.PacketRankAssets::handler);
        NetworkUtils.registerServerToClient(26, net.shurui.shuruisutilities.npcregion.network.PacketOpenNpcRegionEditor.class,
                net.shurui.shuruisutilities.npcregion.network.PacketOpenNpcRegionEditor::encode,
                net.shurui.shuruisutilities.npcregion.network.PacketOpenNpcRegionEditor::decode,
                net.shurui.shuruisutilities.npcregion.network.PacketOpenNpcRegionEditor::handler);
        NetworkUtils.registerClientToServer(27, net.shurui.shuruisutilities.npcregion.network.PacketSaveNpcRegion.class,
                net.shurui.shuruisutilities.npcregion.network.PacketSaveNpcRegion::encode,
                net.shurui.shuruisutilities.npcregion.network.PacketSaveNpcRegion::decode,
                net.shurui.shuruisutilities.npcregion.network.PacketSaveNpcRegion::handler);
        NetworkUtils.registerServerToClient(28, net.shurui.shuruisutilities.npcregion.network.PacketNpcRegionSync.class,
                net.shurui.shuruisutilities.npcregion.network.PacketNpcRegionSync::encode,
                net.shurui.shuruisutilities.npcregion.network.PacketNpcRegionSync::decode,
                net.shurui.shuruisutilities.npcregion.network.PacketNpcRegionSync::handler);
        NetworkUtils.registerServerToClient(29, net.shurui.shuruisutilities.character.PacketCharacterGui.class,
                net.shurui.shuruisutilities.character.PacketCharacterGui::encode,
                net.shurui.shuruisutilities.character.PacketCharacterGui::decode,
                net.shurui.shuruisutilities.character.PacketCharacterGui::handler);
        NetworkUtils.registerClientToServer(30, net.shurui.shuruisutilities.character.PacketCharacterAction.class,
                net.shurui.shuruisutilities.character.PacketCharacterAction::encode,
                net.shurui.shuruisutilities.character.PacketCharacterAction::decode,
                net.shurui.shuruisutilities.character.PacketCharacterAction::handler);
        NetworkUtils.registerServerToClient(31, net.shurui.shuruisutilities.prestige.PacketPrestigeGui.class,
                net.shurui.shuruisutilities.prestige.PacketPrestigeGui::encode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeGui::decode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeGui::handler);
        NetworkUtils.registerClientToServer(32, net.shurui.shuruisutilities.prestige.PacketPrestigeAction.class,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAction::encode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAction::decode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAction::handler);
        NetworkUtils.registerServerToClient(33, net.shurui.shuruisutilities.prestige.PacketPrestigeAdminGui.class,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAdminGui::encode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAdminGui::decode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAdminGui::handler);
        NetworkUtils.registerClientToServer(34, net.shurui.shuruisutilities.prestige.PacketPrestigeAdminSave.class,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAdminSave::encode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAdminSave::decode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeAdminSave::handler);
        NetworkUtils.registerServerToClient(35, net.shurui.shuruisutilities.prestige.PacketSuTpMult.class,
                net.shurui.shuruisutilities.prestige.PacketSuTpMult::encode,
                net.shurui.shuruisutilities.prestige.PacketSuTpMult::decode,
                net.shurui.shuruisutilities.prestige.PacketSuTpMult::handler);
        // ids 36 (was PacketOpenAirdropEditor) and 37 (was PacketSaveAirdrop) intentionally unused: the global
        // airdrop editor was replaced by per-region config. don't reuse these ids; the gap keeps existing ids stable.
        NetworkUtils.registerServerToClient(38, net.shurui.shuruisutilities.prestige.PacketSuCapMult.class,
                net.shurui.shuruisutilities.prestige.PacketSuCapMult::encode,
                net.shurui.shuruisutilities.prestige.PacketSuCapMult::decode,
                net.shurui.shuruisutilities.prestige.PacketSuCapMult::handler);
        NetworkUtils.registerClientToServer(39, net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle.class,
                net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle::encode,
                net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle::decode,
                net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle::handler);
        NetworkUtils.registerClientToServer(40, net.shurui.shuruisutilities.prestige.PacketSuCapSyncRequest.class,
                net.shurui.shuruisutilities.prestige.PacketSuCapSyncRequest::encode,
                net.shurui.shuruisutilities.prestige.PacketSuCapSyncRequest::decode,
                net.shurui.shuruisutilities.prestige.PacketSuCapSyncRequest::handler);
        // prestige-gated races: admin saves the race->required-prestige map (C2S), server pushes each player's
        // lock state (S2C), the latter forwarded to sdu's race-select screen via reflection compat.
        NetworkUtils.registerClientToServer(41, net.shurui.shuruisutilities.prestige.PacketPrestigeRaceSave.class,
                net.shurui.shuruisutilities.prestige.PacketPrestigeRaceSave::encode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeRaceSave::decode,
                net.shurui.shuruisutilities.prestige.PacketPrestigeRaceSave::handler);
        NetworkUtils.registerServerToClient(42, net.shurui.shuruisutilities.prestige.PacketRaceLockSync.class,
                net.shurui.shuruisutilities.prestige.PacketRaceLockSync::encode,
                net.shurui.shuruisutilities.prestige.PacketRaceLockSync::decode,
                net.shurui.shuruisutilities.prestige.PacketRaceLockSync::handler);
        // flat, non-positional UI sound cue: used by the swap cinematic so its voice lines reach every online
        // player in every dimension at identical volume, like the vanilla ender-dragon-death sound.
        NetworkUtils.registerServerToClient(43, net.shurui.shuruisutilities.corrupted.PacketGlobalSound.class,
                net.shurui.shuruisutilities.corrupted.PacketGlobalSound::encode,
                net.shurui.shuruisutilities.corrupted.PacketGlobalSound::decode,
                net.shurui.shuruisutilities.corrupted.PacketGlobalSound::handler);
        // shadow-dragon boss/arena editor (phase 3b): typed transport, one screen with a tab per slot. ids 44-47.
        NetworkUtils.registerServerToClient(44, net.shurui.shuruisutilities.corrupted.network.PacketOpenShadowDragonEditor.class,
                net.shurui.shuruisutilities.corrupted.network.PacketOpenShadowDragonEditor::encode,
                net.shurui.shuruisutilities.corrupted.network.PacketOpenShadowDragonEditor::decode,
                net.shurui.shuruisutilities.corrupted.network.PacketOpenShadowDragonEditor::handler);
        NetworkUtils.registerClientToServer(45, net.shurui.shuruisutilities.corrupted.network.PacketSaveShadowDragons.class,
                net.shurui.shuruisutilities.corrupted.network.PacketSaveShadowDragons::encode,
                net.shurui.shuruisutilities.corrupted.network.PacketSaveShadowDragons::decode,
                net.shurui.shuruisutilities.corrupted.network.PacketSaveShadowDragons::handler);
        NetworkUtils.registerClientToServer(46, net.shurui.shuruisutilities.corrupted.network.PacketSetShadowDragonBounds.class,
                net.shurui.shuruisutilities.corrupted.network.PacketSetShadowDragonBounds::encode,
                net.shurui.shuruisutilities.corrupted.network.PacketSetShadowDragonBounds::decode,
                net.shurui.shuruisutilities.corrupted.network.PacketSetShadowDragonBounds::handler);
        NetworkUtils.registerServerToClient(47, net.shurui.shuruisutilities.corrupted.network.PacketShadowDragonBoundsResult.class,
                net.shurui.shuruisutilities.corrupted.network.PacketShadowDragonBoundsResult::encode,
                net.shurui.shuruisutilities.corrupted.network.PacketShadowDragonBoundsResult::decode,
                net.shurui.shuruisutilities.corrupted.network.PacketShadowDragonBoundsResult::handler);
        // sub-race system (batch B): the receiving player's owned unlock-gated race ids (base shadow_dragon + earned
        // shadow dragon sub-races). Sent from PrestigeManager.sendRaceLock, so it rides the same login / prestige /
        // slot-switch / admin-grant moments as the prestige race-lock sync. Client cache fails closed until it arrives.
        NetworkUtils.registerServerToClient(48, net.shurui.shuruisutilities.corrupted.network.PacketRaceUnlockSync.class,
                net.shurui.shuruisutilities.corrupted.network.PacketRaceUnlockSync::encode,
                net.shurui.shuruisutilities.corrupted.network.PacketRaceUnlockSync::decode,
                net.shurui.shuruisutilities.corrupted.network.PacketRaceUnlockSync::handler);
        // Space packets (ids 49, 50, 52, 53, 54, 58, 61) are registered by the Space module's own @Mod
        // container (net.shurui.shuruisutilities.space.DmzRagnarokSpace) on THIS shared SU channel, with the
        // same explicit ids, so the wire is identical fat vs modular and core's other ids never shift.
        // guild-raid clone appearance capsule: binds a client-side true-appearance puppet to a driver entity, or
        // drops it. Client side is entirely behind DistExecutor so it never loads on a dedicated server.
        NetworkUtils.registerServerToClient(51, net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance.class,
                net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance::encode,
                net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance::decode,
                net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance::handler);
        // dragon ball bag opener: empty C2S signal; the server resolves the player's own equipped bag and opens it.
        NetworkUtils.registerClientToServer(55, net.shurui.shuruisutilities.dragonballbag.PacketOpenDragonBallBag.class,
                net.shurui.shuruisutilities.dragonballbag.PacketOpenDragonBallBag::encode,
                net.shurui.shuruisutilities.dragonballbag.PacketOpenDragonBallBag::decode,
                net.shurui.shuruisutilities.dragonballbag.PacketOpenDragonBallBag::handler);
        // senzu bean bag: empty C2S signals. 56 opens the player's own equipped bag; 57 pulls one bean out of it.
        // Both resolve the bag server-side and never trust the client for its contents.
        NetworkUtils.registerClientToServer(56, net.shurui.shuruisutilities.senzu.bag.PacketOpenSenzuBag.class,
                net.shurui.shuruisutilities.senzu.bag.PacketOpenSenzuBag::encode,
                net.shurui.shuruisutilities.senzu.bag.PacketOpenSenzuBag::decode,
                net.shurui.shuruisutilities.senzu.bag.PacketOpenSenzuBag::handler);
        NetworkUtils.registerClientToServer(57, net.shurui.shuruisutilities.senzu.bag.PacketTakeSenzuBean.class,
                net.shurui.shuruisutilities.senzu.bag.PacketTakeSenzuBean::encode,
                net.shurui.shuruisutilities.senzu.bag.PacketTakeSenzuBean::decode,
                net.shurui.shuruisutilities.senzu.bag.PacketTakeSenzuBean::handler);
        // auction house opener: one typed S2C packet carrying the whole authoritative view (listings + claim queue,
        // each with a real ItemStack the generic string editor transport cannot move). C2S actions reuse
        // PacketEditorAction with editor "auction" (re-validated in AuctionServer).
        NetworkUtils.registerServerToClient(59, net.shurui.shuruisutilities.auction.network.PacketOpenAuction.class,
                net.shurui.shuruisutilities.auction.network.PacketOpenAuction::encode,
                net.shurui.shuruisutilities.auction.network.PacketOpenAuction::decode,
                net.shurui.shuruisutilities.auction.network.PacketOpenAuction::handler);
        // player-to-player trade opener/refresh: one typed S2C packet carrying both sides' offered stacks (real
        // ItemStacks the generic string editor transport cannot move), names and confirm flags. C2S actions reuse
        // PacketEditorAction with editor "trade" (re-validated in TradeManager).
        NetworkUtils.registerServerToClient(60, net.shurui.shuruisutilities.trade.network.PacketOpenTrade.class,
                net.shurui.shuruisutilities.trade.network.PacketOpenTrade::encode,
                net.shurui.shuruisutilities.trade.network.PacketOpenTrade::decode,
                net.shurui.shuruisutilities.trade.network.PacketOpenTrade::handler);
        // defiled-balls flag: mirrors the server-wide ShadowDragonStorage.hasDefiledBallsPresent so the client can swap
        // the Earth radar's dial to the shadow-dragon art while corrupted balls are scattered in the world. Sent on
        // login and dimension change (ShadowDragonForgeHandler) and broadcast the moment the flag flips (arm / disarm /
        // reset). Fails closed until it arrives, so a missing packet keeps DMZ's stock dial rather than leaking the
        // shadow art.
        NetworkUtils.registerServerToClient(62, net.shurui.shuruisutilities.corrupted.network.PacketDefiledSync.class,
                net.shurui.shuruisutilities.corrupted.network.PacketDefiledSync::encode,
                net.shurui.shuruisutilities.corrupted.network.PacketDefiledSync::decode,
                net.shurui.shuruisutilities.corrupted.network.PacketDefiledSync::handler);
        // Guild gravity chamber: the gravity/range GUI open + save pair and the sparring menu open + start pair.
        NetworkUtils.registerServerToClient(63, net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberGravity.class,
                net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberGravity::encode,
                net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberGravity::decode,
                net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberGravity::handler);
        NetworkUtils.registerClientToServer(64, net.shurui.shuruisutilities.gravitychamber.PacketSaveChamberGravity.class,
                net.shurui.shuruisutilities.gravitychamber.PacketSaveChamberGravity::encode,
                net.shurui.shuruisutilities.gravitychamber.PacketSaveChamberGravity::decode,
                net.shurui.shuruisutilities.gravitychamber.PacketSaveChamberGravity::handler);
        NetworkUtils.registerServerToClient(65, net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberSpar.class,
                net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberSpar::encode,
                net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberSpar::decode,
                net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberSpar::handler);
        NetworkUtils.registerClientToServer(66, net.shurui.shuruisutilities.gravitychamber.PacketStartChamberSpar.class,
                net.shurui.shuruisutilities.gravitychamber.PacketStartChamberSpar::encode,
                net.shurui.shuruisutilities.gravitychamber.PacketStartChamberSpar::decode,
                net.shurui.shuruisutilities.gravitychamber.PacketStartChamberSpar::handler);
        NetworkUtils.registerServerToClient(67, net.shurui.shuruisutilities.cosmetics.form.network.PacketFormCosmeticSync.class,
                net.shurui.shuruisutilities.cosmetics.form.network.PacketFormCosmeticSync::encode,
                net.shurui.shuruisutilities.cosmetics.form.network.PacketFormCosmeticSync::decode,
                net.shurui.shuruisutilities.cosmetics.form.network.PacketFormCosmeticSync::handler);
        NetworkUtils.registerClientToServer(68, net.shurui.shuruisutilities.combat.PacketDashRequest.class,
                net.shurui.shuruisutilities.combat.PacketDashRequest::encode,
                net.shurui.shuruisutilities.combat.PacketDashRequest::decode,
                net.shurui.shuruisutilities.combat.PacketDashRequest::handler);
        NetworkUtils.registerServerToClient(69, net.shurui.shuruisutilities.combat.PacketDashState.class,
                net.shurui.shuruisutilities.combat.PacketDashState::encode,
                net.shurui.shuruisutilities.combat.PacketDashState::decode,
                net.shurui.shuruisutilities.combat.PacketDashState::handler);
        NetworkUtils.registerServerToClient(70, net.shurui.shuruisutilities.ritual.PacketIdolPrompt.class,
                net.shurui.shuruisutilities.ritual.PacketIdolPrompt::encode,
                net.shurui.shuruisutilities.ritual.PacketIdolPrompt::decode,
                net.shurui.shuruisutilities.ritual.PacketIdolPrompt::handler);
        NetworkUtils.registerClientToServer(71, net.shurui.shuruisutilities.ritual.PacketIdolConfirm.class,
                net.shurui.shuruisutilities.ritual.PacketIdolConfirm::encode,
                net.shurui.shuruisutilities.ritual.PacketIdolConfirm::decode,
                net.shurui.shuruisutilities.ritual.PacketIdolConfirm::handler);

        // melee clash rhythm game (72 start, 73 score report, 74 result)
        NetworkUtils.registerServerToClient(72, net.shurui.shuruisutilities.combat.PacketClashStart.class,
                net.shurui.shuruisutilities.combat.PacketClashStart::encode,
                net.shurui.shuruisutilities.combat.PacketClashStart::decode,
                net.shurui.shuruisutilities.combat.PacketClashStart::handler);
        NetworkUtils.registerClientToServer(73, net.shurui.shuruisutilities.combat.PacketClashScore.class,
                net.shurui.shuruisutilities.combat.PacketClashScore::encode,
                net.shurui.shuruisutilities.combat.PacketClashScore::decode,
                net.shurui.shuruisutilities.combat.PacketClashScore::handler);
        NetworkUtils.registerServerToClient(74, net.shurui.shuruisutilities.combat.PacketClashEnd.class,
                net.shurui.shuruisutilities.combat.PacketClashEnd::encode,
                net.shurui.shuruisutilities.combat.PacketClashEnd::decode,
                net.shurui.shuruisutilities.combat.PacketClashEnd::handler);
        NetworkUtils.registerServerToClient(75, net.shurui.shuruisutilities.combat.PacketClashVisuals.class,
                net.shurui.shuruisutilities.combat.PacketClashVisuals::encode,
                net.shurui.shuruisutilities.combat.PacketClashVisuals::decode,
                net.shurui.shuruisutilities.combat.PacketClashVisuals::handler);

        // Key state for the ragnarok model picker in the Custom NPCs editor. One boolean at login: the picker
        // runs on the client, where neither KeyGate.present() (the key is a server-side mod) nor
        // KeyGate.unlocked() (a client is never a dedicated server) can answer for the server.
        NetworkUtils.registerServerToClient(76, net.shurui.shuruisutilities.ragnarok.PacketRgKeySync.class,
                net.shurui.shuruisutilities.ragnarok.PacketRgKeySync::encode,
                net.shurui.shuruisutilities.ragnarok.PacketRgKeySync::decode,
                net.shurui.shuruisutilities.ragnarok.PacketRgKeySync::handler);
        NetworkUtils.registerServerToClient(77, net.shurui.shuruisutilities.patreon.PacketCrownSync.class,
                net.shurui.shuruisutilities.patreon.PacketCrownSync::encode,
                net.shurui.shuruisutilities.patreon.PacketCrownSync::decode,
                net.shurui.shuruisutilities.patreon.PacketCrownSync::handler);
        NetworkUtils.registerServerToClient(78, net.shurui.shuruisutilities.economy.PacketZeniSync.class,
                net.shurui.shuruisutilities.economy.PacketZeniSync::encode,
                net.shurui.shuruisutilities.economy.PacketZeniSync::decode,
                net.shurui.shuruisutilities.economy.PacketZeniSync::handler);
        NetworkUtils.registerServerToClient(79, net.shurui.shuruisutilities.energy.PacketEnergySync.class,
                net.shurui.shuruisutilities.energy.PacketEnergySync::encode,
                net.shurui.shuruisutilities.energy.PacketEnergySync::decode,
                net.shurui.shuruisutilities.energy.PacketEnergySync::handler);
        NetworkUtils.registerClientToServer(80, net.shurui.shuruisutilities.energy.PacketAbilityActivate.class,
                net.shurui.shuruisutilities.energy.PacketAbilityActivate::encode,
                net.shurui.shuruisutilities.energy.PacketAbilityActivate::decode,
                net.shurui.shuruisutilities.energy.PacketAbilityActivate::handler);
        NetworkUtils.registerClientToServer(81, net.shurui.shuruisutilities.god.PacketAngelStaffToggle.class,
                net.shurui.shuruisutilities.god.PacketAngelStaffToggle::encode,
                net.shurui.shuruisutilities.god.PacketAngelStaffToggle::decode,
                net.shurui.shuruisutilities.god.PacketAngelStaffToggle::handler);
        // 82 was the keyblade summon. Left as a hole on purpose: these ids are the wire protocol, so renumbering
        // what follows would desync every client that has not updated in lockstep.
        NetworkUtils.registerServerToClient(83, net.shurui.shuruisutilities.dragons.PacketSpinState.class,
                net.shurui.shuruisutilities.dragons.PacketSpinState::encode,
                net.shurui.shuruisutilities.dragons.PacketSpinState::decode,
                net.shurui.shuruisutilities.dragons.PacketSpinState::handler);
        NetworkUtils.registerClientToServer(84, net.shurui.shuruisutilities.combat.PacketSonicBoom.class,
                net.shurui.shuruisutilities.combat.PacketSonicBoom::encode,
                net.shurui.shuruisutilities.combat.PacketSonicBoom::decode,
                net.shurui.shuruisutilities.combat.PacketSonicBoom::handler);
        NetworkUtils.registerServerToClient(85, net.shurui.shuruisutilities.combat.PacketSonicCrashState.class,
                net.shurui.shuruisutilities.combat.PacketSonicCrashState::encode,
                net.shurui.shuruisutilities.combat.PacketSonicCrashState::decode,
                net.shurui.shuruisutilities.combat.PacketSonicCrashState::handler);
        // 89: cross-server ghosts. Server to client only; the client never has anything to say back.
        NetworkUtils.registerServerToClient(89, net.shurui.shuruisutilities.shard.PacketGhosts.class,
                net.shurui.shuruisutilities.shard.PacketGhosts::encode,
                net.shurui.shuruisutilities.shard.PacketGhosts::decode,
                net.shurui.shuruisutilities.shard.PacketGhosts::handler);
        NetworkUtils.registerClientToServer(86, net.shurui.shuruisutilities.ocarina.PacketOcarina.class,
                net.shurui.shuruisutilities.ocarina.PacketOcarina::encode,
                net.shurui.shuruisutilities.ocarina.PacketOcarina::decode,
                net.shurui.shuruisutilities.ocarina.PacketOcarina::handler);
        NetworkUtils.registerServerToClient(87, net.shurui.shuruisutilities.ocarina.PacketOcarinaState.class,
                net.shurui.shuruisutilities.ocarina.PacketOcarinaState::encode,
                net.shurui.shuruisutilities.ocarina.PacketOcarinaState::decode,
                net.shurui.shuruisutilities.ocarina.PacketOcarinaState::handler);
        NetworkUtils.registerServerToClient(88, net.shurui.shuruisutilities.staff.PacketStaffHud.class,
                net.shurui.shuruisutilities.staff.PacketStaffHud::encode,
                net.shurui.shuruisutilities.staff.PacketStaffHud::decode,
                net.shurui.shuruisutilities.staff.PacketStaffHud::handler);
        // The task board itself needs no packets of its own: it rides the generic PacketEditorData / PacketEditorAction
        // transport every other list screen uses. Only the on-screen reminder is new, because it is pushed rather
        // than requested.
        // 90-91: hologram pictures. 90 streams the image folder itself in chunks, exactly as the rank badges are
        // streamed; 91 says where each one stands. Both are server to client only: a client that has them draws
        // them entirely on its own, so there is nothing to send back and nothing per tick.
        NetworkUtils.registerServerToClient(90, net.shurui.shuruisutilities.hologram.network.PacketHologramGifs.class,
                net.shurui.shuruisutilities.hologram.network.PacketHologramGifs::encode,
                net.shurui.shuruisutilities.hologram.network.PacketHologramGifs::decode,
                net.shurui.shuruisutilities.hologram.network.PacketHologramGifs::handler);
        NetworkUtils.registerServerToClient(91,
                net.shurui.shuruisutilities.hologram.network.PacketHologramBillboards.class,
                net.shurui.shuruisutilities.hologram.network.PacketHologramBillboards::encode,
                net.shurui.shuruisutilities.hologram.network.PacketHologramBillboards::decode,
                net.shurui.shuruisutilities.hologram.network.PacketHologramBillboards::handler);
        // 92: "throw this bean to the player I am locked on to". Client to server only, and it carries the aim
        // rather than the throw, because the lock-on is a client structure the server has no copy of.
        NetworkUtils.registerClientToServer(92, net.shurui.shuruisutilities.senzu.PacketSenzuThrow.class,
                net.shurui.shuruisutilities.senzu.PacketSenzuThrow::encode,
                net.shurui.shuruisutilities.senzu.PacketSenzuThrow::decode,
                net.shurui.shuruisutilities.senzu.PacketSenzuThrow::handler);
        // 93: the rgnpc (ninjin) model pack, streamed from the server folder exactly as the rank badges at 90 are.
        // Paired with 97 below: the client speaks first about what it already holds, and this carries only what it
        // turns out to be missing.
        NetworkUtils.registerServerToClient(93, net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssets.class,
                net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssets::encode,
                net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssets::decode,
                net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssets::handler);
        // 97: the other half of 93. The client reports the pack version it holds, and how much of it, so a client
        // with a warm cache is sent nothing at all and an interrupted transfer resumes instead of restarting.
        NetworkUtils.registerClientToServer(97, net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssetsHave.class,
                net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssetsHave::encode,
                net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssetsHave::decode,
                net.shurui.shuruisutilities.ragnarok.PacketRgNpcAssetsHave::handler);
        // 94: the player's stored ki release bonus (the Super Shenron release wish). Server to client, cached in
        // ReleaseBoostClient so the radial release node draws the same ceiling the server enforces.
        NetworkUtils.registerServerToClient(94, net.shurui.shuruisutilities.wish.PacketReleaseBoost.class,
                net.shurui.shuruisutilities.wish.PacketReleaseBoost::encode,
                net.shurui.shuruisutilities.wish.PacketReleaseBoost::decode,
                net.shurui.shuruisutilities.wish.PacketReleaseBoost::handler);
        // 95: one player's whole Cosmetic Armor Reworked inventory as NBT, delivered over OUR channel instead of
        // Cosmetic Armor's. Its channel provably does not survive a Velocity backend hop; ours does, so we carry
        // the wearer's cosmetics ourselves and write them into Cosmetic Armor's client cache. See
        // compat.cosarmor.PacketCosArmorSync. Registered unconditionally (no Cosmetic Armor types touched here) so
        // client and server always agree on the channel whether or not that optional mod is installed.
        NetworkUtils.registerServerToClient(95, net.shurui.shuruisutilities.compat.cosarmor.PacketCosArmorSync.class,
                net.shurui.shuruisutilities.compat.cosarmor.PacketCosArmorSync::encode,
                net.shurui.shuruisutilities.compat.cosarmor.PacketCosArmorSync::decode,
                net.shurui.shuruisutilities.compat.cosarmor.PacketCosArmorSync::handler);
        // 96: whether the receiving player may pick the HARD saga difficulty (prestige >= 1). Feeds sdu's
        // HardSagaGate so the saga screen hides/locks HARD for a never-prestiged player; the server-side sdu
        // mixin refuses a crafted HARD request through the same gate. See prestige.PacketHardSagaGate.
        NetworkUtils.registerServerToClient(96, net.shurui.shuruisutilities.prestige.PacketHardSagaGate.class,
                net.shurui.shuruisutilities.prestige.PacketHardSagaGate::encode,
                net.shurui.shuruisutilities.prestige.PacketHardSagaGate::decode,
                net.shurui.shuruisutilities.prestige.PacketHardSagaGate::handler);
        // 98: this client's suite-announcement preference (one boolean). Client to server, sent on every login and on
        // every toggle flip; the server records it per player in AnnouncementPrefs and skips only OUR on-screen
        // announcements for a player who opted out. 98 is the next free id: the running counter's high water mark was
        // 97 (PacketRgNpcAssetsHave), and 6..9 are reserved for the auth/remote modules. See announce package.
        NetworkUtils.registerClientToServer(98, net.shurui.shuruisutilities.announce.PacketAnnouncementPref.class,
                net.shurui.shuruisutilities.announce.PacketAnnouncementPref::encode,
                net.shurui.shuruisutilities.announce.PacketAnnouncementPref::decode,
                net.shurui.shuruisutilities.announce.PacketAnnouncementPref::handler);
        // 99: which Zeni shop tab a player has open. A trade slot may carry both a buy and a sell price, and the
        // CustomNPCs trade event looks identical either way, so this is what tells the server which direction a
        // click means. Vanilla-only payload, so it registers whether or not CustomNPCs is installed.
        NetworkUtils.registerClientToServer(99, net.shurui.shuruisutilities.compat.customnpcs.PacketZeniShopTab.class,
                net.shurui.shuruisutilities.compat.customnpcs.PacketZeniShopTab::encode,
                net.shurui.shuruisutilities.compat.customnpcs.PacketZeniShopTab::decode,
                net.shurui.shuruisutilities.compat.customnpcs.PacketZeniShopTab::handler);
        // 100: the set of dragon ball sets currently DORMANT (turned to stone by a wish), server to client, so the
        // block renderer can draw a dormant set grey and translucent. Full-state replacement each sync, mirroring
        // PacketDefiledSync. 100 is the next free id past the 99 high water mark.
        NetworkUtils.registerServerToClient(100, net.shurui.shuruisutilities.compat.dmz.network.PacketBallDormancySync.class,
                net.shurui.shuruisutilities.compat.dmz.network.PacketBallDormancySync::encode,
                net.shurui.shuruisutilities.compat.dmz.network.PacketBallDormancySync::decode,
                net.shurui.shuruisutilities.compat.dmz.network.PacketBallDormancySync::handler);
        // Blanks the client's DMZ hair before a character swap re-syncs, so a switched-in character's hair does not
        // merge with the one it replaced (DMZ's Character.load only ever adds hair strands). Sent right before the
        // DMZ stats sync in CharacterSlots.apply. 101 is the next free id past the 100 high water mark.
        NetworkUtils.registerServerToClient(101, net.shurui.shuruisutilities.character.PacketClearClientHair.class,
                net.shurui.shuruisutilities.character.PacketClearClientHair::encode,
                net.shurui.shuruisutilities.character.PacketClearClientHair::decode,
                net.shurui.shuruisutilities.character.PacketClearClientHair::handler);
        // 102: the cosmetic catalogue, server to client. A full replacement each time: the catalogue is a few
        // dozen small records and changes only when an admin edits one, so a delta protocol would be more code
        // and more ways to drift for no measurable saving. Sent on login and again to everybody after an edit.
        // 102 is the next free id past the 101 high water mark.
        NetworkUtils.registerServerToClient(102,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticCatalogSync.class,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticCatalogSync::encode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticCatalogSync::decode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticCatalogSync::handler);
        // 103: who is wearing what, server to client. Full table on login, one player per update after. MUST be
        // sent AFTER 102 on login: this packet is a list of catalogue ids, so a client that got it first would
        // be holding ids it could not resolve. See WardrobeManager.onLogin.
        NetworkUtils.registerServerToClient(103,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketWardrobeSync.class,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketWardrobeSync::encode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketWardrobeSync::decode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketWardrobeSync::handler);
        // 104: the receiving player's OWN Shards balance, for the HUD readout. No uuid, so it can only ever
        // describe the account on the other end of the connection. Sent on login and on every change, never on a
        // timer; see ArgentSync. Registered unconditionally, like every other id here, so client and server agree
        // on the channel whatever the server's key tier or module switches say. 104 is the next free id past the
        // 103 high water mark.
        NetworkUtils.registerServerToClient(104,
                net.shurui.shuruisutilities.cosmetics.argent.PacketShardSync.class,
                net.shurui.shuruisutilities.cosmetics.argent.PacketShardSync::encode,
                net.shurui.shuruisutilities.cosmetics.argent.PacketShardSync::decode,
                net.shurui.shuruisutilities.cosmetics.argent.PacketShardSync::handler);
        // 105: play one triggered cosmetic animation at a world position, server to client. Carries a catalogue id
        // resolved against the client's copy of the catalogue (102), never a definition, so a join storm is a few
        // dozen small packets. Nothing is created server side, so there is no effect entity to leave behind when a
        // hop never completes. 105 is the next free id past the 104 high water mark.
        NetworkUtils.registerServerToClient(105,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAnimationPlay.class,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAnimationPlay::encode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAnimationPlay::decode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAnimationPlay::handler);
        // 106: save a region's Z orb config (and optionally the server-wide globals), client to server. Fixed id,
        // registered unconditionally like every other so client and server agree on the channel whatever the key
        // tier says; the handler itself refuses the write without su.npcregion.admin AND the key's Z orb hook. 106
        // is the next free id past the 105 high water mark.
        NetworkUtils.registerClientToServer(106,
                net.shurui.shuruisutilities.zorb.network.PacketSaveZOrbConfig.class,
                net.shurui.shuruisutilities.zorb.network.PacketSaveZOrbConfig::encode,
                net.shurui.shuruisutilities.zorb.network.PacketSaveZOrbConfig::decode,
                net.shurui.shuruisutilities.zorb.network.PacketSaveZOrbConfig::handler);
        // 107: a Z orb pickup fx (the "+N" popup and combo toast), server to client. Sent by the key in Z2; the
        // client popup is wired in Z3. Registered here so the wire id is fixed forever.
        NetworkUtils.registerServerToClient(107,
                net.shurui.shuruisutilities.zorb.network.PacketZOrbPickupFx.class,
                net.shurui.shuruisutilities.zorb.network.PacketZOrbPickupFx::encode,
                net.shurui.shuruisutilities.zorb.network.PacketZOrbPickupFx::decode,
                net.shurui.shuruisutilities.zorb.network.PacketZOrbPickupFx::handler);
        // 108-122: the PRIVATE racing feature (Mario-Kart-style races on hoverbikes). Fixed ids, registered
        // unconditionally like every other so client and server agree on the channel whatever the key tier says.
        // 123-127 are left UNREGISTERED as spare racing ids. Server-to-client packets route to the client-only
        // RaceClientState (which ignores everything unless ClientGate.feature("racing")); client-to-server packets
        // route to RaceHooks (keyless: an inert no-op). See the design notes sections 5-6.
        NetworkUtils.registerServerToClient(108,
                net.shurui.shuruisutilities.racing.net.PacketRaceFeatureHello.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceFeatureHello::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceFeatureHello::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceFeatureHello::handler);
        NetworkUtils.registerServerToClient(109,
                net.shurui.shuruisutilities.racing.net.PacketRaceSession.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceSession::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceSession::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceSession::handler);
        NetworkUtils.registerServerToClient(110,
                net.shurui.shuruisutilities.racing.net.PacketRaceState.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceState::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceState::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceState::handler);
        NetworkUtils.registerServerToClient(111,
                net.shurui.shuruisutilities.racing.net.PacketRaceItemSlot.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceItemSlot::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceItemSlot::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceItemSlot::handler);
        NetworkUtils.registerServerToClient(112,
                net.shurui.shuruisutilities.racing.net.PacketRaceEffect.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceEffect::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceEffect::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceEffect::handler);
        NetworkUtils.registerServerToClient(113,
                net.shurui.shuruisutilities.racing.net.PacketRaceResults.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceResults::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceResults::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceResults::handler);
        NetworkUtils.registerClientToServer(114,
                net.shurui.shuruisutilities.racing.net.PacketRaceUseItem.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceUseItem::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceUseItem::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceUseItem::handler);
        NetworkUtils.registerClientToServer(115,
                net.shurui.shuruisutilities.racing.net.PacketRaceDrift.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceDrift::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceDrift::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceDrift::handler);
        NetworkUtils.registerClientToServer(116,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyAction.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyAction::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyAction::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyAction::handler);
        NetworkUtils.registerServerToClient(117,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen::handler);
        NetworkUtils.registerServerToClient(118,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorSync.class,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorSync::encode,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorSync::decode,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorSync::handler);
        NetworkUtils.registerClientToServer(119,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorAction.class,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorAction::encode,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorAction::decode,
                net.shurui.shuruisutilities.racing.net.PacketTrackEditorAction::handler);
        NetworkUtils.registerServerToClient(120,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningOpen.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningOpen::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningOpen::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningOpen::handler);
        NetworkUtils.registerClientToServer(121,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningSave.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningSave::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningSave::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceTuningSave::handler);
        NetworkUtils.registerServerToClient(122,
                net.shurui.shuruisutilities.racing.net.PacketRaceRescue.class,
                net.shurui.shuruisutilities.racing.net.PacketRaceRescue::encode,
                net.shurui.shuruisutilities.racing.net.PacketRaceRescue::decode,
                net.shurui.shuruisutilities.racing.net.PacketRaceRescue::handler);
        // 128-130: the PRIVATE timed-event engine. Fixed ids, registered unconditionally like every other so
        // client and server agree on the channel whatever the key tier says. 123-127 are the spare racing ids and
        // stay UNREGISTERED. 128 (editor open, S2C) and 130 (event state, S2C) have inert client sinks until the
        // client editor/HUD lands in E11; 129 (editor save, C2S) is op-gated and routes to EventWorldHooks
        // (keyless: a no-op). See the design notes.
        NetworkUtils.registerServerToClient(128,
                net.shurui.shuruisutilities.events.network.PacketEventEditorOpen.class,
                net.shurui.shuruisutilities.events.network.PacketEventEditorOpen::encode,
                net.shurui.shuruisutilities.events.network.PacketEventEditorOpen::decode,
                net.shurui.shuruisutilities.events.network.PacketEventEditorOpen::handler);
        NetworkUtils.registerClientToServer(129,
                net.shurui.shuruisutilities.events.network.PacketEventEditorSave.class,
                net.shurui.shuruisutilities.events.network.PacketEventEditorSave::encode,
                net.shurui.shuruisutilities.events.network.PacketEventEditorSave::decode,
                net.shurui.shuruisutilities.events.network.PacketEventEditorSave::handler);
        NetworkUtils.registerServerToClient(130,
                net.shurui.shuruisutilities.events.network.PacketEventState.class,
                net.shurui.shuruisutilities.events.network.PacketEventState::encode,
                net.shurui.shuruisutilities.events.network.PacketEventState::decode,
                net.shurui.shuruisutilities.events.network.PacketEventState::handler);
        // Disguise + /model (DM batch): 148-149.
        NetworkUtils.registerServerToClient(148,
                net.shurui.shuruisutilities.disguise.PacketDisguiseSync.class,
                net.shurui.shuruisutilities.disguise.PacketDisguiseSync::encode,
                net.shurui.shuruisutilities.disguise.PacketDisguiseSync::decode,
                net.shurui.shuruisutilities.disguise.PacketDisguiseSync::handler);
        NetworkUtils.registerServerToClient(149,
                net.shurui.shuruisutilities.model.PacketModelSync.class,
                net.shurui.shuruisutilities.model.PacketModelSync::encode,
                net.shurui.shuruisutilities.model.PacketModelSync::decode,
                net.shurui.shuruisutilities.model.PacketModelSync::handler);
        // 150: the cosmetic art stream (non-Patreon wardrobe cosmetic models, textures, animations and sounds, served
        // by the Ragnarok Key instead of shipped in the jar). BOTH directions on one id: server to client carries a
        // chunk, client to server says what the client holds (join report and per-chunk ack), the 93/97 handshake of
        // the rgnpc pack folded into one packet. Next free id is 151.
        NetworkUtils.registerBothWays(150,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAssets.class,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAssets::encode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAssets::decode,
                net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAssets::handler);
        // Packet6AuthLogin is registered in the Auth Module
        // Packet7Remote is registered in the Remote Module
        MinecraftForge.EVENT_BUS.post(new RegisterPacketEvent());
        // Packet8AuthReply is registered in the Remote Module
        // Packet9AuthRequest is registered in the Remote Module

    }

    @SubscribeEvent
    public void registerCommandEvent(final RegisterCommandsEvent event)
    {
        LoggingHandler.sulog.debug("ShuruisUtilities Register Commands Event");
        SUCommandManager.registerCommand(new CommandSUInfo(true), event.getDispatcher());
        SUCommandManager.registerCommand(new net.shurui.shuruisutilities.core.commands.CommandSUGui(true), event.getDispatcher());
        SUCommandManager.registerCommand(new CommandSuReload(true), event.getDispatcher());

        CommandSuSettings settings = new CommandSuSettings(true);
        SUCommandManager.registerCommand(settings, event.getDispatcher());
        MinecraftForge.EVENT_BUS.register(settings);

        SUCommandManager.registerCommand(new CommandTest(true), event.getDispatcher());
        SUCommandManager.registerCommand(new CommandWand(true), event.getDispatcher());
        SUCommandManager.registerCommand(new CommandUuid(true), event.getDispatcher());
        SUCommandManager.registerCommand(new CommandSUWorldInfo(true), event.getDispatcher());
        // /rgrace refresh [raceId|all] - force SU's bundled DMZ race definitions onto disk (OP; node command.rgrace).
        // Backs up before overwriting; no-op when DMZ is absent or the wish-tracking switch is off.
        SUCommandManager.registerCommand(new net.shurui.shuruisutilities.core.commands.CommandSuRace(true), event.getDispatcher());
        // [slot S21] /rgentity (the rgnpc display NPC spawner and review grid) is registered by the Ragnarok Key
        // (RgEntityFeature), same node command.rgentity.
        if (!ModuleLauncher.getModuleList().contains(WEIntegration.weModule))
        {
            SUCommandManager.registerCommand(new CommandPos1(true), event.getDispatcher());
            SUCommandManager.registerCommand(new CommandPos2(true), event.getDispatcher());
            SUCommandManager.registerCommand(new CommandDeselect(true), event.getDispatcher());
            SUCommandManager.registerCommand(new CommandExpand(true), event.getDispatcher());
            SUCommandManager.registerCommand(new CommandExpandY(true), event.getDispatcher());
        }
    }

    @SubscribeEvent
    public void serverPreInit(ServerAboutToStartEvent e)
    {
        // the dedicated-server "must have Shurui's Key" check used to throw HERE. on Mohist the interleaved
        // Bukkit/Forge lifecycle can reach this before the key mod's constructor runs, bricking boot even
        // though the key is present. moved to serverStarted() with a bounded re-query. see requireServerKeyOrHalt().

        // Version check resolves a LOADED mod container's update JSON, so pass the hosting container id
        // (dmz_ragnarok since the merge), not the "shuruisutilities" registry namespace which is no longer a
        // loaded container. Resolved from the cached active container so it survives a future rename.
        BuildInfo.startVersionChecks(MOD_CONTAINER.getModId());
    	LoggingHandler.sulog.info("Registered " + SUCommandManager.getTotalCommandNumber() + " commands");
        LoggingHandler.sulog.info("ShuruisUtilities ServerAboutToStart");

        // FIRST, before anything reads or (via the seeder below) writes the save: if this world holds content
        // (blocks, items, entities) or Space data from a suite module (Dungeons, Raids, Tournaments, Space) that
        // is NOT installed, refuse to start rather than let vanilla silently drop that content chunk by chunk.
        // Halts to stop startup; writes nothing. Folds in the old Space-only check. See ModuleAbsenceGuard.
        net.shurui.shuruisutilities.ragnarok.ModuleAbsenceGuard.checkOrRefuse(e.getServer());

        // boot diagnostics: log the resolved absolute data roots and whether each has content, so a silent
        // scope flip or a working-dir move is immediately visible in the log instead of invisible.
        logDataRootDiagnostics();

        // Network packets are now registered in preInit (FMLCommonSetupEvent) so BOTH sides register them.

        // MUST run before the DataManager base is set below and before ModulePermissions' backup/load (fired
        // later from serverStarting) so every consumer sees the same root.
        migrateSUDataToStableRootIfNeeded();

        DataManager.setInstance(new DataManager(new File(ShuruisUtilities.getSUDataRoot(), "json")));

        // needs DataManager
        SUCommandManager.loadConfigurableCommand();

        // seed the shipped Planet Vegeta / Beerus region files into the save BEFORE any multiworld
        // handler on the event below can register or tick those dimensions. Copy-if-absent + fail-soft;
        // never blocks startup. See PlanetRegionSeeder.
        net.shurui.shuruisutilities.world.space.PlanetRegionSeeder.seedAll(e.getServer());

        // seed the default 4000x4000 boundary for the generated datapack dimensions namekow and kaiow, if no
        // border record exists yet. Runs here (before levels load) so ModuleWorldBorder reads it at LevelEvent.Load.
        // Seed-if-absent + fail-soft; never blocks startup. See DimensionBorderSeeder.
        net.shurui.shuruisutilities.worldborder.DimensionBorderSeeder.seedDefaults();

        MinecraftForge.EVENT_BUS.post(new SUModuleServerAboutToStartEvent(e));
        new BaublesCompat();
    }

    // one-time copy-only migration of legacy per-world SUData into the stable install-scoped root. runs only
    // when worldScopedData is false, at ServerAboutToStartEvent BEFORE the DataManager base is set and before
    // any module loads permissions/data. if the stable root already has content it's used as-is (legacy left
    // untouched); otherwise legacy content is recursively COPIED over + a .migrated-from.txt marker written.
    // legacy is never written to, moved, or deleted.
    private static void migrateSUDataToStableRootIfNeeded()
    {
        if (SUConfig.worldScopedData)
            return; // legacy mode: stable root == legacy root, nothing to migrate

        File stableRoot = getSUDataRoot();
        File legacyRoot = new File(ServerUtil.getWorldPath(), "SUData");

        // If the resolved stable root and the legacy root are the same directory, there is nothing to do.
        try
        {
            if (stableRoot.getCanonicalFile().equals(legacyRoot.getCanonicalFile()))
                return;
        }
        catch (java.io.IOException ignored)
        {
            // Fall through to path-based logic below if canonicalization fails.
        }

        boolean stableHasContent = suDataHasContent(stableRoot);
        boolean legacyHasContent = suDataHasContent(legacyRoot);

        if (stableHasContent)
        {
            if (legacyHasContent)
                LoggingHandler.sulog.info(
                        "[SUData] Using existing installation-scoped data at \"" + stableRoot.getAbsolutePath()
                                + "\". Legacy per-world data at \"" + legacyRoot.getAbsolutePath()
                                + "\" exists but is being IGNORED and left untouched on disk.");
            return;
        }

        if (!legacyHasContent)
            return; // fresh install with no legacy data - nothing to copy; modules will create the stable root.

        // Stable root empty/absent AND legacy has content -> one-time copy legacy -> stable.
        try
        {
            LoggingHandler.sulog.warn("-------------------------------------------------------------------------------------");
            LoggingHandler.sulog.warn("[SUData] Migrating legacy per-world SU data to the stable installation-scoped root.");
            LoggingHandler.sulog.warn("[SUData]   FROM: " + legacyRoot.getAbsolutePath());
            LoggingHandler.sulog.warn("[SUData]   TO:   " + stableRoot.getAbsolutePath());
            LoggingHandler.sulog.warn("[SUData] The legacy directory is COPIED, never moved or deleted; it stays on disk.");

            org.apache.commons.io.FileUtils.copyDirectory(legacyRoot, stableRoot);

            File marker = new File(stableRoot, ".migrated-from.txt");
            String stamp = SUConfig.FORMAT_DATE_TIME_SECONDS.format(new java.util.Date());
            org.apache.commons.io.FileUtils.writeStringToFile(marker,
                    "Migrated from: " + legacyRoot.getAbsolutePath() + System.lineSeparator()
                            + "At: " + stamp + System.lineSeparator()
                            + "Source directory was copied (not moved) and left untouched." + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);

            LoggingHandler.sulog.warn("[SUData] Migration copy complete. Wrote marker \"" + marker.getAbsolutePath() + "\".");
            LoggingHandler.sulog.warn("-------------------------------------------------------------------------------------");
        }
        catch (java.io.IOException ex)
        {
            LoggingHandler.sulog.error(
                    "[SUData] Failed to copy legacy SU data from \"" + legacyRoot.getAbsolutePath() + "\" to \""
                            + stableRoot.getAbsolutePath() + "\". The legacy data is untouched; SU will start with whatever "
                            + "is present at the stable root.",
                    ex);
        }
    }

    // single clearly labelled block listing the resolved absolute data roots + whether they hold content.
    // an admin can grep "[SUData][boot]" to confirm where SU is reading/writing this boot. never throws.
    private static void logDataRootDiagnostics()
    {
        try
        {
            File suDir = getSUDirectory();
            File dataRoot = getSUDataRoot();
            LoggingHandler.sulog.info("=====================================================================================");
            LoggingHandler.sulog.info("[SUData][boot] SU storage diagnostics");
            LoggingHandler.sulog.info("[SUData][boot]   worldScopedData = " + SUConfig.worldScopedData);
            LoggingHandler.sulog.info("[SUData][boot]   getSUDirectory() (group B: economy/guilds/npcregions/...) = "
                    + absolute(suDir) + " [hasContent=" + groupBHasContent(suDir) + "]");
            LoggingHandler.sulog.info("[SUData][boot]   getSUDataRoot()  (permissions.json + json/ tree)         = "
                    + absolute(dataRoot) + " [hasContent=" + suDataHasContent(dataRoot) + "]");
            LoggingHandler.sulog.info("[SUData][boot]   SUData backup root = " + absolute(getSUDataBackupRoot()));
            LoggingHandler.sulog.info("[SUData][boot]   group B backup root = " + absolute(getGroupBBackupRoot()));
            LoggingHandler.sulog.info("=====================================================================================");
        }
        catch (Exception ex)
        {
            LoggingHandler.sulog.warn("[SUData][boot] Failed to log storage diagnostics: " + ex.getMessage());
        }
    }

    private static String absolute(File f)
    {
        if (f == null)
            return "<null>";
        try
        {
            return f.getCanonicalPath();
        }
        catch (java.io.IOException ex)
        {
            return f.getAbsolutePath();
        }
    }

    // true ONLY when an SUData dir holds REAL data, never when it merely holds empty directories. this predicate
    // is reused by ModulePermissions' backup / restore / seed guards, so "empty dirs count as content" here
    // silently defeats all of them (a fresh empty json/ subtree created by DataManager at preInit looked
    // populated, blocking auto-restore and overwriting the last-good backup with nothing). content means any of:
    //   - a non-empty permissions.json                         (SingleFileProvider, the default backend)
    //   - a non-empty json/Permissions dir                     (JsonProvider)
    //   - a non-empty permissions/ dir                          (FlatfileProvider)
    //   - ANY regular non-empty file anywhere beneath json/     (module data: JailPoint, Kit, Portal, ...)
    // empty directories at any depth do NOT count.
    public static boolean suDataHasContent(File suData)
    {
        if (suData == null || !suData.isDirectory())
            return false;

        // singlejson backend
        if (isNonEmptyFile(new File(suData, "permissions.json")))
            return true;

        // flatfile backend lives in permissions/
        if (dirContainsRealFile(new File(suData, "permissions")))
            return true;

        // json backend (json/Permissions) and every module store live under json/. one recursive walk covers
        // both: any real file anywhere beneath json/ is real data; empty subdirs are ignored.
        if (dirContainsRealFile(new File(suData, "json")))
            return true;

        return false;
    }

    // true when f is a regular file with bytes in it. empty (0-byte) files do not count as data.
    private static boolean isNonEmptyFile(File f)
    {
        return f != null && f.isFile() && f.length() > 0L;
    }

    // true when dir is a directory that contains at least one non-empty regular file at any depth. empty dirs,
    // and trees made only of empty dirs, return false. bounded, null-safe, never throws.
    private static boolean dirContainsRealFile(File dir)
    {
        if (dir == null || !dir.isDirectory())
            return false;
        File[] children = dir.listFiles();
        if (children == null)
            return false;
        for (File c : children)
        {
            if (c.isFile())
            {
                if (c.length() > 0L)
                    return true;
            }
            else if (c.isDirectory())
            {
                if (dirContainsRealFile(c))
                    return true;
            }
        }
        return false;
    }

    // group B stores: the durable user-data files/dirs that live directly under getSUDirectory() and, unlike
    // SUData, have historically had NO backup/restore. this is an explicit allowlist (not the whole
    // ShuruisUtilities/ dir) so we never snapshot large or churny regenerable state each boot: module .cfg /
    // .toml, Modules.cfg, Settings.cfg, chat logs, the import/ scratch dir, and SUData itself (which already
    // has its own dedicated backup) are deliberately excluded. derived by reading the manager classes:
    //   economy/ (EconomyManager), guilds/ (GuildManager), ranks/ (rank store), tablist/ (tablist store),
    //   npcregions.json, bounties.json, crates.json, regions.json, shrines.json, airdrop.json,
    //   holograms.json, banned_items.json, tpboost.json.
    public static final String[] GROUP_B_STORE_NAMES = {
        "economy",
        "guilds",
        "ranks",
        "tablist",
        "npcregions.json",
        "bounties.json",
        "crates.json",
        "regions.json",
        "shrines.json",
        "airdrop.json",
        "holograms.json",
        "banned_items.json",
        "tpboost.json",
        "zorbs.json"
    };

    // resolved File for each group B store under a given base dir (live SU dir, or a backup snapshot).
    public static java.util.List<File> getGroupBStores(File baseDir)
    {
        java.util.List<File> out = new java.util.ArrayList<>();
        if (baseDir == null)
            return out;
        for (String name : GROUP_B_STORE_NAMES)
            out.add(new File(baseDir, name));
        return out;
    }

    // true when a store path (file or directory) has real content: a regular file that is non-empty, or a
    // directory containing at least one child. used to decide "has content" for backup-only-when-populated
    // and restore-only-when-missing-or-empty semantics.
    public static boolean storeHasContent(File store)
    {
        if (store == null || !store.exists())
            return false;
        if (store.isDirectory())
        {
            String[] children = store.list();
            return children != null && children.length > 0;
        }
        return store.isFile() && store.length() > 0L;
    }

    // true when ANY group B store under baseDir currently has content. used to broaden the fresh-install test.
    public static boolean groupBHasContent(File baseDir)
    {
        if (baseDir == null)
            return false;
        for (File store : getGroupBStores(baseDir))
            if (storeHasContent(store))
                return true;
        return false;
    }

    // true when ANY SU store anywhere has content: the live SUData root OR any group B store under the SU dir.
    // used to gate default seeding on a genuine fresh install (never reseed over a live server).
    public static boolean anySUStoreHasContent()
    {
        return suDataHasContent(getSUDataRoot()) || groupBHasContent(getSUDirectory());
    }

    // group B backup dir, sibling-style under the SU dir: <SU dir>/GroupB_backup
    public static File getGroupBBackupRoot()
    {
        return new File(getSUDirectory(), "GroupB_backup");
    }

    public static final String GROUPB_BACKUP_PREFIX = "GroupB_backup_";

    @SubscribeEvent
    public void serverStarting(ServerStartingEvent e)
    {
        LoggingHandler.sulog.info("ShuruisUtilities ServerStarting");
        BlockModListFile.makeModList();
        BlockModListFile.dumpFMLRegistries();

        registerPermissions();
        SUCommandManager.aliaseManager.saveData();

        MinecraftForge.EVENT_BUS.post(new SUModuleServerStartingEvent(e));
        if(BuildInfo.isOutdated()) {
        	LoggingHandler.sulog.warn("-------------------------------------------------------------------------------------");
        	LoggingHandler.sulog.warn(Translator.format("WARNING! Using ShuruisUtilities build #%s, latest build is #%s",BuildInfo.getCurrentVersion(), BuildInfo.getLatestVersion()));
        	LoggingHandler.sulog.warn("We highly recommend updating asap to get the latest security and bug fixes");
        	LoggingHandler.sulog.warn("-------------------------------------------------------------------------------------");
        }
    }

    /**
     * Runs a one-time block-update sweep over any planet region files seeded into the save during THIS boot.
     *
     * <p>Fail soft on purpose: a cosmetic repair must never be the reason a server fails to finish starting, so every
     * failure is logged and swallowed. A dimension that is not loaded on this install is skipped rather than forced
     * into existence.
     */
    private void sweepFreshlySeededPlanets(ServerStartedEvent e)
    {
        try
        {
            var bounds = net.shurui.shuruisutilities.world.space.PlanetRegionSeeder.drainFreshlySeededBounds();
            for (var entry : bounds.entrySet())
            {
                net.minecraft.server.level.ServerLevel level = e.getServer().getLevel(entry.getKey());
                if (level == null)
                    continue;
                int[] rect = entry.getValue();
                net.shurui.shuruisutilities.commands.world.TickTaskAreaBlockUpdater.startHeadless(
                        level, rect[0], rect[1], rect[2], rect[3], "seeded planet build");
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetSeeder] could not start the seeded-build block sweep: " + t);
        }
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent e)
    {
        LoggingHandler.sulog.info("ShuruisUtilities ServerStarted");

        // Log which tier this server is on: with the Ragnarok Key it is full; without it runs the public feature set.
        resolveServerKeyTier(e);

        // The keyless tier: no Ragnarok Key leaves only the public feature set standing.
        net.shurui.shuruisutilities.core.config.PublicContent.enforce();

        // Copy our worldgen JSON into this world's own datapacks folder. Minecraft has already baked our dimensions
        // into level.dat by now, and without those definitions the world cannot be opened again once this mod is
        // uninstalled, so the copy travels with the save. Idempotent and silent when nothing changed.
        WorldgenShim.write(e.getServer());

        MinecraftForge.EVENT_BUS.post(new SUModuleServerStartedEvent(e));

        // Reconnect the authored planet builds that were seeded into this save a moment ago. Those chunks arrive as
        // raw region files, so nothing has ever run a shape or neighbour update on them and everything that connects
        // (fences, panes, walls, stairs, redstone, leaves) is sitting in whatever state was written. The sweep is
        // budgeted per tick and skips chunk sections whose palette cannot connect, so on a boot that seeded nothing
        // this costs one empty map lookup. See TickTaskAreaBlockUpdater.
        sweepFreshlySeededPlanets(e);

        // [slot S18a] seed vanish from its store so invisibility survives a restart, then the re-hide handler (keeps
        // vanished players hidden through teleport / dimension change). Both stay core and run keyless as before; the
        // /vanish toggle and the su:vanish shard state are the Ragnarok Key's (ModerationFeature).
        net.shurui.shuruisutilities.commands.player.VanishState.loadFromStorage(e.getServer());
        MinecraftForge.EVENT_BUS.register(new net.shurui.shuruisutilities.commands.player.VanishEventHandler());

        // permission registration happens in the first server tick
        MinecraftForge.EVENT_BUS.register(new CommandPermissionRegistrationHandler());

        // Extract SU's bundled shadow dragon races into DMZ's config tree if absent, then reload DMZ so it scans
        // them. Routed through the guard so DMZ classes are only touched when it is present; server-side only.
        net.shurui.shuruisutilities.compat.dmz.RaceBundleCompat.extractOnServerStart();

        // Seed the dragon-ball ritual forms into DMZ's own saiyan/namekian races (SSJ5, Super Saiyan God, Primal
        // Namekian) if absent, and patch the price rungs so SSJ5/Primal Namekian are grant-only and SSG is gated.
        // Runs after RaceBundle above so DMZ's default race files exist; reloads DMZ once if anything changed.
        net.shurui.shuruisutilities.compat.dmz.RitualRaceMerge.seedOnServerStart();

        // Name duplicate DMZ form group files (two files with one groupName, or one form in two groups of a race).
        // Read only: live data for staff to clean up, never changed here.
        net.shurui.shuruisutilities.compat.dmz.FormGroupDuplicateCheck.warnOnServerStart();

        // DMZ reloads its config from disk each startup, so re-apply the wish-tracking suppression here (after
        // DMZ's ConfigManager has loaded) whenever the saved data is still armed. Routed through the guard so DMZ
        // classes are only touched when it is present.
        net.shurui.shuruisutilities.compat.dmz.WishTrackingCompat.onServerStarted(e.getServer());

        // Seed Planet Vegeta's 10x gravity into DMZ's own per-dimension gravity config if absent. DMZ has already
        // loaded general-server.json (mod construction) and does not rewrite it again at startup, and no player has
        // connected yet, so the value reaches the on-disk file the client sync reads on login. Seed-if-absent, so an
        // admin's tuned value is left alone. Routed through the guard; server-side only, never throws.
        net.shurui.shuruisutilities.compat.dmz.PlanetGravityCompat.seedOnServerStart();

        // [slot S19b] prestige:settings: registered by the Ragnarok Key (PrestigeFeature.install), same key and NBT.

        // The rest of the player-facing state that lives in a SavedData rather than on the player, and so does not
        // travel with the vault. Each MERGES on write (never a whole-table replace) so one player's record is never
        // dropped by an edit made for another. Registered here because sdu cannot import the shard package (the "sdu
        // imports nothing from SU" invariant), and SU may import sdu, so the layer above owns the wiring.
        registerCrossServerPlayerState();

        // Admin-editable server state that lives in an SU manager or toml rather than on the player, and so does not
        // travel with the vault: the task pool, the banned-item list, the staff task board, and the admin-GUI config
        // knobs that used to re-bake on the editing server only. Each MERGES per entry (or is whole-record
        // last-write-wins for a single config record), never a whole-table replace, so a concurrent edit is never
        // dropped and no sync ever deletes by omission.
        registerCrossServerAdminState();

        // Cross-shard dragon radar. Each shard publishes the ball positions of the dimensions it hosts and folds the
        // network-wide view into the radar packet, so a player sees a set everywhere it exists rather than only the
        // part in dimensions the local shard hosts. Per-dimension merge with an owner+stamp, so a shard never erases
        // another's dimensions and an offline shard's last-known balls keep showing. Collection stays local: this
        // tells a player WHERE to travel, it never enables remote pickup. Guarded so a failure only drops this entry.
        registerStateSync(net.shurui.shuruisutilities.compat.dmz.CrossShardRadar.STATE_KEY,
                net.shurui.shuruisutilities.compat.dmz.CrossShardRadar::read,
                net.shurui.shuruisutilities.compat.dmz.CrossShardRadar::write);
    }

    /**
     * Cross-server sync for the suite's admin-owned server state. Every write path MERGES and cannot delete by
     * omission (removals travel as explicit tombstones). Each registration is guarded so a failure only drops that one
     * entry from the network, never server start.
     */
    private void registerCrossServerAdminState()
    {
        // S-phase slot markers (S0): as in the constructor, each marker names the batch that moves the registration
        // right below it into its feature's install(). Keep the blank lines between blocks.

        // [slot S7] su:task_pool: registered by the Ragnarok Key (TaskFeature.install), same key and NBT.

        // [slot S11] su:banned_items: registered by the Ragnarok Key (BanItemFeature.install), same key and NBT.

        // [slot S7] su:staff_tasks: registered by the Ragnarok Key (StaffFeature.install), same key and NBT.

        // Admin-GUI config knobs: each travels AND re-bakes live on the receiving server (no restart). Whole-record
        // last-write-wins per config, the same trade the existing config-file and prestige syncs make.
        registerStaticStateSync("su:cfg_hoverbikes",
                net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes::saveState,
                net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes::mergeState);

        // [slot S19a] su:cfg_saibaman_pet: registered by the Ragnarok Key (SaibamanPetFeature.install), same key and NBT.

        // [slot S19a] su:cfg_sparring: registered by the Ragnarok Key (SparringFeature.install), same key and NBT.

        // [slot S1] su:cfg_economy: registered by the Ragnarok Key (EconomyFeature.install), same key and NBT.

        // Grave/totem despawn lifetime (minutes), set by /rg npc totem time. The store lives in sdu so the /rg
        // command and the grave sweep can both reach it; here we just carry it across shards, whole-record
        // last-write-wins like the other cfg knobs, so setting it on one shard sets it everywhere.
        registerStaticStateSync("su:cfg_grave_totem",
                net.shurui.dev.sdu.grave.GraveTotemConfig::saveState,
                net.shurui.dev.sdu.grave.GraveTotemConfig::mergeState);

        // Game rules. Every registered rule (vanilla and modded, boolean and integer) rides one whole-record entry, so
        // a /gamerule change on any shard is applied live everywhere. Rules live in each world's level.dat and nothing
        // else syncs them, which is how allowKiGriefingMobs/allowKiGriefingPlayers/terrainRegen drifted between shards.
        // Server-aware read/write: it reads and sets the live GameRules through the running server, and each rule is set
        // via its own set() so the rule's callback fires exactly as /gamerule would. See GameRuleSync.
        registerStateSync(net.shurui.shuruisutilities.shard.GameRuleSync.STATE_KEY,
                net.shurui.shuruisutilities.shard.GameRuleSync::read,
                net.shurui.shuruisutilities.shard.GameRuleSync::write);
    }

    /**
     * Register one server-global piece of state (an SU static manager or toml) with the state sync. Unlike
     * {@link #registerStateSync}, the read and write do not need the MinecraftServer, because these stores are static
     * singletons. Guarded, so a registration failure only costs that one entry.
     */
    private void registerStaticStateSync(String key, java.util.function.Supplier<net.minecraft.nbt.CompoundTag> read,
                                         java.util.function.Consumer<net.minecraft.nbt.CompoundTag> write)
    {
        try
        {
            net.shurui.shuruisutilities.shard.ShardStateSync.register(key, read, write);
        }
        catch (Throwable t)
        {
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[shard] Could not register state sync '{}': {}", key, t.toString());
        }
    }

    private void registerCrossServerPlayerState()
    {
        // [slot S18a] su:vanish: registered by the Ragnarok Key (ModerationFeature.install), same key and NBT.

        // Repeatable-quest cooldowns (sdu's own store): keeps the later completion per (player, quest), so a daily or
        // weekly cannot be farmed by bouncing between servers. See QuestRepeatStore.mergeInto.
        registerStateSync("sdu:quest_repeats",
                s -> net.shurui.dev.sdu.quest.QuestRepeatStore.get(s).save(new net.minecraft.nbt.CompoundTag()),
                (s, tag) -> net.shurui.dev.sdu.quest.QuestRepeatStore.get(s).mergeInto(tag),
                s -> net.shurui.dev.sdu.quest.QuestRepeatStore.get(s).shardDirtyVersion());

        // [slot S16] guild:raid_lockouts is registered by the Ragnarok Key's GuildRaidFeature (same key, same NBT).

        // God-of-destruction hakai cooldown: an eight hour real-time gate that must not reset on a hop, or a god fires
        // hakai again the moment they change server. Keeps the LATER expiry per player, so a merge can only hold a
        // cooldown open, never shorten it. See GodHakaiStorage.mergeInto.
        registerStateSync("su:hakai_cooldown",
                s -> net.shurui.shuruisutilities.god.GodHakaiStorage.get(s)
                        .save(new net.minecraft.nbt.CompoundTag()),
                (s, tag) -> net.shurui.shuruisutilities.god.GodHakaiStorage.get(s).mergeInto(tag),
                s -> net.shurui.shuruisutilities.god.GodHakaiStorage.get(s).shardDirtyVersion());

        // Role energy bars and the shared pool: spent energy must not refill by hopping, but it must still regenerate
        // over time, so the merge is per-player timestamped last-write-wins. Regeneration only runs for online players,
        // so the active server always holds the newest stamp: a fresh drain wins across a hop while a stale higher
        // value on a server the player left loses. See EnergyData.mergeInto.
        registerStateSync("su:role_energy",
                s -> net.shurui.shuruisutilities.energy.EnergyData.get(s)
                        .save(new net.minecraft.nbt.CompoundTag()),
                (s, tag) -> net.shurui.shuruisutilities.energy.EnergyData.get(s).mergeInto(tag),
                s -> net.shurui.shuruisutilities.energy.EnergyData.get(s).shardDirtyVersion());

        // Form cosmetic override: a chosen cosmetic should follow the player, not vanish on a hop. A per-player
        // timestamped merge, so both a set and a clear carry across without clobbering another player's concurrent
        // change. Last-write-wins per player, which for a cosmetic has no cheat direction. See FormCosmeticData.mergeInto.
        registerStateSync("su:form_cosmetic",
                s -> net.shurui.shuruisutilities.cosmetics.form.FormCosmeticData.get(s)
                        .save(new net.minecraft.nbt.CompoundTag()),
                (s, tag) -> net.shurui.shuruisutilities.cosmetics.form.FormCosmeticData.get(s).mergeInto(tag),
                s -> net.shurui.shuruisutilities.cosmetics.form.FormCosmeticData.get(s).shardDirtyVersion());

        // Korin's weekly senzu handout cooldown: a real-life week is worthless if it can be dodged by hopping shards, so
        // the per-player wall-clock expiry must be network-wide. Keeps the LATER expiry per player, so a merge can only
        // hold the gate open, never reset it. See KorinCooldowns.mergeInto.
        registerStateSync("su:korin_senzu",
                s -> net.shurui.shuruisutilities.compat.dmz.KorinCooldowns.get(s)
                        .save(new net.minecraft.nbt.CompoundTag()),
                (s, tag) -> net.shurui.shuruisutilities.compat.dmz.KorinCooldowns.get(s).mergeInto(tag),
                s -> net.shurui.shuruisutilities.compat.dmz.KorinCooldowns.get(s).shardDirtyVersion());

        // Manual waypoints (sdu's own sdu_waypoints SavedData, keyed by player UUID): a player's marks should follow
        // them across a hop, not vanish. A per-player timestamped merge, so a set, a rename or a full clear carries
        // across without clobbering another player's marks. Last-write-wins per player, which for a player's own list
        // has no cheat direction because only the server they are on stamps a change. See WaypointStore.mergeInto.
        registerStateSync("su:waypoints",
                s -> net.shurui.dev.sdu.waypoint.WaypointStore.get(s).save(new net.minecraft.nbt.CompoundTag()),
                (s, tag) -> net.shurui.dev.sdu.waypoint.WaypointStore.get(s).mergeInto(tag),
                s -> net.shurui.dev.sdu.waypoint.WaypointStore.get(s).shardDirtyVersion());

        // The cosmetic CATALOGUE: an admin who defines a hat on OW1 must have it on OW2 without a restart.
        // This is the same problem ShardStateSync exists for, and the merge is per definition id with a
        // tombstone for a deletion (see CosmeticCatalog.mergeState), so two admins adding different cosmetics on
        // two servers keep both and a deletion carries rather than being undone by the next merge.
        registerStateSync("su:cosmetic_catalog",
                s -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog.saveState(),
                (s, tag) -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog.mergeState(tag),
                s -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog.dirtyVersion());

        // What each player is WEARING. A per-player timestamped merge, exactly as su:form_cosmetic is and for
        // exactly the same reason: a plain union merge could never propagate a clear, so a cosmetic taken off on
        // one server would come back on the next hop. Last-write-wins per player, which for what somebody has on
        // has no cheat direction. See CosmeticWardrobeData.mergeInto.
        registerStateSync("su:cosmetic_wardrobe",
                s -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticWardrobeData.get(s).saveState(),
                (s, tag) -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticWardrobeData.get(s)
                        .mergeInto(tag),
                s -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticWardrobeData.get(s)
                        .shardDirtyVersion());

        // The ownership LEDGER. NOT last-write-wins: rows are keyed by instance id and merged per row, so this
        // is a grow-only set with tombstones. That is what ShardPayload's own note about the ledgers demands,
        // because a whole-store swap on a set of owned items either loses a grant or resurrects a revoked one,
        // and with real money involved neither is acceptable. Being idempotent and commutative is also what lets
        // the same merge function be fed by the per-player snapshot ShardPayload carries, without the two paths
        // fighting. See CosmeticLedgerData.mergeInto.
        registerStateSync("su:cosmetic_ledger",
                s -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticLedgerData.get(s).saveState(),
                (s, tag) -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticLedgerData.get(s)
                        .mergeInto(tag),
                s -> net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticLedgerData.get(s)
                        .shardDirtyVersion());
    }

    private interface StateRead
    {
        net.minecraft.nbt.CompoundTag apply(net.minecraft.server.MinecraftServer server);
    }

    private interface StateWrite
    {
        void apply(net.minecraft.server.MinecraftServer server, net.minecraft.nbt.CompoundTag tag);
    }

    private interface StateSignal
    {
        long apply(net.minecraft.server.MinecraftServer server);
    }

    // As registerStateSync above, but with a cheap monotonic change signal (the store's shardDirtyVersion()), so the
    // state sync skips rebuilding this store's NBT while it has not changed. Same guards; the signal resolves the
    // server on demand exactly as the read and write do, and reports Long.MIN_VALUE when there is no server yet so the
    // sync simply falls through to the (null) read on that pass.
    private void registerStateSync(String key, StateRead read, StateWrite write, StateSignal signal)
    {
        try
        {
            net.shurui.shuruisutilities.shard.ShardStateSync.register(key,
                    () ->
                    {
                        net.minecraft.server.MinecraftServer s =
                                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        return s == null ? null : read.apply(s);
                    },
                    tag ->
                    {
                        net.minecraft.server.MinecraftServer s =
                                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        if (s != null)
                            write.apply(s, tag);
                    },
                    () ->
                    {
                        net.minecraft.server.MinecraftServer s =
                                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        return s == null ? Long.MIN_VALUE : signal.apply(s);
                    });
        }
        catch (Throwable t)
        {
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[shard] Could not register state sync '{}': {}", key, t.toString());
        }
    }

    // Register one entry, resolving the current server on demand (so it survives a world reload) and guarding the
    // whole thing, since a registration failure must never take server start down; the piece simply does not travel.
    private void registerStateSync(String key, StateRead read, StateWrite write)
    {
        try
        {
            net.shurui.shuruisutilities.shard.ShardStateSync.register(key,
                    () ->
                    {
                        net.minecraft.server.MinecraftServer s =
                                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        return s == null ? null : read.apply(s);
                    },
                    tag ->
                    {
                        net.minecraft.server.MinecraftServer s =
                                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        if (s != null)
                            write.apply(s, tag);
                    });
        }
        catch (Throwable t)
        {
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[shard] Could not register state sync '{}': {}", key, t.toString());
        }
    }

    // [slot S19b] the prestige:settings registration body: moved to the Ragnarok Key (PrestigeFeature.install).

    /**
     * Log which tier this server is on.
     *
     * <p>The Ragnarok Key mod marks its install ({@code KeyFeatures} "ragnarok_key") at mod construction, long before
     * ServerStartedEvent, so one read is final: no re-query is needed.
     *
     * <h2>Why this does not halt</h2>
     * A keyless server runs the PUBLIC feature set (the content editors, 1v1 tournaments, basic raids, the dungeon
     * spawner), so it must boot. The private logic is simply not installed without the key.
     */
    private static void resolveServerKeyTier(ServerStartedEvent e)
    {
        // No singleplayer/LAN exemption: an integrated server without the key runs the public feature set exactly as
        // a keyless dedicated server does.
        if (net.shurui.dev.sdu.api.RagnarokKey.present())
            return;

        LoggingHandler.sulog.info("-------------------------------------------------------------------------------------");
        LoggingHandler.sulog.info("No Server Key found. Running the public feature set.");
        LoggingHandler.sulog.info("Install the Ragnarok Key (dmz_ragnarok_key) to enable the full module set.");
        LoggingHandler.sulog.info("-------------------------------------------------------------------------------------");
    }

    public static final class CommandPermissionRegistrationHandler
    {
        @SubscribeEvent
        public void serverTickEvent(TickEvent.ServerTickEvent event)
        {
            CommandPermissionManager.registerCommandPermissions();
            MinecraftForge.EVENT_BUS.unregister(this);
        }
    }

    @SubscribeEvent
    public void serverStopping(ServerStoppingEvent e)
    {
        LoggingHandler.sulog.info("ShuruisUtilities ServerStopping");
        MinecraftForge.EVENT_BUS.post(new SUModuleServerStoppingEvent(e));
        PlayerInfo.discardAll();
    }

    @SubscribeEvent
    public void serverStopped(ServerStoppedEvent e)
    {
        LoggingHandler.sulog.info("ShuruisUtilities ServerStopped");
        try
        {
            MinecraftForge.EVENT_BUS.post(new SUModuleServerStoppedEvent(e));
            SUCommandManager.clearRegisteredCommands();
            Translator.save();
        }
        catch (RuntimeException ex)
        {
            LoggingHandler.sulog.fatal("Caught Runtime Exception During Server Stop event! Suppressing Fire!", ex);
        }
    }

    protected void registerPermissions()
    {
        // The Permissions module can be switched off in modules.cfg, which leaves APIRegistry.perms null. This
        // runs at ServerStarting and every line below it dereferences that field, so without this guard turning
        // the module off crashed the server on the way up. One check at the top rather than twenty inside,
        // because there is nothing here worth doing piecemeal: with no permission tree, none of these
        // registrations have anywhere to go.
        if (APIRegistry.perms == null)
        {
            LoggingHandler.sulog.info(
                    "[Permissions] Module is off, so SU's own permissions are not registered. Commands fall back "
                            + "to their vanilla op level.");
            return;
        }
        APIRegistry.perms.registerPermission(PERM_VERSIONINFO, DefaultPermissionLevel.OP,
                "Shows notification to the player if SU version is outdated");

        APIRegistry.perms.registerPermission(net.shurui.shuruisutilities.deletionwand.DeletionWandHandler.PERM,
                DefaultPermissionLevel.OP,
                "Use the admin Deletion Wand (left-click deletes entities, right-click deletes holograms)");

        CommandPermissionManager.registerCommandPermission("help", DefaultPermissionLevel.ALL);
        CommandPermissionManager.registerCommandPermission("config", DefaultPermissionLevel.OP);
        CommandPermissionManager.registerCommandPermission("forge", DefaultPermissionLevel.OP);
        CommandPermissionManager.registerCommandPermission("trigger", DefaultPermissionLevel.OP);

        // Teleport
        APIRegistry.perms.registerPermissionProperty(TeleportHelper.TELEPORT_COOLDOWN, "5",
                "Allow bypassing teleport cooldown");
        APIRegistry.perms.registerPermissionProperty(TeleportHelper.TELEPORT_WARMUP, "3",
                "Allow bypassing teleport warmup");
        APIRegistry.perms.registerPermissionPropertyOp(TeleportHelper.TELEPORT_COOLDOWN, "0");
        APIRegistry.perms.registerPermissionPropertyOp(TeleportHelper.TELEPORT_WARMUP, "0");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_CROSSDIM_FROM, DefaultPermissionLevel.ALL,
                "Allow teleporting cross-dimensionally from a dimension");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_CROSSDIM_TO, DefaultPermissionLevel.ALL,
                "Allow teleporting cross-dimensionally to a dimension");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_CROSSDIM_PORTALFROM, DefaultPermissionLevel.ALL,
                "Allow teleporting cross-dimensionally from a dimension via a portal");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_CROSSDIM_PORTALTO, DefaultPermissionLevel.ALL,
                "Allow teleporting cross-dimensionally to a dimension via a portal (target coordinates are origin for vanilla portals)");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_FROM, DefaultPermissionLevel.ALL,
                "Allow being teleported from a certain location / dimension");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_TO, DefaultPermissionLevel.ALL,
                "Allow being teleported to a certain location / dimension");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_PORTALFROM, DefaultPermissionLevel.ALL,
                "Allow being teleported from a certain location / dimension via a portal");
        APIRegistry.perms.registerPermission(TeleportHelper.TELEPORT_PORTALTO, DefaultPermissionLevel.ALL,
                "Allow being teleported to a certain location / dimension via a portal");
        APIRegistry.perms.registerPermission(ProtectionPerms.COMMANDBLOCK_PERM, DefaultPermissionLevel.OP,
                "Lowest node for restricting access to CommandBlocks");
        // [slot S12] The hand-built dimension guard runs on every server, but its bypass node is registered by the
        // Protection module, which is in the Ragnarok Key. Without the module register it here, or the unregistered
        // node would answer "allow" to everyone and the guard would stop nobody (keyless it was registered OP, by the
        // module, before its teardown). With the key the module registers it as it always did.
        if (!net.shurui.shuruisutilities.api.key.ProtectionHooks.available())
            APIRegistry.perms.registerPermission(net.shurui.shuruisutilities.protection.BuildDimensionProtection.PERM_BYPASS,
                    DefaultPermissionLevel.OP,
                    "Bypass protection in hand-built protected dimensions (break/place blocks, harm NPCs, use armor stands)");

        CommandSuSettings.addSetting("Teleport", "warmup", TeleportHelper.TELEPORT_WARMUP);
        CommandSuSettings.addSetting("Teleport", "cooldown", TeleportHelper.TELEPORT_COOLDOWN);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void playerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof Player)
        {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            UserIdent.login(player);
            PlayerInfo.login(player.getGameProfile().getId());
            try
            {
                PlayerInfo.login(player.getGameProfile().getId());
            }
            catch (JsonParseException e)
            {
                player.connection.disconnect(Component.literal(
                        "Unable to Parse PlayerInfo file, please contact your admin for assistance and ask them to check the log!"));
                LoggingHandler.sulog.fatal(
                        "Unable to Parse PlayerInfo file!  If this is date related, please check S:format_gson_compat in your main.cfg file!",
                        e);
            }
            if (SUConfig.checkSpacesInNames)
            {
                Pattern pattern = Pattern.compile("\\s");
                Matcher matcher = pattern.matcher(player.getGameProfile().getName());
                if (matcher.find())
                {
                    String msg = Translator.format("Invalid name \"%s\" containing spaces. Please change your name!",
                            event.getEntity().getDisplayName().getString());
                    Entity entity = event.getEntity();
                    if (!(entity instanceof ServerPlayer))
                    {
                        return;
                    }

                    ServerPlayer serverplayer = (ServerPlayer) entity;
                    serverplayer.connection.disconnect(Component.literal(msg));
                }
            }

            // Shadow dragon (phase 4b): re-apply the transformation skill for a transformation-unlocked shadow dragon
            // (durable across a DMZ character reset) and deliver any unlock notices owed from an offline grant. Cheap:
            // does nothing when the player holds none of the shadow dragon unlock properties.
            net.shurui.shuruisutilities.corrupted.ShadowDragonUnlocks.onLogin(player);

            // Show version notification
            if (BuildInfo.isOutdated() && UserIdent.get(player).checkPermission(PERM_VERSIONINFO))
                ChatOutputHandler.chatWarning(player, "ShuruisUtilities server build #%s is outdated. The current build is #%s. Consider updating to get latest security and bug fixes.", BuildInfo.getCurrentVersion(), BuildInfo.getLatestVersion());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void playerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof Player)
        {
            PlayerInfo.logout(event.getEntity().getGameProfile().getId());
            UserIdent.logout((Player) event.getEntity());
            // Zeni shop tab is UI state worth nothing after a disconnect, and the client re-reports it on the next
            // shop it opens. Dropping it here keeps the map from holding a row per UUID that ever logged in.
            net.shurui.shuruisutilities.compat.customnpcs.ZeniShopTabs.clear(
                    event.getEntity().getGameProfile().getId());
        }
    }

    @SubscribeEvent
    public void playerRespawnEvent(PlayerEvent.PlayerRespawnEvent event)
    {
        if (event.getEntity() instanceof Player)
        {
            UserIdent.get((Player) event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void commandEvent(CommandEvent event) throws CommandSyntaxException
    {
        if (event.getParseResults().getContext().getNodes().isEmpty())
            return;
        boolean perm = false;
        CommandInfo info = CommandUtils.getCommandInfo(event);
        if (info.getSource().getEntity() instanceof ServerPlayer)
        {
            // Fold aliases onto the main command so an alias is gated by the primary command's permission node.
            String node = net.shurui.shuruisutilities.core.commands.registration.SUCommandManager
                    .canonicalPermissionNode(info.getPermissionNode());
            perm = checkPerms("command." + node, CommandUtils.getServerPlayer(info.getSource()));
        }
        else
        {
            perm = true;
        }

        if (logCommandsToConsole)
        {
            LoggingHandler.sulog
                    .info(String.format("Player \"%s\" %s command \"%s %s\"", info.getSource().getTextName(),
                            perm ? "used" : "tried to use", info.getCommandName(), info.getActualArgsString()));
        }

        if (!perm)
        {
            event.setCanceled(true);
            info.getSource().sendFailure(Component.literal("You dont have permission to use this command!"));
        }
    }

    public boolean checkPerms(String commandPermissionNode, ServerPlayer sender)
    {
        //LoggingHandler.sulog.debug("Checking command perm: " + commandPermissionNode);
        return APIRegistry.perms.checkUserPermission(UserIdent.get(sender), commandPermissionNode);
    }

    static ForgeConfigSpec.BooleanValue SUcheckVersion;
    static ForgeConfigSpec.BooleanValue SUdebugMode;
    static ForgeConfigSpec.BooleanValue SUsafeMode;
    static ForgeConfigSpec.BooleanValue SUhideWorldEditCommands;
    static ForgeConfigSpec.BooleanValue SUlogCommandsToConsole;

    public static Builder load(Builder BUILDER, boolean isReload)
    {
        SUcheckVersion = BUILDER.comment("Check for newer versions of ShuruisUtilities on load?").define("versionCheck",
                true);
        // configManager.setUseCanonicalConfig(SERVER_BUILDER.comment("For modules that
        // support it, place their configs in this file.").define("canonicalConfigs",
        // false).get());
        SUdebugMode = BUILDER.comment("Activates developer debug mode. Spams your FML logs.").define("debug", false);
        SUsafeMode = BUILDER
                .comment("Activates safe mode with will ignore some errors which would normally crash the game."
                        + "Please only enable this after being instructed to do so by SU team in response to an issue on GitHub!")
                .define("safeMode", false);
        SUhideWorldEditCommands = BUILDER
                .comment("Hide WorldEdit commands from /help and only show them in //help command")
                .define("hide_worldedit_help", true);
        SUlogCommandsToConsole = BUILDER.comment("Log commands to console").define("logCommands", false);
        return BUILDER;
    }

    public static void bakeConfig(boolean reload)
    {
        if (reload)
            Translator.translations.clear();
        Translator.load();
        BuildInfo.needCheckVersion = SUcheckVersion.get();
        // configManager.setUseCanonicalConfig(SERVER_BUILDER.comment("For modules that
        // support it, place their configs in this file.").define("canonicalConfigs",
        // false).get());
        debugMode = SUdebugMode.get();
        safeMode = SUsafeMode.get();
        HelpFixer.hideWorldEditCommands = SUhideWorldEditCommands.get();
        logCommandsToConsole = SUlogCommandsToConsole.get();
    }

    public static ConfigBase getConfigManager()
    {
        return configManager;
    }

    public static File getSUDirectory()
    {
        return suDirectory;
    }

    // single source of truth for where SUData lives; route every SUData path through here.
    // worldScopedData false (default): <gamedir>/ShuruisUtilities/SUData (stable per install, dist-independent).
    // worldScopedData true: <worldPath>/SUData (legacy).
    public static File getSUDataRoot()
    {
        if (SUConfig.worldScopedData)
            return new File(ServerUtil.getWorldPath(), "SUData");
        return new File(getSUDirectory(), "SUData");
    }

    // SUData_backup dir, sibling of getSUDataRoot()
    public static File getSUDataBackupRoot()
    {
        return new File(getSUDataRoot().getParentFile(), "SUData_backup");
    }

    public static final String SUDATA_BACKUP_PREFIX = "SUData_backup_";

    // true when target is safe to delete as a backup: non-null, resolvable, not a filesystem root, not equal
    // to / an ancestor of the live SUData root or SU dir. compared on CANONICAL paths so symlinks and "."/".."
    // can't slip a live dir past the guard. any resolve failure returns false (fail-safe: refuse the delete).
    public static boolean isSafeToDeleteBackup(File target)
    {
        if (target == null)
            return false;
        try
        {
            File canonTarget = target.getCanonicalFile();

            // Never a filesystem root (a root has no parent).
            if (canonTarget.getParentFile() == null)
                return false;

            File canonDataRoot = getSUDataRoot().getCanonicalFile();
            File canonSuDir = getSUDirectory().getCanonicalFile();

            // Refuse if the target equals, or is an ancestor of, either live location.
            if (pathEqualsOrAncestor(canonTarget, canonDataRoot) || pathEqualsOrAncestor(canonTarget, canonSuDir))
                return false;

            return true;
        }
        catch (java.io.IOException ex)
        {
            LoggingHandler.sulog.error(
                    "[SUData] Could not canonicalize a backup path for the delete guard; refusing to delete \""
                            + target.getPath() + "\".", ex);
            return false;
        }
    }

    // both must be canonical
    private static boolean pathEqualsOrAncestor(File maybeAncestor, File descendant)
    {
        for (File cur = descendant; cur != null; cur = cur.getParentFile())
        {
            if (cur.equals(maybeAncestor))
                return true;
        }
        return false;
    }

    public static boolean isDebug()
    {
        return debugMode;
    }

    public static boolean isSafeMode()
    {
        return safeMode;
    }

    public static File getJarLocation()
    {
        return jarLocation;
    }

    public RespawnHandler getRespawnHandler()
    {
        return respawnHandler;
    }

    public SelectionHandler getSelectionHandler()
    {
        return selectionHandler;
    }
}

package net.shurui.dev.shuruis_dmz_dungeons.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.CustomDrop;
import net.shurui.dev.shuruis_dmz_dungeons.compat.DmzTpCompat;

// grants the configured kill rewards when a spawner mob dies: TP range, SU balance range, custom drops. all
// reward data is stamped into the mob's persistent data at spawn, so this is entity-type agnostic and needs no
// spawner reference. TP/balance go through reflection-guarded compat (no-op when the mod is absent).
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons")
public final class SpawnerRewardEvents {

    // legacy single-value TP key (spawners saved before TP became a range); still read for old mobs
    public static final String TP_TAG = "sdd_tp_reward";

    // range/loot keys stamped on a spawned mob (see AdvancedSpawnerBlockEntity#spawnOne)
    public static final String TP_MIN_TAG = "sdd_tp_min";
    public static final String TP_MAX_TAG = "sdd_tp_max";
    public static final String BAL_MIN_TAG = "sdd_bal_min";
    public static final String BAL_MAX_TAG = "sdd_bal_max";
    public static final String DROPS_TAG = "sdd_drops";
    // semicolon-separated console commands run on kill (%player% = killer's name)
    public static final String KILL_COMMANDS_TAG = "sdd_cmds";
    // suppress the entity's own loot-table drops so only the configured drops appear
    public static final String NO_VANILLA_DROPS_TAG = "sdd_novanilla";
    // level scaling (core LevelScale, the Z orb model): the level the max-level anchors apply at, present only on a
    // mob whose spawner scales. Its TP/balance then interpolate between the level 1 range (the tags above) and the
    // max-level range (below) on the KILLER's DMZ level. Mobs spawned before this carry none and pay as before.
    public static final String SCALE_MAX_TAG = "sdd_lvl_max";
    public static final String TP_MIN_M_TAG = "sdd_tp_min_m";
    public static final String TP_MAX_M_TAG = "sdd_tp_max_m";
    public static final String BAL_MIN_M_TAG = "sdd_bal_min_m";
    public static final String BAL_MAX_M_TAG = "sdd_bal_max_m";

    private SpawnerRewardEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        net.shurui.dev.shuruis_dmz_dungeons.KeyGate.logStatusOnce();
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        CompoundTag data = entity.getPersistentData();
        // floor bosses pay out by damage share in DungeonBossManager.distributeRewards, NOT this killer-only path;
        // skip them so rewards aren't granted twice. (their vanilla-drop suppression still runs below via NO_VANILLA.)
        if (data.contains(net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorManager.TAG_FLOOR_BOSS)) {
            return;
        }
        Player killer = event.getSource().getEntity() instanceof Player p ? p : null;
        RandomSource random = entity.level().getRandom();

        if (killer != null) {
            int tp = rollTp(data, random, killer);
            if (tp > 0 && DmzTpCompat.awardTp(killer, tp)) {
                Shuruis_dmz_dungeons.LOGGER.debug("[{}] Awarded {} TP to {} for a spawner kill.",
                        Shuruis_dmz_dungeons.MODID, tp, killer.getGameProfile().getName());
            }
            // the zeni half is PRIVATE (S22b) and lives in the Ragnarok Key; keyless no zeni is rolled at all.
            net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonRewardHooks.get().spawnerZeni(killer, data, random);
            runKillCommands(data.getString(KILL_COMMANDS_TAG), killer.getGameProfile().getName());
        }

        // custom drops fall at the mob's location no matter what killed it (no key required)
        if (data.contains(DROPS_TAG)) {
            ListTag dropList = data.getList(DROPS_TAG, Tag.TAG_COMPOUND);
            for (int i = 0; i < dropList.size(); i++) {
                ItemStack stack = CustomDrop.load(dropList.getCompound(i)).roll(random);
                if (!stack.isEmpty()) {
                    entity.spawnAtLocation(stack);
                }
            }
        }
    }

    // cancel the entity's own loot-table drops when the spawner turned off vanilla drops. the custom drops
    // above are spawned directly, so they're unaffected.
    @SubscribeEvent
    public static void onLivingDrops(net.minecraftforge.event.entity.living.LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (event.getEntity().getPersistentData().getBoolean(NO_VANILLA_DROPS_TAG)) {
            event.setCanceled(true);
        }
    }

    // run the stamped kill commands as the server console, output suppressed
    private static void runKillCommands(String commands, String killerName) {
        if (commands == null || commands.isBlank()) {
            return;
        }
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (String raw : commands.split(";")) {
            String cmd = raw.trim().replace("%player%", killerName);
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.isBlank()) {
                continue;
            }
            try {
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(), cmd);
            } catch (Throwable t) {
                Shuruis_dmz_dungeons.LOGGER.warn("[{}] Kill command failed: '{}': {}",
                        Shuruis_dmz_dungeons.MODID, cmd, t.toString());
            }
        }
    }

    /**
     * True when this mob's kill TP comes from its spawner's TP range rather than DragonMineZ's health based kill TP
     * (see {@code mixin.MixinDmzSpawnerKillTp}). Server side, spawner stamped mobs only, floor bosses excluded (they
     * pay by damage share elsewhere and keep DragonMineZ's grant as before).
     */
    public static boolean replacesDmzKillTp(Entity entity) {
        if (entity == null || entity.level().isClientSide) {
            return false;
        }
        CompoundTag data = entity.getPersistentData();
        if (data.contains(net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorManager.TAG_FLOOR_BOSS)) {
            return false;
        }
        return data.contains(TP_MIN_TAG) || data.contains(TP_MAX_TAG) || data.getInt(TP_TAG) > 0;
    }

    // TP for this kill: random in [min, max] (interpolated on the killer's level for a scaled mob), falling back to
    // the legacy single value
    private static int rollTp(CompoundTag data, RandomSource random, Player killer) {
        if (data.contains(TP_MIN_TAG) || data.contains(TP_MAX_TAG)) {
            return rollScaled(data, TP_MIN_TAG, TP_MAX_TAG, TP_MIN_M_TAG, TP_MAX_M_TAG, killer, random);
        }
        return Math.max(0, data.getInt(TP_TAG));
    }

    /**
     * A kill reward range, level scaled when the mob carries {@link #SCALE_MAX_TAG}: both ends interpolate between the
     * level 1 range and the max-level range on the killer's DMZ level, then a value between them is rolled, as the
     * unscaled roll does. Without the tag this is exactly {@link #rollRange}. Shared with the key's zeni half.
     */
    public static int rollScaled(CompoundTag data, String minKey, String maxKey, String minMaxKey, String maxMaxKey,
                                 Player killer, RandomSource random) {
        if (!data.contains(SCALE_MAX_TAG)) {
            return rollRange(data, minKey, maxKey, random);
        }
        int level = net.shurui.shuruisutilities.guilds.integration.DmzBridge.level(killer);
        return net.shurui.shuruisutilities.npcregion.LevelScale.roll(data.getInt(minKey), data.getInt(maxKey),
                data.getInt(minMaxKey), data.getInt(maxMaxKey), level, data.getInt(SCALE_MAX_TAG), random);
    }

    // random in [min, max] (order-agnostic); 0 when both bounds are absent/non-positive
    public static int rollRange(CompoundTag data, String minKey, String maxKey, RandomSource random) {
        int a = data.getInt(minKey);
        int b = data.getInt(maxKey);
        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.max(0, Math.max(a, b));
        if (hi <= 0) {
            return 0;
        }
        return hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
    }
}

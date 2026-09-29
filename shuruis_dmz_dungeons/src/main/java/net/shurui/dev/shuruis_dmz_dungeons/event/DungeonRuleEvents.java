package net.shurui.dev.shuruis_dmz_dungeons.event;

import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonRules;
import net.shurui.dev.shuruis_dmz_dungeons.util.SpawnCancelUtil;

// enforces the per-dim dungeon rules. every handler checks isAnyDungeon first (legacy superflat + the seven
// themed floor dims) so overworld / other DMZ dims stay untouched.
//
// ki block destruction gotcha: DMZ ki blasts break blocks via direct Level.destroyBlock/setBlock, firing
// NEITHER ExplosionEvent nor LivingDestroyBlockEvent. DMZ gates all ki griefing on MainGameRules.canKiGrief, so
// the real coverage is MixinDmzKiGriefDungeon (cancels that decision, no gamerule touched). The
// ExplosionEvent.Detonate + LivingDestroyBlockEvent handlers here still cover genuine explosions and mob breaks.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons")
public final class DungeonRuleEvents {

    private DungeonRuleEvents() {
    }

    public static boolean suppressesKiBlockDamage(Level level) {
        if (!DungeonDimensions.isAnyDungeon(level) || level.isClientSide) {
            return false;
        }
        DungeonRules rules = level.getServer() == null ? null : DungeonRules.get(level.getServer());
        return rules != null && !rules.kiBlockDestruction;
    }

    /**
     * Stop an ordinary player rebuilding a dungeon by hand. The ki rule only covered DMZ ki griefing, so hand
     * BREAK and PLACE were never checked: a boss arena could be mined out from under a fight. Both are cancelled
     * inside the dungeon dimensions (tear arenas included, being cells in these same dims).
     *
     * <p>CREATIVE MODE is the bypass: arenas are built and dressed in creative, so it is the mode, not the rank,
     * that means "building now". An operator playing in survival is held to the rule like everyone else.
     */
    private static boolean blockEditingDenied(Level level, Player player) {
        if (player == null || level.isClientSide || !DungeonDimensions.isAnyDungeon(level)) {
            return false;
        }
        // Read the rule off the synced DungeonRules, not the local toml, so the four shards agree on it. Fall back to
        // the config default only when the rules SavedData is not resolvable (dungeon dim not yet materialised here).
        DungeonRules rules = level.getServer() == null ? null : DungeonRules.get(level.getServer());
        boolean editingAllowed = rules != null ? rules.blockEditing : Config.dungeonBlockEditing;
        if (editingAllowed || player.isCreative()) {
            return false;
        }
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("message.dmz_ragnarok.dungeon.no_building"), true);
        return true;
    }

    @SubscribeEvent
    public static void onBlockBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (blockEditingDenied(event.getPlayer().level(), event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player
                && blockEditingDenied(player.level(), player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMultiPlace(net.minecraftforge.event.level.BlockEvent.EntityMultiPlaceEvent event) {
        // A bed, a door, a tall flower: one action, several blocks, its own event. Without this the multi-block
        // half of placing walks straight past the single-block check above.
        if (event.getEntity() instanceof Player player
                && blockEditingDenied(player.level(), player)) {
            event.setCanceled(true);
        }
    }

    private static boolean pvpDisabled(Level level) {
        if (!DungeonDimensions.isAnyDungeon(level) || level.isClientSide) {
            return false;
        }
        DungeonRules rules = level.getServer() == null ? null : DungeonRules.get(level.getServer());
        return rules != null && !rules.pvp;
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!pvpDisabled(victim.level())) {
            return;
        }
        // only cancel player-vs-player (direct or via a player-owned projectile/ki blast)
        if (event.getSource().getEntity() instanceof Player attacker && attacker != victim) {
            event.setCanceled(true);
        }
    }

    // backstop: some DMZ / addon damage re-enters after LivingAttackEvent, so cancel again at the hurt stage
    // with the same pvp + dungeon check. HIGHEST so we bail before resistance math runs.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!pvpDisabled(victim.level())) {
            return;
        }
        if (event.getSource().getEntity() instanceof Player attacker && attacker != victim) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (suppressesKiBlockDamage(event.getLevel())) {
            // keep the visual/knockback, just don't let it break blocks
            event.getAffectedBlocks().clear();
        }
    }

    @SubscribeEvent
    public static void onMobBreakBlock(net.minecraftforge.event.entity.living.LivingDestroyBlockEvent event) {
        if (suppressesKiBlockDamage(event.getEntity().level())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        // void has no natural spawns anyway, but deny NATURAL/CHUNK explicitly so nothing wanders in.
        // our spawner uses SPAWNER and /summon uses COMMAND, both allowed.
        MobSpawnType type = event.getSpawnType();
        if ((type == MobSpawnType.NATURAL || type == MobSpawnType.CHUNK_GENERATION)
                && DungeonDimensions.isAnyDungeon(event.getEntity().level())) {
            SpawnCancelUtil.cancelIfLegal(event);
        }
    }
}

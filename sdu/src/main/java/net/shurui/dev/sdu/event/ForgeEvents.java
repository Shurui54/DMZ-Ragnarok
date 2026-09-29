package net.shurui.dev.sdu.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.command.DmzNpcCommand;
import net.shurui.dev.sdu.network.DmzNet;

/** Forge-bus game events: load NPC definitions at server launch, register the /rg npc command. */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class ForgeEvents {

    private ForgeEvents() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        net.shurui.dev.sdu.saga.SagaSpawnBindings.loadFrom(event.getServer());
        net.shurui.dev.sdu.saga.DeferredSpawnRegistry.loadFrom(event.getServer());
        net.shurui.dev.sdu.saga.TalkNpcRegistry.loadFrom(event.getServer());
        net.shurui.dev.sdu.saga.SagaNpcMap.load();
        // Load tombstones early (before world/player data load) so SkillsRepairMixin sees deleted keys.
        net.shurui.dev.sdu.form.FormTombstoneStore.load();
        // Seed the custom-form-type registry from DMZ's live skills config so pre-existing custom types
        // (from a prior session) are routed/gated against their own skill, not a stock *forms skill they
        // happen to substring-match. Re-seeded on login too, in case DMZ loads its configs after this.
        net.shurui.dev.sdu.form.FormTypeManager.refreshCustomTypesFromDmz();
        // Self-heal servers already corrupted by an older sdu build that let kaioken/ultimate be un-stacked:
        // restore any STACK_DEFAULT missing from stackSkills or missing its skills.<id> cost block, so DMZ's
        // form purchase menu lists them again. Only restores what's missing; never clobbers a custom entry.
        net.shurui.dev.sdu.form.FormTypeManager.healStackDefaults();
        net.shurui.dev.sdu.form.FormCombatConfig.load();
        net.shurui.dev.sdu.form.FormAuraConfig.load();
        net.shurui.dev.sdu.form.FormQuestGateConfig.load();
        net.shurui.dev.sdu.form.FormLevelGateConfig.load();
        net.shurui.dev.sdu.form.FormAlignmentGateConfig.load();
        net.shurui.dev.sdu.race.RacialSkillConfig.load();
        // Build the client-readable base-race aura-size lookup from the race files (DMZ's typed config
        // drops our auraWidth/auraHeight keys, so AuraScaleMixin reads them from here instead).
        net.shurui.dev.sdu.race.RaceAuraConfig.rebuildFromDisk();
        // Persistent suppression of DMZ default races/classes: load our list, then strip the suppressed
        // ids from DMZ's already-loaded (via ConfigManager.initialize()) in-memory maps before players
        // join. No server exists yet for a sync, so this strips the authoritative LOADED_RACES/RACE_*
        // maps only; the SERVER_SYNCED_* stripping + resync happens on player connect / resync.
        net.shurui.dev.sdu.race.SuppressedDefaultsConfig.load();
        net.shurui.dev.sdu.compat.DmzCompat.applySuppression();
        // Reinterpret the legacy formStackable=false default (an old sdu build stamped it on every form)
        // as true on DMZ's in-memory FormData, so forms that should stack do again. Same lifecycle as
        // suppression: also re-run at the end of reloadConfigs() and start of resyncConfigsToAll().
        net.shurui.dev.sdu.compat.DmzCompat.repairFormStackable();
        net.shurui.dev.sdu.quest.QuestTimerConfig.load();
        net.shurui.dev.sdu.quest.RepeatConfig.load();
        net.shurui.dev.sdu.quest.QuestItemConsumeConfig.load();
        net.shurui.dev.sdu.saga.SagaQuestGateConfig.load();
        net.shurui.dev.sdu.saga.SagaRegionBypassConfig.load();
        net.shurui.dev.sdu.shenron.ShrineConfig.load();
        // Admin-settable HTC entry override (where the time-chamber portal sends players on entry).
        net.shurui.dev.sdu.htc.HtcDestination.load();
        // Operator-settable grave/totem despawn lifetime, read by shuruisutilities' grave sweep.
        net.shurui.dev.sdu.grave.GraveTotemConfig.load();
        regenerateGeneratedLang();
    }

    /**
     * Rebuild the custom race/form/class display-name lang keys from the configs on disk, so the names appear on a
     * server that HAS the configs but never received a client lang packet for them: one the files were copied to,
     * or one the shard sync delivered them to.
     *
     * <p>Delegates, so the boot path and the shard-sync path cannot drift apart. They did once: this rebuilt races
     * and forms only, which is why a synced race's CLASSES showed their raw keys on every server but the one they
     * were authored on.
     */
    private static void regenerateGeneratedLang() {
        net.shurui.dev.sdu.lang.GeneratedLangRebuild.fromConfigs(
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DmzNpcCommand.register(event.getDispatcher());
    }

    /** Name-based TALK_TO objectives: talking to any living entity with the configured name completes them. */
    @SubscribeEvent
    public static void onEntityInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        if (event.getTarget() instanceof net.minecraft.world.entity.LivingEntity target && target.isAlive()) {
            net.shurui.dev.sdu.saga.TalkNpcRegistry.onTalk(player, target);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // DMZ's skills config is fully loaded by login; re-seed the custom-type registry so the
            // transform-ascension gate (common) routes reserved-substring custom ids to their own skill.
            net.shurui.dev.sdu.form.FormTypeManager.refreshCustomTypesFromDmz();
            // Belt-and-suspenders for UsedForms (no repair hook of its own): scrub any tombstoned group
            // from this player's usage history. The skill entry itself is already handled by the mixin
            // before this fires; stale UsedForms names are only cosmetic once the skill is gone.
            net.shurui.dev.sdu.form.FormTombstoneScrub.scrubTombstonedGroups(player);
            net.shurui.dev.sdu.KeyGate.logStatusOnce();
            // The key status sync lives in ModuleSyncEvents.onLogin (HIGHEST, key before the module packet).
            DmzNet.syncLangToPlayer(player);   // server-authoritative custom race/form/skill names
            DmzNet.syncFormAurasToPlayer(player); // extra aura layers (client config missing on a server)
            DmzNet.syncRaceAurasToPlayer(player); // per-race base-aura size (DMZ drops our custom keys)
            DmzNet.syncFormTypeMetaToPlayer(player); // per-form-type radial/skills icon + tint overrides
            DmzNet.syncFormQuestGatesToPlayer(player); // quest-gated form-purchase map for advisory skills UX
            DmzNet.syncFormLevelGatesToPlayer(player); // per-form minimum-level-to-use map for the skills lock UX
            DmzNet.syncFormAlignmentGatesToPlayer(player); // per-form alignment unlock/use windows for the skills lock UX
            DmzNet.syncSagaQuestGatesToPlayer(player); // quest-gated saga-unlock map for the quest-tree saga lock
            DmzNet.syncSuppressedDefaultsToPlayer(player); // suppressed default race/class ids so the editor can restore them
            DmzNet.syncPreviewClonesToPlayer(player); // saved-NPC appearances for the DMZ quest-GUI preview
        }
    }

    /**
     * Move a player still saved on a suppressed default class (tank) onto a real one. Separate from the login handler
     * above and pinned to LOWEST so it runs AFTER the shard vault restore, which is the login authority and overwrites
     * the local capability with whatever it holds (ShardSync at login HIGHEST / negotiate): migrating any earlier would
     * be undone the instant the vault payload is applied. Running last means we edit the post-restore class, and the
     * logout vault save then captures the corrected class network-wide. Hiding the class from the picker and /dmzclass
     * (RaceClassListSuppressMixin) never touches a player who already IS it, and such players are exactly what
     * resurrects the class into DMZ's in-memory maps every stat recompute.
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onPlayerLoginMigrateClass(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            net.shurui.dev.sdu.race.SuppressedClassMigration.migrateIfSuppressed(player);
        }
    }
}

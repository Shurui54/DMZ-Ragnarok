package net.shurui.dev.sdu.compat;

import net.minecraft.server.MinecraftServer;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Re-reads sdu's own sidecar config files from disk and re-pushes the client-facing ones, so a sidecar
 * delivered by the shard config sync takes effect without a server restart.
 *
 * <p>The shard sync writes the FILE, then reloads DragonMineZ's {@code ConfigManager}. sdu's sidecars are a
 * different set (form combat, form auras, quest gates, racial skills, the saga npc map, the shrine list, quest
 * timers and repeats, suppressed defaults, form tombstones, the time-chamber destination) that sdu reads only
 * once, at {@code ServerAboutToStartEvent}, into in-memory caches. Without this a synced sidecar sits correct
 * on disk and stale in memory until reboot. Re-runs exactly the boot loads, then broadcasts the client syncs.
 *
 * <p>Blanket on purpose: every sidecar is small and re-reading an unchanged one is harmless, so it reloads all
 * rather than working out which file the sync wrote. Guarded: a failure logs one warn and leaves the caches
 * as they were.
 */
public final class SduSidecarReload
{
    private SduSidecarReload() {}

    public static void reloadAll(MinecraftServer server)
    {
        try
        {
            net.shurui.dev.sdu.form.FormCombatConfig.load();
            net.shurui.dev.sdu.form.FormAuraConfig.load();
            net.shurui.dev.sdu.form.FormQuestGateConfig.load();
            net.shurui.dev.sdu.form.FormLevelGateConfig.load();
            net.shurui.dev.sdu.form.FormAlignmentGateConfig.load();
            net.shurui.dev.sdu.form.FormTypeMetaConfig.load();
            net.shurui.dev.sdu.race.RacialSkillConfig.load();
            // The client readable base race aura sizes are derived from the race files, so rebuild them too.
            net.shurui.dev.sdu.race.RaceAuraConfig.rebuildFromDisk();
            net.shurui.dev.sdu.saga.SagaNpcMap.load();
            net.shurui.dev.sdu.shenron.ShrineConfig.load();
            net.shurui.dev.sdu.quest.QuestTimerConfig.load();
            net.shurui.dev.sdu.quest.RepeatConfig.load();
            net.shurui.dev.sdu.quest.QuestItemConsumeConfig.load();
            net.shurui.dev.sdu.saga.SagaQuestGateConfig.load();
            net.shurui.dev.sdu.saga.SagaRegionBypassConfig.load();
            net.shurui.dev.sdu.race.SuppressedDefaultsConfig.load();
            net.shurui.dev.sdu.form.FormTombstoneStore.load();
            net.shurui.dev.sdu.htc.HtcDestination.load();
            // DMZ's skills.json may have changed (a new custom form type): re-seed the custom type registry and
            // re-apply the suppressed defaults against DMZ's freshly reloaded in memory maps.
            net.shurui.dev.sdu.form.FormTypeManager.refreshCustomTypesFromDmz();
            DmzCompat.applySuppression();

            if (server != null)
            {
                net.shurui.dev.sdu.network.DmzNet.syncFormAurasToAll();
                net.shurui.dev.sdu.network.DmzNet.syncFormQuestGatesToAll();
                net.shurui.dev.sdu.network.DmzNet.syncFormLevelGatesToAll();
                net.shurui.dev.sdu.network.DmzNet.syncFormAlignmentGatesToAll();
                net.shurui.dev.sdu.network.DmzNet.syncSagaQuestGatesToAll();
                net.shurui.dev.sdu.network.DmzNet.syncFormTypeMetaToAll();
                net.shurui.dev.sdu.network.DmzNet.syncRaceAurasToAll();
                net.shurui.dev.sdu.network.DmzNet.syncLangToAll(server);
                net.shurui.dev.sdu.network.DmzNet.syncSuppressedDefaultsToAll(server);
            }
            DmzNpc.LOGGER.info("[{}] Reloaded sdu sidecar configs after a shard config apply.", DmzNpc.MODID);
        }
        catch (Throwable t)
        {
            DmzNpc.LOGGER.warn("[{}] Could not reload sdu sidecars after a shard config apply: {}",
                    DmzNpc.MODID, t.toString());
        }
    }
}

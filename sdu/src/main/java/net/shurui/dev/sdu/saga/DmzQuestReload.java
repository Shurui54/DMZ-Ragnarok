package net.shurui.dev.sdu.saga;

import net.minecraft.server.MinecraftServer;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.SyncQuestRegistryS2C;
import com.dragonminez.common.quest.QuestRegistry;

/**
 * Push freshly saved saga/quest files into DragonMineZ's LIVE registry, and out to every client.
 *
 * <h3>Why this exists</h3>
 * The editor writes saga and quest JSON to disk through {@link SagaFileManager}, and after a save SDU reloads its OWN
 * registries ({@link SagaSpawnBindings}, {@link net.shurui.dev.sdu.saga.DeferredSpawnRegistry}, {@link TalkNpcRegistry})
 * so spawns and talk targets pick the change up immediately. What it never did was tell DMZ. DMZ keeps its sagas and
 * quests in the static maps behind {@code QuestRegistry}, populated once by {@code QuestRegistry.loadAll} at world load,
 * and nothing re-reads the files afterwards.
 *
 * <p>That is what made edited saga NPC stats look like they were being ignored. A KILL objective's {@code health},
 * {@code meleeDamage} and {@code kiDamage} are read by {@code QuestService.spawnKillObjectives} out of the IN-MEMORY
 * quest, not off disk: it stamps them onto the entity as the {@code dmz_quest_hp} / {@code dmz_quest_melee} /
 * {@code dmz_quest_ki} tags that {@code EntitiesEvents.onEntityJoinWorld} then applies. So the file on disk held the new
 * numbers while DMZ went on serving the stale ones it loaded at startup, and the NPC spawned with whatever was loaded
 * then. The values were never "reverted" and nothing clobbered them; they simply never reached the registry. A server
 * restart made the edit appear to work, which is exactly what a stale cache looks like from the outside.
 *
 * <p>Reloading here closes that gap: the same public entry point DMZ itself uses at world load, called again after a
 * save, so an edit takes effect on the next spawn instead of the next restart.
 *
 * <h3>Client sync</h3>
 * {@code QuestRegistry.loadAll} only rebuilds the SERVER maps; it does not push anything to clients (verified against
 * the 2.1.3 bytecode, its body touches only the config, the loaders and the diagnostics report). Clients keep a separate
 * mirror, {@code getClientSagas}/{@code getClientQuests}, fed by DMZ's own {@code SyncQuestRegistryS2C}. So we send that
 * packet ourselves afterwards, or the saga screen would keep drawing the pre-edit text until every player relogged.
 *
 * <h3>Two behaviours inherited from loadAll, deliberately not fought</h3>
 * {@code loadAll} re-runs {@code SagaDefaults.createDefaultSagaFiles} (gated on the {@code createDefaultSagas} config)
 * and the {@code QuestUpgrader} pass (gated on {@code autoUpdateQuests}). Both already run on every world load, so
 * calling loadAll again introduces no behaviour that a restart would not also produce. Worth knowing though: with
 * {@code createDefaultSagas} left ON, a reload recreates deleted default saga files, so an operator removing the default
 * sagas must turn that config OFF or they come back. That is the same config step the saga-screen filter needs.
 */
public final class DmzQuestReload
{
    private DmzQuestReload()
    {
    }

    /**
     * Re-read DMZ's saga/quest files into its live registry and sync every client. Call after any editor write that
     * changes saga or quest JSON. Safe to call repeatedly; it is the same work a world load does.
     *
     * <p>Never lets a DMZ-side failure break the save that triggered it. The file is already on disk by the time we run,
     * so a reload that throws must not turn a successful save into an error the editor reports: we swallow and log
     * instead. Catching {@link Throwable} rather than {@link Exception} is deliberate, since a DMZ API change would
     * surface here as a linkage error, not an exception.
     */
    public static void reloadAndSync(MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        try
        {
            QuestRegistry.loadAll(server);
        }
        catch (Throwable t)
        {
            net.shurui.dev.sdu.DmzNpc.LOGGER.error("[{}] Failed to reload DragonMineZ quest registry after an editor "
                    + "save; the edit is on disk but will not take effect until a restart",
                    net.shurui.dev.sdu.DmzNpc.MODID, t);
            return;
        }

        // only sync once the reload actually succeeded: pushing the registry we failed to rebuild would hand clients the
        // stale data and hide the problem behind a screen that looks freshly updated.
        try
        {
            NetworkHandler.sendToAllPlayers(
                    new SyncQuestRegistryS2C(QuestRegistry.getAllSagas(), QuestRegistry.getAllQuests()));
        }
        catch (Throwable t)
        {
            net.shurui.dev.sdu.DmzNpc.LOGGER.error("[{}] Reloaded the DragonMineZ quest registry but failed to sync it "
                    + "to clients; spawns use the new values, screens may show the old text until relog",
                    net.shurui.dev.sdu.DmzNpc.MODID, t);
        }
    }
}

package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.SagaData;
import net.shurui.dev.sdu.saga.SagaFileManager;

import java.util.function.Supplier;

/** Client -> server. Writes a saga (bundle JSON) to the world save. Op-gated. */
public class SaveSagaPacket {

    private static final Gson GSON = new Gson();
    private final String bundle;

    public SaveSagaPacket(String bundle) {
        this.bundle = bundle;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(bundle, 1_000_000);
    }

    public static SaveSagaPacket decode(FriendlyByteBuf buf) {
        return new SaveSagaPacket(buf.readUtf(1_000_000));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            apply(player, bundle);
        });
        context.setPacketHandled(true);
    }

    /** Apply a saga bundle JSON on the server. Caller must have verified the sender and edit permission. */
    public static void apply(ServerPlayer player, String bundleJson) {
        try {
            SagaData saga = SagaData.fromBundle(GSON.fromJson(bundleJson, JsonObject.class));
            // Reject malformed/traversal ids at the boundary before any file I/O (the manager re-checks too).
            if (!net.shurui.dev.sdu.saga.SafeFileNames.isSafeSegment(saga.id)
                    || !net.shurui.dev.sdu.saga.SafeFileNames.isSafeSegment(saga.questFolder)) {
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.saga.invalid_id", saga.id), false);
                return;
            }
            String err = SagaFileManager.save(player.getServer(), saga);
            if (err == null) {
                net.shurui.dev.sdu.saga.SagaSpawnBindings.loadFrom(player.getServer());
                net.shurui.dev.sdu.saga.DeferredSpawnRegistry.loadFrom(player.getServer());
                net.shurui.dev.sdu.saga.TalkNpcRegistry.loadFrom(player.getServer());
                // also push into DMZ's OWN live registry. Reloading only SDU's registries left DMZ serving what it
                // read at world load, so an edited KILL objective's health/melee/ki never reached spawnKillObjectives
                // and the NPC kept the pre-edit numbers until a restart. See DmzQuestReload.
                net.shurui.dev.sdu.saga.DmzQuestReload.reloadAndSync(player.getServer());
                net.shurui.dev.sdu.network.DmzNet.syncPreviewClonesToAll(player.getServer());
                applyFormPurchaseGates(player, saga);
            }
            player.displayClientMessage(err == null
                    ? Component.translatable("message.dmz_ragnarok.npc.saga.saved", saga.id)
                    : Component.translatable("message.dmz_ragnarok.npc.saga.save_failed", err), false);
        } catch (Exception e) {
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.saga.bad_data", e.getMessage()), false);
        }
    }

    /**
     * Upsert quest-gated form-purchase entries from this saga's FORM_PURCHASE rewards into
     * {@link net.shurui.dev.sdu.form.FormQuestGateConfig} ({@code form_quest_gates.json}), then resync players.
     * Each reward's form maps to the owning quest's completion key: main-line uses
     * {@code sagaQuestKey(sagaId, questId)}, branch quests use {@code Quest.branchSid}. Those are the exact keys
     * {@code FormQuestGate}/{@code UpdateSkillC2SMixin} feed to {@code isQuestCompleted}.
     *
     * <p>Upsert-only: never deletes hand-authored gates. DMZ default side quests (those with a {@code sourcePath})
     * are skipped; their runtime key isn't reconstructable here.
     */
    private static void applyFormPurchaseGates(ServerPlayer player, SagaData saga) {
        try {
            boolean changed = false;
            for (SagaData.Quest q : saga.quests) {
                if (q.branch && q.sourcePath != null && !q.sourcePath.isBlank()) {
                    continue; // DMZ default side quest: its runtime id isn't derivable here.
                }
                String questKey = q.branch
                        ? q.branchSid(saga.id)
                        : com.dragonminez.common.quest.PlayerQuestData.sagaQuestKey(saga.id, q.id);
                if (questKey == null || questKey.isBlank()) {
                    continue;
                }
                // Drop every gate keyed to this questKey (so a removed/renamed reward leaves no dead gate) then
                // re-derive from current rewards. Scoped by questKey, so other sagas are untouched. save() runs once.
                changed |= net.shurui.dev.sdu.form.FormQuestGateConfig.removeByQuest(questKey);
                for (SagaData.Reward r : q.rewards) {
                    if ("FORM_PURCHASE".equals(r.type) && r.formKey != null && !r.formKey.isBlank()) {
                        changed |= net.shurui.dev.sdu.form.FormQuestGateConfig.upsertNoSave(r.formKey, questKey);
                    }
                }
            }
            if (changed) {
                net.shurui.dev.sdu.form.FormQuestGateConfig.save();
                net.shurui.dev.sdu.network.DmzNet.syncFormQuestGatesToAll();
            }
        } catch (Exception e) {
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.npc.saga.gates_failed", e.getMessage()), false);
        }
    }
}

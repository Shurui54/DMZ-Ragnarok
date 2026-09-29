package net.shurui.dev.sdu.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.saga.SagaData;
import net.shurui.dev.sdu.saga.SagaFileManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Forge {@link SimpleChannel} for the addon: opens the editor (S2C) and applies edits (C2S). */
public final class DmzNet {

    private static final String PROTOCOL = "1";
    private static SimpleChannel channel;
    private static int packetId = 0;

    private DmzNet() {
    }

    public static void register() {
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(DmzNpc.MODID, "sdu_main"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

        channel.registerMessage(packetId++, SpawnFighterPacket.class,
                SpawnFighterPacket::encode, SpawnFighterPacket::decode, SpawnFighterPacket::handle);
        channel.registerMessage(packetId++, RequestClonesPacket.class,
                RequestClonesPacket::encode, RequestClonesPacket::decode, RequestClonesPacket::handle);
        channel.registerMessage(packetId++, SyncClonesPacket.class,
                SyncClonesPacket::encode, SyncClonesPacket::decode, SyncClonesPacket::handle);
        channel.registerMessage(packetId++, OpenSagaEditorPacket.class,
                OpenSagaEditorPacket::encode, OpenSagaEditorPacket::decode, OpenSagaEditorPacket::handle);
        channel.registerMessage(packetId++, SaveSagaPacket.class,
                SaveSagaPacket::encode, SaveSagaPacket::decode, SaveSagaPacket::handle);
        channel.registerMessage(packetId++, DeleteSagaPacket.class,
                DeleteSagaPacket::encode, DeleteSagaPacket::decode, DeleteSagaPacket::handle);
        channel.registerMessage(packetId++, OpenSideQuestEditorPacket.class,
                OpenSideQuestEditorPacket::encode, OpenSideQuestEditorPacket::decode, OpenSideQuestEditorPacket::handle);
        channel.registerMessage(packetId++, SaveSideQuestPacket.class,
                SaveSideQuestPacket::encode, SaveSideQuestPacket::decode, SaveSideQuestPacket::handle);
        channel.registerMessage(packetId++, DeleteSideQuestPacket.class,
                DeleteSideQuestPacket::encode, DeleteSideQuestPacket::decode, DeleteSideQuestPacket::handle);
        channel.registerMessage(packetId++, OpenFormEditorPacket.class,
                OpenFormEditorPacket::encode, OpenFormEditorPacket::decode, OpenFormEditorPacket::handle);
        channel.registerMessage(packetId++, SaveFormPacket.class,
                SaveFormPacket::encode, SaveFormPacket::decode, SaveFormPacket::handle);
        channel.registerMessage(packetId++, DeleteFormPacket.class,
                DeleteFormPacket::encode, DeleteFormPacket::decode, DeleteFormPacket::handle);
        channel.registerMessage(packetId++, SaveFormTypePacket.class,
                SaveFormTypePacket::encode, SaveFormTypePacket::decode, SaveFormTypePacket::handle);
        channel.registerMessage(packetId++, OpenRaceEditorPacket.class,
                OpenRaceEditorPacket::encode, OpenRaceEditorPacket::decode, OpenRaceEditorPacket::handle);
        channel.registerMessage(packetId++, SaveRacePacket.class,
                SaveRacePacket::encode, SaveRacePacket::decode, SaveRacePacket::handle);
        channel.registerMessage(packetId++, DeleteRacePacket.class,
                DeleteRacePacket::encode, DeleteRacePacket::decode, DeleteRacePacket::handle);
        channel.registerMessage(packetId++, ActivateRacialPacket.class,
                ActivateRacialPacket::encode, ActivateRacialPacket::decode, ActivateRacialPacket::handle);
        channel.registerMessage(packetId++, OpenWishEditorPacket.class,
                OpenWishEditorPacket::encode, OpenWishEditorPacket::decode, OpenWishEditorPacket::handle);
        channel.registerMessage(packetId++, SaveWishPacket.class,
                SaveWishPacket::encode, SaveWishPacket::decode, SaveWishPacket::handle);
        channel.registerMessage(packetId++, DeleteWishPacket.class,
                DeleteWishPacket::encode, DeleteWishPacket::decode, DeleteWishPacket::handle);
        channel.registerMessage(packetId++, OpenHubPacket.class,
                OpenHubPacket::encode, OpenHubPacket::decode, OpenHubPacket::handle);
        channel.registerMessage(packetId++, OpenEditorRequestPacket.class,
                OpenEditorRequestPacket::encode, OpenEditorRequestPacket::decode, OpenEditorRequestPacket::handle);
        channel.registerMessage(packetId++, SaveLangPacket.class,
                SaveLangPacket::encode, SaveLangPacket::decode, SaveLangPacket::handle);
        channel.registerMessage(packetId++, LangSyncPacket.class,
                LangSyncPacket::encode, LangSyncPacket::decode, LangSyncPacket::handle);
        channel.registerMessage(packetId++, KeySyncPacket.class,
                KeySyncPacket::encode, KeySyncPacket::decode, KeySyncPacket::handle);
        channel.registerMessage(packetId++, DodgeAnimPacket.class,
                DodgeAnimPacket::encode, DodgeAnimPacket::decode, DodgeAnimPacket::handle);
        channel.registerMessage(packetId++, FormAuraSyncPacket.class,
                FormAuraSyncPacket::encode, FormAuraSyncPacket::decode, FormAuraSyncPacket::handle);
        channel.registerMessage(packetId++, WaypointSyncPacket.class,
                WaypointSyncPacket::encode, WaypointSyncPacket::decode, WaypointSyncPacket::handle);
        channel.registerMessage(packetId++, SyncPreviewClonesPacket.class,
                SyncPreviewClonesPacket::encode, SyncPreviewClonesPacket::decode, SyncPreviewClonesPacket::handle);
        // APPEND-ONLY below: registration order is the wire id, so new packets go at the end.
        channel.registerMessage(packetId++, ChunkedSavePacket.class,
                ChunkedSavePacket::encode, ChunkedSavePacket::decode, ChunkedSavePacket::handle);
        channel.registerMessage(packetId++, OpenOptionsPacket.class,
                OpenOptionsPacket::encode, OpenOptionsPacket::decode, OpenOptionsPacket::handle);
        channel.registerMessage(packetId++, SaveConfigPacket.class,
                SaveConfigPacket::encode, SaveConfigPacket::decode, SaveConfigPacket::handle);
        // Shenron-shrine feature (fully custom)
        channel.registerMessage(packetId++, OpenShrineGuiPacket.class,
                OpenShrineGuiPacket::encode, OpenShrineGuiPacket::decode, OpenShrineGuiPacket::handle);
        channel.registerMessage(packetId++, ShrineSummonC2S.class,
                ShrineSummonC2S::encode, ShrineSummonC2S::decode, ShrineSummonC2S::handle);
        channel.registerMessage(packetId++, OpenWishSelectPacket.class,
                OpenWishSelectPacket::encode, OpenWishSelectPacket::decode, OpenWishSelectPacket::handle);
        channel.registerMessage(packetId++, ShrineWishC2S.class,
                ShrineWishC2S::encode, ShrineWishC2S::decode, ShrineWishC2S::handle);
        // Shenron-shrine ADMIN config GUI
        channel.registerMessage(packetId++, OpenShrineConfigPacket.class,
                OpenShrineConfigPacket::encode, OpenShrineConfigPacket::decode, OpenShrineConfigPacket::handle);
        // Per-form-type presentation meta: radial/skills icon + tint
        channel.registerMessage(packetId++, FormTypeMetaSyncPacket.class,
                FormTypeMetaSyncPacket::encode, FormTypeMetaSyncPacket::decode, FormTypeMetaSyncPacket::handle);
        // Quest-gated form-purchase map sync
        channel.registerMessage(packetId++, FormQuestGateSyncPacket.class,
                FormQuestGateSyncPacket::encode, FormQuestGateSyncPacket::decode, FormQuestGateSyncPacket::handle);
        // Custom form-type id rename (op-gated C2S)
        channel.registerMessage(packetId++, RenameFormTypePacket.class,
                RenameFormTypePacket::encode, RenameFormTypePacket::decode, RenameFormTypePacket::handle);
        // Suppressed default race/class id sets S2C
        channel.registerMessage(packetId++, SuppressedDefaultsSyncPacket.class,
                SuppressedDefaultsSyncPacket::encode, SuppressedDefaultsSyncPacket::decode, SuppressedDefaultsSyncPacket::handle);
        // Suppress/restore a default race/class (op-gated C2S)
        channel.registerMessage(packetId++, ToggleSuppressPacket.class,
                ToggleSuppressPacket::encode, ToggleSuppressPacket::decode, ToggleSuppressPacket::handle);
        // Gravity Chamber per-block config GUI: open (S2C) + save (C2S)
        channel.registerMessage(packetId++, OpenGravityChamberConfigPacket.class,
                OpenGravityChamberConfigPacket::encode, OpenGravityChamberConfigPacket::decode, OpenGravityChamberConfigPacket::handle);
        channel.registerMessage(packetId++, SaveGravityChamberPacket.class,
                SaveGravityChamberPacket::encode, SaveGravityChamberPacket::decode, SaveGravityChamberPacket::handle);
        // Level Barrier per-block config GUI: open (S2C) + save (C2S)
        channel.registerMessage(packetId++, OpenBarrierConfigPacket.class,
                OpenBarrierConfigPacket::encode, OpenBarrierConfigPacket::decode, OpenBarrierConfigPacket::handle);
        channel.registerMessage(packetId++, SaveBarrierPacket.class,
                SaveBarrierPacket::encode, SaveBarrierPacket::decode, SaveBarrierPacket::handle);
        // Per-race base-aura size multipliers S2C
        channel.registerMessage(packetId++, RaceAuraSyncPacket.class,
                RaceAuraSyncPacket::encode, RaceAuraSyncPacket::decode, RaceAuraSyncPacket::handle);
        // KILL-objective DMZ NPC default-stats lookup: request (C2S) + reply (S2C)
        channel.registerMessage(packetId++, RequestNpcDefaultsPacket.class,
                RequestNpcDefaultsPacket::encode, RequestNpcDefaultsPacket::decode, RequestNpcDefaultsPacket::handle);
        channel.registerMessage(packetId++, SyncNpcDefaultsPacket.class,
                SyncNpcDefaultsPacket::encode, SyncNpcDefaultsPacket::decode, SyncNpcDefaultsPacket::handle);
        // Stack-skill (kaioken/ultimate) buy: our working grant path, NOT DMZ's broken PURCHASE. PLAY_TO_SERVER.
        channel.registerMessage(packetId++, BuyStackSkillC2S.class,
                BuyStackSkillC2S::encode, BuyStackSkillC2S::decode, BuyStackSkillC2S::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(packetId++, TokenBuffSyncPacket.class,
                TokenBuffSyncPacket::encode, TokenBuffSyncPacket::decode, TokenBuffSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(packetId++, ModuleSyncPacket.class,
                ModuleSyncPacket::encode, ModuleSyncPacket::decode, ModuleSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        // Quest-gated saga-unlock map sync
        channel.registerMessage(packetId++, SagaQuestGateSyncPacket.class,
                SagaQuestGateSyncPacket::encode, SagaQuestGateSyncPacket::decode, SagaQuestGateSyncPacket::handle);
        // Per-form minimum-level-to-transform map sync
        channel.registerMessage(packetId++, FormLevelGateSyncPacket.class,
                FormLevelGateSyncPacket::encode, FormLevelGateSyncPacket::decode, FormLevelGateSyncPacket::handle);
        // Per-form alignment-gate map sync (unlock + use windows)
        channel.registerMessage(packetId++, FormAlignmentGateSyncPacket.class,
                FormAlignmentGateSyncPacket::encode, FormAlignmentGateSyncPacket::decode, FormAlignmentGateSyncPacket::handle);
        // Staff /dmzinfo: open DMZ's V menu read-only on the viewer, showing a target's DMZ progression
        channel.registerMessage(packetId++, PlayerInfoOpenPacket.class,
                PlayerInfoOpenPacket::encode, PlayerInfoOpenPacket::decode, PlayerInfoOpenPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        // Installed PRIVATE key-feature ids S2C (id 56): tells the client which key features the server really
        // installed, so client UI can gate on ClientGate.feature(id) per feature.
        channel.registerMessage(packetId++, KeyFeatureSyncPacket.class,
                KeyFeatureSyncPacket::encode, KeyFeatureSyncPacket::decode, KeyFeatureSyncPacket::handle);
    }

    /**
     * Tell one player which module switches this server turned off, so their screens stop offering what the
     * server will refuse. Sent on login; see {@link ModuleSyncPacket} for why only the disabled set travels.
     */
    public static void syncModulesToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), ModuleSyncPacket.current());
    }

    /** Push the server's suppressed default race/class id sets to one player (on login). */
    public static void syncSuppressedDefaultsToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new SuppressedDefaultsSyncPacket(
                        net.shurui.dev.sdu.race.SuppressedDefaultsConfig.races(),
                        net.shurui.dev.sdu.race.SuppressedDefaultsConfig.classes()));
    }

    /** Push the server's suppressed default race/class id sets to every connected client (after a change). */
    public static void syncSuppressedDefaultsToAll(MinecraftServer server) {
        channel.send(PacketDistributor.ALL.noArg(),
                new SuppressedDefaultsSyncPacket(
                        net.shurui.dev.sdu.race.SuppressedDefaultsConfig.races(),
                        net.shurui.dev.sdu.race.SuppressedDefaultsConfig.classes()));
    }

    /** Push the server's quest-gated form-purchase map to one player (on login), for advisory skills-GUI UX. */
    public static void syncFormQuestGatesToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new FormQuestGateSyncPacket(net.shurui.dev.sdu.form.FormQuestGateConfig.all()));
    }

    /** Push the server's quest-gated form-purchase map to every connected client (after a saga/gate edit). */
    public static void syncFormQuestGatesToAll() {
        channel.send(PacketDistributor.ALL.noArg(),
                new FormQuestGateSyncPacket(net.shurui.dev.sdu.form.FormQuestGateConfig.all()));
    }

    /** Push the server's quest-gated saga-unlock map to one player (on login), for the quest-tree saga lock. */
    public static void syncSagaQuestGatesToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new SagaQuestGateSyncPacket(net.shurui.dev.sdu.saga.SagaQuestGateConfig.all()));
    }

    /** Push the server's quest-gated saga-unlock map to every connected client (after a saga/gate edit). */
    public static void syncSagaQuestGatesToAll() {
        channel.send(PacketDistributor.ALL.noArg(),
                new SagaQuestGateSyncPacket(net.shurui.dev.sdu.saga.SagaQuestGateConfig.all()));
    }

    /** Push the server's per-form minimum-level map to one player (on login), so the skills GUI can show the lock. */
    public static void syncFormLevelGatesToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new FormLevelGateSyncPacket(net.shurui.dev.sdu.form.FormLevelGateConfig.all()));
    }

    /** Push the server's per-form minimum-level map to every connected client (after a form edit). */
    public static void syncFormLevelGatesToAll() {
        channel.send(PacketDistributor.ALL.noArg(),
                new FormLevelGateSyncPacket(net.shurui.dev.sdu.form.FormLevelGateConfig.all()));
    }

    /** Push the server's per-form alignment-gate map to one player (on login), so the skills GUI can show the lock. */
    public static void syncFormAlignmentGatesToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new FormAlignmentGateSyncPacket(net.shurui.dev.sdu.form.FormAlignmentGateConfig.all()));
    }

    /** Push the server's per-form alignment-gate map to every connected client (after a form edit). */
    public static void syncFormAlignmentGatesToAll() {
        channel.send(PacketDistributor.ALL.noArg(),
                new FormAlignmentGateSyncPacket(net.shurui.dev.sdu.form.FormAlignmentGateConfig.all()));
    }

    /** Push the server's per-form-type icon/tint meta to one player (on login). */
    public static void syncFormTypeMetaToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new FormTypeMetaSyncPacket(net.shurui.dev.sdu.form.FormTypeMetaConfig.all()));
    }

    /** Push the server's per-form-type icon/tint meta to every connected client (after a form-type edit). */
    public static void syncFormTypeMetaToAll() {
        channel.send(PacketDistributor.ALL.noArg(),
                new FormTypeMetaSyncPacket(net.shurui.dev.sdu.form.FormTypeMetaConfig.all()));
    }

    /** Open the Shenron-shrine admin config screen, seeded with the server's entire live shrine config. */
    public static void openShrineConfig(net.minecraft.server.level.ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenShrineConfigPacket(net.shurui.dev.sdu.shenron.ShrineConfig.toBundleJson()));
    }

    /** Open the config/options overview on the player's client, seeded with the server's live config values. */
    public static void openOptions(net.minecraft.server.level.ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new OpenOptionsPacket(
                net.shurui.dev.sdu.Config.requireOpToEdit,
                net.shurui.dev.sdu.Config.maxNpcHealth,
                net.shurui.dev.sdu.Config.enableDmzIntegration,
                net.shurui.dev.sdu.Config.enableAuraStacking,
                net.shurui.dev.sdu.Config.shadowDummyCooldownSeconds,
                net.shurui.dev.sdu.Config.shadowDummyMaxAlivePerParty,
                net.shurui.dev.sdu.Config.partyTpFalloffThreshold,
                net.shurui.dev.sdu.Config.partyTpFalloffStepPercent,
                net.shurui.dev.sdu.Config.partyTpFalloffFloorPercent));
    }

    /** Push the per-quest saved-NPC preview appearances to one player (on login), for the DMZ quest GUI. */
    public static void syncPreviewClonesToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncPreviewClonesPacket(net.shurui.dev.sdu.saga.QuestPreviewResolver.get(player.getServer())));
    }

    /** Rebuild and push the per-quest saved-NPC preview appearances to every client (after a quest edit). */
    public static void syncPreviewClonesToAll(MinecraftServer server) {
        net.shurui.dev.sdu.saga.QuestPreviewResolver.invalidate();
        channel.send(PacketDistributor.ALL.noArg(),
                new SyncPreviewClonesPacket(net.shurui.dev.sdu.saga.QuestPreviewResolver.get(server)));
    }

    /** Replace one player's active HUD waypoints (manual + current quest) on their client. */
    public static void syncWaypointsToPlayer(ServerPlayer player,
                                             java.util.List<net.shurui.dev.sdu.waypoint.Waypoint> waypoints) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new WaypointSyncPacket(waypoints));
    }

    /** Play the cosmetic dodge twist on the dodging player for everyone who can see them (and them). */
    public static void sendDodgeAnimation(net.minecraft.world.entity.player.Player player, boolean left) {
        channel.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new DodgeAnimPacket(player.getId(), left));
    }

    /** Tell one player whether this server holds the Ragnarok Key (on login), for client-side feature gating. */
    public static void syncKeyStatusToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new KeySyncPacket(clientKeyPresent()));
    }

    /**
     * The server's own proof, per private feature id, that the real key installed that feature: normally the
     * installed hook's {@code available()}, whose keyless default is false. Registered by core and the module trees
     * (never by the key) at common setup; see {@link #answerFeature(String, java.util.function.BooleanSupplier)}.
     */
    private static final java.util.Map<String, java.util.function.BooleanSupplier> FEATURE_ANSWERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Proofs that the core key hooks are really installed; all must hold before clients are told "key". */
    private static final java.util.List<java.util.function.BooleanSupplier> KEY_PROOFS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        requireForKey(net.shurui.dev.sdu.api.key.CoreGateHooks::available);
        answerFeature(net.shurui.dev.sdu.api.key.CoreGateHooks.FEATURE_ID,
                net.shurui.dev.sdu.api.key.CoreGateHooks::available);
        answerFeature(net.shurui.dev.sdu.api.key.EventHooks.FEATURE_ID,
                net.shurui.dev.sdu.api.key.EventHooks::available);
        answerFeature(net.shurui.dev.sdu.api.key.HakaiHooks.FEATURE_ID,
                net.shurui.dev.sdu.api.key.HakaiHooks::available);
        answerFeature(net.shurui.dev.sdu.api.key.PrivateWorldHooks.FEATURE_ID,
                net.shurui.dev.sdu.api.key.PrivateWorldHooks::available);
        answerFeature(net.shurui.dev.sdu.api.key.TokenBuffHooks.FEATURE_ID,
                net.shurui.dev.sdu.api.key.TokenBuffHooks::available);
        answerFeature(net.shurui.dev.sdu.api.key.WaypointHooks.FEATURE_ID,
                net.shurui.dev.sdu.api.key.WaypointHooks::available);
    }

    /**
     * Register the server's own answer for a private feature id. An id reaches clients only when it is marked AND
     * has an answer AND that answer is true at sync time; an id with no answer is never sent. A jar that marks ids
     * without installing the real hooks (a fake key) therefore turns no client UI on. Wire format unchanged: still
     * the id list.
     */
    public static void answerFeature(String featureId, java.util.function.BooleanSupplier answer) {
        if (featureId != null && answer != null) {
            FEATURE_ANSWERS.put(featureId, answer);
        }
    }

    /** Add a proof that the core key hooks are installed; the client key flag needs every proof true. */
    public static void requireForKey(java.util.function.BooleanSupplier proof) {
        if (proof != null) {
            KEY_PROOFS.add(proof);
        }
    }

    private static boolean holds(java.util.function.BooleanSupplier answer) {
        try {
            return answer != null && answer.getAsBoolean();
        } catch (Throwable t) {
            return false; // an answer that cannot be read is a no
        }
    }

    /**
     * The key flag clients receive: the key marked itself installed ({@code RagnarokKey.present()}) AND every core
     * hook proof holds. A jar that only marks {@code ragnarok_key} answers false here.
     */
    public static boolean clientKeyPresent() {
        if (!net.shurui.dev.sdu.api.RagnarokKey.present() || KEY_PROOFS.isEmpty()) {
            return false;
        }
        for (java.util.function.BooleanSupplier proof : KEY_PROOFS) {
            if (!holds(proof)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a root command of this name is registered on the running server (a proof for command-only features). */
    public static boolean commandRegistered(String name) {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null && server.getCommands().getDispatcher().getRoot().getChild(name) != null;
    }

    /**
     * Sorted private feature ids clients receive: only when {@link #clientKeyPresent()}, and of the ids the key
     * marked, only those whose registered answer holds ({@code ragnarok_key} itself follows the key flag).
     */
    public static List<String> clientFeatureIds() {
        List<String> ids = new ArrayList<>();
        if (!clientKeyPresent()) {
            return ids;
        }
        for (String id : net.shurui.dev.sdu.api.KeyFeatures.ids()) {
            if (net.shurui.dev.sdu.api.RagnarokKey.KEY_FEATURE.equals(id) || holds(FEATURE_ANSWERS.get(id))) {
                ids.add(id);
            }
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    /** Tell one player which PRIVATE key features this server installed (on login), for client-side UI gating. */
    public static void syncKeyFeaturesToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new KeyFeatureSyncPacket(clientFeatureIds()));
    }

    /**
     * Re-push the installed key-feature set to every connected client. Called when {@code KeyFeatures.mark} adds a
     * new id after players are already online (unlikely: the key marks in its constructor, before any login).
     */
    public static void syncKeyFeaturesToAll(MinecraftServer server) {
        channel.send(PacketDistributor.ALL.noArg(), new KeyFeatureSyncPacket(clientFeatureIds()));
    }

    /** Push the server's stored generated names/descriptions to one player (on login). */
    public static void syncLangToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new LangSyncPacket(net.shurui.dev.sdu.lang.GeneratedLangStore.all()));
    }

    /** Push the server's stored generated names/descriptions to every connected client (after an edit). */
    public static void syncLangToAll(MinecraftServer server) {
        channel.send(PacketDistributor.ALL.noArg(),
                new LangSyncPacket(net.shurui.dev.sdu.lang.GeneratedLangStore.all()));
    }

    /** Push the server's per-form extra aura layers to one player (on login). */
    public static void syncFormAurasToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new FormAuraSyncPacket(net.shurui.dev.sdu.form.FormAuraConfig.all()));
    }

    /** Push the server's per-form extra aura layers to every connected client (after a form edit). */
    public static void syncFormAurasToAll() {
        channel.send(PacketDistributor.ALL.noArg(),
                new FormAuraSyncPacket(net.shurui.dev.sdu.form.FormAuraConfig.all()));
    }

    /** Push the server's per-race base-aura size multipliers to one player (on login). */
    public static void syncRaceAurasToPlayer(ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new RaceAuraSyncPacket(net.shurui.dev.sdu.race.RaceAuraConfig.all()));
    }

    /** Push the server's per-race base-aura size multipliers to every connected client (after a race edit). */
    public static void syncRaceAurasToAll() {
        channel.send(PacketDistributor.ALL.noArg(),
                new RaceAuraSyncPacket(net.shurui.dev.sdu.race.RaceAuraConfig.all()));
    }

    /** Open the main editor hub menu on the player's client. */
    public static void openHub(net.minecraft.server.level.ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new OpenHubPacket());
    }

    /** Load DMZ races from config and open the race editor on the given player's client. */
    public static void openRaceEditor(net.minecraft.server.level.ServerPlayer player) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<String> bundles = new ArrayList<>();
        for (net.shurui.dev.sdu.race.RaceData race : net.shurui.dev.sdu.race.RaceFileManager.loadAll()) {
            bundles.add(gson.toJson(race.toBundle()));
        }
        channel.send(PacketDistributor.PLAYER.with(() -> player), new OpenRaceEditorPacket(bundles));
    }

    /** Load DMZ form groups from config and open the form editor on the given player's client. */
    public static void openFormEditor(net.minecraft.server.level.ServerPlayer player) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<String> bundles = new ArrayList<>();
        for (net.shurui.dev.sdu.form.FormGroupData group : net.shurui.dev.sdu.form.FormFileManager.loadAll()) {
            bundles.add(gson.toJson(group.toBundle()));
        }
        channel.send(PacketDistributor.PLAYER.with(() -> player), new OpenFormEditorPacket(bundles));
    }

    /** Load sagas from the world save and open the editor on the given player's client. */
    public static void openSagaEditor(net.minecraft.server.level.ServerPlayer player) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<String> bundles = new ArrayList<>();
        // DMZ writes its default sagas only when absent, so an edit saved here persists: DMZ won't overwrite it.
        for (SagaData saga : SagaFileManager.loadAll(player.getServer())) {
            bundles.add(gson.toJson(saga.toBundle()));
        }
        channel.send(PacketDistributor.PLAYER.with(() -> player), new OpenSagaEditorPacket(bundles));
    }

    /** Load side quests from the world save and open the editor on the given player's client. */
    public static void openSideQuestEditor(net.minecraft.server.level.ServerPlayer player) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<String> bundles = new ArrayList<>();
        for (net.shurui.dev.sdu.saga.SideQuestData sq : net.shurui.dev.sdu.saga.SideQuestFileManager.loadAll(player.getServer())) {
            bundles.add(gson.toJson(sq.toBundle()));
        }
        channel.send(PacketDistributor.PLAYER.with(() -> player), new OpenSideQuestEditorPacket(bundles));
    }

    /** Load dragon wish sets + custom dragon packs and open the wishes editor on the player's client. */
    public static void openWishEditor(net.minecraft.server.level.ServerPlayer player) {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        // Disk wish files unioned with the dragons DMZ knows at runtime, keyed by dragon id. TreeMap for a stable
        // alphabetical order. Disk files loaded LAST so a saved edit wins over the in-memory default.
        java.util.Map<String, net.shurui.dev.sdu.wish.WishSetData> sets = new java.util.TreeMap<>();
        for (net.shurui.dev.sdu.wish.WishSetData set : runtimeWishSets()) {
            sets.putIfAbsent(set.dragon, set);
        }
        for (net.shurui.dev.sdu.wish.WishSetData set : net.shurui.dev.sdu.wish.WishFileManager.loadAll(player.getServer())) {
            sets.put(set.dragon, set);
        }

        List<String> bundles = new ArrayList<>();
        for (net.shurui.dev.sdu.wish.WishSetData set : sets.values()) {
            bundles.add(gson.toJson(set.toBundle()));
        }
        channel.send(PacketDistributor.PLAYER.with(() -> player), new OpenWishEditorPacket(bundles));
    }

    // Every dragon DMZ knows at runtime (DragonWishRegistry.getServerWishes), including code-only sets with no disk
    // file. Each Wish round-trips through toJson() then the same parse a loaded file uses (WishSetData.fromWishArray
    // -> WishData.fromJson) so type detection matches. DragonWishRegistry is DMZ-internal and could move; a failure
    // degrades to disk-only and logs one line.
    private static List<net.shurui.dev.sdu.wish.WishSetData> runtimeWishSets() {
        List<net.shurui.dev.sdu.wish.WishSetData> result = new ArrayList<>();
        try {
            for (Map.Entry<String, java.util.List<com.dragonminez.common.wish.Wish>> e :
                    com.dragonminez.common.wish.DragonWishRegistry.getServerWishes().entrySet()) {
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (com.dragonminez.common.wish.Wish wish : e.getValue()) {
                    arr.add(com.google.gson.JsonParser.parseString(wish.toJson()).getAsJsonObject());
                }
                result.add(net.shurui.dev.sdu.wish.WishSetData.fromWishArray(e.getKey(), arr));
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not enumerate runtime DMZ wishes ({}); showing disk files only.", DmzNpc.MODID, t.toString());
            return new ArrayList<>();
        }
        return result;
    }

    public static void sendToPlayer(Object packet, ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Staff {@code /dmzinfo} bridge: open DMZ's V menu on {@code viewer}'s client in read-only viewing mode,
     * showing {@code targetName}'s DragonMineZ progression from the given {@code StatsData.save()} blob. Called
     * from the ShuruisUtilities command (SU -> sdu is permitted; sdu adds no SU reference). A null blob is not
     * sent; the caller reports "no DMZ data" to the viewer instead.
     */
    public static void sendPlayerInfo(ServerPlayer viewer, String targetName,
                                      java.util.UUID targetUuid, net.minecraft.nbt.CompoundTag statsNbt) {
        if (viewer == null || statsNbt == null) {
            return;
        }
        channel.send(PacketDistributor.PLAYER.with(() -> viewer),
                new PlayerInfoOpenPacket(targetName, targetUuid, statsNbt));
    }

    /** The mod's network channel, for packet handlers that need to reply (e.g. clone-list sync). */
    public static SimpleChannel channel() {
        return channel;
    }

    public static void sendToServer(Object packet) {
        channel.sendToServer(packet);
    }

    /** Max uncompressed slice size per {@link ChunkedSavePacket}; safely under the 32767-byte C2S wire limit. */
    private static final int CHUNK_SIZE = 28 * 1024;

    /**
     * Client-side: GZIP a large JSON payload and send it to the server as ordered {@link #CHUNK_SIZE}-byte
     * {@link ChunkedSavePacket}s, reassembled and dispatched by {@code kind} (see {@link ChunkedSaveBuffer}).
     * Use for every editor save (saga/sidequest/form/race/wish/dragon/lang); the legacy single-payload packets
     * stay registered only for wire-id stability.
     */
    public static void sendLargeToServer(String kind, String json) {
        byte[] gz;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(baos)) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            gz = baos.toByteArray();
        } catch (Exception e) {
            net.shurui.dev.sdu.DmzNpc.LOGGER.error("[{}] Failed to compress '{}' save payload",
                    net.shurui.dev.sdu.DmzNpc.MODID, kind, e);
            return;
        }

        int total = Math.max(1, (gz.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_SIZE;
            int len = Math.min(CHUNK_SIZE, gz.length - start);
            byte[] chunk = new byte[len];
            System.arraycopy(gz, start, chunk, 0, len);
            channel.sendToServer(new ChunkedSavePacket(kind, i, total, chunk));
        }
    }

}

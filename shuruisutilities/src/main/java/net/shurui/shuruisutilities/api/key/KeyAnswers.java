package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.network.DmzNet;

/**
 * Core's answers for what clients are told about the Ragnarok Key (SF follow-up, fake jar B). A client learns a private
 * feature id only when the SERVER can see the real hook for it installed, never from the {@code KeyFeatures} mark
 * alone: any jar with the key's mod id can call {@code KeyFeatures.mark}, but only the real key installs the hooks.
 *
 * <p>Each id is answered by its hook's {@code available()} (keyless default false). The three features the key
 * installs without a core hook (the {@code /model} command, {@code /rgentity}, and the admin command set) are
 * answered by their key-only commands being registered. The mutant nerf keeps its own answer,
 * {@link MutantHooks#nerfActive()}, because its client display follows whether the nerf is in force. The client key
 * flag additionally needs the core economy and permission hooks installed ({@link DmzNet#requireForKey}).
 *
 * <p>Registered once from common setup. Dungeons, Raids and Tournaments register their own ids from their own setup.
 */
public final class KeyAnswers
{
    private KeyAnswers() {}

    public static void register()
    {
        DmzNet.requireForKey(EconomyHooks::available);
        DmzNet.requireForKey(PermissionHooks::available);

        DmzNet.answerFeature(AdminHooks.FEATURE_ID, AdminHooks::available);
        DmzNet.answerFeature(AirdropHooks.FEATURE_ID, AirdropHooks::available);
        DmzNet.answerFeature(ArgentHooks.FEATURE_ID, ArgentHooks::available);
        DmzNet.answerFeature(AuctionHooks.FEATURE_ID, AuctionHooks::available);
        DmzNet.answerFeature(BountyHooks.FEATURE_ID, BountyHooks::available);
        DmzNet.answerFeature(ChatHooks.FEATURE_ID, ChatHooks::available);
        DmzNet.answerFeature(CorruptedHooks.FEATURE_ID, CorruptedHooks::available);
        DmzNet.answerFeature(CosmeticHooks.FEATURE_ID, CosmeticHooks::available);
        DmzNet.answerFeature(CrateHooks.FEATURE_ID, CrateHooks::available);
        DmzNet.answerFeature(DisguiseHooks.FEATURE_ID, DisguiseHooks::available);
        DmzNet.answerFeature(EconomyHooks.FEATURE_ID, EconomyHooks::available);
        DmzNet.answerFeature(GuildHooks.FEATURE_ID, GuildHooks::available);
        DmzNet.answerFeature(GuildRaidHooks.FEATURE_ID, GuildRaidHooks::available);
        DmzNet.answerFeature(HologramHooks.FEATURE_ID, HologramHooks::available);
        DmzNet.answerFeature(JailHooks.FEATURE_ID, JailHooks::available);
        DmzNet.answerFeature(ModerationHooks.FEATURE_ID, ModerationHooks::available);
        DmzNet.answerFeature(NpcRegionHooks.FEATURE_ID, NpcRegionHooks::available);
        DmzNet.answerFeature(OcarinaHooks.FEATURE_ID, OcarinaHooks::available);
        DmzNet.answerFeature(PermissionHooks.FEATURE_ID, PermissionHooks::available);
        DmzNet.answerFeature(PrestigeHooks.FEATURE_ID, PrestigeHooks::available);
        DmzNet.answerFeature(ProtectionHooks.FEATURE_ID, ProtectionHooks::available);
        DmzNet.answerFeature(RaceHooks.FEATURE_ID, RaceHooks::available);
        DmzNet.answerFeature(RankHooks.FEATURE_ID, RankHooks::available);
        DmzNet.answerFeature(RegionHooks.FEATURE_ID, RegionHooks::available);
        DmzNet.answerFeature(RitualHooks.FEATURE_ID, RitualHooks::available);
        DmzNet.answerFeature(RoleHooks.FEATURE_ID, RoleHooks::available);
        DmzNet.answerFeature(SaibamanPetHooks.FEATURE_ID, SaibamanPetHooks::available);
        DmzNet.answerFeature(ShadowFormHooks.FEATURE_ID, ShadowFormHooks::available);
        DmzNet.answerFeature(ShardHooks.FEATURE_ID, ShardHooks::available);
        DmzNet.answerFeature(ShrineHooks.FEATURE_ID, ShrineHooks::available);
        DmzNet.answerFeature(SparringHooks.FEATURE_ID, SparringHooks::available);
        DmzNet.answerFeature(StaffHooks.FEATURE_ID, StaffHooks::available);
        DmzNet.answerFeature(TaskHooks.FEATURE_ID, TaskHooks::available);
        DmzNet.answerFeature(TeleportHooks.FEATURE_ID, TeleportHooks::available);
        DmzNet.answerFeature(TpBoostHooks.FEATURE_ID, TpBoostHooks::available);
        DmzNet.answerFeature(TradeHooks.FEATURE_ID, TradeHooks::available);
        DmzNet.answerFeature(VanishHooks.FEATURE_ID, VanishHooks::available);
        DmzNet.answerFeature(WorldBorderHooks.FEATURE_ID, WorldBorderHooks::available);
        DmzNet.answerFeature(ZOrbHooks.FEATURE_ID, ZOrbHooks::available);
        DmzNet.answerFeature(MutantHooks.FEATURE_ID, MutantHooks::nerfActive);
        // Installed by the key with no core hook: proven by the key-only command it registers.
        DmzNet.answerFeature("model", () -> DmzNet.commandRegistered("model"));
        DmzNet.answerFeature("rgentity", () -> DmzNet.commandRegistered("rgentity"));
        DmzNet.answerFeature("admincommands", () -> DmzNet.commandRegistered("modlist"));
    }
}

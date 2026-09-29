package net.shurui.dev.sdu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import com.dragonminez.common.network.C2S.CombatAttackRequestC2S;

// Live-server fix: in crowded fights players get kicked with
//   io.netty.handler.codec.DecoderException: CombatAttackRequestC2S: invalid entity id count 66
// DMZ's server-side decoder (the FriendlyByteBuf ctor) reads a length int and throws when it exceeds 64. A
// throw inside a Netty decoder drops the whole connection, so a big melee swing disconnects the player. We
// raise the decode cap 64 -> 256 so the packet reads instead of dropping.
//
// The 64 is a real inlined bipush appearing EXACTLY ONCE in the ctor (MAX_ENTITY_IDS is never read via
// getstatic), so this @ModifyConstant binds unambiguously. The throw precedes the array allocation and the
// read loop consumes exactly `length` ints, so raising the cap leaves the buffer fully consumed: no desync.
// The separate `length < 0` guard still stands, so a bounded raise cannot cause an allocation DoS.
//
// We deliberately DO NOT truncate entityIds back to 64 after decode (the standalone dmz_mohist_melee_fix mod
// does). DMZ's processAttackRequest iterates entityIds with a plain enhanced-for, no fixed indexing, and
// range/relation validates every id server-side. Truncating would silently drop legitimate hits. Do not
// "restore" the truncation.
//
// 256 is a deliberate BOUNDED constant: it keeps the array allocation gated so a hostile client cannot
// request an arbitrarily large array. Do not widen this to Integer.MAX_VALUE.
//
// require = 0 (safe-off): a DMZ refactor of this constant makes the injector no-op rather than hard-fail,
// degrading to DMZ's original behaviour (disconnect on > 64). PLAY_TO_SERVER only, so server-side decode.
// remap = false. Registered in the COMMON mixins array of sdu.mixins.json.
@Mixin(targets = "com.dragonminez.common.network.C2S.CombatAttackRequestC2S", remap = false)
public abstract class CombatAttackRequestC2SMixin {

    // DMZ's persisted "when did this player last swing" stamp, in level.getGameTime() ticks. Spelled out here rather
    // than referenced because DMZ keeps it a private constant on the packet. Matches ShardSync.DMZ_LAST_MELEE_TAG.
    private static final String LAST_MELEE_ATTACK_TIME_TAG = "dmz_last_melee_attack_time";

    // The choke-point backstop for the "cannot punch after a shard hop / dungeon entry" bug. DMZ's melee gate (in
    // lambda$processAttackRequest$2) is:
    //   long now  = player.level().getGameTime();
    //   long last = player.getPersistentData().getLong("dmz_last_melee_attack_time");
    //   int  cd   = Math.max(0, (int) Math.floor(player.getCurrentItemAttackStrengthDelay()) - 2);
    //   if (last > 0 && (now - last) < cd) return;   // SILENT, before any Forge event
    // now - last is a signed long, so a stamp dated in this world's FUTURE makes the difference negative, below any
    // cooldown, and the swing is refused with no log, no message and healthy reach/stats. The stamp lives in
    // getPersistentData() (ForgeData) and the shard vault carries it verbatim (saveWithoutId). Shard game clocks
    // drift by tens of thousands to millions of ticks (measured 2026-09-14: main ~2.9M ticks ahead of ow1/ow2), so a
    // stamp set on a leading shard, then carried to a trailing one (a plain hop, or entering a dungeon dimension
    // hosted on another shard), sits far in this world's future and blocks melee until death clears it (death wipes
    // ForgeData not under PlayerPersisted), only for a relog to restore it from the vault: exactly bugs 736/745/746.
    //
    // ShardSync.clampFutureMeleeTimestamp already clears any future stamp on arrival and on dimension change, which is
    // the precise fix for every skew magnitude. This is the belt to that braces: it runs at the ONE point every swing
    // passes through, so any path that strands the stamp (an arrival hook that did not run, a future DMZ code path,
    // an operator that set a shard clock back) still self-heals on the next swing.
    //
    // Exploit-safe by construction: DMZ only ever writes the stamp as the CURRENT gameTime, which is monotonic, so a
    // legitimate cooldown always has last <= now and last - now <= 0, never above the threshold. A client cannot set
    // the stamp. The only way last exceeds now by more than a real cooldown is clock skew, so clamping it can never be
    // abused to swing faster. The threshold is deliberately an order of magnitude above any conceivable melee cooldown
    // (a few seconds at most) yet far below even the mildest observed shard skew, so a stamp beyond it is necessarily
    // stranded, not a live cooldown.
    private static final long MAX_PLAUSIBLE_MELEE_COOLDOWN_TICKS = 1200L;

    // Static: processAttackRequest is a public STATIC method (javap), so the injector must be static too, or Mixin's
    // checkTargetModifiers fails at APPLY (which require = 0 does NOT soften). Runs on the server thread (DMZ calls it
    // from handle()'s enqueueWork) at HEAD, before it schedules the gate lambda onto the same thread, so removing the
    // stamp here means the gate reads 0 and skips. Argument capture is all or nothing: we take DMZ's exact params
    // (ServerPlayer, CombatAttackRequestC2S) plus CallbackInfo, and nothing else. We never cancel: DMZ's own gate,
    // now seeing a sane stamp, still decides the swing. remap = false: processAttackRequest is DMZ's own name.
    @Inject(
        method = "processAttackRequest(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/network/C2S/CombatAttackRequestC2S;)V",
        at = @At("HEAD"),
        require = 0,
        remap = false
    )
    private static void sdu$clampStrandedMeleeCooldown(ServerPlayer player, CombatAttackRequestC2S request,
            CallbackInfo ci) {
        try {
            CompoundTag pd = player.getPersistentData();
            if (!pd.contains(LAST_MELEE_ATTACK_TIME_TAG)) {
                return;
            }
            long last = pd.getLong(LAST_MELEE_ATTACK_TIME_TAG);
            long now = player.level().getGameTime();
            if (last - now > MAX_PLAUSIBLE_MELEE_COOLDOWN_TICKS) {
                // A stamp this far in this world's future cannot be a live cooldown; treat it as expired. At absent the
                // gate is skipped (last > 0 is false), so the next swing lands at once.
                pd.remove(LAST_MELEE_ATTACK_TIME_TAG);
            }
        } catch (Throwable ignored) {
            // Guarding a swing must never break it: on any fault, leave DMZ's stamp untouched and let its gate run.
        }
    }

    @ModifyConstant(
        method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
        constant = @Constant(intValue = 64),
        require = 0,
        remap = false
    )
    private int sdu$raiseEntityIdDecodeCap(int original) {
        return 256;
    }

}

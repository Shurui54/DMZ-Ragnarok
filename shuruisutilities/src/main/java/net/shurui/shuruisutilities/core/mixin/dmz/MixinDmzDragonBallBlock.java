package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.common.init.block.custom.DragonBallBlock;
import com.dragonminez.common.init.block.custom.DragonBallType;

import net.shurui.shuruisutilities.compat.dmz.BallDormancy;
import net.shurui.shuruisutilities.compat.dmz.SuperDragonBallCollision;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Independent, data-safe changes to DragonMineZ's shared {@code DragonBallBlock}: two relaxations of the SUMMON
 * rules, one collision override for Shurui's oversized Super ball set, and one withhold gate that keeps the three
 * custom sets inert on a keyless dedicated server without ever affecting whether they register. All live here
 * because this is the single block class every ball set shares. The withhold gate ({@code su$gateWithheldCustomSets})
 * is the relocated home of the {@code PublicContent.FEATURE_DRAGONBALL_SETS} check that used to sit, wrongly, on
 * bootstrap registration; see that method for why gating registration desynced clients.
 *
 * <h2>1. Summon a gathered set in ANY dimension (without scattering it everywhere)</h2>
 *
 * <p>A set's {@code dimensions} list drives BOTH where its balls SCATTER and whether a gathered set may be
 * SUMMONED there, because {@code DragonBallBlock.use} guards the summon on
 * {@code !ballSetDefinition.supportsDimension(level.dimension())}. Widening a set's {@code dimensions} by data
 * would therefore also scatter its balls into every listed dimension, which we do NOT want: each set must keep
 * scattering only in its real home dimension. So we leave the data alone and neutralise ONLY the summon-side guard
 * here, by redirecting that single {@code supportsDimension} call inside {@code use} to always report true. The
 * rest of {@code use} (all-balls-nearby, the count check, spawning the dragon) is untouched, so a player can only
 * summon a set they have actually gathered; they simply may now do it in any dimension. The other half of the
 * gate, the DRAGON definition's own {@code dimensions}, is separate and safe to widen by data, and we widen it in
 * the pack instead of touching it here.</p>
 *
 * <h2>2. Any set SIZE, not a hard-coded seven (so a genuine two-ball set can be summoned)</h2>
 *
 * <p>{@code areAllDragonBallsNearby} ends {@code return foundBalls.size() == 7}, so only a seven-ball set can ever
 * satisfy the summon. A real two-ball set (Cerulean) registers and scatters fine by data, but could never be
 * summoned because of that literal 7. We rewrite the constant to the set's own {@code getStars().size()}, so the
 * check becomes "all of THIS set's stars are nearby" for a set of any size (2, 7, or anything else a future set
 * declares). For every existing seven-ball set this is identical to the old behaviour, because their star count is
 * seven.</p>
 *
 * <h2>3. A solid, near-spherical collision shape for the Super set only</h2>
 *
 * <p>Every ball set shares this block's tiny 0.5-block collision nub, which is invisible next to our Super model
 * (a sphere ~2.9 blocks across, {@code dball_super4x.geo.json}), so a player walks straight through the visible
 * ball. We override {@code getShape} (which the block also uses for collision) to return a near-spherical shape
 * from {@link SuperDragonBallCollision} for balls whose set id is {@code super}, and leave every other set on DMZ's
 * original nub. Because the returned shape reaches past the block's own cell, vanilla marks the block as having a
 * large collision shape and honours the overhang for entities in the neighbouring cells, so the whole sphere blocks
 * the player up to the one-block reach limit vanilla allows (see {@link SuperDragonBallCollision} for the residual
 * top-of-sphere note). Guarded on the set id via the shadowed {@code getBallSetId}, so it is Super-only.</p>
 *
 * <p>{@code remap = false} at class level: the {@code @Mixin} target and DMZ's own {@code supportsDimension} /
 * {@code areAllDragonBallsNearby} / {@code getBallSetId} names resolve against DragonMineZ's non-Mojmap names,
 * exactly like SU's other DMZ mixins. The exceptions are the vanilla {@code Block} overrides {@code use} (Mojmap
 * {@code use}, SRG {@code m_6227_} in prod) and {@code getShape} (SRG {@code m_5940_}), so those injectors carry
 * {@code remap = true} to let the refmap map them, while a DMZ {@code @At} target that is NOT remapped is pinned
 * back to {@code remap = false}. {@code require = 0} on every injector per the standing rule: if DMZ renames or
 * reshapes any of these methods, each injector degrades to DMZ's own behaviour (dimension-locked summon,
 * seven-ball-only, nub collision) instead of crashing mod load.</p>
 */
@Mixin(targets = "com.dragonminez.common.init.block.custom.DragonBallBlock", remap = false)
public abstract class MixinDmzDragonBallBlock
{
    // DMZ's own accessor for the set id carried on each ball block instance ("earth", "namek", "super", ...). Shadowed
    // (not re-declared) so we read the real value; remap = false because it is a DMZ name, not a vanilla one.
    @Shadow(remap = false)
    public abstract String getBallSetId();

    // GOAL D: give the Super set a solid, sphere-sized collision/outline shape in place of DMZ's centre nub, so the
    // oversized model actually blocks the player. getShape drives both the outline and, via the block's default
    // collision path, the collision shape, so this one override handles both. Only the Super set is touched; every
    // other set falls through to DMZ's original SHAPE. HEAD + cancellable so a null/other set id runs DMZ's body.
    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$solidSuperBall(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir)
    {
        if (SuperDragonBallCollision.isSuper(getBallSetId()))
        {
            cir.setReturnValue(SuperDragonBallCollision.shape());
        }
    }

    // The three ball sets this suite adds. DMZ's own "earth" and "namek" are never in this set and are never gated.
    @Unique
    private static final Set<String> SU$CUSTOM_SET_IDS = Set.of("blackstar", "super", "cerulean");

    // RELOCATED GATE. This is where the PublicContent FEATURE_DRAGONBALL_SETS gate lives now. It used to sit on the
    // bootstrap registration in MixinDmzDragonBallBootstrap, which was wrong: gating registration made a keyless
    // dedicated server register 16 fewer blocks and items than its clients and shifted every id after them. The sets
    // therefore always REGISTER (a compile-time ReleaseToggles decision, identical on both sides), and the withhold
    // is enforced here on the EFFECT instead: on a keyless dedicated server that does not list the feature in
    // PUBLIC_FEATURES, the three custom sets cannot be summoned, so they grant no dragon, no wish and no training,
    // exactly as if they were absent. PublicContent.allows() is false only on a restricted dedicated server, so a
    // client and a singleplayer/LAN host answer true and never take this branch, which keeps the interaction result
    // identical on both ends of a normal connection. Cancels with PASS (vanilla "nothing happened"), matching the
    // silent do-nothing a player got when the blocks did not exist at all. Runs at HEAD before the Black Star gate
    // below; if the feature is allowed this returns without acting and the rest of use() proceeds untouched.
    @Inject(method = "use", at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$gateWithheldCustomSets(BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir)
    {
        if (level.isClientSide || !SU$CUSTOM_SET_IDS.contains(getBallSetId()))
        {
            return;
        }
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_DRAGONBALL_SETS))
        {
            // Withheld on this keyless dedicated server: the set is registered but inert. Do nothing, quietly.
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    // DORMANCY summon gate: a set spent on a wish turns to stone for a per set duration (see BallDormancy). While
    // dormant its balls exist (freshly re-scattered by DMZ) but must NOT grant another wish, so any summon attempt
    // is refused here with PASS and an action bar line, exactly the way the Black Star gate refuses off-planet. This
    // is what makes dormancy meaningful: rather than trying to stop DMZ from re-scattering the set (which would leave
    // no stone balls to show), we let the set exist but neutralise its usability until it wakes. Runs at HEAD, before
    // the dimension guard is relaxed below. require = 0 + remap = true like the other use() gates: if DMZ reshapes
    // use(), this degrades to "no dormancy gate" rather than crashing mod load.
    @Inject(method = "use", at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$gateDormantSummon(BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir)
    {
        if (level.isClientSide)
        {
            return;
        }
        if (BallDormancy.isDormant(level.getServer(), getBallSetId()))
        {
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.dormancy.summon_denied"), true);
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    // BLACK STAR summon gate: the red dragon (apophis) may be summoned ONLY on a generated planet, i.e. inside the
    // shared planet_surface dimension. This runs at HEAD, before GOAL A below neutralises the stock dimension guard,
    // so it re-restricts JUST the Black Star set while leaving every other set summonable anywhere. It only intervenes
    // when a COMPLETE Black Star set is actually present off-surface (a real summon attempt), so a stray single ball
    // right-clicked elsewhere is left to DMZ's own PASS with no spurious message. Cancels with PASS (the vanilla
    // "nothing happened" result) plus a refusal on the action bar. require = 0 + remap = true like the getShape inject:
    // if DMZ reshapes use(), this degrades to "no gate" (the set's own dimensions still restrict scatter and radar).
    @Inject(method = "use", at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$gateBlackStarSummon(BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir)
    {
        if (level.isClientSide || !"blackstar".equals(getBallSetId()))
        {
            return;
        }
        if (SpaceKeys.isSurface(level))
        {
            // on a generated planet: let the summon proceed normally.
            return;
        }
        if (!su$blackStarSetComplete(level, pos))
        {
            // not a full set here, so no summon would happen anyway: stay quiet and let DMZ run its own PASS.
            return;
        }
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.apophis_summon_denied"), true);
        cir.setReturnValue(InteractionResult.PASS);
    }

    // count whether all seven distinct Black Star ball types sit within the set's summon radius of pos, using only the
    // public DragonBallBlock accessors (no DMZ private call). Mirrors DMZ's own areAllDragonBallsNearby shape so the
    // gate fires exactly when a real summon would.
    @Unique
    private boolean su$blackStarSetComplete(Level level, BlockPos pos)
    {
        DragonBallSetDefinition def = DragonBallDefinitions.getBallSet("blackstar");
        int radius = def != null ? def.getSummonRadius() : 5;
        Set<DragonBallType> found = new HashSet<>();
        for (BlockPos check : BlockPos.betweenClosed(
                pos.offset(-radius, -radius, -radius), pos.offset(radius, radius, radius)))
        {
            Block block = level.getBlockState(check).getBlock();
            if (block instanceof DragonBallBlock ball && "blackstar".equals(ball.getBallSetId()))
            {
                found.add(ball.getBallType());
            }
        }
        return found.size() >= 7;
    }

    // GOAL A: force the summon-side dimension guard true. Only the call inside use() is redirected, so scatter (which
    // reads the same dimensions list elsewhere) is completely unaffected and each set still scatters only at home.
    @Redirect(
        method = "use",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dragonminez/common/dragonball/DragonBallSetDefinition;supportsDimension(Lnet/minecraft/resources/ResourceKey;)Z",
            remap = false
        ),
        require = 0,
        remap = true
    )
    private boolean su$summonInAnyDimension(DragonBallSetDefinition setDefinition, ResourceKey<Level> dimension)
    {
        // Always allow the summon to proceed regardless of the current dimension. The player still had to gather the
        // set, so this only removes the "you may not summon HERE" restriction, nothing else.
        return true;
    }

    // GOAL C: compare the found-ball count against THIS set's declared star count instead of a hard-coded seven, so a
    // two-ball (or any-size) set can be completed. The captured target args give us the set definition; a two-ball
    // set returns 2, a seven-ball set returns 7 (identical to before).
    @ModifyConstant(
        method = "areAllDragonBallsNearby(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lcom/dragonminez/common/dragonball/DragonBallSetDefinition;)Z",
        constant = @Constant(intValue = 7),
        require = 0
    )
    private int su$anySetSize(int hardCodedSeven, Level level, BlockPos pos, DragonBallSetDefinition setDefinition)
    {
        // getStars() is the set's declared star keys (each within 1..7); its size is the true number of balls in the
        // set. On any unexpected null, fall back to DMZ's original 7 so seven-ball sets keep working.
        if (setDefinition == null)
        {
            return hardCodedSeven;
        }
        return setDefinition.getStars().size();
    }
}

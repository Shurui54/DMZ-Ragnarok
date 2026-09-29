package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.compat.dmz.SuperDragonBallPicker;

/**
 * Lets a Super dragon ball be clicked from any angle, not only from directly underneath it.
 *
 * <p>{@code GameRenderer.pick} is where the client decides what the crosshair is on: it runs the block ray, then the
 * entity ray, and leaves the answer in {@code Minecraft.hitResult}. Every later path reads that one field, so
 * correcting it here is enough for the outline, the arm swing, block breaking AND the use packet sent to the server.
 *
 * <p>The block ray it runs is {@code Level.clip}, which tests a block's shape only while the ray is inside that
 * block's own cell. Our Super balls are spheres about 2.9 blocks across grown from the cell at their base, so a ray
 * aimed at one from the side never enters the owning cell and comes back a miss, which is why the ball has only ever
 * answered a right-click from below. After vanilla has picked we retry the same ray against the ball's ellipsoid
 * directly (see {@link SuperDragonBallPicker}, which also explains why the result the server receives is an ordinary
 * block interaction needing no server-side counterpart).
 *
 * <p>We only ever replace a hit the ball is genuinely IN FRONT OF: the picker is given the distance to whatever
 * vanilla found, so a wall or an entity between the player and the ball still wins and the ball cannot be clicked
 * through terrain. When the ball does win over an entity we clear {@code crosshairPickEntity} as well, since that
 * field is vanilla's record of the entity the crosshair settled on.
 *
 * <p>Skipped for spectators and whenever the camera is not the player, because both the reach test and the packet the
 * server will validate are about the player.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererSuperBallPick
{
    @Inject(method = "pick", at = @At("TAIL"))
    private void su$pickSuperBall(float partialTick, CallbackInfo ci)
    {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || player.isSpectator() || minecraft.getCameraEntity() != player)
        {
            return;
        }

        // both taken at partialTick, exactly as Entity.pick built the block ray a few lines earlier, so this is the
        // SAME ray and the distance comparison below is meaningful rather than an interpolation artefact.
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);

        HitResult current = minecraft.hitResult;
        double currentDistance = current != null && current.getType() != HitResult.Type.MISS
                ? eye.distanceTo(current.getLocation())
                : Double.MAX_VALUE;

        BlockHitResult ball = SuperDragonBallPicker.pick(level, eye, look, player.getBlockReach(), currentDistance);
        if (ball != null)
        {
            minecraft.hitResult = ball;
            minecraft.crosshairPickEntity = null;
            return;
        }

        // Vanilla found the ball on its own, which is what happened from underneath even before this class existed.
        // Its location is the point where the ray met the SHAPE, and our sphere reaches well out of its own cell, so
        // that point can still be outside the box the server measures and the click is dropped with "Rejecting
        // UseItemOnPacket ... too far away from hit block". Clamp those hits too, or the oldest way of using a Super
        // ball stays half broken.
        // the type check is not redundant: a MISS is also carried in a BlockHitResult, and it names a block position.
        if (current != null && current.getType() == HitResult.Type.BLOCK && current instanceof BlockHitResult hit
                && SuperDragonBallPicker.isSuperBall(level, hit.getBlockPos()))
        {
            minecraft.hitResult = SuperDragonBallPicker.clampToCell(hit);
        }
    }
}

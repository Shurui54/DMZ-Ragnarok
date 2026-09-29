package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import com.dragonminez.client.init.entities.model.ki.SPSkillsModel;
import com.dragonminez.common.init.entities.ki.SPBlueHurricaneEntity;

/**
 * Gives Mighty Hurricane Fury a paler, water-coloured funnel while leaving Burter's own hurricane exactly as it is.
 *
 * <h2>Why a mixin and not a texture override</h2>
 * {@code SPSkillsModel} resolves its texture from the ENTITY TYPE's registry name, so both hurricanes read the same
 * {@code dragonminez:textures/entity/skills/sp_blue_hurricane.png}. Shipping a replacement at that path would
 * recolour the saga's Burter too, and would depend on which mod's resource pack happens to win. Swapping the returned
 * location for one hurricane instead leaves the other untouched and needs no pack ordering to be right.
 *
 * <h2>Telling them apart</h2>
 * By OWNER. DMZ spawns this entity from exactly one place, {@code SkillManager}'s skill 12, whose user is always a
 * {@code DBSagasEntity} - so a hurricane owned by a PLAYER can only be a shadow dragon's. That is a real distinction
 * rather than a marker we have to remember to stamp, and it survives a relog: the owner is synced.
 *
 * <p>{@code require = 0}: a DMZ reshape of the model class degrades to Burter's blue funnel rather than crashing.
 */
@Mixin(value = SPSkillsModel.class, remap = false)
public abstract class MixinDmzHurricaneTexture
{
    /** Ours, at {@code assets/dmz_ragnarok/textures/entity/skills/hurricane_water.png}. */
    private static final ResourceLocation SU$WATER_HURRICANE =
            new ResourceLocation("dmz_ragnarok", "textures/entity/skills/hurricane_water.png");

    /**
     * The same funnel at a quarter alpha, shown ONLY to the dragon standing inside it.
     *
     * <p>The storm is centred on its caster, so from their own eyes it is a wall across the whole screen: they were
     * fighting blind inside their own move. Everyone else keeps the solid one, because for them it is a thing to see
     * coming rather than something to see through. This works because the model draws with
     * {@code entityTranslucentEmissive}, which honours per-texel alpha.
     */
    private static final ResourceLocation SU$WATER_HURRICANE_SELF =
            new ResourceLocation("dmz_ragnarok", "textures/entity/skills/hurricane_water_self.png");

    /**
     * The descriptor is spelled out because {@code SPSkillsModel} is generic: javac emits BOTH the real
     * {@code getTextureResource(SPBlueHurricaneEntity)} and a synthetic bridge taking {@code GeoAnimatable}, and a
     * bare method name would try to inject into both and fail on the one whose signature does not match.
     */
    @Inject(method = "getTextureResource(Lcom/dragonminez/common/init/entities/ki/SPBlueHurricaneEntity;)"
            + "Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$waterHurricane(SPBlueHurricaneEntity animatable, CallbackInfoReturnable<ResourceLocation> cir)
    {
        try
        {
            Entity owner = animatable.getOwner();
            if (!(owner instanceof Player))
                return;
            // The person being asked is the one at the keyboard, so "am I the caster" is a straight identity check.
            cir.setReturnValue(owner == net.minecraft.client.Minecraft.getInstance().player
                    ? SU$WATER_HURRICANE_SELF : SU$WATER_HURRICANE);
        }
        catch (Throwable ignored)
        {
        }
    }
}

package net.shurui.shuruisutilities.client.clone;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.dragonminez.client.render.DMZPlayerRenderer;
import com.dragonminez.client.render.DMZRendererCache;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;

import net.shurui.shuruisutilities.clone.MiniClone;
import net.shurui.shuruisutilities.clone.MiniCloneEntity;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Draws a PLAYER_COPY mini clone as the caster's real DragonMineZ character, scaled down.
 *
 * <p>DMZ replaces every player with its GeckoLib {@code DMZPlayerRenderer}, and that is the only renderer that knows
 * race bodies, hair, face, race parts and forms. A clone is a TamableAnimal, so it gets a client-only puppet player per
 * clone, loaded with the caster's character snapshot the server put on the clone, and drawn through DMZ's renderer. This
 * is the same pattern DMZ 2.2 uses for its own player-shaped NPCs ({@code DemonVillagerRenderer.Puppet}), including the
 * {@link DMZRendererCache.NpcPuppet} marker that picks the NPC animation set and hides the name tag.
 *
 * <p>Any failure returns false, and the caller falls back to the old vanilla-model path, so a DMZ change costs looks,
 * never a render-thread crash.
 */
@OnlyIn(Dist.CLIENT)
public final class MiniCloneDmzLook
{
    private MiniCloneDmzLook() {}

    private static final Map<Integer, Puppet> PUPPETS = new HashMap<>();
    private static final int SWEEP_INTERVAL = 200;
    private static ClientLevel puppetLevel;
    private static int renderCounter;
    private static boolean failureLogged;

    /** @return true when the clone was drawn with DMZ's renderer, false to fall back to the vanilla copy. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean render(MiniCloneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                                 MultiBufferSource buffer, int packedLight)
    {
        try
        {
            CompoundTag look = entity.getLookSnapshot();
            if (look == null || !look.contains(MiniClone.LOOK_CHARACTER, Tag.TAG_COMPOUND))
            {
                return false;
            }
            Puppet puppet = puppetFor(entity);
            if (puppet == null || puppet.broken)
            {
                return false;
            }
            if (!puppet.sync(entity, look))
            {
                return false;
            }
            DMZPlayerRenderer renderer = DMZRendererCache.getTPRenderer(puppet);
            if (renderer == null)
            {
                return false;
            }
            // A private stack: a throw half way through the draw must not leave the caller's stack unbalanced.
            PoseStack local = new PoseStack();
            local.last().pose().set(poseStack.last().pose());
            local.last().normal().set(poseStack.last().normal());
            float s = entity.getCloneScale();
            local.scale(s, s, s);
            try
            {
                renderer.render(puppet, entityYaw, partialTick, local, buffer, packedLight);
            }
            catch (RuntimeException e)
            {
                puppet.broken = true;
                logOnce(e);
                return false;
            }
            return true;
        }
        catch (Throwable t)
        {
            logOnce(t);
            return false;
        }
    }

    private static void logOnce(Throwable t)
    {
        if (!failureLogged)
        {
            failureLogged = true;
            LoggingHandler.sulog.warn("[miniclone] could not draw a clone with the DMZ player renderer: {}", t.toString());
        }
    }

    private static Puppet puppetFor(MiniCloneEntity entity)
    {
        if (!(entity.level() instanceof ClientLevel level))
        {
            return null;
        }
        if (level != puppetLevel)
        {
            clearPuppets();
            puppetLevel = level;
        }
        sweep(level);

        Puppet puppet = PUPPETS.get(entity.getId());
        if (puppet == null || puppet.clone != entity)
        {
            if (puppet != null)
            {
                DMZRendererCache.evict(puppet.getUUID());
            }
            puppet = new Puppet(level, entity);
            PUPPETS.put(entity.getId(), puppet);
        }
        return puppet;
    }

    private static void sweep(ClientLevel level)
    {
        if (++renderCounter < SWEEP_INTERVAL)
        {
            return;
        }
        renderCounter = 0;
        Iterator<Map.Entry<Integer, Puppet>> it = PUPPETS.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<Integer, Puppet> entry = it.next();
            Entity live = level.getEntity(entry.getKey());
            if (live == null || live.isRemoved() || live != entry.getValue().clone)
            {
                DMZRendererCache.evict(entry.getValue().getUUID());
                it.remove();
            }
        }
    }

    private static void clearPuppets()
    {
        for (Puppet puppet : PUPPETS.values())
        {
            DMZRendererCache.evict(puppet.getUUID());
        }
        PUPPETS.clear();
    }

    /** A never-added stand-in player that only exists to be handed to DMZ's renderer. */
    static final class Puppet extends RemotePlayer implements GeoAnimatable, DMZRendererCache.NpcPuppet
    {
        private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("base.idle");
        private static final RawAnimation WALK = RawAnimation.begin().thenLoop("base.walk");

        private final MiniCloneEntity clone;
        private final AnimatableInstanceCache animationCache = new SingletonAnimatableInstanceCache(this);
        private CompoundTag appliedLook;
        private PlayerInfo ownerInfo;
        private boolean broken;
        private int syncedTick = Integer.MIN_VALUE;

        private Puppet(ClientLevel level, MiniCloneEntity clone)
        {
            // Its own uuid, never the caster's: DMZ caches one renderer per uuid, and sharing the caster's would make
            // the two fight over it whenever their forms differ.
            super(level, new GameProfile(puppetId(clone.getUUID()), nameOf(clone)));
            this.clone = clone;
        }

        private static String nameOf(MiniCloneEntity clone)
        {
            String name = clone.getOwnerName();
            return name == null || name.isEmpty() ? "clone" : name;
        }

        private static UUID puppetId(UUID clone)
        {
            return new UUID(clone.getMostSignificantBits() ^ 0x5C10E5C10E5C10E5L, clone.getLeastSignificantBits());
        }

        @Override
        public ItemStack getScouter()
        {
            return ItemStack.EMPTY;
        }

        // The caster's tab-list entry, so a body type 0 character (which DMZ paints with the vanilla skin) and the
        // slim/wide arm choice match the caster. Built from the profile when they are not in the tab list.
        @Override
        protected PlayerInfo getPlayerInfo()
        {
            UUID owner = this.clone.getOwnerProfileId();
            if (owner == null)
            {
                return null;
            }
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            PlayerInfo live = connection == null ? null : connection.getPlayerInfo(owner);
            if (live != null)
            {
                return live;
            }
            if (this.ownerInfo == null)
            {
                this.ownerInfo = new PlayerInfo(new GameProfile(owner, nameOf(this.clone)), false);
            }
            return this.ownerInfo;
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
        {
            controllers.add(new AnimationController<>(this, "base_controller", 5, this::walkPredicate));
        }

        private PlayState walkPredicate(AnimationState<Puppet> state)
        {
            return state.setAndContinue(state.isMoving() ? WALK : IDLE);
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache()
        {
            return this.animationCache;
        }

        @Override
        public double getTick(Object object)
        {
            return this.tickCount;
        }

        /** @return false when the puppet has no stats to load the look into. */
        private boolean sync(MiniCloneEntity clone, CompoundTag look)
        {
            StatsData data = StatsProvider.get(StatsCapability.INSTANCE, this).orElse(null);
            if (data == null)
            {
                return false;
            }
            if (!look.equals(this.appliedLook))
            {
                applyLook(data, look);
            }

            this.setPos(clone.getX(), clone.getY(), clone.getZ());
            this.xo = clone.xo;
            this.yo = clone.yo;
            this.zo = clone.zo;
            this.xOld = clone.xOld;
            this.yOld = clone.yOld;
            this.zOld = clone.zOld;
            this.setYRot(clone.getYRot());
            this.yRotO = clone.yRotO;
            this.setXRot(clone.getXRot());
            this.xRotO = clone.xRotO;
            this.yBodyRot = clone.yBodyRot;
            this.yBodyRotO = clone.yBodyRotO;
            this.yHeadRot = clone.yHeadRot;
            this.yHeadRotO = clone.yHeadRotO;
            this.setOnGround(clone.onGround());
            this.setDeltaMovement(clone.getDeltaMovement());
            this.hurtTime = clone.hurtTime;
            this.hurtDuration = clone.hurtDuration;
            this.deathTime = clone.deathTime;
            this.setInvisible(clone.isInvisible());

            if (this.syncedTick != clone.tickCount)
            {
                this.syncedTick = clone.tickCount;
                this.tickCount = clone.tickCount;
                this.walkAnimation.update(clone.walkAnimation.speed(), 1.0F);
            }
            return true;
        }

        private void applyLook(StatsData data, CompoundTag look)
        {
            data.getCharacter().load(look.getCompound(MiniClone.LOOK_CHARACTER));
            data.getStatus().setHasCreatedCharacter(true);
            ListTag armor = look.getList(MiniClone.LOOK_ARMOR, Tag.TAG_COMPOUND);
            for (int i = 0; i < this.getInventory().armor.size(); i++)
            {
                ItemStack stack = i < armor.size() ? ItemStack.of(armor.getCompound(i)) : ItemStack.EMPTY;
                this.getInventory().armor.set(i, stack);
            }
            this.appliedLook = look;
            this.refreshDimensions();
        }
    }
}

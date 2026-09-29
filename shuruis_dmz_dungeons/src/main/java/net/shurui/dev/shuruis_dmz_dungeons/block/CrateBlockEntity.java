package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The animated crate, shared by the chest and the barrel forms.
 *
 * <p>Holds NO loot and no rarity. A crate's rarity and metal are worked out from position, refresh window and viewer
 * on whichever side asks (see {@link CrateTier} and {@link CrateMetal}), so nothing is synced per block.
 *
 * <p>Carries animation state, only while an opening lasts. Idle loops forever; {@link #triggerOpen()} plays the open
 * once and holds its last frame, so an opened crate stays open rather than snapping shut.
 */
public class CrateBlockEntity extends BlockEntity implements GeoBlockEntity {

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation OPEN =
            RawAnimation.begin().then("open", Animation.LoopType.PLAY_ONCE);

    private static final String CONTROLLER = "crate";
    private static final String TRIGGER_OPEN = "open";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /**
     * A crate told exactly which of the twelve it is, or -1 for the usual derived answer. A generated crate derives
     * rarity/metal from position; a crate PLACED from a creative variant is decoration, so its look is stored here and
     * beats the derivation.
     */
    private int variantTier = -1;
    private int variantMetal = -1;

    /** A whole model to wear instead of the derived one, or blank for the usual crate_&lt;rarity&gt;_&lt;metal&gt;. */
    private String variantSkin = "";

    public CrateBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The chosen rarity/metal ordinals, or -1 each when this crate derives them from its position. */
    public int variantTier() {
        return variantTier;
    }

    public int variantMetal() {
        return variantMetal;
    }

    public String variantSkin() {
        return variantSkin;
    }

    public void setVariant(int tier, int metal) {
        setVariant(tier, metal, this.variantSkin);
    }

    public void setVariant(int tier, int metal, String skin) {
        this.variantTier = tier;
        this.variantMetal = metal;
        this.variantSkin = skin == null ? "" : skin;
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        if (variantTier >= 0) {
            tag.putInt("VariantTier", variantTier);
        }
        if (variantMetal >= 0) {
            tag.putInt("VariantMetal", variantMetal);
        }
        if (!variantSkin.isEmpty()) {
            tag.putString("VariantSkin", variantSkin);
        }
    }

    @Override
    public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        variantTier = tag.contains("VariantTier") ? tag.getInt("VariantTier") : -1;
        variantMetal = tag.contains("VariantMetal") ? tag.getInt("VariantMetal") : -1;
        variantSkin = tag.contains("VariantSkin") ? tag.getString("VariantSkin") : "";
    }

    // the variant is a VISUAL, so the client needs it, else a placed variant renders as the derived one.
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag() {
        net.minecraft.nbt.CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>
            getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // idle is the controller's standing animation, always running. open is a TRIGGER on the same controller:
        // GeckoLib plays it over idle then returns to idle, so the open must shut its own lid on the way out.
        controllers.add(new AnimationController<>(this, CONTROLLER, 5, state ->
                state.setAndContinue(IDLE))
                .triggerableAnim(TRIGGER_OPEN, OPEN));
    }

    /**
     * Play the open animation on every client that can see this crate. Server side: GeckoLib sends the trigger itself,
     * no packet needed, and it no-ops on the client.
     */
    public void triggerOpen() {
        if (this.level != null && !this.level.isClientSide) {
            triggerAnim(CONTROLLER, TRIGGER_OPEN);
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}

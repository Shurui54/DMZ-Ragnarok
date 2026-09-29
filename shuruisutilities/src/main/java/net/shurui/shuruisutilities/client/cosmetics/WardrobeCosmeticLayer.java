package net.shurui.shuruisutilities.client.cosmetics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.compat.SodiumSpriteAnimation;
import net.shurui.shuruisutilities.compat.dmz.KiWeaponActive;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAccessoryStyle;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.EquippedCosmetic;
import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Draws a player's equipped HEAD, BACK and ACCESSORY cosmetics on their body, in third person, on themselves and
 * on everyone else.
 *
 * <h2>Why a plain vanilla layer draws on a DragonMineZ player at all</h2>
 * DragonMineZ replaces the player renderer ({@code DMZPlayerRenderer}, a GeckoLib {@code GeoEntityRenderer}), so a
 * {@link RenderLayer} added to the vanilla {@code PlayerRenderer} would normally never run. DMZ ships the way back
 * in: {@code com.dragonminez.client.render.layer.DMZThirdPartyLayerForwarder}, a geo layer on its own renderer
 * that, at the {@code root} bone, looks the vanilla {@code PlayerRenderer} up out of the skin map, poses a vanilla
 * {@code PlayerModel} from the geo bones ({@code VanillaModelSync}), flips and offsets the pose the way
 * {@code LivingEntityRenderer} does, and then calls {@code render} on every layer the vanilla renderer carries
 * that is not one of its own eleven vanilla layers. Adding this layer to the vanilla {@code PlayerRenderer}
 * (see {@code CosmeticRenderClientBusEvents}) is therefore all it takes to be drawn on the geo player. This is a
 * supported DMZ extension point, read from the 2.1.3 bytecode, not a mixin and not reflection.
 *
 * <h2>Two hard constraints the forwarder imposes, both honoured here</h2>
 * <ul>
 *   <li><b>The class name must not contain {@code cosmeticarmor} or {@code cosarmor}.</b> The forwarder lowercases
 *       each layer's class name and SKIPS any that contains either substring (that is how it keeps Cosmetic Armor
 *       Reworked out). {@code WardrobeCosmeticLayer} is clear of both; a class called
 *       {@code CosArmorCosmeticLayer} would silently never draw.</li>
 *   <li><b>Nothing draws during Oozaru.</b> The forwarder returns early when the character is Oozaru cached, so a
 *       giant ape wears no cosmetics. That is a documented gap, not a bug here: the alternative is a mixin into
 *       DMZ we do not need, and a hat on a 20-block ape was never going to sit right anyway.</li>
 * </ul>
 *
 * <h2>How a cosmetic is placed: one recipe per slot</h2>
 * Every wearable is drawn as its baked ITEM model through {@link ItemRenderer#render}, but the display context,
 * the attach bone and the transform depend on the slot, because the source packs (three different Halloween packs
 * built for ItemsAdder/Oraxen/MagicCosmetics) authored each kind for a different anchor:
 * <ul>
 *   <li><b>HEAD</b> on the head bone, in the {@link ItemDisplayContext#HEAD} context, with vanilla
 *       {@code CustomHeadLayer}'s exact recipe (translate {@code (0,-0.25,0)}, yaw 180, scale
 *       {@code (0.625,-0.625,-0.625)}). The hats are player-head cosmetics whose own {@code display.head} carries
 *       the placement, so this is trusted; the per-def {@link CosmeticDef#scale} corrects the ones authored two to
 *       three times head size.</li>
 *   <li><b>BACK</b> on the BODY bone, anchored by the model's own measured CENTRE on the upper back at the artist's
 *       authored SIZE only. The packs authored each back piece's {@code display.head} for a plugin that hung the item
 *       on an armour stand's HEAD behind the player, so that transform's large downward translate (and, in the three
 *       Premium 2 worn variants, a {@code [4,4,4]} scale) is a mount offset, not on-body intent: honouring it dropped
 *       the wings and bag to the feet and blew the cauldron and backpack up in front. drawBack keeps ONLY the authored
 *       SIZE (the base model's {@code display.head} scale) and derives the position itself, so every piece lands
 *       upright on the back at a believable size. See {@link #drawBack}.</li>
 *   <li><b>ACCESSORY</b> has three sub-styles ({@link CosmeticAccessoryStyle}, a per-def field): HAND is a SKIN over
 *       the DragonMineZ ki weapon. It draws ONLY while a ki weapon is drawn, off the same arm bone DMZ anchors its
 *       weapon to (so the swing and charge match frame for frame), holding as a ki construct out of the fist rather
 *       than tilted like a sword, and DMZ's own weapon model is suppressed for that player so only the skin shows
 *       (see {@link #willDrawHandAccessory} and {@code MixinDmzWeaponsLayer}); CARRIED draws the same held prop at
 *       all times (the prior always-in-hand behaviour, and the default), gripping like a sword when its model has no
 *       real third-person hand pose of its own; FLOAT draws a balloon above the player on a fishing-line string that
 *       follows and sways client side (see {@link #drawBalloon}). See {@link #drawAccessory}.</li>
 * </ul>
 * Rendering as a baked item model also means the block/item atlas animates the tall {@code .mcmeta} strips with no
 * code, which is why a bespoke {@code RenderType} bound to the raw PNG (a 20-frame smear) is deliberately not used.
 * See {@code CosmeticStyle} for the same choice on the GUI side.
 *
 * <p>The definition's {@link CosmeticDef#offset}, {@link CosmeticDef#rotation}, {@link CosmeticDef#scale} and
 * {@link CosmeticDef#attachBone} are applied as an ADMIN fine-tune on top of the slot recipe, in bone space, so a
 * specific cosmetic can be nudged from the in game editor without a build. An {@code attachBone} that names a
 * vanilla part also selects that part's recipe, so moving a piece to a new bone moves its whole placement recipe.
 *
 * <h2>Worn variants</h2>
 * A cosmetic whose worn look differs from its inventory look (the three Premium 2 back pieces shipped a
 * {@code _thirdperson} model) is resolved by convention: {@code dmz_ragnarok:item/cosmetic_worn/<id>} if that model
 * baked, else the item's own model. Those worn models are registered for baking in
 * {@code CosmeticRenderClientBusEvents}; nothing else references them, so without that they would be absent.
 *
 * <h2>Fail soft, never on the render thread</h2>
 * A missing definition, a model id that resolves to no item, or any throw draws NOTHING and logs once per id. A
 * wrong hat is worse than no hat, and a throw here is on the render thread, so there is deliberately no visible
 * fallback. Client only.
 */
public class WardrobeCosmeticLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>
{
    /** Worn on the body, so drawn here. PET and MOUNT are follower/rideable entities, not items on the body. */
    private static final CosmeticSlot[] WEARABLE_SLOTS = { CosmeticSlot.HEAD, CosmeticSlot.BACK,
            CosmeticSlot.ACCESSORY, CosmeticSlot.BODY };

    // One log line per offending id for the client's lifetime, never one per frame.
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    // Cached sprite lists per baked model, so marking animated strips active under Embeddium does not walk the
    // quads every frame. Identity keyed: a rebaked model on resource reload is a new object and re-gathers.
    private static final Map<BakedModel, List<TextureAtlasSprite>> SPRITE_CACHE = new IdentityHashMap<>();

    // Cached model bounds (min x,y,z, max x,y,z in baked units, where 1.0 == one block) per baked model, so the
    // BACK recipe can centre and size an item without walking its quads every frame. Identity keyed like the
    // sprite cache: a rebaked model on reload is a new object and re-measures. Null means "no readable quads".
    private static final Map<BakedModel, float[]> BOUNDS_CACHE = new IdentityHashMap<>();

    // ------- BACK placement, in BLOCKS in raw body-bone space (+Y is DOWN, +Z is the player's BACK) -------
    // A back piece is anchored by its measured CENTRE, not by the pack's authored display transform. Those transforms
    // were baked for a plugin that hung the item on an armour stand's HEAD behind the player, so their large downward
    // translate dropped wings and the bag to the feet and their facing sat the piece on the chest. drawBack cancels
    // that translate (it centres the model on its own measured bounds) and re-anchors it here, on the upper back:
    // a little below the neck (mid torso) and out behind the back so it clears the spine.
    //
    // The Z sign is +Z here, the player's BACK. This matches raw vanilla body-bone space: the vanilla torso cube runs
    // z = -2..+2 px (front face at -Z, back face at +Z), and ElytraLayer reaches the back with a +Z 0.125 translate.
    // DragonMineZ's DMZThirdPartyLayerForwarder poses this vanilla PlayerModel with the same scale(-1,-1,1) Living
    // EntityRenderer uses (Z left unflipped) and adds no yaw, so the body frame our layer draws in is vanilla's, read
    // from the 2.1.3 bytecode. An earlier build anchored at -Z, which put every back piece on the CHEST.
    private static final float BACK_ANCHOR_Y = 0.35F;

    private static final float BACK_ANCHOR_Z = 0.25F;

    // The artist's authored size is honoured through the base model's display.head scale, times the same 0.625 the
    // vanilla head recipe uses, so a back piece lands at the size the pack intended rather than a refit-to-torso guess
    // (which read too small). Clamped so a stray or armour-stand-inflated scale cannot blow a piece up.
    private static final float BACK_SIZE_BASE = 0.625F;

    private static final float BACK_AUTHORED_SCALE_DEFAULT = 1.6F;

    private static final float BACK_AUTHORED_SCALE_MIN = 0.5F;

    private static final float BACK_AUTHORED_SCALE_MAX = 2.5F;

    // The ACCESSORY recipe reproduces vanilla's held-item placement (ItemInHandLayer.renderArmWithItem): after the
    // right arm bone is posed (drawSlot already ran translateAndRotate, which is translateToHand for that arm), the
    // item is tipped into the fist and drawn in its THIRD_PERSON_RIGHT_HAND context, so its own hand transform
    // sizes and orients it and it sits IN the hand instead of buried in the arm. Right-hand translate, in blocks.
    // Used by the CARRIED sub-style; a HAND skin instead rides DMZ's own weapon pose (see renderHandSkin).
    private static final float HAND_TIP_X = 1.0F / 16.0F;

    private static final float HAND_TIP_Y = 0.125F;

    private static final float HAND_TIP_Z = -0.625F;

    // DMZ's standard ki blade box, read from assets/dragonminez/geo/weapons/kiweapon_blade.geo.json in the 2.1.3
    // jar: the single top-level bone kiweapon_blade (pivot [-5,22,0], no rotation, no animated position, and a name
    // no player-model bone shares so KiWeaponRenderer.syncTargetBoneAndParents leaves it untouched) draws its cubes
    // at their AUTHORED coordinates directly in the right_arm bone's frame. So the ki weapon occupies this box, in
    // MODEL PIXELS (16 == one block), in the exact frame our HAND skin is anchored in. Centre and long-axis extent
    // (the box is the axis-aligned span of the authored cube origins and sizes):
    //   X [-9.35, -3.15] -> centre -6.25   Y [-1.0, 17.0] -> centre 8.0, extent 18   Z [-3.5, 3.5] -> centre 0.0
    // A HAND cosmetic is anchored on this box so it lands exactly where DMZ's own weapon sits and swings with it.
    private static final float KI_BLADE_CX = -6.25F;

    private static final float KI_BLADE_CY = 8.0F;

    private static final float KI_BLADE_CZ = 0.0F;

    // The ki blade's long axis (the Y extent, 17.0 - -1.0). A HAND prop's longest axis is fitted to this, so a model
    // authored at any size lands the same length as the ki weapon it skins rather than at its own raw item size.
    private static final float KI_BLADE_LONG_PX = 18.0F;

    // Whether a player has a ki weapon drawn, cached per player per client tick. Value packs (gameTime << 1 | bit):
    // the DMZ query then runs at most once a tick per player however often the layer draws in a frame. Client render
    // thread only, but a concurrent map costs nothing and guards against a stray off-thread read.
    private static final Map<java.util.UUID, Long> KI_ACTIVE_CACHE = new ConcurrentHashMap<>();

    // ------- FLOAT balloon placement, in BLOCKS relative to the body bone (+Y is DOWN in that space) -------
    // The hand the string ties to, roughly the right fist when the arm hangs: to the player's right (-X), below the
    // shoulder line (+Y), a touch forward. Approximate on purpose; the string is a rope, not a rig constraint.
    private static final float BALLOON_HAND_X = -0.34F;

    private static final float BALLOON_HAND_Y = 0.62F;

    private static final float BALLOON_HAND_Z = 0.05F;

    // Where the balloon floats when the player is still: up past the head (-Y is up) and out to the same side as the
    // hand, slightly back so it never sits in front of the face. Well above the head top (about -0.75) and off to the
    // side, so a standing player never clips it and it never reaches the ground.
    private static final float BALLOON_X = -0.55F;

    private static final float BALLOON_Y = -1.75F;

    private static final float BALLOON_Z = -0.10F;

    // The largest-dimension size a balloon model is fitted to, in blocks. Raised from 0.85 to 1.3 on the owner's
    // note that the balloons read too small: a party balloon on a string wants to be clearly bigger than a head.
    // Still well clear of the player, since it floats up past the head (BALLOON_Y) and off to the side.
    private static final float BALLOON_TARGET_BLOCKS = 1.3F;

    // Gentle vertical bob: amplitude in blocks and angular speed per tick. Small, so it reads as a float, not a
    // bounce.
    private static final float BALLOON_BOB_AMP = 0.055F;

    private static final float BALLOON_BOB_SPEED = 0.10F;

    // A slight sag in the string's middle, in blocks, so the rope has a belly like the fishing line rather than a
    // dead-straight segment. Zero at both ends of the run.
    private static final float BALLOON_STRING_SAG = 0.12F;

    // The damped-follow sway state per player, so the balloon lags behind and swings as the player moves. Bounded and
    // cleared if it ever grows large, like the ki cache above.
    private static final Map<java.util.UUID, BalloonSway> BALLOON_SWAY = new ConcurrentHashMap<>();

    /**
     * Client-only preview hook. While set, it supplies what the LOCAL player "wears" in a slot for a GUI preview
     * (the shop's live portrait) WITHOUT touching their real equipment. It is consulted only for the local player,
     * and only where it returns a non-null record: a slot it does not override falls through to the real equipped
     * set, so the preview adds the item being shopped over the player's own outfit. Set it around a preview draw
     * and clear it afterwards; it is read and written on the render thread only. See {@code CosmeticShopScreen}.
     */
    public static volatile java.util.function.Function<CosmeticSlot, EquippedCosmetic> previewOverride;

    public WardrobeCosmeticLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent)
    {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, AbstractClientPlayer player,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
            float headPitch)
    {
        if (player == null || player.isInvisible())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null)
            return;

        boolean self = player == mc.player;
        // Hide your own cosmetics only in a genuine first person WORLD view. A screen being open (the wardrobe or
        // inventory portrait draws the real player in third person) or an F5 third person view must still show
        // them. DMZ already routes true first person through a different renderer, so this is belt and braces.
        if (self && mc.screen == null && mc.options.getCameraType().isFirstPerson())
            return;
        if (!self && !CosmeticRenderOptions.showOthers())
            return;

        PlayerModel<AbstractClientPlayer> model = getParentModel();
        // A GUI preview may override what the LOCAL player wears in a slot, so a shop tile can be shown on the
        // player without changing their real outfit. A null from the override for a slot falls through to the real
        // equipped set, so the previewed item is added over the player's own cosmetics rather than replacing them.
        java.util.function.Function<CosmeticSlot, EquippedCosmetic> ov = self ? previewOverride : null;
        for (CosmeticSlot slot : WEARABLE_SLOTS)
        {
            if (!slot.usable())
                continue;
            EquippedCosmetic override = ov == null ? null : ov.apply(slot);
            EquippedCosmetic eq = override != null ? override : CosmeticClientStore.equipped(player.getUUID(), slot);
            if (eq == null || !eq.valid())
                continue;
            CosmeticDef def = CosmeticClientStore.def(eq.catalogId);
            if (def == null)
                continue;
            try
            {
                drawSlot(mc, pose, buffer, light, player, model, def, slot, ageInTicks, partialTick);
            }
            catch (Throwable t)
            {
                warnOnce(def.id, t);
            }
        }
    }

    private void drawSlot(Minecraft mc, PoseStack pose, MultiBufferSource buffer, int light,
            AbstractClientPlayer player, PlayerModel<AbstractClientPlayer> model, CosmeticDef def, CosmeticSlot slot,
            float ageInTicks, float partialTick)
    {
        ItemStack stack = stackFor(def.modelId);
        if (stack.isEmpty())
            return;

        // The attach part an admin actually chose, so a per-def bone override selects the recipe too: an admin who
        // moves a back piece onto the head gets the head recipe, which is what makes the override meaningful.
        CosmeticSlot recipe = recipeSlot(def, slot);

        // A BACK piece draws the item's OWN (base) model, never the worn variant: the worn variants exist only to
        // carry the armour-stand-head display transform (a 4x scale and a large drop) that drawBack now derives its
        // own placement instead of honouring, so the base model's authored size is the one to read. Every other
        // recipe keeps the worn lookup.
        BakedModel baked = recipe == CosmeticSlot.BACK
                ? mc.getItemRenderer().getModel(stack, player.level(), player, player.getId())
                : resolveBaked(mc, def, player, stack);
        // The missing model means the art has not arrived yet (it is streamed by the Ragnarok Key, see
        // CosmeticAssetCache): draw nothing rather than a magenta cube until the pack reloads.
        if (baked == null || baked == missing(mc))
            return;

        markSpritesActive(baked);

        // Accessory sub-styles decide the whole render path. HAND is a skin over the ki weapon and only draws while
        // one is out; FLOAT is a balloon on a string above the player; CARRIED (the default) is the prior
        // always-in-hand behaviour. Only the accessory recipe branches; every other recipe ignores the style.
        if (recipe == CosmeticSlot.ACCESSORY)
        {
            CosmeticAccessoryStyle style = def.accessoryStyle == null ? CosmeticAccessoryStyle.CARRIED
                    : def.accessoryStyle;
            if (style == CosmeticAccessoryStyle.FLOAT)
            {
                drawBalloon(mc, pose, buffer, light, player, model, def, baked, stack, ageInTicks);
                return;
            }
            if (style == CosmeticAccessoryStyle.HAND)
            {
                // A HAND skin is NOT drawn in this forwarder pass. It is drawn by MixinDmzWeaponsLayer at the ki
                // weapon's own arm bone, in DMZ's exact captured pose (see renderHandSkin), so it sits in the fist
                // and rides every swing, charge and idle sway frame for frame instead of on the vanilla arm bone
                // this pass poses. Drawing it here as well would double it and place the copy off the hand.
                return;
            }
        }

        ModelPart part = boneFor(model, def, slot);
        if (part == null)
            return;

        pose.pushPose();
        part.translateAndRotate(pose);
        switch (recipe)
        {
            case BACK -> drawBack(mc, pose, buffer, light, def, baked, stack);
            case ACCESSORY -> drawAccessory(mc, pose, buffer, light, def, baked, stack);
            default -> drawHead(mc, pose, buffer, light, def, baked, stack);
        }
        pose.popPose();
    }

    /**
     * Draw a player's HAND-style accessory cosmetic AS the ki weapon, called from {@code MixinDmzWeaponsLayer} at the
     * HEAD of {@code DMZWeaponsLayer.renderForBone} on the main-hand arm bone, with that layer's own {@link PoseStack}.
     *
     * <h2>Why here, and why frame-exact</h2>
     * DMZ captures its weapon's pose as {@code new Matrix4f(poseStack.last().pose())} at exactly this point (read from
     * the 2.1.3 bytecode: {@code renderForBone} passes its own poseStack straight into
     * {@code PlayerEffectQueue.addWeapon}), and its standard-weapon draw applies no held-item tilt, no clawlance or
     * Oozaru special case, so the weapon renders in this very frame. The GeckoLib layer callback runs AFTER
     * {@code RenderUtils.prepMatrixForBone}, so this poseStack already carries the arm bone's full animated transform
     * (swing, charge, idle sway). Drawing here therefore inherits the identical position, rotation, scale and live
     * animation with no capture-and-replay and no one-frame lag: the alternative, letting the cosmetic layer read a
     * matrix the weapon layer stored, would draw a frame stale because DMZ's own layer forwarder (which drives the
     * cosmetic layer) runs on the root bone BEFORE this arm-bone layer captures. Same poseStack, same frame, exact.
     *
     * <h2>Mapping the item model into weapon space</h2>
     * The weapon geo authors its cubes in this arm-bone frame's coordinates (see the {@code KI_BLADE_*} box). Our
     * cosmetic is a baked ITEM model with its own origin, so it is anchored on that box centre, its own measured
     * bounds are centred there, and its longest axis is fitted to the blade's long axis, so a model authored at any
     * size or origin lands the same length in the fist. The only PER-ITEM value is orientation, applied through the
     * definition's {@link CosmeticDef#rotation}/{@link CosmeticDef#offset}/{@link CosmeticDef#scale} (a seeded default
     * plus the live editor), because a sword, a wand and a broom point different ways out of the same fist. See
     * {@code CosmeticHalloweenDefaults}.
     *
     * <p>Static and client only. Fails soft: a missing def, no art or unreadable bounds draws nothing.
     */
    public static void renderHandSkin(PoseStack pose, AbstractClientPlayer player, MultiBufferSource buffer,
            int packedLight, float partialTick)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || player == null)
            return;
        CosmeticDef def = CosmeticClientStore.worn(player.getUUID(), CosmeticSlot.ACCESSORY);
        if (def == null)
            return;
        CosmeticAccessoryStyle style = def.accessoryStyle == null ? CosmeticAccessoryStyle.CARRIED
                : def.accessoryStyle;
        if (style != CosmeticAccessoryStyle.HAND)
            return;
        ItemStack stack = stackFor(def.modelId);
        if (stack.isEmpty())
            return;
        BakedModel baked = resolveBaked(mc, def, player, stack);
        if (baked == null || baked == missing(mc))
            return;   // art not streamed yet: draw nothing, never the missing-model cube
        float[] bounds = boundsOf(baked);
        if (bounds == null)
            return;
        markSpritesActive(baked);

        // This draws into DMZ's OWN live poseStack (the arm-bone geo frame), and MixinDmzWeaponsLayer swallows any
        // throwable, so the push MUST be balanced whatever happens: an unbalanced pop would corrupt every bone DMZ
        // renders after this one in the same frame. try/finally guarantees the pop.
        pose.pushPose();
        try
        {
            // Anchor on DMZ's own ki-weapon box, authored in the same model coordinates this arm-bone frame carries,
            // so the cosmetic sits where the weapon sits.
            pose.translate(KI_BLADE_CX / 16.0F, KI_BLADE_CY / 16.0F, KI_BLADE_CZ / 16.0F);
            // The per-item orientation into weapon space (data: seeded default plus the live editor). Applied about
            // the anchor and before the fit below, so it reads as turning the whole prop in the fist.
            applyDefTransform(pose, def);
            // Fit the prop's longest axis to the blade's long axis, then centre its own bounds on the anchor. The
            // centre translate runs in the rotated, scaled frame, so the model's centre lands on the anchor for any
            // orientation. The 0.5 offset cancels ItemRenderer.render's own translate(-0.5,-0.5,-0.5), which it
            // applies to a baked item model whose quads live in [0,1] model space; without it the prop lands half a
            // model unit off the anchor (see drawBalloon, which centres the same way).
            float s = fitScale(bounds, KI_BLADE_LONG_PX / 16.0F);
            pose.scale(s, s, s);
            pose.translate(0.5F - centre(bounds, 0), 0.5F - centre(bounds, 1), 0.5F - centre(bounds, 2));
            mc.getItemRenderer().render(stack, ItemDisplayContext.NONE, false, pose, buffer, packedLight,
                    OverlayTexture.NO_OVERLAY, baked);
        }
        finally
        {
            pose.popPose();
        }
    }

    /**
     * Whether the player has a DragonMineZ ki weapon drawn, cached per player per client tick so the DMZ query runs
     * at most once a tick however many times the layer is asked to draw in a frame. The cheap path is the common one:
     * a tick stamp comparison and a single boolean bit, both packed into one long.
     */
    private static boolean kiWeaponActive(AbstractClientPlayer player)
    {
        long now = player.level().getGameTime();
        java.util.UUID id = player.getUUID();
        Long packed = KI_ACTIVE_CACHE.get(id);
        if (packed != null && (packed >> 1) == now)
            return (packed & 1L) != 0L;
        boolean active = KiWeaponActive.isActive(player);
        // A light cap so the client cache cannot grow without bound over a long session with many players seen.
        if (KI_ACTIVE_CACHE.size() > 512)
            KI_ACTIVE_CACHE.clear();
        KI_ACTIVE_CACHE.put(id, (now << 1) | (active ? 1L : 0L));
        return active;
    }

    /**
     * Whether this player's HAND-style accessory cosmetic is actually being DRAWN this frame, so DragonMineZ's own ki
     * weapon model can be suppressed for exactly that player (see {@code MixinDmzWeaponsLayer}). This is the single
     * authority both the layer and the suppression consult, so DMZ's weapon is hidden ONLY when our skin genuinely
     * replaces it and the player is never left empty handed: it repeats {@link #render}'s exact visibility gate (not
     * invisible; the local player's own cosmetics only in a screen or third person; another player's only when
     * {@link CosmeticRenderOptions#showOthers} is on), then requires the accessory slot to hold a HAND-style def that
     * resolves to real item art, and a ki weapon to actually be drawn (the existing "hand accessories only show with
     * a ki weapon" rule). Any failure answers false, which leaves DMZ its weapon rather than hiding it under nothing.
     * Static and client only, called from the DMZ weapon-layer mixin.
     */
    public static boolean willDrawHandAccessory(AbstractClientPlayer player)
    {
        try
        {
            if (player == null || player.isInvisible())
                return false;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null)
                return false;
            boolean self = player == mc.player;
            if (self && mc.screen == null && mc.options.getCameraType().isFirstPerson())
                return false;
            if (!self && !CosmeticRenderOptions.showOthers())
                return false;
            CosmeticDef def = CosmeticClientStore.worn(player.getUUID(), CosmeticSlot.ACCESSORY);
            if (def == null)
                return false;
            CosmeticAccessoryStyle style = def.accessoryStyle == null ? CosmeticAccessoryStyle.CARRIED
                    : def.accessoryStyle;
            if (style != CosmeticAccessoryStyle.HAND)
                return false;
            if (!kiWeaponActive(player))
                return false;
            return !stackFor(def.modelId).isEmpty();
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * HEAD recipe: vanilla {@code CustomHeadLayer}'s exact head-item recipe, in the {@link ItemDisplayContext#HEAD}
     * display context, so the model's own {@code display.head} transform (authored as a player-head cosmetic)
     * places it. The per-def {@link CosmeticDef#scale} corrects the packs that authored a hat two to three times
     * head size; it is applied outermost so shrinking a hat also draws its authored offset in proportionally.
     */
    private void drawHead(Minecraft mc, PoseStack pose, MultiBufferSource buffer, int light, CosmeticDef def,
            BakedModel baked, ItemStack stack)
    {
        applyDefTransform(pose, def);
        pose.translate(0.0F, -0.25F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.scale(0.625F, -0.625F, -0.625F);
        mc.getItemRenderer().render(stack, ItemDisplayContext.HEAD, false, pose, buffer, light,
                OverlayTexture.NO_OVERLAY, baked);
    }

    /**
     * BACK recipe: on the BODY bone, anchored by the model's own measured CENTRE on the upper back, at the artist's
     * authored SIZE, upright, facing outward.
     *
     * <h2>Why the authored transform is NOT honoured as a placement</h2>
     * The three source packs authored each back piece's {@code display.head} for a plugin that hung the item on an
     * armour stand's HEAD positioned behind the player, not on the player's own body bone. That transform therefore
     * carries a large downward translate (about two to four blocks) and, in the three Premium 2 worn variants, a
     * {@code [4,4,4]} scale, both of which are the armour-stand-to-body offset, not the artist's on-body intent.
     * Honouring it (an earlier build) dropped the wings and the bag to the feet and blew the cauldron and backpack up
     * in front of the torso. Measuring and refitting every piece to one torso size (the build before that) read too
     * small and floating. This recipe keeps ONLY the artist's SIZE (the base model's authored {@code display.head}
     * scale, see {@link #authoredBackScale}) and derives the position itself: it centres the model on its measured
     * bounds (cancelling the mount translate) and anchors that centre on the upper back
     * ({@link #BACK_ANCHOR_Y}/{@link #BACK_ANCHOR_Z}).
     *
     * <p>The BACK, not the chest, is reached by the anchor's {@link #BACK_ANCHOR_Z}, which is {@code +Z} (the player's
     * back in raw body-bone space, see that field). The {@link Axis#YP} 180 turn is a FACING flip, not a placement: it
     * points the model's decorated side outward, the same flip a baked item model needs (and the head recipe uses)
     * because an item model faces the opposite way to the body it hangs on. Every piece runs the identical recipe now,
     * so a genuine per-item facing quirk (a model whose decorated face is authored the other way) is a seeded
     * {@link CosmeticDef#rotation} in {@code CosmeticHalloweenDefaults}, applied by
     * {@link #applyDefTransform} as an admin-tunable data correction, never a special case here. The per-def
     * offset/rotation/scale fine-tune the anchor and the size from the in game editor.
     *
     * <p>Because the whole layer draws inside DragonMineZ's already-scaled render pose (its
     * {@code DMZPlayerRenderer.preRender} applies {@code Character.getResolvedModelScaling} before the forwarder runs
     * our layer), the anchor and the size are in that scaled frame, so a bigger race or form carries the back piece up
     * in proportion with no code here.
     */
    private void drawBack(Minecraft mc, PoseStack pose, MultiBufferSource buffer, int light, CosmeticDef def,
            BakedModel baked, ItemStack stack)
    {
        float[] bounds = boundsOf(baked);
        // Anchor the piece CENTRE on the upper back, in raw body space, before the facing turn, so a seeded facing
        // rotation spins the piece in place rather than swinging it off the anchor.
        pose.translate(0.0F, BACK_ANCHOR_Y, BACK_ANCHOR_Z);
        applyDefTransform(pose, def);
        // Face the decorated side outward (the DMZ geo player faces opposite vanilla, so this puts it on the back)
        // and set upright at the artist's authored size.
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        float s = BACK_SIZE_BASE * authoredBackScale(baked);
        pose.scale(s, -s, -s);
        // Centre the model on its measured bounds so the anchor lands its visual centre. This is what cancels the
        // pack's armour-stand-head translate. A model with no readable bounds falls back to the vanilla item centre.
        if (bounds != null)
            pose.translate(0.5F - centre(bounds, 0), 0.5F - centre(bounds, 1), 0.5F - centre(bounds, 2));
        mc.getItemRenderer().render(stack, ItemDisplayContext.NONE, false, pose, buffer, light,
                OverlayTexture.NO_OVERLAY, baked);
    }

    /**
     * The artist's authored back-piece SIZE: the uniform scale from the base model's {@code display.head} transform,
     * clamped to a sane band. Read from the base model (drawSlot resolves the base, never the armour-stand-scaled worn
     * variant), so it is the pack's ~1.5 to 1.8 on-body scale rather than the worn variant's 4x mount scale. A model
     * with no head transform, or an unreadable one, falls back to {@link #BACK_AUTHORED_SCALE_DEFAULT}.
     */
    private static float authoredBackScale(BakedModel baked)
    {
        try
        {
            net.minecraft.client.renderer.block.model.ItemTransform t = baked.getTransforms()
                    .getTransform(ItemDisplayContext.HEAD);
            if (t == net.minecraft.client.renderer.block.model.ItemTransform.NO_TRANSFORM || t.scale == null)
                return BACK_AUTHORED_SCALE_DEFAULT;
            float s = Math.max(t.scale.x(), Math.max(t.scale.y(), t.scale.z()));
            if (!(s > 0.0F))
                return BACK_AUTHORED_SCALE_DEFAULT;
            return Math.min(Math.max(s, BACK_AUTHORED_SCALE_MIN), BACK_AUTHORED_SCALE_MAX);
        }
        catch (Throwable ex)
        {
            return BACK_AUTHORED_SCALE_DEFAULT;
        }
    }

    /**
     * ACCESSORY recipe for the CARRIED and default sub-styles: a held piece off the right arm's hand. Placed by
     * vanilla's own held-item recipe (see {@code ItemInHandLayer.renderArmWithItem}) so it sits in the fist rather
     * than inside the arm; drawSlot already posed the right arm bone (which is what {@code translateToHand} does).
     * A HAND skin does NOT come through here: it is drawn by {@code MixinDmzWeaponsLayer} at the ki weapon's own bone
     * (see {@link #renderHandSkin}), so this method only ever handles the always-in-hand CARRIED props.
     *
     * <p>Two grips: a CARRIED oddment with a real authored hand pose is drawn in the
     * {@link ItemDisplayContext#THIRD_PERSON_RIGHT_HAND} context so the model's own transform sizes it; everything
     * else grips like a sword. See {@link #applyHandheldSwordPose}.
     */
    private void drawAccessory(Minecraft mc, PoseStack pose, MultiBufferSource buffer, int light, CosmeticDef def,
            BakedModel baked, ItemStack stack)
    {
        // Vanilla's exact held-item recipe: tip the item forward off the posed arm so it sits in the grip.
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(HAND_TIP_X, HAND_TIP_Y, HAND_TIP_Z);
        applyDefTransform(pose, def);
        if (usesAuthoredHeldPose(baked))
        {
            // The model authors a real held pose (a non-zero rotation and a sane scale), so trust it: draw in the
            // THIRD_PERSON_RIGHT_HAND context and let the model's own transform place and size it, as vanilla does.
            mc.getItemRenderer().render(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, pose, buffer, light,
                    OverlayTexture.NO_OVERLAY, baked);
        }
        else
        {
            // The model's third-person hand transform is degenerate: a zero rotation (the item stands straight up out
            // of the fist, lined up with the arm) or a zero/absurd scale (authored for an armour-stand head). Grip it
            // like a sword instead by applying vanilla minecraft:item/handheld's own thirdperson_righthand transform
            // (rotation [0,-90,55], translation [0,4,0.5] in 1/16, scale 0.85), then draw with NO transform so this is
            // the only one applied. Every hand prop that lacks a real pose then holds at the same angle a sword does.
            applyHandheldSwordPose(pose);
            mc.getItemRenderer().render(stack, ItemDisplayContext.NONE, false, pose, buffer, light,
                    OverlayTexture.NO_OVERLAY, baked);
        }
    }

    /**
     * Whether a model authors a usable third-person right-hand pose, so it should be trusted over the sword default.
     * A pose counts as real only when it turns the item (a non-zero rotation) and keeps a sane scale; a transform
     * that is absent, unrotated (the item stands vertical in the fist) or scaled to nothing (authored for an
     * armour-stand head) is rejected in favour of the handheld sword pose.
     */
    private static boolean usesAuthoredHeldPose(BakedModel baked)
    {
        try
        {
            net.minecraft.client.renderer.block.model.ItemTransform t = baked.getTransforms()
                    .getTransform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
            if (t == net.minecraft.client.renderer.block.model.ItemTransform.NO_TRANSFORM)
                return false;
            org.joml.Vector3f rot = t.rotation;
            org.joml.Vector3f sc = t.scale;
            boolean rotates = rot != null && (rot.x() != 0.0F || rot.y() != 0.0F || rot.z() != 0.0F);
            boolean saneScale = sc != null && sc.x() > 0.1F && sc.y() > 0.1F && sc.z() > 0.1F;
            return rotates && saneScale;
        }
        catch (Throwable t)
        {
            // A model whose transforms cannot be read falls back to the sword pose, which is the safe held look.
            return false;
        }
    }

    /**
     * Apply vanilla {@code minecraft:item/handheld}'s own {@code thirdperson_righthand} transform, the one every
     * sword and tool grips with. Reproduced as three sequential rotations (which compose to the same quaternion
     * {@code ItemTransform.apply} builds with {@code rotationXYZ}) plus the /16 translation and the 0.85 scale.
     */
    private static void applyHandheldSwordPose(PoseStack pose)
    {
        pose.translate(0.0F, 4.0F / 16.0F, 0.5F / 16.0F);
        // rotation [0, -90, 55]: X is zero, so only Y then Z. mulPose multiplies on the right, so this composes to
        // rotationXYZ(0, -90, 55), matching ItemTransform.apply exactly.
        pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(55.0F));
        pose.scale(0.85F, 0.85F, 0.85F);
    }

    /**
     * FLOAT recipe: a balloon that floats above and to the side of the player on a string tied to the hand. Drawn in
     * the body bone's space (which carries the player's body yaw but no head pitch, so the balloon rides with the
     * body and never with a head turn). The balloon bobs gently and lags behind the player's movement through a
     * client-side damped-follow ({@link BalloonSway}); the string is drawn the way vanilla draws a fishing line (see
     * {@link #balloonString}). The per-def offset/rotation/scale still fine-tune the balloon from the editor.
     *
     * <p>Placement is deliberately well clear of the body and the ground: the balloon floats above the head and off
     * to one side, and the sway is small and clamped, so it does not clip into the player or reach the floor.
     */
    private void drawBalloon(Minecraft mc, PoseStack pose, MultiBufferSource buffer, int light,
            AbstractClientPlayer player, PlayerModel<AbstractClientPlayer> model, CosmeticDef def, BakedModel baked,
            ItemStack stack, float ageInTicks)
    {
        float[] bounds = boundsOf(baked);
        if (bounds == null)
            return;

        // The damped-follow sway (blocks), plus a gentle bob. Sway lags the player's horizontal movement so the
        // balloon trails and swings; the bob is a small vertical float. Both are bounded so the balloon stays clear.
        float[] sway = balloonSway(player);
        float bob = Mth.sin(ageInTicks * BALLOON_BOB_SPEED) * BALLOON_BOB_AMP;

        float handX = BALLOON_HAND_X;
        float handY = BALLOON_HAND_Y;
        float handZ = BALLOON_HAND_Z;
        float balloonX = BALLOON_X + sway[0];
        float balloonY = BALLOON_Y + bob;
        float balloonZ = BALLOON_Z + sway[1];

        pose.pushPose();
        model.body.translateAndRotate(pose);

        // The string first, from the hand out to the balloon, in body space. Drawn in its own pushed pose translated
        // to the hand so the segment maths runs from the origin, exactly like FishingHookRenderer.
        pose.pushPose();
        pose.translate(handX, handY, handZ);
        balloonString(buffer, pose.last(), balloonX - handX, balloonY - handY, balloonZ - handZ);
        pose.popPose();

        // The balloon model, centred on its own bounds and fitted to a held-balloon size, then hung at the float
        // point. Identity display like the BACK recipe, so the model's own (armour-stand-head) transform is ignored
        // and the piece lands the same size regardless of how it was authored. The per-def transform fine-tunes.
        pose.translate(balloonX, balloonY, balloonZ);
        applyDefTransform(pose, def);
        float s = fitScale(bounds, BALLOON_TARGET_BLOCKS);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.scale(s, -s, -s);
        pose.translate(0.5F - centre(bounds, 0), 0.5F - centre(bounds, 1), 0.5F - centre(bounds, 2));
        mc.getItemRenderer().render(stack, ItemDisplayContext.NONE, false, pose, buffer, light,
                OverlayTexture.NO_OVERLAY, baked);
        pose.popPose();
    }

    /**
     * Draw the balloon's tether the way vanilla draws a fishing line (see
     * {@code FishingHookRenderer.render}/{@code stringVertex}): a line strip of sixteen segments, each vertex black
     * and carrying the normalised segment direction as its normal, so it reads as the same rope. The vector
     * {@code (dx,dy,dz)} runs from the hand (the pose origin here) to the balloon.
     *
     * <p>Two changes from the raw fishing line. First the render type: vanilla's {@code RenderType.lineStrip()} leaves
     * the GL line width UNSET, so it falls back to a width that scales with the window resolution
     * ({@code max(2.5, width/1920 * 2.5)}) and reads as a thick bar up close, which is why the string looked like a
     * stick. {@link CosmeticLineType#balloonString()} is the same shader, blend and target but with a fixed thin
     * width, so it draws as a fine dark line at every resolution. The LINES shader still expands each segment along
     * its normal, so every vertex keeps its normal exactly as {@code FishingHookRenderer} feeds it. Second the sag:
     * a fishing line's belly scales with its length ({@code dy*(t*t+t)}), which for a long upward tether would bow
     * absurdly, so a small fixed sag ({@link #BALLOON_STRING_SAG}, zero at both ends) gives a slightly slack string
     * with just a hint of a belly.
     */
    private static void balloonString(MultiBufferSource buffer, PoseStack.Pose pose, float dx, float dy, float dz)
    {
        com.mojang.blaze3d.vertex.VertexConsumer vc = buffer.getBuffer(CosmeticLineType.balloonString());
        int segments = 16;
        for (int k = 0; k <= segments; k++)
        {
            float t0 = (float) k / segments;
            float t1 = (float) (k + 1) / segments;
            stringVertex(vc, pose, dx, dy, dz, t0, t1);
        }
    }

    // One string vertex, mirroring FishingHookRenderer.stringVertex: position along the run at parameter t with a
    // small belly, black colour, and the normal set to the normalised direction to the next sample. +Y is down in
    // body space, so a positive sag bows the rope downward in the middle, like a rope under its own weight.
    private static void stringVertex(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose pose, float dx,
            float dy, float dz, float t0, float t1)
    {
        float x0 = dx * t0;
        float y0 = dy * t0 + BALLOON_STRING_SAG * (t0 - t0 * t0);
        float z0 = dz * t0;
        float x1 = dx * t1;
        float y1 = dy * t1 + BALLOON_STRING_SAG * (t1 - t1 * t1);
        float z1 = dz * t1;
        float nx = x1 - x0;
        float ny = y1 - y0;
        float nz = z1 - z0;
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0E-5F)
        {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        vc.vertex(pose.pose(), x0, y0, z0).color(0, 0, 0, 255).normal(pose.normal(), nx, ny, nz).endVertex();
    }

    /**
     * The balloon's damped-follow sway in body-space blocks, {@code [x, z]}. Advanced once per client tick from the
     * player's horizontal movement rotated into the body frame, so the balloon trails behind and swings when the
     * player moves and settles when they stop. Bounded and clamped so it can never swing far enough to clip the body.
     */
    private static float[] balloonSway(AbstractClientPlayer player)
    {
        BalloonSway state = BALLOON_SWAY.computeIfAbsent(player.getUUID(), k -> new BalloonSway());
        if (BALLOON_SWAY.size() > 512)
            BALLOON_SWAY.clear();
        state.update(player);
        return new float[] { state.swayX, state.swayZ };
    }

    /**
     * Per-player spring state for the FLOAT balloon: a simple critically-damped follow toward a target derived from
     * the player's per-tick horizontal movement, so the balloon lags and swings like a real one on a string. Advanced
     * at most once per game tick (keyed on the client game time), never per frame, so the motion is deterministic and
     * frame-rate independent. All values are BLOCKS in the body-bone frame.
     */
    private static final class BalloonSway
    {
        // How far the balloon trails per block of per-tick movement, and the clamp that keeps it clear of the body.
        private static final float LAG = 6.0F;

        private static final float MAX = 0.28F;

        // Spring stiffness and damping. Soft and well damped, so the balloon eases rather than snapping or ringing.
        private static final float STIFFNESS = 0.22F;

        private static final float DAMPING = 0.60F;

        private float swayX;

        private float swayZ;

        private float velX;

        private float velZ;

        private double prevX;

        private double prevZ;

        private long lastTick = Long.MIN_VALUE;

        private void update(AbstractClientPlayer player)
        {
            long now = player.level().getGameTime();
            if (now == lastTick)
                return;
            // First sight of this player, or a resumed session: seed the position and skip, so a huge one-off delta
            // does not fling the balloon.
            if (lastTick == Long.MIN_VALUE)
            {
                prevX = player.getX();
                prevZ = player.getZ();
                lastTick = now;
                return;
            }
            int steps = (int) Math.min(Math.max(1L, now - lastTick), 4L);
            lastTick = now;

            double worldDx = player.getX() - prevX;
            double worldDz = player.getZ() - prevZ;
            prevX = player.getX();
            prevZ = player.getZ();

            // Rotate the world movement into the body frame so the trail direction rides with the player's facing.
            double yaw = Math.toRadians(player.yBodyRot);
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            float localX = (float) (worldDx * cos + worldDz * sin);
            float localZ = (float) (-worldDx * sin + worldDz * cos);

            // The balloon trails OPPOSITE the movement, so a step forward pushes it back and to the side.
            float targetX = clamp(-LAG * localX, -MAX, MAX);
            float targetZ = clamp(-LAG * localZ, -MAX, MAX);

            for (int i = 0; i < steps; i++)
            {
                velX += (targetX - swayX) * STIFFNESS;
                velX *= DAMPING;
                swayX = clamp(swayX + velX, -MAX, MAX);
                velZ += (targetZ - swayZ) * STIFFNESS;
                velZ *= DAMPING;
                swayZ = clamp(swayZ + velZ, -MAX, MAX);
            }
        }

        private static float clamp(float v, float lo, float hi)
        {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }

    /**
     * The recipe a definition draws with. Normally the slot, but a per-def {@link CosmeticDef#attachBone} override
     * that names a specific vanilla part chooses the matching recipe, so moving a piece onto a new bone from the
     * editor moves its whole placement recipe with it rather than leaving it half on the old one.
     */
    private CosmeticSlot recipeSlot(CosmeticDef def, CosmeticSlot slot)
    {
        String bone = def.attachBone == null ? "" : def.attachBone.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (bone)
        {
            case "body" -> CosmeticSlot.BACK;
            case "right_arm", "rightarm", "left_arm", "leftarm" -> CosmeticSlot.ACCESSORY;
            case "head", "hat" -> CosmeticSlot.HEAD;
            default -> slot;
        };
    }

    /**
     * Which vanilla body part a cosmetic hangs off. The default now follows the SLOT (head on the head bone, back
     * on the body bone, accessory on the right arm) rather than every slot sharing the head bone, which is what
     * put backpacks up by the head. An admin may still override per definition with {@link CosmeticDef#attachBone};
     * unknown names fall back to the slot default rather than throwing.
     */
    private ModelPart boneFor(PlayerModel<AbstractClientPlayer> model, CosmeticDef def, CosmeticSlot slot)
    {
        String bone = def.attachBone == null ? "" : def.attachBone.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (bone)
        {
            case "body" -> model.body;
            case "right_arm", "rightarm" -> model.rightArm;
            case "left_arm", "leftarm" -> model.leftArm;
            case "right_leg", "rightleg" -> model.rightLeg;
            case "left_leg", "leftleg" -> model.leftLeg;
            case "hat" -> model.hat;
            case "head" -> model.head;
            default -> switch (slot)
            {
                case BACK -> model.body;
                case ACCESSORY -> model.rightArm;
                default -> model.head;
            };
        };
    }

    /** Largest-dimension fit: the uniform scale that makes a model's biggest axis {@code targetBlocks} across. */
    private static float fitScale(float[] bounds, float targetBlocks)
    {
        float dx = bounds[3] - bounds[0];
        float dy = bounds[4] - bounds[1];
        float dz = bounds[5] - bounds[2];
        float max = Math.max(dx, Math.max(dy, dz));
        // A degenerate or unreadable box would divide by ~0; fall back to 1 so the item at least draws untouched.
        return max > 1.0E-4F ? targetBlocks / max : 1.0F;
    }

    private static float centre(float[] bounds, int axis)
    {
        return (bounds[axis] + bounds[axis + 3]) * 0.5F;
    }

    /**
     * The model's bounding box in baked units (1.0 == one block), cached per baked model. Read from the model's
     * own quads once, the same way the sprite gatherer walks them. Null when no quads are readable, which the
     * caller treats as "draw nothing" rather than risk a divide by zero on the render thread.
     */
    private static float[] boundsOf(BakedModel baked)
    {
        if (baked == null)
            return null;
        synchronized (BOUNDS_CACHE)
        {
            if (BOUNDS_CACHE.containsKey(baked))
                return BOUNDS_CACHE.get(baked);
            float[] b = gatherBounds(baked);
            BOUNDS_CACHE.put(baked, b);
            return b;
        }
    }

    private static float[] gatherBounds(BakedModel baked)
    {
        float[] b = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                -Float.MAX_VALUE };
        boolean any;
        try
        {
            RandomSource rand = RandomSource.create(42L);
            any = accumulate(b, baked.getQuads(null, null, rand));
            for (Direction d : Direction.values())
                any |= accumulate(b, baked.getQuads(null, d, rand));
        }
        catch (Throwable ignored)
        {
            return null;
        }
        return any ? b : null;
    }

    // A BakedQuad's vertex data is DefaultVertexFormat.BLOCK: 8 ints per vertex, position in the first three as
    // float bits. Reading x,y,z of every vertex is all the bounds need.
    private static boolean accumulate(float[] b,
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads)
    {
        if (quads == null || quads.isEmpty())
            return false;
        boolean any = false;
        for (net.minecraft.client.renderer.block.model.BakedQuad q : quads)
        {
            int[] v = q.getVertices();
            int stride = v.length / 4;
            if (stride < 3)
                continue;
            for (int i = 0; i < 4; i++)
            {
                float x = Float.intBitsToFloat(v[i * stride]);
                float y = Float.intBitsToFloat(v[i * stride + 1]);
                float z = Float.intBitsToFloat(v[i * stride + 2]);
                b[0] = Math.min(b[0], x);
                b[1] = Math.min(b[1], y);
                b[2] = Math.min(b[2], z);
                b[3] = Math.max(b[3], x);
                b[4] = Math.max(b[4], y);
                b[5] = Math.max(b[5], z);
                any = true;
            }
        }
        return any;
    }

    /**
     * The admin fine-tune, applied in bone space BEFORE the head recipe so the fields read intuitively: offset in
     * 1/16 blocks with +Y up, rotation in degrees, scale a plain multiplier. All default to identity.
     */
    private static void applyDefTransform(PoseStack pose, CosmeticDef def)
    {
        float[] o = def.offset;
        if (o != null && o.length == 3 && (o[0] != 0.0F || o[1] != 0.0F || o[2] != 0.0F))
            pose.translate(o[0] / 16.0F, -o[1] / 16.0F, o[2] / 16.0F);
        float[] r = def.rotation;
        if (r != null && r.length == 3)
        {
            if (r[0] != 0.0F)
                pose.mulPose(Axis.XP.rotationDegrees(r[0]));
            if (r[1] != 0.0F)
                pose.mulPose(Axis.YP.rotationDegrees(r[1]));
            if (r[2] != 0.0F)
                pose.mulPose(Axis.ZP.rotationDegrees(r[2]));
        }
        if (def.scale > 0.0F && def.scale != 1.0F)
            pose.scale(def.scale, def.scale, def.scale);
    }

    /**
     * The baked model to draw: the worn variant {@code dmz_ragnarok:item/cosmetic_worn/<id>} when it baked, else
     * the item's own model. Never null unless the item has no model at all, which the caller treats as "draw
     * nothing".
     */
    private static BakedModel resolveBaked(Minecraft mc, CosmeticDef def, AbstractClientPlayer player, ItemStack stack)
    {
        BakedModel worn = wornModel(mc, def.id);
        if (worn != null)
            return worn;
        // The item's own model. getModel never returns null (it hands back the missing model for an absent id).
        // Callers treat the missing model as "draw nothing": non-Patreon cosmetic art is streamed by the Ragnarok
        // Key, so a client can see a cosmetic before its model exists here.
        return mc.getItemRenderer().getModel(stack, player.level(), player, player.getId());
    }

    private static BakedModel wornModel(Minecraft mc, String id)
    {
        if (id == null || id.isBlank())
            return null;
        ResourceLocation rl = new ResourceLocation(ShuruisUtilities.MODID, "item/cosmetic_worn/" + id);
        BakedModel m = mc.getModelManager().getModel(rl);
        return m == null || m == missing(mc) ? null : m;
    }

    private static BakedModel missing(Minecraft mc)
    {
        return mc.getModelManager().getMissingModel();
    }

    /**
     * Resolve a cosmetic's {@code modelId} to a stack. Accepts the plain item id ({@code dmz_ragnarok:hw_bat_hat},
     * the contract) and, leniently, an old-style model path whose last segment is the item id in the same
     * namespace. Never throws; an unparseable value is an empty stack, which the caller treats as "no art yet".
     * Kept in step with {@code CosmeticStyle.itemFor} so the wardrobe icon and the worn model always agree.
     */
    private static ItemStack stackFor(String modelId)
    {
        Item item = itemFor(modelId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static Item itemFor(String raw)
    {
        if (raw == null || raw.isBlank())
            return null;
        String value = raw.trim();
        Item direct = lookup(value);
        if (direct != null)
            return direct;
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length())
            return null;
        int colon = value.indexOf(':');
        String namespace = colon > 0 ? value.substring(0, colon + 1) : "";
        return lookup(namespace + value.substring(slash + 1));
    }

    private static Item lookup(String id)
    {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null)
            return null;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        return item == null || item == Items.AIR ? null : item;
    }

    /**
     * Keep the animated {@code .mcmeta} strips ticking under Embeddium/Sodium, which freeze any atlas sprite the
     * chunk mesher has not marked. A worn item drawn from a layer is exactly that case. No-op on vanilla Forge and
     * whenever no Sodium-family marker is present; sprites are gathered once per baked model and cached.
     */
    private static void markSpritesActive(BakedModel baked)
    {
        if (!SodiumSpriteAnimation.available() || baked == null)
            return;
        List<TextureAtlasSprite> sprites;
        synchronized (SPRITE_CACHE)
        {
            sprites = SPRITE_CACHE.get(baked);
            if (sprites == null)
            {
                sprites = gatherSprites(baked);
                SPRITE_CACHE.put(baked, sprites);
            }
        }
        for (TextureAtlasSprite s : sprites)
            SodiumSpriteAnimation.markActive(s);
    }

    private static List<TextureAtlasSprite> gatherSprites(BakedModel baked)
    {
        List<TextureAtlasSprite> out = new ArrayList<>();
        try
        {
            RandomSource rand = RandomSource.create(42L);
            java.util.Set<TextureAtlasSprite> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            addSprites(out, seen, baked.getQuads(null, null, rand));
            for (Direction d : Direction.values())
                addSprites(out, seen, baked.getQuads(null, d, rand));
            TextureAtlasSprite particle = baked.getParticleIcon();
            if (particle != null && seen.add(particle))
                out.add(particle);
        }
        catch (Throwable ignored)
        {
            // Decoration only. A model whose quads cannot be read simply does not get its animation nudged.
        }
        return out;
    }

    private static void addSprites(List<TextureAtlasSprite> out, java.util.Set<TextureAtlasSprite> seen,
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads)
    {
        if (quads == null)
            return;
        for (net.minecraft.client.renderer.block.model.BakedQuad q : quads)
        {
            TextureAtlasSprite s = q.getSprite();
            if (s != null && seen.add(s))
                out.add(s);
        }
    }

    private static void warnOnce(String id, Throwable t)
    {
        String key = id == null ? "?" : id;
        if (WARNED.add(key))
            LoggingHandler.sulog.warn("[Cosmetics] worn render for '{}' failed and was skipped: {}", key,
                    t == null ? "?" : t.toString());
    }
}

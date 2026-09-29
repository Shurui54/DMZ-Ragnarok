package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import com.dragonminez.common.dragonball.DragonRadarDefinition;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Lets DragonMineZ's dragon-radar HUD draw in every dimension, whichever radar a player holds.
 *
 * <p>This class carries THREE client-side bindings into {@code RadarRenderEvent.onRenderGameOverlay}, each described
 * in full on its own method: {@link #su$drawInSuDimensions} (index 5, {@code isNamek}) keeps the original space and
 * planet-surface widening; {@link #su$treatEveryNonNamekAsOverworld} (index 4, {@code isOverworld}) makes DMZ's own
 * earth and fused radars usable everywhere except Namek; and {@link #su$customRadarsSupportEveryDimension} (a redirect
 * on {@code DragonRadarDefinition.supportsDimension}) lets the custom Super, Black Star and Cerulean radars draw
 * everywhere. All three gate DRAWING only; WHICH set each radar shows is unchanged.
 *
 * <p>The server already syncs radar positions to every player in every dimension
 * ({@code DragonBallsHandler.buildRadarPacket} / {@code syncRadar}); only the CLIENT overlay refuses to paint. In
 * {@code RadarRenderEvent.onRenderGameOverlay} DMZ computes two booleans, {@code isOverworld} and {@code isNamek},
 * and early-returns on {@code if (!isOverworld && !isNamek) return;}, so the whole overlay is skipped in any other
 * dimension. Everything AFTER that guard that concerns our custom set radars (the generic {@code DragonRadarItem}
 * branch) does NOT gate on those two booleans at all: it gates on the radar definition's OWN
 * {@code supportsDimension}, which is the data half of this feature (we list space on the Super radar and
 * planet_surface on the Black Star radar). So the ONLY thing standing between a synced custom radar and the screen
 * in our dimensions is that early return.</p>
 *
 * <p>We widen it by ORing our two dimensions into {@code isNamek} as it is stored: in space or on a planet surface
 * the value becomes true, the {@code !isOverworld && !isNamek} test is false, and the method proceeds to the
 * generic radar branch where the radar definition's dimensions decide what actually draws. We re-read the level
 * from {@code Minecraft.getInstance().player} (the same source DMZ used two locals earlier) rather than capturing a
 * DMZ local, so we do not depend on its local-variable layout.</p>
 *
 * <p><b>Why {@code isNamek} and its one tiny side effect.</b> The early return is driven by these two locals, and
 * both are re-read later to route the VANILLA Earth and Namek radar items to their position lists. Setting
 * {@code isNamek} true in our dimensions means a player who is physically holding a vanilla DMZ Namek radar while
 * standing in SU space or on a planet surface would see Namek blips. That is the only behavioural change, it is
 * cosmetic, and it is vanishingly unlikely in these endgame SU dimensions (where players carry the Super and Black
 * Star radars, which take the generic branch instead). Our own custom radars are unaffected by which of the two
 * booleans we flip, because they never match the Earth/Namek item checks and always fall through to the generic
 * branch. There is no vanilla-Sponge way to skip JUST the return without touching one of these two branch inputs,
 * so this is the least-invasive option.</p>
 *
 * <p>{@code remap = false}: the {@code @Mixin} target and DMZ's own {@code onRenderGameOverlay} name are
 * DragonMineZ's, not Mojmap. {@code require = 0}: if DMZ reshapes the handler or its locals, this degrades to a
 * no-op and the radar simply keeps its original overworld/Namek-only behaviour rather than crashing the client.</p>
 */
@Mixin(targets = "com.dragonminez.client.events.RadarRenderEvent", remap = false)
public abstract class MixinDmzRadarRender
{
    // Local index 5 is DMZ's isNamek boolean (index 4 is isOverworld). @At("STORE") intercepts the value as it is
    // written, so we transform isNamek before the early-return test reads it.
    @ModifyVariable(
        method = "onRenderGameOverlay(Lnet/minecraftforge/client/event/RenderGuiOverlayEvent$Pre;)V",
        at = @At("STORE"),
        index = 5,
        require = 0,
        remap = false
    )
    private static boolean su$drawInSuDimensions(boolean isNamek)
    {
        if (isNamek)
        {
            return true;
        }
        try
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null)
            {
                return false;
            }
            Level level = mc.player.level();
            // Treat SU space and planet-surface as draw-eligible so the early return is skipped there; the per-radar
            // dimensions list downstream still decides which radar actually paints.
            if (SpaceKeys.isSpace(level) || SpaceKeys.isSurface(level))
            {
                return true;
            }
        }
        catch (Throwable ignored)
        {
            // Never let the widening break DMZ's overlay; on any error keep DMZ's original isNamek value.
        }
        return false;
    }

    /**
     * Makes DMZ's OWN earth and fused radars usable in every dimension except Namek, by treating {@code isOverworld}
     * as true everywhere that is not Namek.
     *
     * <p>Unlike the custom radars (which take the generic {@code DragonRadarItem} branch, handled by the
     * {@link #su$customRadarsSupportEveryDimension} redirect), DMZ's built-in earth and fused radars are routed by
     * HARDCODED tests on this {@code isOverworld} local: {@code isOverworld && mainHand == earthRadarItem} sends the
     * earth radar to {@code clientEarthPositions}, and the fused radar picks {@code isOverworld ? earth : namek}.
     * Both are dead in any non-overworld dimension. Forcing {@code isOverworld} true wherever the player is not on
     * Namek routes the earth radar to its own positions everywhere and skips the early return (which reads this same
     * local), while leaving Namek alone so the fused radar there still shows Namek balls.
     *
     * <p><b>Accepted caveat.</b> Because the fused radar picks {@code isOverworld ? earth : namek}, forcing
     * {@code isOverworld} true in every non-Namek dimension means a fused ("bi-dimensional") radar held OFF Namek now
     * shows EARTH balls rather than Namek balls. It already did exactly that in the overworld; this only extends that
     * behaviour to the other non-Namek dimensions. On Namek the value stays false, so the fused radar there is
     * unchanged. This is the deliberate trade for making the plain earth radar work everywhere, and is the reason we
     * force the value true for "every non-Namek dimension" rather than "always".
     *
     * <p>Local index 4 is DMZ's {@code isOverworld} boolean: verified against the disassembly of
     * {@code onRenderGameOverlay}, where the only {@code istore 4} follows the
     * {@code level.dimension().equals(Level.OVERWORLD)} compare (index 5, {@code istore 5}, is the following
     * {@code NAMEK_KEY} compare). {@code @At("STORE")} intercepts the value as it is written, before the early-return
     * test and the item-routing branches read it. {@code require = 0}: if DMZ reshapes the handler this degrades to a
     * no-op and the earth radar simply keeps its overworld-only behaviour rather than crashing the client.
     */
    @ModifyVariable(
        method = "onRenderGameOverlay(Lnet/minecraftforge/client/event/RenderGuiOverlayEvent$Pre;)V",
        at = @At("STORE"),
        index = 4,
        require = 0,
        remap = false
    )
    private static boolean su$treatEveryNonNamekAsOverworld(boolean isOverworld)
    {
        if (isOverworld)
        {
            return true;
        }
        try
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null)
            {
                return false;
            }
            // Keep Namek genuinely non-overworld so the fused radar there still resolves to Namek; treat every other
            // dimension as overworld so the earth and fused radars route and the early return is skipped.
            if (su$isNamek(mc.player.level()))
            {
                return false;
            }
            return true;
        }
        catch (Throwable ignored)
        {
            // Never let the widening break DMZ's overlay; on any error keep DMZ's original isOverworld value.
        }
        return false;
    }

    /**
     * Lets every CUSTOM ({@code DragonRadarItem}) radar draw in every dimension, by forcing the per-radar
     * {@code supportsDimension} check inside {@code onRenderGameOverlay} to report true.
     *
     * <p>The generic branch that handles our Super, Black Star and Cerulean radars gates on
     * {@code definition.supportsDimension(level.dimension())}, which is a membership test against each radar's own
     * dimension list. That list was the whole reason a radar refused to paint outside a handful of dimensions.
     * Redirecting the call (both invoke sites, main hand and off hand, verified as the only two
     * {@code DragonRadarDefinition.supportsDimension} invokes in the method) to true removes the dimension gate for
     * the DRAW decision only. WHAT a radar then draws is still its own set's positions and nothing else (requirement
     * 2 is untouched); this only decides WHETHER it draws. The early return is handled by the two ModifyVariable
     * bindings above, so by the time this runs the method is already proceeding.
     *
     * <p>{@code remap = false}: both the injected method {@code onRenderGameOverlay} and the redirected
     * {@code DragonRadarDefinition.supportsDimension} are DragonMineZ's own names (official in the production jar),
     * not vanilla overrides, so nothing here is refmapped. {@code require = 0}: if DMZ renames or reshapes either the
     * method or the call, this degrades to a no-op and the custom radars keep their dimension-list behaviour rather
     * than crashing the client.
     */
    @Redirect(
        method = "onRenderGameOverlay(Lnet/minecraftforge/client/event/RenderGuiOverlayEvent$Pre;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dragonminez/common/dragonball/DragonRadarDefinition;"
                    + "supportsDimension(Lnet/minecraft/resources/ResourceKey;)Z",
            remap = false
        ),
        require = 0,
        remap = false
    )
    private static boolean su$customRadarsSupportEveryDimension(DragonRadarDefinition definition,
            ResourceKey<Level> dimension)
    {
        return true;
    }

    // True when this level is DMZ's Namek dimension. Compared on the dimension id rather than DMZ's NamekDimension
    // constant so this client mixin does not classload a DMZ server-world class.
    private static boolean su$isNamek(Level level)
    {
        return level != null && level.dimension().location().equals(new ResourceLocation("dragonminez", "namek"));
    }
}

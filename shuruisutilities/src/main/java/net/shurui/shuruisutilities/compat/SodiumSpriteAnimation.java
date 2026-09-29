package net.shurui.shuruisutilities.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Keeps a block-atlas sprite's animation ticking under Sodium-family renderers, which freeze animations they believe
 * nothing is looking at.
 *
 * <h2>The problem</h2>
 * Embeddium (and Rubidium, and Sodium itself) ships {@code performance.animate_only_visible_textures}, on by DEFAULT,
 * including in this modpack. Rather than advance every animated sprite on the atlas every tick, it advances only the
 * ones it has seen REFERENCED, and the only referencing it knows about is the chunk mesher and the vanilla model paths
 * it hooks. A sprite is marked active when geometry that samples it is built, and a sprite nothing marks is simply not
 * ticked: it holds frame 0 forever.
 *
 * <p>SU's space bodies are exactly the case that falls through that. Their sheets live on the BLOCK atlas, but no block
 * in the world uses them: {@code SpaceBodyRenderer} pulls the baked quads itself and pushes them straight into an
 * atlas-bound render type once a frame. Embeddium therefore never sees them referenced and never ticks them, so the
 * sun's four flare sheets (and the slow drift on its body sheet) sit frozen on their first frame. That is the
 * "solar flares stopped animating" report, and it is why it only shows in the real client: the dev run has no Embeddium,
 * so the animation there is vanilla's and runs unconditionally.
 *
 * <h2>The fix</h2>
 * Sodium exposes the marker its own mesher uses, {@code SpriteUtil.markSpriteActive}, so anything drawing atlas sprites
 * outside the chunk mesher can declare them visible. Call it once per frame for every sprite we are about to draw and
 * the animation runs again, with the pack's own setting left alone.
 *
 * <p>Probed with {@link Class#forName} rather than a {@code ModList} check, deliberately. The class is the real
 * requirement and several different mod ids provide it (embeddium, rubidium, sodium), so a modid check would both miss
 * forks and, per the standing rule, prove nothing about the API actually being there. A miss, or any later signature
 * change, leaves {@link #HANDLE} null and every call becomes a no-op: on vanilla Forge, where animations tick anyway,
 * that is exactly the right outcome.
 */
public final class SodiumSpriteAnimation
{
    private SodiumSpriteAnimation() {}

    // Resolved once at class init. Null whenever no Sodium-family renderer is present, or its API has moved, in which
    // case every call below does nothing at all.
    private static final MethodHandle HANDLE = resolve();

    private static MethodHandle resolve()
    {
        try
        {
            Class<?> util = Class.forName("me.jellysquid.mods.sodium.client.render.texture.SpriteUtil");
            return MethodHandles.lookup().findStatic(util, "markSpriteActive",
                    MethodType.methodType(void.class, TextureAtlasSprite.class));
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** Whether a Sodium-family sprite marker was found, so callers can skip gathering sprites they cannot use. */
    public static boolean available()
    {
        return HANDLE != null;
    }

    /**
     * Declare this sprite visible for this frame, so its animation is ticked. Safe to call every frame and safe to call
     * for a still sprite: marking one that has no animation costs a flag write and changes nothing.
     */
    public static void markActive(TextureAtlasSprite sprite)
    {
        if (HANDLE == null || sprite == null)
            return;
        try
        {
            HANDLE.invokeExact(sprite);
        }
        catch (Throwable ignored)
        {
            // Decoration only. A renderer that changes this API must never take a draw call down with it.
        }
    }
}

package net.shurui.shuruisutilities.client.radial;

import net.minecraft.resources.ResourceLocation;

/**
 * The wheel icons for our own entries.
 *
 * <p>Eighteen by eighteen white silhouettes with an alpha cutout, which is the shape and size DragonMineZ's own radial
 * icons use, so ours sit at the same weight beside them and take the same tint the wheel applies per form.
 */
public final class RagnarokRadialIcons
{
    private RagnarokRadialIcons() {}

    /** The staff: the halo and its orb over a long shaft, the same shape the item model builds. */
    public static final ResourceLocation ANGEL_STAFF =
            new ResourceLocation("dmz_ragnarok", "textures/gui/radial/angel_staff.png");
}

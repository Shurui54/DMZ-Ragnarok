package net.shurui.shuruisutilities.hologram.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One picture standing in the world: which image, where its centre is, and how big to draw it.
 *
 * <p>Lives in the network package because both sides own a copy of it, and neither the server's
 * {@code HologramManager} nor the client's renderer has any business importing the other.
 *
 * @param gif       the image's name, without its extension
 * @param dim       dimension registry id, so a client can ignore the billboards it is nowhere near
 * @param width     how wide to draw it, in blocks
 * @param height    how tall, which follows from the picture's own shape rather than being set by hand
 * @param billboard how it turns to face a viewer: center, vertical, horizontal or fixed
 * @param yaw       facing in degrees, used only when {@code billboard} is fixed
 * @param glow      draw it at full brightness instead of taking the light where it stands
 * @param viewRange multiplier on how far away it is still drawn
 */
public record GifBillboard(String gif, String dim, double x, double y, double z, float width, float height,
        String billboard, float yaw, float pitch, boolean glow, float viewRange)
{
    public void write(FriendlyByteBuf buf)
    {
        buf.writeUtf(gif);
        buf.writeUtf(dim);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(width);
        buf.writeFloat(height);
        buf.writeUtf(billboard);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeBoolean(glow);
        buf.writeFloat(viewRange);
    }

    public static GifBillboard read(FriendlyByteBuf buf)
    {
        return new GifBillboard(buf.readUtf(), buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readUtf(), buf.readFloat(), buf.readFloat(), buf.readBoolean(),
                buf.readFloat());
    }
}

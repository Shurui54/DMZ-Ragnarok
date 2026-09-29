package net.shurui.shuruisutilities.tablist.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import com.mojang.blaze3d.platform.NativeImage;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side holder for the tab-list banner image synced from the server ({@code PacketTabListBanner}).
 * Decodes the PNG bytes into a {@link DynamicTexture} registered under {@link #TEXTURE} so
 * {@code MixinPlayerTabOverlay} can blit it above the player list. No server-only references.
 */
public final class TabListBannerClient
{
    private TabListBannerClient() {}

    /** Texture location the banner is registered under (and blitted from). */
    public static final ResourceLocation TEXTURE = new ResourceLocation("dmz_ragnarok", "tablist_banner");

    private static boolean loaded = false;
    private static int width;
    private static int height;

    public static boolean isLoaded() { return loaded; }

    public static int width() { return width; }

    public static int height() { return height; }

    /** Replace the banner from freshly-synced PNG bytes. Empty/invalid input clears it. Must run on the client thread. */
    public static void set(byte[] png)
    {
        if (png == null || png.length == 0)
        {
            clear();
            return;
        }
        try
        {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            width = image.getWidth();
            height = image.getHeight();
            // register() closes any texture previously bound to this location, so there is no leak on refresh.
            Minecraft.getInstance().getTextureManager().register(TEXTURE, new DynamicTexture(image));
            loaded = true;
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("Failed to decode tab-list banner image", e);
            clear();
        }
    }

    private static void clear()
    {
        loaded = false;
        width = 0;
        height = 0;
        Minecraft.getInstance().getTextureManager().release(TEXTURE);
    }

    /** Public entry point for the disconnect reset, so one server's banner never lingers into the next. */
    public static void reset()
    {
        clear();
    }
}

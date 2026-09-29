package net.shurui.shuruisutilities.disguise.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.disguise.DisguiseView;

/**
 * Resolves a disguise target's SKIN on the client from the Mojang textures property carried in the {@link DisguiseView}.
 *
 * <p>The disguised player is a real player, so the client normally has only the STAFF member's skin. To show the
 * target's skin the server ships the target's signed {@code textures} property (which it looked up in its profile
 * cache / session service, even for an offline or random target). Here we rebuild a {@link GameProfile} from it and
 * ask the vanilla {@link net.minecraft.client.resources.SkinManager} to download and register the skin exactly as it
 * would for any player, then cache the resulting {@link ResourceLocation} and the slim/wide model flag.
 *
 * <p>Everything degrades: an empty property, a malformed value or a failed download leaves no cache entry, and the
 * render override then keeps the disguised player's OWN skin rather than a default Steve. Never throws to the caller.
 */
@OnlyIn(Dist.CLIENT)
public final class DisguiseSkins
{
    /** A resolved skin: its texture location and whether the target's model is the slim ("alex") arm variant. */
    public static final class Resolved
    {
        public final ResourceLocation location;
        public final boolean slim;

        Resolved(ResourceLocation location, boolean slim)
        {
            this.location = location;
            this.slim = slim;
        }
    }

    // Keyed by the DISGUISED player's UUID (the staff member), so a lookup at render time is one map get by the
    // entity being drawn. Value is null until the async download completes.
    private static final Map<UUID, Resolved> RESOLVED = new ConcurrentHashMap<>();
    // Remembers which textures value we already kicked off, so repeated syncs do not re-download the same skin.
    private static final Map<UUID, String> REQUESTED = new ConcurrentHashMap<>();

    private DisguiseSkins() {}

    /** Begin resolving the target's skin for this disguise, if not already resolved for the same textures value. */
    public static void prepare(DisguiseView v)
    {
        try
        {
            if (v == null || v.realId == null)
                return;
            if (v.skinTexturesValue == null || v.skinTexturesValue.isEmpty())
            {
                // No skin for this target: forget any earlier disguise's skin so the player's own shows.
                forget(v.realId);
                return;
            }
            String already = REQUESTED.get(v.realId);
            if (v.skinTexturesValue.equals(already))
                return;
            REQUESTED.put(v.realId, v.skinTexturesValue);

            GameProfile profile = new GameProfile(v.targetId != null ? v.targetId : v.realId,
                    v.targetName == null || v.targetName.isEmpty() ? "disguise" : v.targetName);
            Property property = v.skinTexturesSignature == null || v.skinTexturesSignature.isEmpty()
                    ? new Property("textures", v.skinTexturesValue)
                    : new Property("textures", v.skinTexturesValue, v.skinTexturesSignature);
            profile.getProperties().put("textures", property);

            final UUID key = v.realId;
            final String value = v.skinTexturesValue;
            final boolean fallbackSlim = v.slim;
            Minecraft.getInstance().getSkinManager().registerSkins(profile,
                    (type, location, texture) ->
                    {
                        if (type != MinecraftProfileTexture.Type.SKIN || location == null)
                            return;
                        boolean slim = fallbackSlim;
                        try
                        {
                            String model = texture == null ? null : texture.getMetadata("model");
                            if (model != null)
                                slim = "slim".equals(model);
                        }
                        catch (Throwable ignored)
                        {
                            // keep the DTO's slim flag
                        }
                        // Only if this is still the skin the player is disguised with (a later sync may have replaced
                        // or cleared it while the download ran).
                        if (value.equals(REQUESTED.get(key)))
                            RESOLVED.put(key, new Resolved(location, slim));
                    }, false);
        }
        catch (Throwable ignored)
        {
            // No cache entry: the render override keeps the player's own skin.
        }
    }

    /** The resolved skin for a disguised player, or null (caller keeps the player's own skin). */
    public static Resolved get(UUID disguisedId)
    {
        return disguisedId == null ? null : RESOLVED.get(disguisedId);
    }

    /** Forget one player's disguise skin (their disguise ended or changed to a target without one). */
    public static void forget(UUID disguisedId)
    {
        if (disguisedId == null)
            return;
        RESOLVED.remove(disguisedId);
        REQUESTED.remove(disguisedId);
    }

    public static void reset()
    {
        RESOLVED.clear();
        REQUESTED.clear();
    }
}

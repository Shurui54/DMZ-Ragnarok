package net.shurui.shuruisutilities.cosmetics.wardrobe;

/**
 * Which files the server-streamed cosmetic art pack may carry, in ONE place, because two sides must agree on it: the
 * Ragnarok Key, which builds the pack from its own jar ({@code ragnarokkey/cosmetic_assets/}) and refuses anything
 * else, and the client pack ({@code CosmeticPackResources}), which drops anything else a zip names.
 *
 * <h2>What the pack is</h2>
 * The art of every NON-Patreon wardrobe cosmetic (item models and their textures, the worn variants, the pet, mount
 * and triggered-animation GeckoLib rigs with their textures and animations, the animation player tracks, and the
 * cosmetic sounds) is not in any client jar. The key streams it to each client on join
 * ({@code PacketCosmeticAssets}, id 150), exactly as the rank badges are streamed, and the client serves it from
 * memory at the SAME {@code dmz_ragnarok:} resource locations the jar used, so renderers, item ids and the catalogue
 * need no change. A keyless server streams nothing, which is right: every one of these cosmetics is private.
 *
 * <h2>Patreon cosmetics are the exception, and stay in the core jar</h2>
 * A Patreon cosmetic ({@link CosmeticDef#patreonTier}) is PUBLIC: it must render on keyless servers, which have no
 * key to stream anything. Its art therefore ships bundled in core under {@code assets/dmz_ragnarok/} and must NOT be
 * placed under the key's {@code cosmetic_assets/} (the key logs a warning at boot for any Patreon definition whose
 * item model it finds in the stream). Form cosmetics are a different system and were never part of this.
 *
 * <h2>Why a prefix allow-list</h2>
 * A zip is an untrusted input on the client, and the pack sits at the top of the resource stack: an entry named
 * {@code textures/gui/...} or {@code ../} must never be able to shadow a file a jar owns. Every prefix here is one the
 * suite reserves for wardrobe cosmetics. A new cosmetic set whose item ids do not start with {@code hw_} or
 * {@code fx_} needs its prefix added here (both sides read this list, so one edit covers both).
 */
public final class CosmeticAssetPaths
{
    private CosmeticAssetPaths() {}

    /** Namespace-relative (under {@code dmz_ragnarok}) path prefixes the stream may carry. */
    public static final String[] ALLOWED_PREFIXES = {
            "models/item/hw_",
            "models/item/fx_",
            "models/item/cosmetic_worn/",
            "textures/item/cosmetic/",
            "geo/entity/cosmetic_mount/",
            "geo/entity/cosmetic_pet/",
            "geo/fx/cosmetic_anim/",
            "textures/entity/cosmetic_mount/",
            "textures/entity/cosmetic_pet/",
            "textures/fx/cosmetic_anim/",
            "animations/entity/cosmetic_mount/",
            "animations/entity/cosmetic_pet/",
            "animations/fx/cosmetic_anim/",
            "fx/cosmetic_anim/",
            "sounds/cosmetic/",
    };

    /** Whether a namespace-relative path may be carried by the cosmetic art stream. */
    public static boolean allowed(String path)
    {
        if (path == null || path.isEmpty() || path.contains("..") || path.startsWith("/") || path.contains("\\"))
            return false;
        for (String prefix : ALLOWED_PREFIXES)
        {
            if (path.startsWith(prefix))
                return true;
        }
        return false;
    }

    /** The item model path an item id's inventory model lives at, e.g. {@code models/item/hw_bat_hat.json}. */
    public static String itemModelPath(String itemPath)
    {
        return "models/item/" + itemPath + ".json";
    }
}

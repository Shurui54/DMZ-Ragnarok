package net.shurui.dev.sdu.compat.dmz;

import net.shurui.dev.sdu.DmzNpc;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Clears DragonMineZ's client-side model-resolution caches.
 *
 * <p>{@code com.dragonminez.client.model.DMZPlayerModel} memoises which {@link net.minecraft.resources.ResourceLocation}
 * a player's state resolves to ({@code MODEL_RESOLUTION_CACHE}, state-string -> geo) and caches file-existence
 * probes ({@code FILE_EXISTS_CACHE}, location -> boolean). Neither key includes resource-pack state and DMZ
 * never clears them on reload, so a custom race/form geo delivered by a pack enabled <em>after</em> the first
 * render stays stuck on the human fallback for the session, and a race edit changing {@code customModel} needs
 * a relog.
 *
 * <p>We clear both at the two moments resolution can go stale: a client resource reload (pack toggle, F3+T)
 * and after DMZ applies a synced config (how an sdu race edit lands). GeckoLib re-bakes its own caches on
 * reload, so only DMZ's resolution layer needs clearing.
 *
 * <p>DMZ classes keep their names in production, so plain reflection (no mixin/config churn). Every failure is
 * swallowed with a one-line warn: a stale model must never crash the client. Reflected members are cached
 * after the first lookup.
 */
public final class DmzModelCache {

    private static final String MODEL_CLASS = "com.dragonminez.client.model.DMZPlayerModel";

    private static boolean resolved;
    private static boolean unavailable;
    private static Field modelResolutionCache;
    private static Field fileExistsCache;

    private DmzModelCache() {
    }

    /** Clear DMZ's model-resolution + file-existence caches so custom geos re-resolve. Never throws. */
    public static void clear() {
        if (unavailable) {
            return;
        }
        try {
            resolve();
            clearMap(modelResolutionCache);
            clearMap(fileExistsCache);
        } catch (Throwable t) {
            unavailable = true;
            DmzNpc.LOGGER.warn("[{}] Could not clear DMZ model-resolution cache ({}); custom race models may need a relog.",
                    DmzNpc.MODID, t.toString());
        }
    }

    private static void resolve() throws ReflectiveOperationException {
        if (resolved) {
            return;
        }
        Class<?> cls = Class.forName(MODEL_CLASS);
        modelResolutionCache = cls.getDeclaredField("MODEL_RESOLUTION_CACHE");
        modelResolutionCache.setAccessible(true);
        fileExistsCache = cls.getDeclaredField("FILE_EXISTS_CACHE");
        fileExistsCache.setAccessible(true);
        resolved = true;
    }

    private static void clearMap(Field field) throws IllegalAccessException {
        if (field == null) {
            return;
        }
        Object value = field.get(null);
        if (value instanceof Map<?, ?> map) {
            map.clear();
        }
    }
}

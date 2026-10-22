package net.shurui.dev.sdu.compat.dmz;

import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.hair.HairCodec;
import com.dragonminez.common.hair.HairStyleSlot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoises DragonMineZ's per-form <em>forced hair code</em> decodes for the {@code DMZHairLayer} render path.
 *
 * <p>{@code renderHair} runs every frame per visible player and decodes a form's {@code forcedHairCode} anew
 * each time: a BigInteger base62 decode of a multi-thousand-char string + zlib inflate + NBT parse +
 * {@link CustomHair} allocations, with no cache (DMZ caches only preset hairs). Uncached this collapses FPS
 * (421&rarr;35). We decode each code once and cache the slots keyed by the code.
 *
 * <p>A full-set code (Base+SSJ+SSJ2+SSJ3) decodes to a length&ge;4 array {@code [base, ssj, ssj2, ssj3]}; a
 * single-slot code to a length-1 array. Decode failures cache the {@link #EMPTY} sentinel. Cleared on client
 * resource reload ({@code ClientModBusEvents}) and after a config sync ({@code ConfigSyncSeedMixin}), so edited
 * codes take effect without a relog.
 *
 * <p>Plain (non-mixin) helper: a mixin must never be classloaded by regular code, so the cache/decode logic
 * lives here and {@code DMZHairLayerForcedCodeMixin} only delegates. Free of client-only Minecraft imports
 * (touches only DMZ's {@link HairCodec}/{@link CustomHair}), matching {@link DmzModelCache}.
 */
public final class HairCodeCache {

    /** Sentinel cached when a code fails to decode, so a bad code doesn't re-decode every frame. */
    public static final CustomHair[] EMPTY = new CustomHair[0];

    /** Memoized decoded slots per forced-hair code string. Single-slot codes cache as a length-1 array. */
    private static final Map<String, CustomHair[]> CACHE = new ConcurrentHashMap<>();

    /** Modest guard: clear on overflow rather than grow unbounded (codes are large and rarely numerous). */
    private static final int MAX_ENTRIES = 64;

    private HairCodeCache() {
    }

    /** Clears the forced-code cache. Called where sdu already clears {@code DmzModelCache} (reload / config sync). */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * DragonMineZ 2.2 decodes a full set to a slot map (and accepts the 2.1 DMZF1 codes); callers here index the old
     * [base, ssj, ssj2, ssj3] array, so the map is flattened in that order. Null when any of the four is missing.
     */
    public static CustomHair[] fullSet(java.util.EnumMap<HairStyleSlot, CustomHair> set) {
        if (set == null) {
            return null;
        }
        CustomHair[] out = { set.get(HairStyleSlot.BASE), set.get(HairStyleSlot.SSJ), set.get(HairStyleSlot.SSJ2),
                set.get(HairStyleSlot.SSJ3) };
        for (CustomHair h : out) {
            if (h == null) {
                return null;
            }
        }
        return out;
    }

    /** Decode a forced code once and memoize. Full-set codes -> 4 slots; single -> 1 slot; failures -> empty sentinel. */
    public static CustomHair[] decode(String code) {
        if (code == null || code.isBlank()) {
            return EMPTY;
        }
        CustomHair[] cached = CACHE.get(code);
        if (cached != null) {
            return cached;
        }
        CustomHair[] decoded = EMPTY;
        try {
            if (HairCodec.isFullSetCode(code)) {
                // Reference: sdu FormPreview.applyHairCode, a full set decodes to [base, ssj, ssj2, ssj3].
                CustomHair[] set = fullSet(HairCodec.fromFullSetCode(code));
                if (set != null) {
                    decoded = set;
                }
            } else {
                CustomHair single = HairCodec.fromCode(code);
                if (single != null && !single.isEmpty()) {
                    decoded = new CustomHair[] { single };
                }
            }
        } catch (Throwable t) {
            decoded = EMPTY; // cache the sentinel so the bad code isn't re-decoded per frame
        }
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(code, decoded);
        return decoded;
    }

    /** Maps DMZ's hairType string to a full-set slot index (mirrors {@code DMZHairLayer.resolveHairType}). */
    public static int slotForType(String type) {
        if (type == null) {
            return 0;
        }
        return switch (type.toLowerCase()) {
            case "ssj" -> 1;
            case "ssj2" -> 2;
            case "ssj3" -> 3;
            default -> 0; // "base" and anything undefined -> Base slot
        };
    }
}

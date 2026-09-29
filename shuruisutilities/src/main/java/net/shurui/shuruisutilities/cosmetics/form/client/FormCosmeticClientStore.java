package net.shurui.shuruisutilities.cosmetics.form.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.dragonminez.common.config.FormConfig;

import net.shurui.shuruisutilities.cosmetics.form.FormCosmetic;
import net.shurui.shuruisutilities.cosmetics.form.network.PacketFormCosmeticSync;

/**
 * Client-side table of every player's form cosmetic override, fed by {@link PacketFormCosmeticSync} and read by the
 * render mixin ({@code MixinDmzCharacterFormCosmetic}) to override a form's appearance for the player being drawn.
 *
 * <p>{@link #derive} builds a MODIFIED COPY of a form's {@code FormData}: it copies every field of the real config
 * verbatim (so all stats, drains, mastery, model scaling and custom model are byte-identical) and then replaces
 * ONLY the appearance colour/outline fields the override sets. Stats therefore cannot change, and a channel the
 * override leaves blank keeps the form's own value. Derived copies are cached per player and rebuilt only when the
 * override changes or the base config instance changes (a config reload), so this is cheap per frame.
 *
 * <p>Client only: it imports DMZ's {@code FormConfig.FormData}, and is only ever reached from the client packet
 * handler (via DistExecutor) and the client-side mixin.
 */
public final class FormCosmeticClientStore
{
    private FormCosmeticClientStore() {}

    private static final Map<UUID, FormCosmetic> OVERRIDES = new ConcurrentHashMap<>();

    // bumped on every table change so cached derivations invalidate without tracking per-field diffs
    private static volatile long version = 0L;

    // The player currently being RENDERED, set by the player-renderer mixin for the duration of one render call.
    // Character.getActiveFormData has no owner reference, so this scopes "whose override applies" to the entity the
    // renderer is drawing right now. Null outside a player render, so non-render callers of getActiveFormData (HUD,
    // logic) are never touched.
    private static final ThreadLocal<UUID> RENDER_TARGET = new ThreadLocal<>();

    public static void setRenderTarget(UUID uuid)
    {
        RENDER_TARGET.set(uuid);
    }

    public static void clearRenderTarget()
    {
        RENDER_TARGET.remove();
    }

    public static UUID currentRenderTarget()
    {
        return RENDER_TARGET.get();
    }

    // cached derived FormData per player, tied to the base instance and table version it was built from
    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    private static final class Cached
    {
        final FormConfig.FormData base;   // identity of the config form this was derived from
        final long version;               // table version at build time
        final FormConfig.FormData result; // the overridden copy (or null: no override applies)

        Cached(FormConfig.FormData base, long version, FormConfig.FormData result)
        {
            this.base = base;
            this.version = version;
            this.result = result;
        }
    }

    /** Apply a sync packet: replace the whole table (full) or merge/remove single entries. */
    public static void apply(PacketFormCosmeticSync p)
    {
        if (p == null)
            return;
        if (p.full)
            OVERRIDES.clear();
        for (int i = 0; i < p.ids.size(); i++)
        {
            UUID id = p.ids.get(i);
            boolean present = i < p.present.size() && p.present.get(i);
            if (present && i < p.cosmetics.size())
                OVERRIDES.put(id, p.cosmetics.get(i).copy().sanitize());
            else
                OVERRIDES.remove(id);
        }
        version++;
        CACHE.clear();
    }

    /** The raw override for a player, or null. */
    public static FormCosmetic get(UUID uuid)
    {
        return uuid == null ? null : OVERRIDES.get(uuid);
    }

    /**
     * The appearance-overridden copy of {@code base} for the given player and active (group, form), or {@code null}
     * when no override applies (the caller then uses the stock form untouched). Never throws: any failure returns
     * null so rendering degrades to the stock look.
     */
    public static FormConfig.FormData derive(UUID uuid, String activeGroup, String activeForm, FormConfig.FormData base)
    {
        if (uuid == null || base == null)
            return null;
        FormCosmetic ov = OVERRIDES.get(uuid);
        if (ov == null || !ov.matches(activeGroup, activeForm) || !ov.hasAnyOverride())
            return null;
        Cached c = CACHE.get(uuid);
        if (c != null && c.base == base && c.version == version)
            return c.result;
        FormConfig.FormData result = null;
        try
        {
            result = build(base, ov);
        }
        catch (Throwable t)
        {
            result = null; // degrade to stock look on any reflection/DMZ change
        }
        CACHE.put(uuid, new Cached(base, version, result));
        return result;
    }

    /**
     * Public entry point for building an overridden copy from an ARBITRARY override, used by the editor to preview
     * unsaved edits. Deliberately the same code the real render path uses: a separate "preview" implementation would
     * drift from what players actually see, which is the one thing a preview must not do. Returns null on any
     * failure, meaning "show the stock form".
     */
    public static FormConfig.FormData deriveFor(FormConfig.FormData base, FormCosmetic ov)
    {
        if (base == null || ov == null || !ov.hasAnyOverride())
            return null;
        try
        {
            return build(base, ov);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // build an overridden copy: verbatim field copy of the config form, then appearance-only replacements
    private static FormConfig.FormData build(FormConfig.FormData base, FormCosmetic ov) throws Exception
    {
        FormConfig.FormData clone = new FormConfig.FormData();
        // copy every non-static, non-transient field so all stats/scaling/model are identical; transient rgb caches
        // are intentionally NOT copied so they recompute from the (possibly overridden) hex strings.
        for (Field f : FormConfig.FormData.class.getDeclaredFields())
        {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod))
                continue;
            f.setAccessible(true);
            f.set(clone, f.get(base));
        }
        // appearance overrides (empty string = keep the form's own value)
        if (!ov.bodyColor1.isEmpty())
            clone.setBodyColor1(ov.bodyColor1);
        if (!ov.bodyColor2.isEmpty())
            clone.setBodyColor2(ov.bodyColor2);
        if (!ov.bodyColor3.isEmpty())
            clone.setBodyColor3(ov.bodyColor3);
        if (!ov.hairColor.isEmpty())
            clone.setHairColor(ov.hairColor);
        if (!ov.eye1Color.isEmpty())
            clone.setEye1Color(ov.eye1Color);
        if (!ov.eye2Color.isEmpty())
            clone.setEye2Color(ov.eye2Color);
        if (!ov.auraColor.isEmpty())
            clone.setAuraColor(ov.auraColor);
        if (!ov.extraFormColor.isEmpty())
            clone.setExtraFormColor(ov.extraFormColor);
        if (!ov.tintColor.isEmpty() && ov.tintIntensity > 0.0)
        {
            clone.setTintColor(ov.tintColor);
            clone.setTintIntensity(ov.tintIntensity);
        }
        // id-string channels. Empty keeps the form's own value; anything set was validated against DMZ's own
        // configs server-side before it was stored, so these always name something DMZ can resolve.
        if (!ov.hairType.isEmpty())
            clone.setHairType(ov.hairType);
        if (!ov.hairCode.isEmpty())
            clone.setForcedHairCode(ov.hairCode);
        if (!ov.customModel.isEmpty())
            clone.setCustomModel(ov.customModel);
        if (!ov.extraFormLayer.isEmpty())
            clone.setExtraFormLayer(ov.extraFormLayer);
        if (!ov.auraType.isEmpty())
            clone.setAuraType(ov.auraType);
        if (ov.outlineEnabled)
        {
            FormConfig.FormData.OutlineShaderConfig o = new FormConfig.FormData.OutlineShaderConfig();
            o.setEnabled(Boolean.TRUE);
            if (!ov.outlinePrimary.isEmpty())
                o.setPrimaryColor(ov.outlinePrimary);
            if (!ov.outlineSecondary.isEmpty())
                o.setSecondaryColor(ov.outlineSecondary);
            o.setOutlineThickness(ov.outlineThickness);
            clone.setOutlineShader(o);
        }
        // null the transient rgb caches so overridden colours are recomputed from the new hex on first read
        clearRgbCaches(clone);
        return clone;
    }

    private static void clearRgbCaches(FormConfig.FormData clone)
    {
        clone.setRgbBodyColor1(null);
        clone.setRgbBodyColor2(null);
        clone.setRgbBodyColor3(null);
        clone.setRgbHairColor(null);
        clone.setRgbEye1Color(null);
        clone.setRgbEye2Color(null);
        clone.setRgbAuraColor(null);
        clone.setRgbExtraFormColor(null);
        clone.setRgbTintColor(null);
    }
}

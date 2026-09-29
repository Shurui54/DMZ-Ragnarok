package net.shurui.dev.sdu.form;

import com.google.gson.JsonObject;

/**
 * Presentation metadata for a custom form type. Custom types ship no icon of their own, so
 * {@link #iconBase} borrows one of DMZ's six stock icons or the radial/skills screen shows a
 * missing texture. {@link #tintArgb} is packed {@code 0xRRGGBB}, or {@code -1} for the form's
 * own aura colour (DMZ's default).
 */
public final class FormTypeMeta {

    /** DMZ's six stock icon names, valid for both {@code textures/gui/radial/} and {@code textures/gui/icons/}. */
    public static final java.util.List<String> STOCK_ICONS = java.util.List.of(
            "superforms", "godforms", "legendaryforms", "androidforms", "kaioken", "ultimate");

    /** Default stock icon used when a type has no explicit selection. */
    public static final String DEFAULT_ICON = "superforms";

    /** "Use the form's own aura colour" - DMZ's default tint. */
    public static final int USE_AURA_TINT = -1;

    public final String iconBase;
    public final int tintArgb;

    public FormTypeMeta(String iconBase, int tintArgb) {
        this.iconBase = normalizeIcon(iconBase);
        this.tintArgb = tintArgb < 0 ? USE_AURA_TINT : (tintArgb & 0xFFFFFF);
    }

    /** Coerce an arbitrary icon name to one of the six stock names, defaulting to {@link #DEFAULT_ICON}. */
    public static String normalizeIcon(String name) {
        if (name != null) {
            String t = name.trim().toLowerCase(java.util.Locale.ROOT);
            if (STOCK_ICONS.contains(t)) {
                return t;
            }
        }
        return DEFAULT_ICON;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("iconBase", iconBase);
        o.addProperty("tintArgb", tintArgb);
        return o;
    }

    public static FormTypeMeta fromJson(JsonObject o) {
        if (o == null) {
            return new FormTypeMeta(DEFAULT_ICON, USE_AURA_TINT);
        }
        String icon = o.has("iconBase") && o.get("iconBase").isJsonPrimitive() ? o.get("iconBase").getAsString() : DEFAULT_ICON;
        int tint = o.has("tintArgb") && o.get("tintArgb").isJsonPrimitive() ? o.get("tintArgb").getAsInt() : USE_AURA_TINT;
        return new FormTypeMeta(icon, tint);
    }
}

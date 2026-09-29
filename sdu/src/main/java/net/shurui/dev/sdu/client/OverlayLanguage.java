package net.shurui.dev.sdu.client;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.shurui.dev.sdu.lang.GeneratedNames;

import java.util.HashMap;
import java.util.Map;

// Wraps the active Language and overlays a live map of extra translations, so we can force custom race/form
// names to show immediately without a resource pack or client reload. overrides keys win; everything else
// delegates. The map is read live, so adding an entry updates the display at once. Re-applied after each
// resource/language reload (which replaces the injected instance).
//
// One exception to "overrides win": an override that is byte-identical to what the generator would have
// auto-derived from the key (a titlecased id, e.g. "Shadow Dragon 2star") is treated as a FALLBACK, not an
// edit. If the wrapped Language already has a real value for that key (e.g. a shipped bilingual lang entry),
// the real value wins so shipped translations are not clobbered by our auto-names. Genuine admin edits (any
// value that is not the auto-derived string) still override normally.
public class OverlayLanguage extends Language {

    final Language delegate;
    private final Map<String, String> overrides;
    // Per-key cache of "is this override just the auto-derived fallback?"; the override map is immutable
    // between reinjections, so a decision is stable for the life of this instance. A fresh OverlayLanguage is
    // built on every reinject/applySynced/load, so the cache is naturally rebuilt with the new overrides.
    private final Map<String, Boolean> autoCache = new HashMap<>();

    public OverlayLanguage(Language delegate, Map<String, String> overrides) {
        this.delegate = delegate;
        this.overrides = overrides;
    }

    // True when the override for key is merely the generator's auto-derived name AND the wrapped Language has a
    // real value to fall back to. In that case the override is noise and the delegate should win.
    private boolean overrideIsFallback(String key, String override) {
        Boolean cached = autoCache.get(key);
        if (cached != null) {
            return cached;
        }
        String auto = GeneratedNames.autoDerived(key);
        boolean fallback = auto != null && auto.equals(override) && delegate.has(key);
        autoCache.put(key, fallback);
        return fallback;
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        String v = overrides.get(key);
        if (v == null || overrideIsFallback(key, v)) {
            return delegate.getOrDefault(key, defaultValue);
        }
        // overrides keep raw '&' colour codes (so they stay editable in the vanilla EditBox); translate to
        // '§' here, the single choke point where DMZ's language lookup reads them
        return net.shurui.dev.sdu.util.ColorCodes.translate(v);
    }

    @Override
    public boolean has(String key) {
        String v = overrides.get(key);
        if (v != null && !overrideIsFallback(key, v)) {
            return true;
        }
        return delegate.has(key);
    }

    @Override
    public boolean isDefaultRightToLeft() {
        return delegate.isDefaultRightToLeft();
    }

    @Override
    public FormattedCharSequence getVisualOrder(FormattedText text) {
        return delegate.getVisualOrder(text);
    }
}

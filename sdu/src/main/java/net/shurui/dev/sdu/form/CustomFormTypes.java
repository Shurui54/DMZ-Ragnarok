package net.shurui.dev.sdu.form;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom (non-stock) form-type ids registered this run, lowercased. Source of truth for
 * {@code TransformationsHelperMixin} (common) and {@code RadialFormsMixin} (client), which fix DMZ's
 * substring routing:
 *
 * <ul>
 *   <li>{@code getSkillNameForType} maps any id containing {@code super}/{@code legendary}/{@code god}/
 *       {@code android} to the stock {@code *forms} skill, so custom {@code god_of_destruction} gates against
 *       stock {@code godforms} instead of its own. The mixin returns the raw id for anything in this set.</li>
 *   <li>{@code RadialForms} splits Super vs More by the same substring test; the client mixin routes
 *       anything in this set to More.</li>
 * </ul>
 *
 * <p>Populated both sides: server from {@link FormTypeManager}, client from the meta-sync apply path.
 * Stock ids always excluded so their routing stays intact.
 */
public final class CustomFormTypes {

    /** stock ids that must never be treated as custom (keeps their stock routing) */
    private static final Set<String> STOCK = Set.of(
            "superforms", "legendaryforms", "godforms", "androidforms", "kaioken", "ultimate");

    private static final Set<String> CUSTOM = ConcurrentHashMap.newKeySet();

    private CustomFormTypes() {
    }

    private static String norm(String type) {
        if (type == null) {
            return "";
        }
        String t = type.trim();
        if (t.isEmpty()) {
            return "";
        }
        // key on the same sanitized id FormTypeManager registers under, so a lookup on a group's raw
        // formType matches regardless of spacing/casing
        return net.shurui.dev.sdu.util.SduIds.sanitize(t).toLowerCase(Locale.ROOT);
    }

    /** true if {@code type} is a registered custom form type (case-insensitive). mixin hot path. */
    public static boolean contains(String type) {
        String t = norm(type);
        return !t.isEmpty() && CUSTOM.contains(t);
    }

    /** Register a single custom type; stock ids and blanks are ignored. */
    public static void register(String type) {
        String t = norm(type);
        if (!t.isEmpty() && !STOCK.contains(t)) {
            CUSTOM.add(t);
        }
    }

    /** Register every id in {@code types} (stock/blank filtered). */
    public static void registerAll(Iterable<String> types) {
        if (types != null) {
            for (String t : types) {
                register(t);
            }
        }
    }

    /** drop a custom type (e.g. on removal). stock ids are never in the set anyway. */
    public static void unregister(String type) {
        String t = norm(type);
        if (!t.isEmpty()) {
            CUSTOM.remove(t);
        }
    }

    /** Fold in the given form + stack skill lists (from DMZ's live skills config), filtering stock ids. */
    public static void seed(List<String> formSkills, List<String> stackSkills) {
        registerAll(formSkills);
        registerAll(stackSkills);
    }
}

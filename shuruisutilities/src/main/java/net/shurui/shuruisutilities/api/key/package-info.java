/**
 * Core-side hook holders for the shuruisutilities tree's PRIVATE features (the ones whose logic lives in the
 * Ragnarok Key mod, {@code dmz_ragnarok_key}).
 *
 * <p>This is the shuruisutilities-tree counterpart of {@code net.shurui.dev.sdu.api.key}: the buff MobEffects,
 * god roles / role energy, and the other SU private systems get their hook holders here in later batches. It
 * follows the SAME XHooks pattern documented on {@code net.shurui.dev.sdu.api.key} (an {@code Impl} interface whose
 * DEFAULTS are the keyless behaviour, a {@code volatile} impl field, {@code install()} that marks
 * {@link net.shurui.dev.sdu.api.KeyFeatures}, and lazy reads at the point of use).
 *
 * <p>It lives in the shuruisutilities tree because these features are SU's; the sdu-tree holders stay in
 * {@code net.shurui.dev.sdu.api.key} so the "sdu imports nothing from shuruisutilities" invariant is preserved
 * (the key mod, and only it, installs into both). Empty in K0 (scaffolding); the holders arrive with their features.
 */
package net.shurui.shuruisutilities.api.key;

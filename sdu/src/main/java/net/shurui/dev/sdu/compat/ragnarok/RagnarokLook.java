package net.shurui.dev.sdu.compat.ragnarok;

import java.util.function.BiConsumer;

import net.minecraft.world.entity.Entity;

/**
 * Dresses a spawned ragnarok NPC in a chosen character, without sdu knowing what a ragnarok NPC is.
 *
 * <h2>Why the indirection</h2>
 * The whole cast, the ninjin characters included, shares ONE registered entity type, and which character an
 * instance is comes from a field on the entity. Any editor that picks a spawn from the entity-type registry
 * therefore reaches exactly one of them and gets the default look, which is why sdu's spawn-carrying editors
 * (a saga KILL objective, a transform chain's next form) need a second field naming the character.
 *
 * <p>Applying it is Shurui's Utilities' job: it owns the entity, the model table and the texture defaults. The
 * dependency runs SU -&gt; sdu and never the reverse (that direction is what lets sdu ship without SU at all), so
 * this is the same shape as {@link net.shurui.dev.sdu.compat.cnpc.RagnarokModelCatalog}: sdu declares the hook,
 * SU installs the implementation at startup.
 *
 * <p>Nothing is required. With no applier installed every call is a no-op and the entity keeps whatever look it
 * spawned with, which is exactly the behaviour sdu had before this existed.
 */
public final class RagnarokLook {

    /** Installed by SU at startup; null until then, and null forever in an sdu-only install. */
    private static volatile BiConsumer<Entity, String> applier;

    private RagnarokLook() {
    }

    /** Install the implementation. Called by SU on both sides; a later call replaces an earlier one. */
    public static void setApplier(BiConsumer<Entity, String> impl) {
        applier = impl;
    }

    /** True when an implementation is installed, i.e. when a character picker has anything to offer. */
    public static boolean available() {
        return applier != null;
    }

    /**
     * Give {@code entity} the character named by {@code modelId}, if it can wear one.
     *
     * <p>Call it BEFORE the entity enters the world where you can: the character rides the entity's synched
     * data, so setting it first means the first packet clients receive already carries the right model. Set
     * afterwards it still works, but every viewer draws the default model for a tick.
     *
     * <p>A blank id means "leave the entity alone", so it is safe to call unconditionally on a spawn whose type
     * has nothing to do with the ragnarok cast.
     */
    /**
     * The one entity type the whole cast shares, and the separator that carries a character inside an entity
     * dropdown. Mirrors SU's {@code RgNpcPicker} (sdu cannot import it), and mirrored deliberately rather than
     * shared: {@code #} cannot appear in a resource location, so a synthetic option can never be mistaken for a
     * real entity id in either direction.
     */
    public static final String FIGHTER_ID = "dmz_ragnarok:rgnpc_fighter";
    public static final String RGNPC_ID = "dmz_ragnarok:rgnpc";
    public static final String SEP = "#";

    /**
     * {@code entityIds} with every published ragnarok character added as its own row, sorted.
     *
     * <p>The characters belong IN the entity list, not in a second control beside it: they all share one entity
     * type, so a list built from the registry offers the entire cast as a single "rgnpc" row that can only ever
     * spawn the default one. Returns the list unchanged when nothing has published a catalogue.
     */
    public static java.util.List<String> options(java.util.List<String> entityIds) {
        java.util.List<String> out =
                new java.util.ArrayList<>(entityIds == null ? java.util.List.<String>of() : entityIds);
        // The FIGHTER type, not the display one: a character picked in an editor is meant to be an opponent, and
        // the display NPC has no AI, no animation and nothing the stat fields can drive.
        for (String id : net.shurui.dev.sdu.compat.cnpc.RagnarokModelCatalog.availableIds()) {
            out.add(FIGHTER_ID + SEP + id);
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    /** The option representing an (entity, character) pair, for preselecting a dropdown. */
    public static String value(String entityTypeId, String modelId) {
        String entity = entityTypeId == null ? "" : entityTypeId.trim();
        String model = modelId == null ? "" : modelId.trim();
        boolean ragnarok = FIGHTER_ID.equals(entity) || RGNPC_ID.equals(entity);
        return model.isEmpty() || !ragnarok ? entity : FIGHTER_ID + SEP + model;
    }

    /** The entity type id an option names. A plain entity id is returned unchanged. */
    public static String entityOf(String option) {
        if (option == null) {
            return "";
        }
        int at = option.indexOf(SEP);
        return at < 0 ? option.trim() : option.substring(0, at).trim();
    }

    /** The character an option names, or blank when it names a plain entity type. */
    public static String modelOf(String option) {
        if (option == null) {
            return "";
        }
        int at = option.indexOf(SEP);
        return at < 0 ? "" : option.substring(at + SEP.length()).trim();
    }

    public static void apply(Entity entity, String modelId) {
        BiConsumer<Entity, String> impl = applier;
        if (impl == null || entity == null || modelId == null || modelId.isBlank()) {
            return;
        }
        try {
            impl.accept(entity, modelId.trim());
        } catch (Throwable t) {
            // A look is cosmetic. It must never stop a quest boss or a transformed form from spawning.
        }
    }
}

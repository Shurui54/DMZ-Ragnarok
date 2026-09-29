package net.shurui.shuruisutilities.ragnarok;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Publishes the rgnpc model table to sdu's Custom NPCs model editor, so the ragnarok characters can be picked
 * by name on a Custom NPC the same way {@code /su entity} picks one for an rgnpc.
 *
 * <p>The table stays owned here. sdu declares the shape it wants ({@code RagnarokModelCatalog.Entry}) and this
 * class fills it, because the dependency runs SU -&gt; sdu and never the other way; sdu has no business knowing
 * what an rgnpc is.
 *
 * <p>Each published entry carries the geo, its own texture and the baked collision box together. That grouping
 * is the point: a geo on its own leaves the NPC wearing a player skin over a UV map that is not a player skin's,
 * at a hitbox that has nothing to do with the model's height.
 *
 * <p>The key gate is handed over as a supplier rather than a snapshot, so the answer is read when the editor
 * opens rather than frozen at startup, and it reads the server's answer ({@link RgKeyClientState}) rather than
 * the client's own {@code KeyGate}, which cannot see a server-side key mod.
 */
public final class RgNpcCnpcCatalog {

    /** Where the shipped models live. Same layout {@code RgNpcModel} resolves at render time. */
    private static final String GEO_FMT = "dmz_ragnarok:geo/entity/ragnarok/%s.geo.json";
    private static final String TEX_FMT = "dmz_ragnarok:textures/entity/ragnarok/%s.png";

    /** The Gecko addon's own defaults, used when a geo has no baked box (it always should, but never assume). */
    private static final float FALLBACK_WIDTH = 0.7f;
    private static final float FALLBACK_HEIGHT = 2.0f;

    private RgNpcCnpcCatalog() {
    }

    /**
     * Build and publish the catalogue. Safe to call on either side and safe to call more than once (it replaces
     * rather than appends). Failure is swallowed: the editor then simply has no ragnarok picker.
     */
    public static void install() {
        try {
            List<net.shurui.dev.sdu.compat.cnpc.RagnarokModelCatalog.Entry> entries = new ArrayList<>();
            for (String id : RgNpcModels.ids()) {
                String geoId = RgNpcModels.geoId(id);
                float[] size = RgNpcModelSizes.sizeFor(geoId);
                entries.add(new net.shurui.dev.sdu.compat.cnpc.RagnarokModelCatalog.Entry(
                        id,
                        String.format(GEO_FMT, geoId),
                        String.format(TEX_FMT, RgNpcModels.defaultTexture(id)),
                        size != null ? size[0] : FALLBACK_WIDTH,
                        size != null ? size[1] : FALLBACK_HEIGHT,
                        RgNpcModels.isGated(id)));
            }
            net.shurui.dev.sdu.compat.cnpc.RagnarokModelCatalog.replaceAll(entries);
            net.shurui.dev.sdu.compat.cnpc.RagnarokModelCatalog.setGate(RgKeyClientState::unlocked);
            // The same table, reachable as an ACTION rather than a list: sdu's own spawn-carrying editors (a
            // saga KILL objective, a transform chain's next form) can name a character, and this is what turns
            // that name into the look on the spawned entity. Installed here so there is one publish point.
            net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.setApplier(RgNpcLook::apply);
            LoggingHandler.sulog.info("[shuruisutilities] Published {} ragnarok models to the Custom NPCs model editor.",
                    entries.size());
        } catch (Throwable t) {
            LoggingHandler.sulog.warn("[shuruisutilities] Could not publish the ragnarok models to the Custom NPCs editor: {}",
                    t.toString());
        }
    }
}

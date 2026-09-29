package net.shurui.shuruisutilities.guilds.client;

/**
 * A map screen that can say which chunk its cursor is over.
 *
 * <p>Implemented onto Xaero's world map by {@code MixinXaeroWorldMapGui}. It exists so the drag handler can be an
 * ordinary Forge screen-event listener that never names an Xaero type: it tests for this interface, so with Xaero
 * absent nothing implements it and the handler simply never fires.
 *
 * <p>The alternative was injecting into the map's own {@code mouseClicked}, and that cannot be done cleanly. Those
 * are a mod class's OVERRIDES of vanilla methods: named {@code mouseClicked} in a deobfuscated dev run and by their
 * SRG name in production, with no mapping the annotation processor can resolve because the owner is a mod class. A
 * screen event carries no such problem.
 */
public interface MapCursorChunk
{
    /** Chunk X under the cursor right now. */
    int su$cursorChunkX();

    /** Chunk Z under the cursor right now. */
    int su$cursorChunkZ();
}

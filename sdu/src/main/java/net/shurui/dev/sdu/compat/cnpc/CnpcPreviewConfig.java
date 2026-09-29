package net.shurui.dev.sdu.compat.cnpc;

/**
 * What the quest-GUI enemy preview needs to draw a saved Custom NPC clone like its real spawn: name plus
 * appearance ({@code modelGeo} + skin + hair). Resolved server-side from a {@code cnpc$tab$name} ref, synced
 * to clients ({@link net.shurui.dev.sdu.client.ClientPreviewClones}).
 */
public record CnpcPreviewConfig(String name, String modelGeo, int skinType, String skinValue,
                                String hairCode, String hairColor) {

    public static final CnpcPreviewConfig EMPTY = new CnpcPreviewConfig("", "", 0, "", "", "");

    public boolean isEmpty() {
        return (modelGeo == null || modelGeo.isBlank()) && (name == null || name.isBlank());
    }
}

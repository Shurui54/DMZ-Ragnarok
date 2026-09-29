package net.shurui.dev.sdu.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side cache of the saved Custom NPCs clones, synced from the server (which reads Custom NPCs'
 * {@code ServerCloneController}). Each entry is a {@code "tab$name"} token; the saga/quest editor's "Saved NPC"
 * picker lists these so an objective can spawn an {@code sdu:dmz_fighter} configured from that clone.
 */
public final class ClientCloneList {

    private static List<String> clones = new ArrayList<>();

    private ClientCloneList() {
    }

    public static void replace(List<String> incoming) {
        clones = incoming == null ? new ArrayList<>() : new ArrayList<>(incoming);
    }

    /** Tokens {@code "tab$name"}, in server order. */
    public static List<String> tokens() {
        return new ArrayList<>(clones);
    }

    /** Human label for a {@code "tab$name"} token, e.g. {@code "Raditz (tab 0)"}. */
    public static String label(String token) {
        int sep = token.indexOf('$');
        if (sep < 0) {
            return token;
        }
        return token.substring(sep + 1) + " (tab " + token.substring(0, sep) + ")";
    }
}

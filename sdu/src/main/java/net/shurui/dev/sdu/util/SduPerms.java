package net.shurui.dev.sdu.util;

import net.minecraft.world.entity.player.Player;

/** Shared "can edit SDU content" permission gate used by the editor save/delete packets (op level 2). */
public final class SduPerms {

    private SduPerms() {
    }

    public static boolean canEdit(Player player) {
        return player != null && player.hasPermissions(2);
    }
}

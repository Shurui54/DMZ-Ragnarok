package net.shurui.dev.sdu.compat;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.registry.ModSounds;

/**
 * Plays the custom firing sound for the {@code biden_blast} ki technique. A normal class (reobf'd like the
 * rest of the mod) so the Minecraft calls remap correctly: the mixin only checks the technique id and
 * delegates here. Server-side; {@code Level.playSound(null, ...)} broadcasts to nearby clients, which resolve
 * {@code sdu:biden_blast} from our sounds.json.
 */
public final class BidenBlastSound {

    private BidenBlastSound() {
    }

    public static void play(AbstractKiProjectile projectile) {
        try {
            Level level = projectile.level();
            if (level == null || level.isClientSide) {
                return;
            }
            SoundEvent sound = ModSounds.BIDEN_BLAST.get();
            level.playSound(null, projectile.getX(), projectile.getY(), projectile.getZ(),
                    sound, SoundSource.PLAYERS, 2.0f, 1.0f);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not play biden_blast sound: {}", DmzNpc.MODID, t.toString());
        }
    }
}

package net.shurui.dev.shuruis_raid_bosses.compat.sdu;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.ModList;

import java.util.function.Consumer;

/**
 * Optional-dependency bridge to sdu's transform-chain editor. The boss's chain is stored on
 * {@link net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef} as raw {@link CompoundTag} NBT (sdu-agnostic);
 * when sdu is present this opens sdu's {@code TransformChainEditScreen}.
 *
 * <p>This outer class names NO sdu types and is safe to classload when sdu is absent: it only checks
 * {@link ModList#isLoaded(String)} and delegates to the inner {@link Impl} holder, the only class touching
 * {@code net.shurui.dev.sdu.*}, never classloaded unless sdu is present (the optional-dependency pattern).
 * Client-side only.
 */
public final class SduTransformCompat {

    private SduTransformCompat() {}

    /** sdu is now part of this same container, so the shared transform editor is always available. */
    public static boolean available() {
        return true;
    }

    /**
     * Open sdu's transform-chain editor seeded from {@code chainNbt}; {@code onSave} receives the edited chain
     * back as NBT when the user leaves the editor. No-op unless {@link #available()}.
     */
    public static void openEditor(Screen parent, CompoundTag chainNbt, Consumer<CompoundTag> onSave) {
        if (available()) {
            Impl.open(parent, chainNbt, onSave);
        }
    }

    /** The only class that names sdu types; never classloaded unless sdu is present. */
    private static final class Impl {
        static void open(Screen parent, CompoundTag chainNbt, Consumer<CompoundTag> onSave) {
            net.shurui.dev.sdu.transform.TransformChain c =
                    net.shurui.dev.sdu.transform.TransformChain.fromNbt(chainNbt);
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new net.shurui.dev.sdu.client.gui.transform.TransformChainEditScreen(
                            parent, c,
                            net.minecraft.network.chat.Component.translatable("compat.dmz_ragnarok.raid.npc.transform_title"),
                            () -> onSave.accept(c.toNbt())));
        }
    }
}

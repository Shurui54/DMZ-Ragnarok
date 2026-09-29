package net.shurui.dev.shuruis_dmz_dungeons.compat.sdu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

import java.util.function.Consumer;

// guarded bridge to sdu's transform editor + engine. the only sdu classes referenced live in openEditor,
// reached ONLY on the available() path, so with sdu absent nothing here classloads an sdu type. storing the
// chain elsewhere uses plain NBT (sdu_tf / dmz_quest_no_transform), so only this editor hook needs sdu.
public final class SduTransformCompat {

    private SduTransformCompat() {
    }

    // sdu is now part of this same container, so the shared transform editor is always available.
    public static boolean available() {
        return true;
    }

    // open sdu's TransformChainEditScreen, calling onSave with the edited chain NBT on close. only call when
    // available() is true. client-only.
    @OnlyIn(Dist.CLIENT)
    public static void openEditor(Screen parent, CompoundTag chainNbt, Consumer<CompoundTag> onSave) {
        net.shurui.dev.sdu.transform.TransformChain c =
                net.shurui.dev.sdu.transform.TransformChain.fromNbt(chainNbt);
        Minecraft.getInstance().setScreen(new net.shurui.dev.sdu.client.gui.transform.TransformChainEditScreen(
                parent, c, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.transformations"), () -> onSave.accept(c.toNbt())));
    }
}

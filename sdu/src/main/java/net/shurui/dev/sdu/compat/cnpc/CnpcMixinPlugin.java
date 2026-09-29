package net.shurui.dev.sdu.compat.cnpc;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Soft-gate for {@code sdu.cnpc.mixins.json}. The DMZ &times; Custom NPCs GUI mixins target {@code noppes.npcs.*},
 * which exist only with the CustomNPCs-Unofficial port installed. {@link #shouldApplyMixin} returns {@code false}
 * when that mod is absent, so the mixins skip and the game still loads without Custom NPCs (AC8).
 *
 * <p>{@link LoadingModList} not {@code ModList}: mixin configs are processed during early class transformation,
 * before {@code ModList} is populated, but the loading mod list is already available then.</p>
 */
public class CnpcMixinPlugin implements IMixinConfigPlugin {

    private boolean cnpcPresent;

    @Override
    public void onLoad(String mixinPackage) {
        cnpcPresent = LoadingModList.get() != null
                && LoadingModList.get().getModFileById("customnpcs") != null
                && LoadingModList.get().getModFileById("cnpcgeckoaddon") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return cnpcPresent;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

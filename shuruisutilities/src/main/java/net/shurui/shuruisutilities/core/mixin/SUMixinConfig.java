package net.shurui.shuruisutilities.core.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin config for SU hooks. Likely to be permanent until Forge gets its act together.
 *
 * <h2>Hybrid-server (Mohist/Arclight/Magma) guard</h2>
 * A Bukkit hybrid rewrites the vanilla networking class {@code ServerGamePacketListenerImpl} so it can fire
 * Bukkit events on packets. That rewrite changes the field and method shapes our mixins into that class expect,
 * so a {@code @Shadow} field like {@code chatSpamTickCount} is "not located" and the mixin crashes the server at
 * APPLY, before it can even start (observed on Mohist Forge, where {@code MixinServerPlayNetHandlerSpam} took the
 * whole server down). None of the four hooks into that class is load bearing (own-command spam exemption, custom
 * leave message, vanish and staff bits), so on a hybrid we SKIP them and let the server boot on vanilla
 * behaviour rather than not boot at all. Pure Forge is unaffected: Bukkit is absent there, so the guard is off.
 */
public class SUMixinConfig implements IMixinConfigPlugin
{

    protected static List<String> injectedPatches = new ArrayList<>();

    /**
     * Vanilla target classes a Bukkit hybrid rewrites; our mixins into these are skipped on a hybrid.
     *
     * <h2>{@code Commands}, added 2026-09-04</h2>
     * Wolf ran a hybrid build with {@code MixinCommands} and {@code MixinCommandsG} deleted from the mixin config
     * and reported it working properly where the stock jar did not. Diffing his jar against a stock 1.1.36 showed
     * those two config lines were the ONLY difference in the whole file: no class was added, removed or changed.
     * So the finding is real and narrow, and it belongs here rather than in the config, because deleting the lines
     * disables the mixins for EVERYONE and the servers this suite actually runs on are vanilla Forge.
     *
     * <p>Why these two are hybrid-fragile is consistent with the rule above. {@code MixinCommandsG} constructs a
     * fresh vanilla {@code CommandSourceStack} to raise the parse-time permission level, and a hybrid substitutes
     * its own subclass so it can carry the Bukkit sender; building the vanilla one by hand throws that away
     * mid-dispatch. {@code MixinCommands} rebuilds the whole client command tree from the dispatcher root, which
     * on a hybrid also holds Bukkit-registered commands.
     *
     * <p>THE COST ON A HYBRID IS REAL, and worth stating rather than discovering: without {@code MixinCommandsG},
     * command execution is gated by vanilla op level instead of SU permissions, so an SU grant alone will not let
     * a non-op run a gated command there. Without {@code MixinCommands}, tab completion falls back to vanilla
     * visibility and the legacy branded roots reappear in it. Both are worse than the mod working at all, which is
     * the trade Wolf's build was making implicitly.
     */
    private static final Set<String> HYBRID_FRAGILE_TARGETS =
            Set.of("net.minecraft.server.network.ServerGamePacketListenerImpl",
                    "net.minecraft.commands.Commands");

    /** True on a Bukkit hybrid (Mohist/Arclight/Magma), detected by Bukkit being on the classpath. */
    private static final boolean HYBRID = detectHybrid();

    private static boolean detectHybrid()
    {
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                SUMixinConfig.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders)
        {
            if (cl == null)
                continue;
            try
            {
                Class.forName("org.bukkit.Bukkit", false, cl);
                return true;
            }
            catch (Throwable ignored)
            {
                // not visible through this loader; try the next
            }
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage)
    {
        /* do nothing */
    }

    @Override
    public String getRefMapperConfig()
    {
        return null;
    }

    @Override
    public List<String> getMixins()
    {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets)
    {
        /* do nothing */
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName)
    {
        if (HYBRID && HYBRID_FRAGILE_TARGETS.contains(targetClassName))
        {
            // No logger here on purpose: this runs during mixin bootstrap, before SU's logging is up, and a
            // hybrid's rewritten target is exactly where a stray classload can deadlock. System.out is safe.
            System.out.println("[SU] Bukkit hybrid detected: skipping mixin " + mixinClassName
                    + " into " + targetClassName + " (its vanilla shape is rewritten by the hybrid).");
            return false;
        }
        return true;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
    {
        /* do nothing */
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
    {
        injectedPatches.add(mixinInfo.getName());
    }

    public static List<String> getInjectedPatches()
    {
        return injectedPatches;
    }

}

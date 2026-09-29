package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.spacepod.SpacePodDestinationDefinition;

import net.minecraftforge.fml.loading.FMLEnvironment;

import net.shurui.dev.sdu.api.key.CoreGateHooks;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Removes destinations that only exist on OUR live server from the space-pod list everywhere else.
 *
 * <h2>Why the list is filtered rather than the travel refused</h2>
 * The existing key gate on a destination (namekow / kaiow) refuses the TRIP and leaves the entry visible, which is
 * right for content a player might one day be given. The SMP world is different: it is one specific server's
 * survival world, so on any other install the entry is not locked content, it is a destination that does not exist.
 * Stripping it here, at the single point the datapack list is built, means it is absent from the pod menu, absent
 * from the client's synced copy, and absent from {@code PlanetRegistry}, so no planet is drawn for it and no course
 * can be set to it. One removal covers every consumer.
 *
 * <h2>The gate is deliberately stricter than the usual one</h2>
 * Every other key check in the suite passes in singleplayer and on LAN, because restrictions are only meant to bite
 * on a dedicated server. This one does not: the SMP planet is only correct where the SMP world actually is, so it
 * requires a DEDICATED server that is genuinely running Shurui's Key. In singleplayer, key or no key, the entry is
 * removed.
 *
 * <p>require = 0 per the standing rule for mixins into DMZ classes: if DMZ renames this method the injector
 * degrades to a no-op, and the worst case is an SMP entry showing where it should not rather than a crash.
 */
@Mixin(targets = "com.dragonminez.common.spacepod.SpacePodDestinationRegistry", remap = false)
public abstract class MixinDmzSpacePodDestinations
{

    /** Destination ids that exist only on a dedicated server running Shurui's Key. */
    private static final Set<String> SU_SERVER_ONLY_DESTINATIONS = Set.of("smp");

    @Shadow(remap = false)
    private static List<SpacePodDestinationDefinition> serverDestinations;

    /**
     * NOT static. {@code apply} is an instance method (the registry is a
     * {@code SimpleJsonResourceReloadListener}), and a static handler on a non-static target is an APPLY-phase
     * crash that {@code require = 0} does not soften: it fails the whole mod load with
     * "'static' modifier of handler method does not match target". The field it writes is static, which is fine to
     * touch from here.
     */
    @Inject(method = "apply", at = @At("TAIL"), require = 0, remap = false)
    private void su$stripServerOnlyDestinations(CallbackInfo ci)
    {
        if (su$serverOnlyAllowed())
        {
            return;
        }
        List<SpacePodDestinationDefinition> kept = new ArrayList<>();
        int removed = 0;
        for (SpacePodDestinationDefinition def : serverDestinations)
        {
            if (def != null && def.id() != null && SU_SERVER_ONLY_DESTINATIONS.contains(def.id()))
            {
                removed++;
                continue;
            }
            kept.add(def);
        }
        if (removed > 0)
        {
            serverDestinations = List.copyOf(kept);
            LoggingHandler.sulog.info("[SpacePod] Removed {} server-only destination(s) from the pod list:"
                    + " this is not a dedicated server running Shurui's Key.", removed);
        }
    }

    // The key half is CoreGateHooks, which only the Ragnarok Key installs (keyless default: false, stripped), so a jar
    // that merely carries the key's mod id never brings the SMP entry back.
    private static boolean su$serverOnlyAllowed()
    {
        return FMLEnvironment.dist.isDedicatedServer() && CoreGateHooks.get().serverOnlyPodDestinations();
    }
}

package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Makes resourcefullib's file discovery deterministic across machines.
 *
 * <h2>The symptom</h2>
 * On the live network, four of the eight Iron Chests: Restocked ("ironchests") chest types opened ANOTHER
 * type's GUI, but only on one server: obsidian opened a 1-slot dirt menu, diamond opened iron's 54-slot menu,
 * iron and dirt opened 12-wide 72-slot menus, while copper, gold, crystal and netherite happened to land
 * correctly. The jars were byte identical, every config file was byte identical, and the world's block and
 * block entity ids agreed at every position, so nothing that a human normally compares showed a difference.
 *
 * <h2>The cause</h2>
 * {@code tech.thatgravyboat.ironchests.common.chesttypes.ChestTypeLoader.setupChest()} loads its chest types by
 * walking a directory and registering each parsed file in the order the walk yields it, so REGISTRATION ORDER
 * IS FILE ORDER. resourcefullib's {@code FileUtils.streamFiles} does that walk with
 * {@link Files#walk(Path, FileVisitOption...)} and then {@code filter(...).forEach(...)} with NO intervening
 * sort. {@code Files.walk} returns entries in raw filesystem order, and on ext4 that order comes from a hashed
 * directory index whose seed is chosen PER FILESYSTEM. Two machines therefore enumerate the identical set of
 * files in different sequences, ironchests assigns each chest type to whichever menu type registered in the
 * matching slot, and the client and the affected server disagree about which menu a given chest opens. Because
 * the seed is per filesystem, this never reproduces in singleplayer (one filesystem, one order) and is
 * invisible to anyone diffing the config files (the files are identical, only their enumeration order differs).
 *
 * <h2>The fix</h2>
 * Redirect the {@code Files.walk} call inside {@code streamFiles} and append a deterministic sort before the
 * stream is consumed. We sort by the FULL PATH STRING using {@link String}'s natural ordering
 * ({@link Comparator#comparing} over {@link Path#toString}). Natural {@code String} ordering is
 * {@code String.compareTo}, a plain UTF-16 code-unit comparison, so it is locale independent: two machines
 * with different locales cannot disagree about the order (a {@code Collator} would have been locale sensitive
 * and is deliberately avoided). Full path is a safe total order here because the discovered files share an
 * identical parent prefix, so their relative order depends only on the file name portion and the path
 * separator that differs between operating systems appears only inside that equal, shared prefix. Both our
 * servers and our players' clients run this jar, so shipping the sort here makes both ends walk the files in
 * the same sequence and agree on the registration order again.
 *
 * <h2>Scope and side effects</h2>
 * {@code FileUtils} is shared by every resourcefullib consumer, not just ironchests. In this pack the only
 * caller of {@code streamFiles} is ironchests (chipped depends on resourcefullib but does not touch the file
 * walker). Sorting a discovery stream is purely order stabilising: any consumer that appeared to rely on the
 * previous order was in fact relying on a per-filesystem hash seed, which no correct code can depend on, so
 * this can only make behaviour more deterministic, never less. {@code sorted()} buffers the walk to order it,
 * but resourcefullib already consumed the whole stream eagerly with {@code forEach}, and these directories hold
 * a handful of files, so the extra buffering is negligible.
 *
 * <p>Targeted by name with {@code remap = false} and {@code require = 0}: with resourcefullib absent the class
 * is never found and the mixin is simply skipped, exactly like the Tinkers and Xaero compat mixins in this
 * config. No resourcefullib type is named here, so the mixin needs no compile dependency on it. The redirect
 * handler is static because {@code streamFiles} is static and the redirected call is a static invoke.</p>
 */
@Mixin(targets = "com.teamresourceful.resourcefullib.common.utils.FileUtils", remap = false)
public abstract class MixinResourcefulFileUtils {

    @Redirect(
            method = "streamFiles",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/nio/file/Files;walk(Ljava/nio/file/Path;[Ljava/nio/file/FileVisitOption;)Ljava/util/stream/Stream;"
            ),
            remap = false,
            require = 0
    )
    private static Stream<Path> su$sortWalk(Path source, FileVisitOption[] options) throws IOException {
        return Files.walk(source, options).sorted(Comparator.comparing(Path::toString));
    }
}

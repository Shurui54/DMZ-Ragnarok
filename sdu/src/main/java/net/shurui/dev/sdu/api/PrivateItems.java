package net.shurui.dev.sdu.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * The items that belong to a PRIVATE feature, so client listings (the creative tabs, the optional JEI plugin) can
 * leave them out when the connected server does not hold the key. This only decides what is LISTED: the items always
 * register on every side (registry counts must match), and whether one works is still the server's call
 * ({@code ContentGate} and each feature's own gate).
 *
 * <p>Each tree registers its own private items once, at class load of its creative-tab code, under either the
 * licence ({@code null}: {@link ClientGate#key()}) or a key feature id ({@link ClientGate#feature(String)}). Modules
 * register here rather than core naming their items, because core never references a module.
 *
 * <p>Plain by design, like {@link ClientGate}: no client-only imports, so a dedicated server that loads a
 * registering class is fine (the answers are simply never asked there).
 */
public final class PrivateItems {

    private PrivateItems() {
    }

    private static final class Entry {
        final String feature;
        final Supplier<? extends Iterable<? extends ItemLike>> source;
        volatile List<Item> resolved;

        Entry(String feature, Supplier<? extends Iterable<? extends ItemLike>> source) {
            this.feature = feature;
            this.source = source;
        }

        /** Resolved lazily: the registry objects are bound only after registration, long after this is built. */
        List<Item> items() {
            List<Item> local = resolved;
            if (local != null) {
                return local;
            }
            List<Item> out = new ArrayList<>();
            try {
                for (ItemLike like : source.get()) {
                    if (like != null) {
                        out.add(like.asItem());
                    }
                }
            } catch (Throwable t) {
                // An unbound registry object (asked too early) is retried next time rather than cached empty.
                return Collections.emptyList();
            }
            resolved = out;
            return out;
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    /**
     * Register a group of private items. {@code feature} is a key feature id, or {@code null} for the licence.
     * The supplier is read lazily and once (after the registries are bound).
     */
    public static void register(String feature, Supplier<? extends Iterable<? extends ItemLike>> items) {
        if (items != null) {
            ENTRIES.add(new Entry(feature, items));
        }
    }

    /** Whether the synced answer opens this gate ({@code null} = the licence). */
    public static boolean open(String feature) {
        return feature == null ? ClientGate.key() : ClientGate.feature(feature);
    }

    /** Whether this item must be left out of client listings right now. */
    public static boolean hidden(Item item) {
        if (item == null) {
            return false;
        }
        for (Entry e : ENTRIES) {
            if (!open(e.feature) && e.items().contains(item)) {
                return true;
            }
        }
        return false;
    }

    /** Every item currently hidden, as one stack each (the JEI plugin removes these). */
    public static List<ItemStack> hiddenStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (Entry e : ENTRIES) {
            if (open(e.feature)) {
                continue;
            }
            for (Item item : e.items()) {
                out.add(new ItemStack(item));
            }
        }
        return out;
    }

    /** Every registered private item, hidden or not, as one stack each. */
    public static List<ItemStack> allStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (Entry e : ENTRIES) {
            for (Item item : e.items()) {
                out.add(new ItemStack(item));
            }
        }
        return out;
    }

    /**
     * A creative-tab output that drops hidden items and passes everything else through unchanged. Wrap a tab's
     * output (or a {@code BuildCreativeModeTabContentsEvent}) with it; the display items are built on the client
     * after login, so the synced answer is already in.
     */
    public static CreativeModeTab.Output filtered(CreativeModeTab.Output out) {
        return (stack, visibility) -> {
            if (!hidden(stack.getItem())) {
                out.accept(stack, visibility);
            }
        };
    }
}

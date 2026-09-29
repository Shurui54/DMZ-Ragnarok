package net.shurui.dev.sdu.client.gui.radial;

import com.dragonminez.client.gui.radial.RadialLayoutStore;
import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.client.gui.radial.nodes.MoreNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Ports DMZ's private ring-finishing pipeline ({@code RadialForms.orderAndCap} / {@code capWithMore}) so
 * sdu's Extra-Forms rings order, cap and overflow <em>identically</em> to native rings and, crucially, share
 * DMZ's saved-order persistence.
 *
 * <p>DMZ's {@code RadialForms.orderAndCap}/{@code capWithMore} are {@code private}, but the store they read
 * from - {@link RadialLayoutStore} - is fully {@code public}. So we reuse the store verbatim (its
 * {@link RadialLayoutStore#applyOrder} keyed by a category string, ordering nodes by {@link RadialNode#orderKey})
 * and reproduce only the tiny cap/overflow shape:
 * <ul>
 *   <li>read: {@code RadialLayoutStore.applyOrder(categoryKey, nodes)} sorts the ring by the saved order for
 *       {@code categoryKey}, appending any not-yet-ordered nodes;</li>
 *   <li>cap: rings over 5 keep the first 4 and fold the whole (ordered) list into a {@link MoreNode} carrying
 *       the SAME {@code categoryKey} - which is exactly what {@code UtilityMenuScreen.reorderOption} writes
 *       back through ({@code RadialLayoutStore.setOrder(more.categoryKey(), keys)} + {@code save()}), so the
 *       in-wheel reorder UI round-trips against the same key/store.</li>
 * </ul>
 *
 * <p>Because the read key, the {@code MoreNode}'s key and the reorder-write key are the same string, using
 * DMZ's legacy {@code moreforms:<group>} keys here means any per-group form order a player already saved in
 * the old Extra-Forms wheel carries straight over.
 */
public final class RadialOrdering {

    /** DMZ's per-ring slot cap; the 5th slot becomes the {@link MoreNode} overflow. Matches RadialForms. */
    private static final int MAX_SLOTS = 5;

    private RadialOrdering() {
    }

    /** DMZ's {@code orderAndCap}: apply the saved order for {@code categoryKey}, then cap at 5 with overflow. */
    public static List<RadialNode> orderAndCap(String categoryKey, List<RadialNode> nodes) {
        List<RadialNode> ordered = new ArrayList<>(RadialLayoutStore.applyOrder(categoryKey, nodes));
        return capWithMore(categoryKey, ordered);
    }

    /** DMZ's {@code capWithMore}: >5 → first 4 + a {@link MoreNode}(categoryKey, allOrdered) in slot 5. */
    public static List<RadialNode> capWithMore(String categoryKey, List<RadialNode> all) {
        if (all.size() <= MAX_SLOTS) {
            return all;
        }
        List<RadialNode> head = new ArrayList<>(all.subList(0, MAX_SLOTS - 1));
        head.add(new MoreNode(categoryKey, new ArrayList<>(all)));
        return head;
    }

    /**
     * Carry a form type's saved radial order across a type-id rename in DMZ's shared
     * {@link RadialLayoutStore} (the {@code config/dragonminez/radial_layout.json} the reorder UI writes).
     * A form-type rename changes two order references:
     * <ul>
     *   <li>the per-type group-head ring is stored under category {@code moreforms:type:<type>}
     *       ({@link FormTypeNode#buildChildren}) - move the whole saved list from the old key to the new;</li>
     *   <li>each type node participates in the top-level type ring by its {@link FormTypeNode#orderKey}
     *       {@code formtype:<type>}, whose saved order lives under {@link SduExtraFormsCategory}'s
     *       {@code extraforms:types} category - rewrite that entry in place so the type keeps its slot.</li>
     * </ul>
     * Best-effort and non-fatal: on any failure the type simply loses its saved order (it re-appends).
     *
     * @return {@code true} if the store was touched and saved.
     */
    public static boolean renameFormTypeOrder(String oldType, String newType) {
        if (oldType == null || newType == null || oldType.equals(newType)) {
            return false;
        }
        boolean changed = false;
        try {
            // 1) Per-type group-head ring: moreforms:type:<old> -> moreforms:type:<new>.
            String oldGroupKey = "moreforms:type:" + oldType;
            String newGroupKey = "moreforms:type:" + newType;
            List<String> saved = RadialLayoutStore.getOrder(oldGroupKey);
            if (saved != null && !saved.isEmpty()) {
                RadialLayoutStore.setOrder(newGroupKey, new ArrayList<>(saved));
                RadialLayoutStore.setOrder(oldGroupKey, new ArrayList<>());
                changed = true;
            }

            // 2) Top-level type ring order (extraforms:types): entry formtype:<old> -> formtype:<new>.
            List<String> types = RadialLayoutStore.getOrder("extraforms:types");
            if (types != null && !types.isEmpty()) {
                String oldEntry = "formtype:" + oldType;
                String newEntry = "formtype:" + newType;
                List<String> out = new ArrayList<>(types.size());
                boolean hit = false;
                for (String e : types) {
                    if (oldEntry.equals(e)) {
                        out.add(newEntry);
                        hit = true;
                    } else {
                        out.add(e);
                    }
                }
                if (hit) {
                    RadialLayoutStore.setOrder("extraforms:types", out);
                    changed = true;
                }
            }

            if (changed) {
                RadialLayoutStore.save();
            }
        } catch (Throwable ignored) {
            // Store schema changed / IO issue: non-fatal, the type just re-appends to its ring.
        }
        return changed;
    }
}

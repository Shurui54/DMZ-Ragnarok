package net.shurui.dev.sdu.client.gui.radial;

import com.dragonminez.client.gui.radial.AbstractRadialNode;
import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.DmzAssets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One radial node per form <em>type</em> (god, kaioken, ultimate, plus the user's custom types such as
 * saiyangod / truegod / divinefrost) inside sdu's {@link SduExtraFormsCategory}. This is the intermediate
 * "type" tier the base game's radial lacks: DMZ's own "Extra Forms" wheel lists every non-super group
 * flat, whereas here each type is a pure expandable category that opens into that type's unlocked groups.
 *
 * <p>This is a <b>locked-preview</b> wheel: children are built from <em>every</em> form of each group (not
 * just the unlocked ones), each wrapped in an sdu {@link PreviewFormSelectNode}/{@link PreviewFormGroupHeadNode}
 * that is always {@code visible} but only {@code interactive} while the form is currently selectable
 * (unlocked). So a locked form/group shows greyed and non-clickable with its DMZ model preview intact, while
 * an unlocked one selects exactly as before - firing the normal {@code SelectFormC2S}, which the server
 * re-validates, so no unowned form can ever be entered from here.
 *
 * <p>Ordering: both this type's group-head ring and each group's inner form ring go through
 * {@link RadialOrdering} (DMZ's {@link com.dragonminez.client.gui.radial.RadialLayoutStore}), so they order,
 * cap and persist exactly like native rings. The inner form ring reuses DMZ's <em>legacy</em> category key
 * {@code moreforms:<group>}, so any per-group form order a player saved in the old Extra-Forms wheel carries
 * straight over.
 */
public final class FormTypeNode extends AbstractRadialNode {

    private final String race;
    /** Lowercased form-type id (the bucket key), e.g. {@code god}, {@code kaioken}, {@code truegod}. */
    private final String type;
    /** Group names of this type for the race, in stable insertion order (from getAllFormsForRace). */
    private final List<String> groups;

    public FormTypeNode(String race, String type, List<String> groups) {
        this.race = race;
        this.type = type == null ? "" : type.toLowerCase(Locale.ROOT);
        this.groups = groups != null ? new ArrayList<>(groups) : new ArrayList<>();
    }

    @Override
    public Component label(StatsData stats) {
        return Component.literal(prettifyType(this.type));
    }

    @Override
    public ResourceLocation icon(StatsData stats) {
        // Reuse DMZ's type->icon mapping; the sdu AbstractRadialNodeMixin further swaps in the admin-chosen
        // stock icon for registered custom types at the same call site, so custom types render a real image.
        return iconForFormType(this.type);
    }

    @Override
    public int iconTint(StatsData stats) {
        // Admin-configured tint for this custom type, if any (-1 = leave DMZ's default alone).
        return DmzAssets.formTypeTint(this.type);
    }

    /** Pure category: never a transform target itself, only an expander. */
    @Override
    public boolean interactive(StatsData stats) {
        return false;
    }

    /**
     * Stable order key so this type node participates in the top-level type ring's saved order via DMZ's
     * reorder UI ({@code UtilityMenuScreen.reorderOption} skips a ring the moment any node's orderKey is
     * empty, so an explicit key here is what makes the type ring persistable).
     */
    @Override
    public String orderKey() {
        return "formtype:" + this.type;
    }

    /**
     * Preview mode: show this type whenever the race has any group of it at all, even with nothing unlocked
     * (children are now always-visible preview nodes, so this is equivalent to "has a resolvable group").
     */
    @Override
    public boolean visible(StatsData stats) {
        for (RadialNode child : this.children(stats)) {
            if (child.visible(stats)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected List<RadialNode> buildChildren(StatsData stats) {
        List<RadialNode> heads = new ArrayList<>();
        for (String group : this.groups) {
            FormConfig config = ConfigManager.getFormGroup(this.race, group);
            boolean isStack = false;
            if (config == null || config.getForms() == null || config.getForms().isEmpty()) {
                // Stack-form groups (kaioken, ultimate, ...) aren't per-race, so getFormGroup returns null
                // for them; resolve them from DMZ's disjoint stack-form map instead. A group resolved this
                // way is a stack group, so downstream selectability/selection must use the stack path.
                FormConfig stack = ConfigManager.getStackFormGroup(group);
                if (stack != null) {
                    config = stack;
                    isStack = true;
                }
            }
            if (config == null) {
                continue;
            }
            // Preview mode: enumerate EVERY form of the group (config order), not just the unlocked ones, so
            // locked forms still appear as greyed previews. Selectability is re-tested per node at
            // interactive()-time by PreviewFormSelectNode/PreviewFormGroupHeadNode. We deliberately do NOT gate
            // on isSkillAllowedForRace here: a type whose skill can't (yet) exist should still preview as
            // permanently locked rather than vanish.
            List<String> formNames = new ArrayList<>();
            for (FormConfig.FormData d : config.getForms().values()) {
                if (d != null && d.getName() != null && !d.getName().isEmpty()) {
                    formNames.add(d.getName());
                }
            }
            RadialNode head = buildGroupHead(group, formNames, isStack);
            if (head != null) {
                heads.add(head);
            }
        }
        // This type's group-head ring is new (grouping by type didn't exist before), so it gets its own key.
        return RadialOrdering.orderAndCap("moreforms:type:" + this.type, heads);
    }

    /**
     * Like DMZ's {@code RadialForms.buildGroupHead}, but over <em>all</em> forms of the group and using sdu's
     * preview nodes: the first form is a {@link PreviewFormGroupHeadNode} (selectable head + expander), the
     * rest are {@link PreviewFormSelectNode}s. Both are always visible and only interactive when unlocked. The
     * inner form ring is ordered/capped through {@link RadialOrdering} under DMZ's <em>legacy</em>
     * {@code moreforms:<group>} key, so a per-group form order saved in the old Extra-Forms wheel round-trips
     * unchanged (newly-shown locked forms simply append after any saved keys).
     */
    private RadialNode buildGroupHead(String group, List<String> formNames, boolean isStack) {
        if (formNames == null || formNames.isEmpty()) {
            return null;
        }
        List<RadialNode> rest = new ArrayList<>();
        for (int i = 1; i < formNames.size(); i++) {
            rest.add(new PreviewFormSelectNode(this.race, group, formNames.get(i), isStack));
        }
        rest = RadialOrdering.orderAndCap("moreforms:" + group, rest);
        return new PreviewFormGroupHeadNode(this.race, group, formNames.get(0), isStack, rest);
    }

    /** Human label for a type id: prefer a DMZ translatable, else title-case the sanitized id. */
    static String prettifyType(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        Component tr = Component.translatable("gui.dragonminez.formtype." + type);
        String s = tr.getString();
        if (!s.equals("gui.dragonminez.formtype." + type)) {
            return s;
        }
        String[] parts = type.replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.toString();
    }
}

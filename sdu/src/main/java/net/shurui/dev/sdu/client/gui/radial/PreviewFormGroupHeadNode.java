package net.shurui.dev.sdu.client.gui.radial;

import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.client.gui.radial.nodes.FormGroupHeadNode;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.util.TransformationsHelper;

import java.util.List;

/**
 * The locked-preview variant of DMZ's {@link FormGroupHeadNode}: the first form of a group, which is both a
 * selectable leaf <em>and</em> the expander into the group's remaining forms. It is always visible, and is
 * interactive (transformable) only while the head form is currently selectable (unlocked); otherwise it is a
 * greyed, non-clickable preview that still expands into the group's other (preview) forms.
 *
 * <p>DMZ's {@code FormGroupHeadNode.buildChildren} returns the {@code rest} list captured in its constructor,
 * so passing {@code rest} up preserves the group's inner ring untouched. {@code orderKey} ({@code group:<group>})
 * and the inherited icon/tint/label/preview/onSelect all come straight from DMZ, so ordering, persistence,
 * icons and model previews behave exactly as the native head - only visibility and interactivity change.
 */
public final class PreviewFormGroupHeadNode extends FormGroupHeadNode {

    private final String group;
    private final String firstForm;
    private final boolean stack;

    public PreviewFormGroupHeadNode(String race, String group, String firstForm, boolean stack, List<RadialNode> rest) {
        super(race, group, firstForm, stack, rest);
        // The super's group/form are private; keep our own copies for the interactive() re-test.
        this.group = group;
        this.firstForm = firstForm;
        this.stack = stack;
    }

    /** Always show in the wheel so a fully-locked group still appears as a preview. */
    @Override
    public boolean visible(StatsData stats) {
        return true;
    }

    /** Transformable only while the head form is currently selectable (unlocked); else a greyed preview. */
    @Override
    public boolean interactive(StatsData stats) {
        try {
            // Stack groups route through the stack-form selectability check; non-stack keep the original path.
            return this.stack
                    ? TransformationsHelper.isSelectableStackForm(stats, this.group, this.firstForm)
                    : TransformationsHelper.isSelectableForm(stats, this.group, this.firstForm);
        } catch (Throwable t) {
            return false;
        }
    }
}

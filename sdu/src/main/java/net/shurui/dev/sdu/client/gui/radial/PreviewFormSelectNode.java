package net.shurui.dev.sdu.client.gui.radial;

import com.dragonminez.client.gui.radial.nodes.FormSelectNode;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.util.TransformationsHelper;

/**
 * A {@link FormSelectNode} that is <em>always visible</em> in sdu's Extra-Forms wheel but only
 * <em>interactive</em> (clickable / transformable) when the player has actually unlocked the form. This is
 * the locked-preview leaf: locked forms render greyed and non-selectable while their DMZ model preview still
 * shows, so the player can see what's ahead without being able to enter it.
 *
 * <p>Everything else is inherited from DMZ verbatim - the icon/tint (computed in the super ctor from the
 * group's {@code formType}, so the sdu {@code AbstractRadialNodeMixin} still swaps in the admin icon/tint),
 * the {@link #preview} {@code FormPreview} (model render), the {@code label}, the {@code orderKey}
 * ({@code form:<group>:<form>}, so ordering/persistence via {@link RadialOrdering} is unchanged), and
 * {@code onSelect} which fires the normal {@code SelectFormC2S}. Selection can only be reached when
 * {@code interactive} is true ({@code UtilityMenuScreen.mouseClicked} gates on it), and the server
 * re-validates {@code SelectFormC2S} anyway, so a locked form can never be entered from here.
 */
public final class PreviewFormSelectNode extends FormSelectNode {

    private final String group;
    private final String form;
    private final boolean stack;

    public PreviewFormSelectNode(String race, String group, String form, boolean stack) {
        super(race, group, form, stack);
        // The super's race/group/form are private; re-store what interactive() needs to re-test selectability.
        this.group = group;
        this.form = form;
        this.stack = stack;
    }

    /** Always show in the wheel, unlocked or not (this is the whole point of preview mode). */
    @Override
    public boolean visible(StatsData stats) {
        return true;
    }

    /** Clickable/transformable only while the form is currently selectable (unlocked); else a greyed preview. */
    @Override
    public boolean interactive(StatsData stats) {
        try {
            // Stack groups route through the stack-form selectability check (honors per-form
            // unlockOnSkillLevel + formRequisite); non-stack groups keep the original path.
            return this.stack
                    ? TransformationsHelper.isSelectableStackForm(stats, this.group, this.form)
                    : TransformationsHelper.isSelectableForm(stats, this.group, this.form);
        } catch (Throwable t) {
            // Fail open toward the safe side: treat as locked (non-interactive) if DMZ errored.
            return false;
        }
    }
}

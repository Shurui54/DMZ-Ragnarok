package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.DmzAssets;
import net.shurui.dev.sdu.client.GeneratedLang;
import net.shurui.dev.sdu.util.ColorCodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

// Renders a custom form's description in DMZ's skills GUI. SkillsMenuScreen shows a form's name (via
// GeneratedLang lang overlays) but reads no description key, so at render TAIL, if the cursor is over a form
// node, we look up the addon description (GeneratedLang.formDesc) and draw it as a tooltip.
//
// DMZ's node/hover internals (toUiX/toUiY/getHoveredFormNode, private FormNode.data) use a scaled/panned coord
// space, so we call them reflectively by their stable mod names and cache the handles. require=0; any failure
// disables the feature.
@Mixin(targets = "com.dragonminez.client.gui.character.SkillsMenuScreen", remap = false)
public abstract class SkillsMenuScreenMixin {

    private static Method sdu$toUiX;
    private static Method sdu$toUiY;
    private static Method sdu$hovered;
    private static Field sdu$dataField;
    private static Field sdu$formTypeField;
    private static boolean sdu$ready;
    private static boolean sdu$failed;

    // The stack-skill first-buy (kaioken/ultimate) trigger used to be a require=0 @Inject on mouseClicked here
    // and silently never ran on the live server, so it moved to a guaranteed-to-fire Forge client screen event
    // (StackSkillBuyEvents). This mixin now only renders the tooltip/label overrides and swaps skill icons.

    // per-screen player stats (holds the local race), read to resolve this form's real TP price
    @Shadow
    private StatsData statsData;

    // Makes any form with a REAL admin-set TP price buyable from the skills tree, regardless of DMZ's per-race
    // buyFromMaster flag. DMZ gates the level-0 form buy behind isMasterOnlyFirstFormLevel; we cancel it to
    // false when the local race has a resolvable non-negative price for this formType at targetLevel. null / -1
    // / MAX_VALUE / out-of-range falls through to DMZ. Mirrors the server bypass in UpdateSkillC2SMixin.
    @Inject(method = "isMasterOnlyFirstFormLevel", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sdu$allowTreeBuyWithRealPrice(String formType, int targetLevel, CallbackInfoReturnable<Boolean> cir) {
        // quest-gated form purchases (advisory client mirror of the server gate): if this form maps to a quest
        // the player hasn't completed AND doesn't own, treat as "master only" so DMZ hides the buyable marker
        // and refuses the client-side buy. takes precedence over the real-price tree-buy below. tooltip fixed
        // in sdu$overrideUnlockLabel.
        if (net.shurui.dev.sdu.form.FormQuestGate.blocksUpgradeToLevel(this.statsData, formType, targetLevel)) {
            cir.setReturnValue(true);
            return;
        }
        if (sdu$hasRealPrice(this.statsData, formType, targetLevel)) {
            cir.setReturnValue(false);
        }
    }

    // true only when the race config exposes an explicit buyable price for formType at level (costs non-null,
    // level in range, entry non-null and >= 0). null / -1 / MAX_VALUE / out-of-range = "no real price",
    // matching how DMZ resolves an unbuyable/Priceless slot.
    private static boolean sdu$hasRealPrice(StatsData data, String formType, int level) {
        if (data == null || formType == null || formType.isEmpty() || level < 0) {
            return false;
        }
        try {
            String race = data.getCharacter().getRaceName();
            RaceCharacterConfig raceConfig = ConfigManager.getRaceCharacter(race);
            if (raceConfig == null) {
                return false;
            }
            Integer[] prices = raceConfig.getFormSkillTpCosts(formType);
            if (prices == null || level >= prices.length) {
                return false;
            }
            Integer price = prices[level];
            return price != null && price >= 0 && price != Integer.MAX_VALUE;
        } catch (Throwable t) {
            return false; // never break DMZ's gate
        }
    }

    // FormData of the form under the cursor, or null. Uses DMZ's private toUiX/toUiY/getHoveredFormNode
    // (scaled/panned space) and the private FormNode.data via cached reflection. Sets sdu$failed on the first
    // hard failure so both injectors go quiet together.
    private FormConfig.FormData sdu$hoveredForm(int mouseX, int mouseY) throws Exception {
        if (!sdu$ready) {
            Class<?> cls = this.getClass();
            sdu$toUiX = findMethod(cls, "toUiX", double.class);
            sdu$toUiY = findMethod(cls, "toUiY", double.class);
            sdu$hovered = findMethod(cls, "getHoveredFormNode", double.class, double.class);
            sdu$ready = true;
        }
        double ux = (double) sdu$toUiX.invoke(this, (double) mouseX);
        double uy = (double) sdu$toUiY.invoke(this, (double) mouseY);
        Object node = sdu$hovered.invoke(this, ux, uy);
        if (node == null) {
            return null;
        }
        if (sdu$dataField == null) {
            sdu$dataField = node.getClass().getDeclaredField("data");
            sdu$dataField.setAccessible(true);
        }
        Object data = sdu$dataField.get(node);
        return (data instanceof FormConfig.FormData form) ? form : null;
    }

    // DMZ form-skill name (formType) of the node under the cursor, or null. Reads the private FormNode.formType
    // via cached reflection. Used to look up the quest gate for the locked tooltip.
    private String sdu$hoveredFormType(int mouseX, int mouseY) throws Exception {
        if (!sdu$ready) {
            Class<?> cls = this.getClass();
            sdu$toUiX = findMethod(cls, "toUiX", double.class);
            sdu$toUiY = findMethod(cls, "toUiY", double.class);
            sdu$hovered = findMethod(cls, "getHoveredFormNode", double.class, double.class);
            sdu$ready = true;
        }
        double ux = (double) sdu$toUiX.invoke(this, (double) mouseX);
        double uy = (double) sdu$toUiY.invoke(this, (double) mouseY);
        Object node = sdu$hovered.invoke(this, ux, uy);
        if (node == null) {
            return null;
        }
        if (sdu$formTypeField == null) {
            sdu$formTypeField = node.getClass().getDeclaredField("formType");
            sdu$formTypeField.setAccessible(true);
        }
        Object type = sdu$formTypeField.get(node);
        return type instanceof String s ? s : null;
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = true)
    private void sdu$renderFormDescription(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (sdu$failed) {
            return;
        }
        try {
            FormConfig.FormData form = sdu$hoveredForm(mouseX, mouseY);
            if (form == null) {
                return;
            }
            String desc = GeneratedLang.formDesc(form.getName());
            String unlockDesc = GeneratedLang.formUnlockDesc(form.getName());
            boolean hasDesc = desc != null && !desc.isBlank();
            boolean hasUnlock = unlockDesc != null && !unlockDesc.isBlank();
            // Per-form minimum level to USE the form (synced FormLevelGateConfig). Advisory: shows the lock line
            // when the local character is below it, matching the server-side transform block. Keyed by form name
            // here (the tooltip does not always carry the group), which the server-side gate resolves exactly.
            int minLevel = net.shurui.dev.sdu.form.FormLevelGateConfig.requiredForFormName(form.getName());
            boolean levelLocked = minLevel >= 1 && this.statsData != null && this.statsData.getLevel() < minLevel;
            if (!hasDesc && !hasUnlock && !levelLocked) {
                return;
            }
            // description grey; unlock-requirement text on its own gold (§6) line below. only the tooltip line,
            // not DMZ's "Priceless"/cost label (scaled/panned space, fragile to overwrite).
            java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
            if (hasDesc) {
                lines.add(Component.literal("§7" + net.shurui.dev.sdu.util.ColorCodes.translate(desc)));
            }
            if (hasUnlock) {
                lines.add(Component.literal("§6" + net.shurui.dev.sdu.util.ColorCodes.translate(unlockDesc)));
            }
            if (levelLocked) {
                lines.add(Component.translatable("gui.dmz_ragnarok.form.level_locked_tooltip", minLevel)
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
        } catch (Throwable t) {
            sdu$failed = true; // DMZ internals differ; disable, don't spam/crash
        }
    }

    // Overrides the unlock-requirement label in the hovered form's tooltip. DMZ builds that tooltip in
    // renderFormsMenu as a List<Component> whose fixed first 3 lines are group title, form name and
    // "skill...: <level>", then a variable unlock block, and hands it to TextUtil.renderAdvancedTooltip. We
    // redirect that call: when the hovered form has a non-empty addon unlockDescription, replace everything
    // from the unlock block onward with one literal line. Forms without a description pass through.
    //
    // This is the ONLY site that renders any unlock-requirement text (the per-node loop draws only
    // icons/borders/exclamation), so it covers every entry.
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dragonminez/client/util/TextUtil;renderAdvancedTooltip(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIILnet/minecraft/network/chat/Component;Ljava/util/List;Ljava/util/List;I)V",
            // the 'render' selector must remap to m_88315_ (remap=true on the @Redirect), but the @At target is
            // DMZ's own TextUtil.renderAdvancedTooltip whose official-name descriptor the jar keeps, so it must
            // NOT remap. mirrors SU's MixinDmzTpMultiplierTooltip @ModifyArg; also silences the AP "Unable to
            // locate method mapping" note.
            remap = false
        ),
        require = 0,
        remap = true
    )
    private void sdu$overrideUnlockLabel(GuiGraphics graphics, Font font, int mouseX, int mouseY,
                                         int screenWidth, int screenHeight, Component title,
                                         List<Component> description, List<Component> extras, int color) {
        List<Component> outDesc = description;
        if (!sdu$failed) {
            try {
                FormConfig.FormData form = sdu$hoveredForm(mouseX, mouseY);
                if (form != null && description != null && description.size() > sdu$UNLOCK_LABEL_INDEX) {
                    // quest-gated buys take precedence: if this form maps to a quest the player hasn't done,
                    // replace the unlock block with DMZ's "unlocked by quest" line + quest title, matching how
                    // DMZ labels native quest-reward forms. server gate is authoritative; this is advisory UX.
                    String formType = sdu$hoveredFormType(mouseX, mouseY);
                    Integer lvlBoxed = form.getUnlockOnSkillLevel();
                    int formLevel = lvlBoxed == null ? 0 : lvlBoxed;
                    Component gateTitle = formLevel >= 1
                        ? net.shurui.dev.sdu.form.FormQuestGate
                            .blockingQuestTitle(this.statsData, formType, formLevel)
                        : null;
                    if (gateTitle != null) {
                        List<Component> replaced = new java.util.ArrayList<>(
                            description.subList(0, sdu$UNLOCK_LABEL_INDEX));
                        replaced.add(Component.translatable("gui.dragonminez.skills.unlocked_by_quest")
                            .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                        replaced.add(gateTitle.copy().withStyle(net.minecraft.ChatFormatting.GRAY));
                        outDesc = replaced;
                    } else {
                        String unlockDesc = GeneratedLang.formUnlockDesc(form.getName());
                        if (unlockDesc != null && !unlockDesc.isBlank()) {
                            List<Component> replaced = new java.util.ArrayList<>(
                                description.subList(0, sdu$UNLOCK_LABEL_INDEX));
                            replaced.add(Component.literal(ColorCodes.translate(unlockDesc)));
                            outDesc = replaced;
                        }
                    }
                }
            } catch (Throwable t) {
                sdu$failed = true; // fall back to DMZ's original list
                outDesc = description;
            }
        }
        com.dragonminez.client.util.TextUtil.renderAdvancedTooltip(
            graphics, font, mouseX, mouseY, screenWidth, screenHeight, title, outDesc, extras, color);
    }

    // leading tooltip lines DMZ always adds before the unlock block (group title, form name, "skill...:
    // <level>"), so the unlock block starts here
    private static final int sdu$UNLOCK_LABEL_INDEX = 3;

    // prefix DMZ builds the per-form icon path from (textures/gui/icons/<type>.png)
    private static final String sdu$ICON_PREFIX = "textures/gui/icons/";

    // Swaps the inline skills-tree icon DMZ builds for a form node to the type's chosen stock icon.
    // renderFormsTree builds dragonminez:textures/gui/icons/<formType>.png directly (no helper), so a custom
    // type without a bundled icon renders missing. We redirect that fromNamespaceAndPath call: if DmzAssets
    // has an iconBase for the type parsed from the path, rewrite to the stock icon (same six names exist
    // there). Unregistered types pass through. Only fromNamespaceAndPath call in renderFormsTree, so it's
    // unambiguous. remap=false; require=0.
    @Redirect(
        method = "renderFormsTree",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/resources/ResourceLocation;fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"
        ),
        require = 0,
        remap = false
    )
    private ResourceLocation sdu$overrideSkillIcon(String namespace, String path) {
        try {
            if ("dragonminez".equals(namespace) && path != null
                    && path.startsWith(sdu$ICON_PREFIX) && path.endsWith(".png")) {
                String type = path.substring(sdu$ICON_PREFIX.length(), path.length() - ".png".length());
                String iconBase = DmzAssets.formTypeIcon(type);
                if (iconBase != null) {
                    return ResourceLocation.fromNamespaceAndPath(
                            "dragonminez", sdu$ICON_PREFIX + iconBase + ".png");
                }
            }
        } catch (Throwable ignored) {
            // fall through to DMZ's original path
        }
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchMethodException(name);
    }
}

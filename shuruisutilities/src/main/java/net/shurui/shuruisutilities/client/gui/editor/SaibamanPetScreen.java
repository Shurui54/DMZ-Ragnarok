package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// tamed saibaman pet tuning editor: max health, attack damage, move speed, and the near-death explosion threshold,
// radius and damage, backed by SaibamanPet.toml. Save clamps to the config ranges server-side, persists the toml,
// and re-bakes onto every loaded pet live. meta = [maxHealth, attackDamage, moveSpeed, threshold, radius, damage].
public class SaibamanPetScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private String maxHealth, attackDamage, moveSpeed, threshold, radius, damage;

    public SaibamanPetScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.saibamanpets"), UI_W, UI_H, null);
        this.maxHealth = meta.size() > 0 ? meta.get(0) : "20.0";
        this.attackDamage = meta.size() > 1 ? meta.get(1) : "4.0";
        this.moveSpeed = meta.size() > 2 ? meta.get(2) : "0.3";
        this.threshold = meta.size() > 3 ? meta.get(3) : "0.2";
        this.radius = meta.size() > 4 ? meta.get(4) : "3.0";
        this.damage = meta.size() > 5 ? meta.get(5) : "6.0";
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = 34;
        tf(tr("gui.dmz_ragnarok.core.saibaman.health"), maxHealth, v -> maxHealth = v);
        tip(tr("gui.dmz_ragnarok.core.saibaman.health_tip"));
        tf(tr("gui.dmz_ragnarok.core.saibaman.attack"), attackDamage, v -> attackDamage = v);
        tip(tr("gui.dmz_ragnarok.core.saibaman.attack_tip"));
        tf(tr("gui.dmz_ragnarok.core.saibaman.speed"), moveSpeed, v -> moveSpeed = v);
        tip(tr("gui.dmz_ragnarok.core.saibaman.speed_tip"));
        tf(tr("gui.dmz_ragnarok.core.saibaman.threshold"), threshold, v -> threshold = v);
        tip(tr("gui.dmz_ragnarok.core.saibaman.threshold_tip"));
        tf(tr("gui.dmz_ragnarok.core.saibaman.radius"), radius, v -> radius = v);
        tip(tr("gui.dmz_ragnarok.core.saibaman.radius_tip"));
        tf(tr("gui.dmz_ragnarok.core.saibaman.explosion_damage"), damage, v -> damage = v);
        tip(tr("gui.dmz_ragnarok.core.saibaman.explosion_damage_tip"));

        btn(14, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void save()
    {
        applyFields();
        EditorScreens.act("saibamanpets", "save", maxHealth.trim(), attackDamage.trim(), moveSpeed.trim(),
                threshold.trim(), radius.trim(), damage.trim());
    }
}

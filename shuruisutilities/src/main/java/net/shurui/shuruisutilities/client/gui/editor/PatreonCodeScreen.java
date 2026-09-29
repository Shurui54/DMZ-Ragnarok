package net.shurui.shuruisutilities.client.gui.editor;

import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Where a player types the link code the website gave them.
 *
 * <p>This exists for the path where the one-click link cannot finish on its own: no valid Mojang session, or the
 * player started the link in a browser instead of from the menu. {@link net.shurui.shuruisutilities.patreon.client
 * .PatreonLinkClient} opens this automatically in that case, so pressing Link always leads somewhere rather than
 * leaving the player holding a code with nowhere to put it.
 *
 * <p>The field is deliberately forgiving about how the code is typed. The website prints it grouped as
 * {@code ABCD EFGH} because that is easier to read off a screen, and people paste it with the space, in lower case,
 * or with a dash. All of that is stripped here and again in the backend, so only the characters matter.
 */
public class PatreonCodeScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private EditBox codeField;

    public PatreonCodeScreen()
    {
        super(Component.translatable("gui.dmz_ragnarok.core.cosmetics.patreon_code_title"), UI_W, UI_H, null);
    }

    @Override
    protected void init()
    {
        super.init();

        codeField = field(UI_W / 2 - 70, 44, 140, "");
        codeField.setHint(Component.translatable("gui.dmz_ragnarok.core.cosmetics.patreon_code_hint"));
        // room for 8 characters plus whatever grouping or case the player pastes around them
        codeField.setMaxLength(24);
        setInitialFocus(codeField);

        DmzTextureButton confirm = btn(UI_W / 2 - 70, 64, 140, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.cosmetics.patreon_code_confirm"), this::submit);
        confirm.active = true;

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.menu"), EditorScreens::openPlayerHub);
    }

    private void submit()
    {
        String code = normalize(codeField.getValue());
        if (code.isEmpty())
            return;
        EditorScreens.act("cosmetics", "patreonclaim", code);
        // close so the server's result (linked / bad code / try again) is visible in chat
        Minecraft.getInstance().setScreen(null);
    }

    /** Keep only the characters a code is made of, so grouping, case and stray punctuation cannot break a claim. */
    private static String normalize(String raw)
    {
        if (raw == null)
            return "";
        return raw.toUpperCase(java.util.Locale.ROOT).replaceAll("[^0-9A-Z]", "");
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods)
    {
        // Enter submits, which is what anyone typing a short code expects.
        if (key == 257 || key == 335)
        {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}

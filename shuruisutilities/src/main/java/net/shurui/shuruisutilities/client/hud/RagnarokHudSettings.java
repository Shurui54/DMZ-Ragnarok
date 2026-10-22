package net.shurui.shuruisutilities.client.hud;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHud;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudBridge;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;
import net.shurui.shuruisutilities.core.SUConfig;

/**
 * The suite's old HUD options, carried onto DragonMineZ's HUD layout.
 *
 * <h2>Migration (once per client)</h2>
 * The first time a player is in a world with DMZ's layout system available, the old per-player settings move into
 * DMZ's {@code hud_layout.json}: {@code CustomStatHud} on (the default) selects the Ragnarok style, a non-default
 * {@code CustomStatHudScale} becomes the panel's scale, and a region or staff panel the player had dragged in the old
 * mover keeps its spot. Nothing the player already placed in DMZ's editor is overwritten. The old files are left
 * as they were, because they are still the fallback placement if DMZ's layout system is ever unavailable.
 *
 * <h2>The two old option rows</h2>
 * The "Custom Stat HUD" switch and the "Stat HUD Scale" slider in DMZ's config menu stay, and drive DMZ's layout:
 * the switch selects or leaves the Ragnarok style (back to the style the player came from), the slider scales the
 * panel and keeps the currency pills on it.
 *
 * <p>State lives in {@code config/shuruisutilities-ragnarokhud.json}, beside the region and staff panel files.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RagnarokHudSettings
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static State state;
    private static boolean checked;

    private RagnarokHudSettings() {}

    /** The persisted bits: whether the migration ran, and the DMZ style to go back to when leaving Ragnarok. */
    private static final class State
    {
        boolean migrated;
        String previousStyle = "xenoverse";
        // where we last put the currency pills when carrying them with the panel; a different stored box means the
        // player moved them in DMZ's editor, and then they stay put
        float currencyX = Float.NaN;
        float currencyY = Float.NaN;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || checked)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
            return;
        // once per session is enough: after the first run the state file says it is done
        checked = true;
        try
        {
            migrate(mc);
        }
        catch (Throwable ignored)
        {
            // a DMZ layout API shift must never break joining a world; the old placement keeps working
        }
    }

    private static void migrate(Minecraft mc)
    {
        if (!RagnarokHud.available())
            return;
        State st = state();
        if (st.migrated)
            return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float unit = Math.max(0.25f, sh / 360.0f);

        if (SUConfig.customStatHud)
            setRagnarokStyle(true);
        if (Math.abs(SUConfig.customStatHudScale - 1.0) > 0.001 && !RagnarokHudBridge.hasStoredPlacement(RagnarokHudSlot.STATS))
            applyPanelScale((float) SUConfig.customStatHudScale);

        // the old mover's panels, only if the player ever saved a position there
        File region = FMLPaths.CONFIGDIR.get().resolve("shuruisutilities-regionhud.json").toFile();
        if (region.isFile() && !RagnarokHudBridge.hasStoredPlacement(RagnarokHudSlot.REGION))
        {
            RegionHudConfig cfg = RegionHudConfig.get();
            storePanel(RagnarokHudSlot.REGION, cfg.xPct, cfg.yPct, sw, sh, unit);
        }
        File staff = FMLPaths.CONFIGDIR.get().resolve("shuruisutilities-staffhud.json").toFile();
        if (staff.isFile() && !RagnarokHudBridge.hasStoredPlacement(RagnarokHudSlot.STAFF_TASK))
        {
            StaffTaskHudConfig cfg = StaffTaskHudConfig.get();
            storePanel(RagnarokHudSlot.STAFF_TASK, cfg.xPct, cfg.yPct, sw, sh, unit);
        }
        RagnarokHudBridge.save();
        st.migrated = true;
        save();
    }

    // the old panels stored their centre x and top y as screen fractions, at scale 1
    private static void storePanel(RagnarokHudSlot slot, double xPct, double yPct, int sw, int sh, float unit)
    {
        float w = RagnarokHud.element(slot).width() * unit;
        float x = (float) Math.max(0.0, Math.min(sw - w, xPct * sw - w / 2.0f));
        float y = (float) (yPct * sh);
        RagnarokHudBridge.storeBox(slot, x, y, 1.0f, sw, sh);
    }

    /** The "Custom Stat HUD" row: is the Ragnarok style (or, without DMZ's layout, the legacy panel) on. */
    public static boolean ragnarokStyleOn()
    {
        return RagnarokHud.styleActive();
    }

    /** The "Custom Stat HUD" row's setter: select the Ragnarok style, or go back to the style the player came from. */
    public static void setRagnarokStyle(boolean on)
    {
        SUConfig.setCustomStatHud(on);
        if (!RagnarokHud.available())
            return;
        State st = state();
        String current = RagnarokHud.styleName();
        if (on && current != null && !RagnarokHudSlot.STYLE_NAME.equals(current))
        {
            st.previousStyle = current;
            save();
        }
        RagnarokHud.setStyleActive(on, st.previousStyle);
    }

    /** The "Stat HUD Scale" row: the panel's scale in DMZ's layout (or, without it, the legacy scale). */
    public static float panelScale()
    {
        float scale = RagnarokHudBridge.placementScale(RagnarokHudSlot.STATS);
        return Float.isNaN(scale) ? (float) SUConfig.customStatHudScale : scale;
    }

    /**
     * The "Stat HUD Scale" row's setter. Scales the panel in DMZ's layout, and carries the currency pills along so they
     * stay where they sit on the assembled panel (a player who moved them in DMZ's editor keeps their own spot).
     */
    public static void applyPanelScale(float scale)
    {
        SUConfig.setCustomStatHudScale(scale);
        if (!RagnarokHud.available())
            return;
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        State st = state();
        float[] currency = RagnarokHudBridge.box(RagnarokHudSlot.CURRENCY, sw, sh);
        boolean currencyFollows = !RagnarokHudBridge.hasStoredPlacement(RagnarokHudSlot.CURRENCY)
                || (currency != null && Math.abs(currency[0] - st.currencyX) < 0.5f && Math.abs(currency[1] - st.currencyY) < 0.5f);
        RagnarokHudBridge.setPlacementScale(RagnarokHudSlot.STATS, scale);
        float[] stats = RagnarokHudBridge.box(RagnarokHudSlot.STATS, sw, sh);
        if (currencyFollows && stats != null)
        {
            RagnarokHudBridge.storeBox(RagnarokHudSlot.CURRENCY,
                    stats[0] + ScouterStatHudOverlay.currencyOffsetX() * stats[4], stats[1],
                    RagnarokHudBridge.placementScale(RagnarokHudSlot.STATS), sw, sh);
            float[] placed = RagnarokHudBridge.box(RagnarokHudSlot.CURRENCY, sw, sh);
            if (placed != null)
            {
                st.currencyX = placed[0];
                st.currencyY = placed[1];
                save();
            }
        }
        RagnarokHudBridge.save();
    }

    private static State state()
    {
        if (state == null)
        {
            try (FileReader in = new FileReader(file()))
            {
                state = GSON.fromJson(in, State.class);
            }
            catch (Exception ignored)
            {
            }
            if (state == null)
                state = new State();
        }
        return state;
    }

    private static void save()
    {
        try (FileWriter out = new FileWriter(file()))
        {
            GSON.toJson(state(), out);
        }
        catch (Exception ignored)
        {
        }
    }

    private static File file()
    {
        return FMLPaths.CONFIGDIR.get().resolve("shuruisutilities-ragnarokhud.json").toFile();
    }
}

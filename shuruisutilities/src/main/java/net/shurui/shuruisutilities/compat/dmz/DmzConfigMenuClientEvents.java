package net.shurui.shuruisutilities.compat.dmz;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.core.SUConfig.DragonBallRenderStyle;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Injects SU's extra option rows into DragonMineZ's own in-game config menu
 * ({@code com.dragonminez.client.gui.character.ConfigMenuScreen}, a tab of the character/stats menu, NOT the vanilla
 * options screen). The row is a native DMZ boolean switch for SU's dragon ball render style: ON = CUBE, OFF = SPHERE.
 *
 * <p>DMZ's option rows are data objects in a private {@code List configOptions} field; for a BOOLEAN row DMZ itself
 * builds the {@code SwitchButton}, draws the label, plays the switch sounds, handles scrolling and calls our setter, so
 * getting the row object into that list yields a 100% native row with no widget work on our side.
 *
 * <p>Both {@code ConfigOption} and {@code ConfigType} are package-private DMZ inner types that cannot be named in
 * source, so the whole thing is reflection. Every target here (the screen class name, the {@code configOptions} field,
 * {@code updateConfigsList()}, the ConfigOption ctor, the ConfigType.BOOLEAN constant) is a rename risk across DMZ
 * updates, so all reflection is wrapped and degrades to a silent no-op rather than crashing, per the workspace rule for
 * DMZ-targeting integration. We never classload the DMZ screen type at the guard (name string match only).
 *
 * <p>The row's label comes from DMZ's {@code tr("gui.dragonminez." + key)}, which forces the key into the dragonminez
 * namespace. Our label lives in {@code assets/dragonminez/lang/en_us.json} (a deliberate, documented exception to the
 * "lang namespace matches owning modid" rule): MC merges lang files per namespace across mods, so a dragonminez-namespaced
 * file shipped by SU is the only way to give this key a real label. JSON cannot hold comments, hence this note.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DmzConfigMenuClientEvents {

    private static final String SCREEN_CLASS = "com.dragonminez.client.gui.character.ConfigMenuScreen";
    private static final String CONFIG_OPTION_CLASS = SCREEN_CLASS + "$ConfigOption";
    private static final String CONFIG_TYPE_CLASS = SCREEN_CLASS + "$ConfigType";

    // keys DMZ turns into the label lookups gui.dragonminez.config.dragonBallRenderStyle / .customStatHud / .auraTrails
    // / .flightAura / .suiteAnnouncements / .customStatHudScale
    private static final String ROW_KEY_BALL_STYLE = "config.dragonBallRenderStyle";
    private static final String ROW_KEY_STAT_HUD = "config.customStatHud";
    private static final String ROW_KEY_AURA_TRAILS = "config.auraTrails";
    private static final String ROW_KEY_FLIGHT_AURA = "config.flightAura";
    private static final String ROW_KEY_SUITE_ANNOUNCEMENTS = "config.suiteAnnouncements";
    private static final String ROW_KEY_STAT_HUD_SCALE = "config.customStatHudScale";

    @SubscribeEvent
    public static void onInitPost(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen == null || !SCREEN_CLASS.equals(screen.getClass().getName())) return;

        try {
            Field optionsField = screen.getClass().getDeclaredField("configOptions");
            optionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> opts = (List<Object>) optionsField.get(screen);
            if (opts == null) return;

            Class<?> optionClass = Class.forName(CONFIG_OPTION_CLASS);
            Field keyField = optionClass.getDeclaredField("key");
            keyField.setAccessible(true);

            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> typeClass = (Class<? extends Enum>) Class.forName(CONFIG_TYPE_CLASS);
            @SuppressWarnings("unchecked")
            Object booleanType = Enum.valueOf(typeClass, "BOOLEAN");
            // FLOAT gives DMZ's native min/max slider, used for the stat HUD scale row below.
            @SuppressWarnings("unchecked")
            Object floatType = Enum.valueOf(typeClass, "FLOAT");

            Constructor<?> ctor = optionClass.getDeclaredConstructor(
                    String.class,
                    typeClass,
                    float.class, float.class, float.class,
                    Consumer.class);
            ctor.setAccessible(true);

            boolean added = false;

            // Dragon ball render style: DMZ reads the switch state as value > 0, so 1f = ON = CUBE, 0f = OFF = SPHERE.
            if (!hasRow(opts, keyField, ROW_KEY_BALL_STYLE)) {
                float value = SUConfig.dragonBallRenderStyle == DragonBallRenderStyle.CUBE ? 1f : 0f;
                Consumer<Float> setter = v -> SUConfig.setDragonBallRenderStyle(
                        v > 0f ? DragonBallRenderStyle.CUBE : DragonBallRenderStyle.SPHERE);
                opts.add(ctor.newInstance(ROW_KEY_BALL_STYLE, booleanType, value, 0f, 1f, setter));
                added = true;
            }

            // Custom stat HUD: 1f = ON = SU's scouter HUD active (config default true), 0f = OFF = DMZ's default HUD.
            if (!hasRow(opts, keyField, ROW_KEY_STAT_HUD)) {
                float value = SUConfig.customStatHud ? 1f : 0f;
                Consumer<Float> setter = v -> SUConfig.setCustomStatHud(v > 0f);
                opts.add(ctor.newInstance(ROW_KEY_STAT_HUD, booleanType, value, 0f, 1f, setter));
                added = true;
            }

            // Aura trails: 1f = ON = the streak behind a dashing or flying player is drawn (config default true).
            if (!hasRow(opts, keyField, ROW_KEY_AURA_TRAILS)) {
                float value = SUConfig.auraTrails ? 1f : 0f;
                Consumer<Float> setter = v -> SUConfig.setAuraTrails(v > 0f);
                opts.add(ctor.newInstance(ROW_KEY_AURA_TRAILS, booleanType, value, 0f, 1f, setter));
                added = true;
            }

            // Flight aura: 1f = ON = SU lays its aura over the screen during fast flight (config default true), 0f =
            // OFF = no constant fast-flight aura. This gates only SU's laid-over aura, not DMZ's own powered-up one.
            if (!hasRow(opts, keyField, ROW_KEY_FLIGHT_AURA)) {
                float value = SUConfig.flightAura ? 1f : 0f;
                Consumer<Float> setter = v -> SUConfig.setFlightAura(v > 0f);
                opts.add(ctor.newInstance(ROW_KEY_FLIGHT_AURA, booleanType, value, 0f, 1f, setter));
                added = true;
            }

            // Suite announcements: 1f = ON = SU's own on-screen event titles are shown (config default true), 0f = OFF =
            // they are suppressed for this player, while DMZ's own and vanilla titles keep working. Unlike the cosmetic
            // rows above, this preference must reach the SERVER (SUConfig is COMMON, so Forge never syncs it), so the
            // setter both persists locally and sends the new value; the client also re-sends on every login.
            if (!hasRow(opts, keyField, ROW_KEY_SUITE_ANNOUNCEMENTS)) {
                float value = SUConfig.suiteAnnouncements ? 1f : 0f;
                Consumer<Float> setter = v -> {
                    boolean on = v > 0f;
                    SUConfig.setSuiteAnnouncements(on);
                    net.shurui.shuruisutilities.commons.network.NetworkUtils.sendToServer(
                            new net.shurui.shuruisutilities.announce.PacketAnnouncementPref(on));
                };
                opts.add(ctor.newInstance(ROW_KEY_SUITE_ANNOUNCEMENTS, booleanType, value, 0f, 1f, setter));
                added = true;
            }

            // Custom stat HUD scale: a native FLOAT slider over the config's documented sane range 0.25..4.0. The stored
            // value is a double; DMZ hands the setter a float, which widens losslessly back into the double field.
            if (!hasRow(opts, keyField, ROW_KEY_STAT_HUD_SCALE)) {
                float value = (float) SUConfig.customStatHudScale;
                Consumer<Float> setter = v -> SUConfig.setCustomStatHudScale(v.doubleValue());
                opts.add(ctor.newInstance(ROW_KEY_STAT_HUD_SCALE, floatType, value, 0.25f, 4.0f, setter));
                added = true;
            }

            if (!added) return;

            // Init.Post runs after the screen's own init, so maxScroll was already computed without our rows. Recompute
            // it or an appended row sits past the scroll range and can never be reached.
            Method update = screen.getClass().getDeclaredMethod("updateConfigsList");
            update.setAccessible(true);
            update.invoke(screen);
        } catch (Throwable ignored) {
            // DMZ internals renamed or otherwise unavailable: skip the injection silently, never crash the screen.
        }
    }

    // Init.Post re-fires on every rebuildWidgets and configOptions is a persistent instance field, so we dedupe by key
    // to avoid appending a duplicate row on each re-init.
    private static boolean hasRow(List<Object> opts, Field keyField, String key) throws IllegalAccessException {
        for (Object existing : opts) {
            if (existing != null && key.equals(keyField.get(existing))) return true;
        }
        return false;
    }

    private DmzConfigMenuClientEvents() {}
}

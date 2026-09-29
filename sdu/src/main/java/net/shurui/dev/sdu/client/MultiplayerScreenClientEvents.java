package net.shurui.dev.sdu.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Pins the official DMZ Ragnarok server to the top of the vanilla multiplayer list and paints a clickable
 * Discord promo (pixel-art logo + one line of text) in the top-left corner of that screen.
 *
 * <p>ONE class for the whole suite. It registers on the {@code dmz_ragnarok} container, which is present in every
 * 2.0 output (the fat jar and the standalone core jar both declare it, and each module jar ships alongside core at
 * runtime), so this single copy runs once in all of them. The four former per-tree copies (SU, dungeons, raids,
 * tournaments) were deleted: with one registration there is nothing to dedupe against and nothing to run twice.</p>
 *
 * <h2>Robustness against third-party "cleaner" client mods</h2>
 *
 * <p>A third-party client mod removes, on {@code JoinMultiplayerScreen} init, every child WIDGET whose class name
 * starts with {@code net.shurui} or whose message contains {@code "shurui"} / {@code "modpack.gg"}, and every
 * {@code ServerData} whose ip contains {@code "modpack.gg"}. Two defences keep our promo alive without ever
 * detecting, blocking or punishing that mod:</p>
 *
 * <ol>
 *   <li><b>The Discord promo is NOT a widget.</b> It is drawn in {@link ScreenEvent.Render.Post} and its click is
 *       claimed in {@link ScreenEvent.MouseButtonPressed.Pre}, exactly like the Kinetic banner next door. There is
 *       no {@code net.shurui} widget on the screen and no widget carrying the label text, so the widget sweep finds
 *       nothing of ours to strip. Keeping it a draw also lets the label keep its wording.</li>
 *   <li><b>The server entry is re-pinned last.</b> Our pin runs at {@link EventPriority#LOWEST} on init, after the
 *       cleaner's default-priority handler, so anything it removed we put back at index 0. Our current address is a
 *       raw ip (no {@code modpack.gg}), so the cleaner does not target it today, but a cheap once-per-second guard
 *       while the screen is open re-adds the entry in memory if it ever goes missing. That guard never writes
 *       servers.dat.</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MultiplayerScreenClientEvents {

    private MultiplayerScreenClientEvents() {}

    private static final String URL = "https://discord.gg/K7vFwWkKrF";
    private static final String LABEL = "Join the official Shurui's DMZ Addon Server!";
    private static final ResourceLocation ICON = new ResourceLocation("dmz_ragnarok", "textures/gui/discord.png");

    /** Where the server currently lives. A raw ip, deliberately not a {@code modpack.gg} host. */
    private static final String IP = "172.240.47.191:25593";

    // Addresses this pin used to use. A player who ever ran an older build has one saved, and nothing removed it,
    // so it would sit in the list beside the new entry for ever, still pointing at a backend that is now behind
    // the proxy and refuses direct connections. Purged rather than merely left unpinned, or "the old ip is still
    // showing" never ends.
    private static final String[] LEGACY = { "dmz-ragnarok.modpack.gg" };

    // Promo overlay geometry, in logical (gui-scaled) pixels: pixel-art logo top-left, label text to its right.
    private static final int OX = 8;
    private static final int OY = 4;
    private static final int ICON_SIZE = 20;
    private static final int GAP = 6;

    /** Throttle for the open-screen re-pin guard, so it does not rebuild the list every frame. */
    private static long lastGuardMs = 0L;

    /**
     * Pin on init at LOWEST priority, so any cleaner running at default priority has already had its turn and we
     * re-add the entry afterwards. This pass MAY write servers.dat, but only to purge a stale legacy address or to
     * restyle the pinned name in place, never on a plain re-pin.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) return;
        pin(screen, true);
    }

    /**
     * Cheap open-screen guard, at most once per second. If our entry is no longer pinned at the top (another mod
     * removed it, or the list changed), put it back IN MEMORY. Never saves: re-adding on each open is enough even
     * if someone deleted it from disk.
     */
    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) return;
        long now = System.currentTimeMillis();
        if (now - lastGuardMs < 1000L) return;
        lastGuardMs = now;
        pin(screen, false);
    }

    /**
     * Paint the Discord promo on top of the screen's own widgets. Drawn every frame, in Render.Post so it lands
     * above the server rows rather than under them. Not a widget, so nothing can strip it.
     */
    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen)) return;
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(ICON, OX, OY, ICON_SIZE, ICON_SIZE, 0.0F, 0.0F, 64, 64, 64, 64);
        RenderSystem.disableBlend();

        int textX = OX + ICON_SIZE + GAP;
        int textY = OY + (ICON_SIZE - font.lineHeight) / 2;
        g.drawString(font, LABEL, textX, textY, 0xFFFFFFFF, true);

        if (within(event.getMouseX(), event.getMouseY())) {
            g.fill(OX, OY, OX + ICON_SIZE, OY + ICON_SIZE, 0x33FFFFFF);
        }
    }

    /**
     * Claim a left-click on the promo before the screen underneath sees it, so it opens the Discord link and does
     * not also press whatever sits behind it.
     */
    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) return;
        if (event.getButton() != 0) return;
        if (within(event.getMouseX(), event.getMouseY())) {
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            ConfirmLinkScreen.confirmLinkNow(URL, screen, false);
            event.setCanceled(true);
        }
    }

    /** Right edge of the clickable strip (icon plus label), recomputed from the live font width. */
    private static int overlayRight() {
        return OX + ICON_SIZE + GAP + Minecraft.getInstance().font.width(LABEL);
    }

    private static boolean within(double mx, double my) {
        return mx >= OX && mx < overlayRight() && my >= OY && my < OY + ICON_SIZE;
    }

    /**
     * Ensure the pinned entry sits at index 0 with the styled name, purging any legacy address.
     *
     * @param allowSave whether this pass may write servers.dat. The init pass passes true (so a legacy purge or a
     *                  name restyle survives a restart); the open-screen guard passes false (an in-memory re-add on
     *                  each open is enough, and we must never loop-save).
     */
    private static void pin(JoinMultiplayerScreen screen, boolean allowSave) {
        try {
            net.minecraft.client.multiplayer.ServerList sl = screen.getServers();
            if (sl == null || competitorPresent(sl)) return;
            final String NAME = colors(DISPLAY_NAME);

            boolean legacyPresent = false;
            for (int i = 0; i < sl.size(); i++) {
                net.minecraft.client.multiplayer.ServerData d = sl.get(i);
                if (d == null || d.ip == null) continue;
                for (String old : LEGACY) if (old.equalsIgnoreCase(d.ip)) { legacyPresent = true; break; }
                if (legacyPresent) break;
            }
            boolean alreadyTop = sl.size() > 0 && sl.get(0) != null && IP.equalsIgnoreCase(sl.get(0).ip);

            if (alreadyTop && !legacyPresent) {
                // Already pinned, but the entry may have been saved before this name was styled (or with a
                // different style). Rewrite it in place, or the styling would only ever reach people who had
                // never opened the server list before, which is nobody.
                if (!NAME.equals(sl.get(0).name)) {
                    sl.get(0).name = NAME;
                    if (allowSave) sl.save();
                    refreshServerSelection(screen, sl);
                }
                return;
            }

            java.util.List<net.minecraft.client.multiplayer.ServerData> keep = new java.util.ArrayList<>();
            for (int i = 0; i < sl.size(); i++) {
                net.minecraft.client.multiplayer.ServerData d = sl.get(i);
                if (d == null || IP.equalsIgnoreCase(d.ip)) continue;
                boolean stale = false;
                for (String old : LEGACY) if (d.ip != null && old.equalsIgnoreCase(d.ip)) { stale = true; break; }
                if (!stale) keep.add(d);
            }
            while (sl.size() > 0) sl.remove(sl.get(0));
            sl.add(new net.minecraft.client.multiplayer.ServerData(NAME, IP, false), false);
            for (net.minecraft.client.multiplayer.ServerData d : keep) sl.add(d, false);
            // Written back only when a stale entry was actually dropped, and only on a pass allowed to save: the
            // alternative is re-hiding it on every screen open and leaving it in servers.dat for ever.
            if (allowSave && legacyPresent) sl.save();
            refreshServerSelection(screen, sl);
        } catch (Throwable ignored) {}
    }

    /**
     * The pinned entry's display name, written with {@code &} codes.
     *
     * <p>Both COLOUR and FORMATTING work here. The multiplayer list draws {@code ServerData.name} as a raw String
     * through {@code GuiGraphics.drawString(Font, String, ...)}, which routes to {@code Font.drawInBatch} and runs
     * it through the same formatting decomposer chat uses, so {@code &l} bold, {@code &n} underline, {@code &o}
     * italic and {@code &k} obfuscated all render alongside the sixteen colours.
     *
     * <p>Authored with {@code &} rather than the section sign so the source stays readable and greppable; the
     * conversion happens in {@link #colors}. Vanilla's name field cannot be typed with section signs (the edit box
     * filters them), which is why the styling has to be applied here rather than left to the player.
     */
    private static final String DISPLAY_NAME = "&3&l&n&kiO&r&5&l&nDMZ Ragnarok&3&l&n&kOi&r";

    /**
     * {@code &} codes to section signs.
     *
     * <p>Only converts a {@code &} that is actually followed by a valid code character, so an ampersand meant as
     * an ampersand survives. Deliberately local rather than borrowed from Shurui's Utilities: sdu imports nothing
     * from that source tree, and keeping it that way is what lets the public tier run with every SU module torn
     * down.
     */
    private static String colors(String s) {
        if (s == null) return "";
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length - 1; i++) {
            if (c[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(c[i + 1]) > -1) {
                c[i] = '§';
                c[i + 1] = Character.toLowerCase(c[i + 1]);
            }
        }
        return new String(c);
    }

    private static final String[] SUPPRESS_IF_PRESENT = {
            "DBZLegacyreborn.kinetic.host",
            "216.39.241.21:25588",
            "www.allhailpopotheonetruegod.com"
    };

    /** Canonical host: lowercase, trimmed, drop a leading "www.", drop any :port, drop a trailing dot. */
    private static String canonHost(String addr) {
        if (addr == null) return "";
        String s = addr.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("www.")) s = s.substring(4);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static boolean competitorPresent(net.minecraft.client.multiplayer.ServerList sl) {
        for (int i = 0; i < sl.size(); i++) {
            net.minecraft.client.multiplayer.ServerData d = sl.get(i);
            if (d == null || d.ip == null) continue;
            String entry = canonHost(d.ip);
            if (entry.isEmpty()) continue;
            for (String c : SUPPRESS_IF_PRESENT) {
                if (entry.equals(canonHost(c))) return true;
            }
        }
        return false;
    }

    private static void refreshServerSelection(JoinMultiplayerScreen screen, net.minecraft.client.multiplayer.ServerList sl) {
        try {
            for (java.lang.reflect.Field f : JoinMultiplayerScreen.class.getDeclaredFields()) {
                if (net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object w = f.get(screen);
                    if (w instanceof net.minecraft.client.gui.screens.multiplayer.ServerSelectionList list) {
                        list.updateOnlineServers(sl);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}

package net.shurui.dev.sdu.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.entity.SduDmzFighter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

// Resolves an SduDmzFighter's skin to a texture RL, mirroring Custom NPCs' NpcTextureUtils: 0 = direct
// location, 1 = player name, 2 = URL (downloaded once + cached). Player skins resolve the GameProfile by name
// (SkullBlockEntity.updateGameprofile + skin manager) like CNPC, so an OFFLINE player's skin still shows.
// Cached; a default skin shows while a name/URL resolves.
public final class FighterSkins {

    private static final Map<String, ResourceLocation> URL_CACHE = new ConcurrentHashMap<>();
    // name -> resolved GameProfile (with textures), filled async by SkullBlockEntity
    private static final Map<String, GameProfile> PROFILE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PROFILE_INFLIGHT = ConcurrentHashMap.newKeySet();

    // log throttle: last resolved RL per entity id, so resolve() logs on change not every frame
    private static final Map<Integer, String> RESOLVE_LOG_GUARD = new ConcurrentHashMap<>();
    private static final Set<String> URL_LOG_GUARD = ConcurrentHashMap.newKeySet();
    // per-RL re-download guard: once a placeholder is registered and download started, later calls no-op, so
    // per-frame callers (the CNPC mixin) don't re-download.
    private static final Set<ResourceLocation> INSTALLED_LOCS = ConcurrentHashMap.newKeySet();

    private FighterSkins() {
    }

    public static ResourceLocation resolve(SduDmzFighter fighter) {
        int type = fighter.getSkinType();
        String value = fighter.getTextureLocation();
        ResourceLocation result;
        try {
            result = switch (type) {
                case 1 -> playerSkin(value);
                case 2 -> urlSkin(value);
                default -> value == null || value.isBlank()
                        ? DefaultPlayerSkin.getDefaultSkin()
                        : ResourceLocation.parse(value);
            };
        } catch (Throwable t) {
            result = DefaultPlayerSkin.getDefaultSkin();
        }
        try {
            String rl = result == null ? "null" : result.toString();
            String prev = RESOLVE_LOG_GUARD.put(fighter.getId(), rl);
            if (prev == null || !prev.equals(rl)) {
                DmzNpc.LOGGER.info("[sdu-skin] resolve entityId={} skinType={} rawTexture='{}' -> finalRL={}",
                        fighter.getId(), type, value, rl);
            }
        } catch (Throwable ignored) {
            // logging must never affect rendering
        }
        return result;
    }

    private static ResourceLocation playerSkin(String name) {
        if (name == null || name.isBlank()) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        // fast path: player in the current session, skin already loaded
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(name);
            if (info != null) {
                return info.getSkinLocation();
            }
        }
        // else resolve the profile by name like CNPC, so offline players' skins still show
        GameProfile resolved = PROFILE_CACHE.get(name);
        if (resolved != null) {
            return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(resolved);
        }
        if (PROFILE_INFLIGHT.add(name)) {
            try {
                SkullBlockEntity.updateGameprofile(new GameProfile(null, name), profile -> {
                    if (profile != null) {
                        PROFILE_CACHE.put(name, profile);
                    }
                    PROFILE_INFLIGHT.remove(name);
                });
            } catch (Throwable t) {
                PROFILE_INFLIGHT.remove(name);
                DmzNpc.LOGGER.debug("[{}] Player skin resolve failed for '{}': {}", DmzNpc.MODID, name, t.toString());
            }
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }

    private static ResourceLocation urlSkin(String url) {
        if (url == null || url.isBlank()) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        boolean cacheMiss = !URL_CACHE.containsKey(url);
        ResourceLocation cached = URL_CACHE.computeIfAbsent(url, u -> {
            try {
                ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("dmz_ragnarok", "skins/url/" + md5(u));
                installUrlTexture(loc, u);
                return loc;
            } catch (Throwable t) {
                DmzNpc.LOGGER.warn("[sdu-skin] urlSkin build failed for url='{}': {}", u, t.toString(), t);
                return DefaultPlayerSkin.getDefaultSkin();
            }
        });
        if (URL_LOG_GUARD.add(url)) {
            String md5Rl;
            try {
                md5Rl = "dmz_ragnarok:skins/url/" + md5(url);
            } catch (Throwable t) {
                md5Rl = "<md5 failed: " + t + ">";
            }
            DmzNpc.LOGGER.info("[sdu-skin] urlSkin url='{}' md5RL={} cache={} returnedRL={}",
                    url, md5Rl, cacheMiss ? "MISS" : "HIT", cached);
        }
        return cached;
    }

    // Installs a normalized URL skin under an arbitrary texture RL. Used by the sdu fighter path (urlSkin) and
    // the CNPC-Gecko-Addon mixin (which hands us the RL its model reads). (a) synchronously register a
    // placeholder HttpTexture so the RL is always a valid registered texture (never a SimpleTexture that
    // FileNotFounds into purple) while the image downloads, then (b) downloadAndNormalize overwrites loc with a
    // complete 64x64 DynamicTexture. INSTALLED_LOCS makes repeat calls no-op. Render-thread only.
    public static void installUrlTexture(ResourceLocation loc, String url) {
        if (loc == null || url == null || url.isBlank()) {
            return;
        }
        // Restrict skin fetches to http/https. This is the single outbound network path in the mod; an
        // unrestricted URL fetch reads as a security risk in CurseForge review even though it is vanilla
        // equivalent. Both fetch paths funnel through here (the HttpTexture placeholder and
        // downloadAndNormalize). DO NOT remove thinking it is redundant. On rejection return cleanly so the
        // caller keeps the default skin, like a blank or unreachable URL.
        try {
            String protocol = new URL(url).getProtocol();
            if (protocol == null || !(protocol.equalsIgnoreCase("http") || protocol.equalsIgnoreCase("https"))) {
                if (URL_LOG_GUARD.add(url)) {
                    DmzNpc.LOGGER.warn("[sdu-skin] rejecting non-http(s) skin url='{}' (protocol='{}')", url, protocol);
                }
                return;
            }
        } catch (Throwable t) {
            // malformed URL: degrade to the default skin, never throw into rendering
            if (URL_LOG_GUARD.add(url)) {
                DmzNpc.LOGGER.warn("[sdu-skin] rejecting malformed skin url='{}': {}", url, t.toString());
            }
            return;
        }
        // Self-healing per-RL guard. Later calls normally no-op, BUT a resource reload releases every texture,
        // and a plain no-op would then strand this RL as the default skin for the session. So only no-op while
        // the texture is STILL registered; if it went missing, fall through and re-install under the same guard
        // entry (no remove/re-add, so no race). The placeholder is synchronous, so the next frame no-ops again:
        // at most one re-install per reload, never a per-frame storm.
        if (!INSTALLED_LOCS.add(loc)) {
            if (isStillRegistered(loc)) {
                return;
            }
            DmzNpc.LOGGER.info("[sdu-skin] installUrlTexture re-installing loc={} (texture no longer registered, likely a resource reload)", loc);
        }
        try {
            DmzNpc.LOGGER.info("[sdu-skin] installUrlTexture loc={} url='{}'", loc, url);
            // placeholder shows the default skin and is the fallback if normalize never finishes; keeps the RL
            // resolvable, so no FileNotFound/purple.
            HttpTexture placeholder = new HttpTexture(null, url, DefaultPlayerSkin.getDefaultSkin(), true, null);
            Minecraft.getInstance().getTextureManager().register(loc, placeholder);
            downloadAndNormalize(url, loc);
        } catch (Throwable t) {
            // couldn't register the placeholder: drop the guard so a later call can retry
            INSTALLED_LOCS.remove(loc);
            DmzNpc.LOGGER.warn("[sdu-skin] installUrlTexture FAILED loc={} url='{}': {}", loc, url, t.toString(), t);
        }
    }

    // True if loc maps to a real texture. Uses the two-arg getTexture(loc, default) overload, a plain lookup
    // that NEVER auto-registers a SimpleTexture the way single-arg getTexture(loc) does. Any failure (e.g. off
    // the render thread) is treated as "still registered" so an ambiguous answer never triggers a re-install storm.
    private static boolean isStillRegistered(ResourceLocation loc) {
        try {
            return Minecraft.getInstance().getTextureManager().getTexture(loc, (AbstractTexture) null) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    // download off-thread, normalize to a complete 64x64 modern skin, register as a DynamicTexture on the render
    // thread. Any failure keeps the HttpTexture placeholder.
    private static void downloadAndNormalize(String url, ResourceLocation loc) {
        DmzNpc.LOGGER.info("[sdu-skin] downloadAndNormalize START url='{}' loc={}", url, loc);
        CompletableFuture.runAsync(() -> {
            NativeImage raw = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(Minecraft.getInstance().getProxy());
                conn.setDoInput(true);
                conn.setDoOutput(false);
                conn.connect();
                try {
                    int code = conn.getResponseCode();
                    DmzNpc.LOGGER.info("[sdu-skin] downloadAndNormalize HTTP {} for url='{}'", code, url);
                    if (code / 100 != 2) {
                        throw new IllegalStateException("HTTP " + code);
                    }
                    try (InputStream in = conn.getInputStream()) {
                        raw = NativeImage.read(in);
                    }
                } finally {
                    conn.disconnect();
                }
                DmzNpc.LOGGER.info("[sdu-skin] downloadAndNormalize decoded image {}x{} for url='{}'",
                        raw.getWidth(), raw.getHeight(), url);
                NativeImage normalized = normalizeSkin(raw, url);
                // normalizeSkin owns/closes raw (or returns it); do not double-close here.
                raw = null;
                Minecraft.getInstance().execute(() -> {
                    try {
                        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(normalized));
                        DmzNpc.LOGGER.info("[sdu-skin] downloadAndNormalize registered DynamicTexture OK loc={} url='{}'",
                                loc, url);
                    } catch (Throwable t) {
                        normalized.close();
                        DmzNpc.LOGGER.warn("[sdu-skin] DynamicTexture register FAILED loc={} url='{}': {}",
                                loc, url, t.toString(), t);
                    }
                });
            } catch (Throwable t) {
                if (raw != null) {
                    raw.close();
                }
                // the HttpTexture placeholder stays as fallback
                DmzNpc.LOGGER.warn("[sdu-skin] downloadAndNormalize FAILED url='{}', keeping HttpTexture fallback: {}",
                        url, t.toString(), t);
            }
        }, Util.backgroundExecutor());
    }

    // Turns any decoded skin into a complete 64x64 modern skin. A modern 64x64 has real data in the bottom-row
    // left-arm/left-leg block (y in [48,64)); legacy layouts leave it empty, which is why left limbs render
    // invisible on our 64x64 geo. Reuses vanilla HttpTexture's exact legacy mirror. src is always consumed.
    private static NativeImage normalizeSkin(NativeImage src, String url) {
        int w = src.getWidth();
        int h = src.getHeight();

        // off-size (HD 128x128/128x64, or odd): resize to a standard size first, then recurse
        if (!(w == 64 && (h == 32 || h == 64))) {
            boolean twoToOne = h != 0 && w == h * 2;
            int tw = 64;
            int th = twoToOne ? 32 : 64;
            DmzNpc.LOGGER.info("[sdu-skin] normalize branch=RESIZE from {}x{} to {}x{} url='{}'", w, h, tw, th, url);
            NativeImage resized = new NativeImage(tw, th, false);
            try {
                src.resizeSubRectTo(0, 0, w, h, resized);
            } finally {
                src.close();
            }
            return normalizeSkin(resized, url);
        }

        // true 64x32 legacy, or a 64x64 with a fully transparent left-limb block (legacy authored on a 64x64
        // canvas): both need the legacy mirror to fill the left arm + leg
        boolean legacy = h == 32 || leftLimbsEmpty(src);
        DmzNpc.LOGGER.info("[sdu-skin] normalize branch={} at {}x{} url='{}'",
                legacy ? "LEGACY_MIRROR" : "MODERN_64x64", w, h, url);
        return processLegacySkin(src, legacy);
    }

    // true if the bottom-row left-limb block (x [0,48), y [48,64), covering left_arm UVs 32/48+48/48 and
    // left_leg UVs 16/48+0/48) is entirely alpha 0, i.e. legacy-authored
    private static boolean leftLimbsEmpty(NativeImage img) {
        for (int y = 48; y < 64; y++) {
            for (int x = 0; x < 48; x++) {
                if ((img.getPixelRGBA(x, y) >> 24 & 0xFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    // vanilla HttpTexture.processLegacySkin's pixel ops, extracted so we can drive the mirror from our own
    // legacy detection (vanilla only mirrors when source height is 32). legacy=true mirrors right arm/leg into
    // the left. Always yields a complete 64x64 image; consumes src.
    private static NativeImage processLegacySkin(NativeImage src, boolean legacy) {
        NativeImage img = new NativeImage(64, 64, true);
        img.copyFrom(src);
        src.close();
        if (legacy) {
            img.fillRect(0, 32, 64, 32, 0);
            img.copyRect(4, 16, 16, 32, 4, 4, true, false);
            img.copyRect(8, 16, 16, 32, 4, 4, true, false);
            img.copyRect(0, 20, 24, 32, 4, 12, true, false);
            img.copyRect(4, 20, 16, 32, 4, 12, true, false);
            img.copyRect(8, 20, 8, 32, 4, 12, true, false);
            img.copyRect(12, 20, 16, 32, 4, 12, true, false);
            img.copyRect(44, 16, -8, 32, 4, 4, true, false);
            img.copyRect(48, 16, -8, 32, 4, 4, true, false);
            img.copyRect(40, 20, 0, 32, 4, 12, true, false);
            img.copyRect(44, 20, -8, 32, 4, 12, true, false);
            img.copyRect(48, 20, -16, 32, 4, 12, true, false);
            img.copyRect(52, 20, -8, 32, 4, 12, true, false);
        }
        setNoAlpha(img, 0, 0, 32, 16);
        if (legacy) {
            doNotchTransparencyHack(img, 32, 0, 64, 32);
        }
        setNoAlpha(img, 0, 16, 64, 32);
        setNoAlpha(img, 16, 48, 48, 64);
        return img;
    }

    private static void doNotchTransparencyHack(NativeImage img, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if ((img.getPixelRGBA(x, y) >> 24 & 0xFF) < 128) {
                    return;
                }
            }
        }
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                img.setPixelRGBA(x, y, img.getPixelRGBA(x, y) & 0x00FFFFFF);
            }
        }
    }

    private static void setNoAlpha(NativeImage img, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                img.setPixelRGBA(x, y, img.getPixelRGBA(x, y) | 0xFF000000);
            }
        }
    }

    private static String md5(String s) throws Exception {
        byte[] hash = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}

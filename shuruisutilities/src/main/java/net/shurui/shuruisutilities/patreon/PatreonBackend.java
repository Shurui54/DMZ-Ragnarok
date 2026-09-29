package net.shurui.shuruisutilities.patreon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Thin HTTP wrapper around the Cloudflare Worker backend. Every method here BLOCKS on network I/O and must only be
 * called from {@link PatreonManager}'s off-thread worker, never from a game tick. The shared API key is read from
 * the COMMON config and sent as a header; it is NEVER included in any log line.
 */
final class PatreonBackend
{
    private PatreonBackend() {}

    private static final String API_KEY_HEADER = "X-Api-Key";

    // Built lazily and reused. Connect timeout mirrors the per-request timeout from config.
    private static volatile HttpClient client;

    private static HttpClient client()
    {
        HttpClient c = client;
        if (c == null)
        {
            synchronized (PatreonBackend.class)
            {
                c = client;
                if (c == null)
                {
                    c = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(Math.max(1, SUConfig.patreonHttpTimeoutSeconds)))
                            .build();
                    client = c;
                }
            }
        }
        return c;
    }

    private static String base()
    {
        // Effective, not raw: a blank setting means "use the baked-in default", so servers whose config file predates
        // this feature still get supporter perks without anyone editing anything.
        String url = SUConfig.patreonEffectiveBackendUrl();
        if (url == null)
            return "";
        url = url.trim();
        while (url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        return url;
    }

    /**
     * The URL a player opens in a browser to start a link themselves, with no server-held secret involved. The
     * backend attaches no Minecraft account to this flow: it hands the player a short claim code at the end, which
     * they enter in game (Cosmetics menu or {@code /patreon claim}) to bind it to whichever account is theirs.
     */
    static String startUrl()
    {
        String b = base();
        return b.isEmpty() ? "" : b + "/start";
    }

    /** Backend base URL with no trailing slash, or empty when the feature is switched off. */
    static String baseUrl()
    {
        return base();
    }

    /** True when this server holds the shared key, so it may mint link codes itself. */
    private static boolean keyed()
    {
        return SUConfig.patreonServerKeyed();
    }

    // Attach the shared key only when this server actually has one. Every endpoint the mod calls except /code works
    // without it, so an unkeyed server is fully functional rather than degraded.
    private static HttpRequest.Builder withKey(HttpRequest.Builder b)
    {
        if (keyed())
            b.header(API_KEY_HEADER, SUConfig.patreonServerApiKey);
        return b;
    }

    /** Result of POST /code: a single-use code plus the link URL the player should open. */
    static final class CodeResult
    {
        final String code;
        final String url;

        CodeResult(String code, String url)
        {
            this.code = code;
            this.url = url;
        }
    }

    /**
     * Result of GET /entitlements/{uuid}. {@code ok} distinguishes a real answer from an unreachable backend: only
     * an {@code ok} result should reset the grace window. When {@code ok} is true, {@code tier} is authoritative and
     * may be empty (meaning the player is not currently entitled).
     */
    static final class Entitlement
    {
        final boolean ok;
        final boolean linked;
        final String tier;

        private Entitlement(boolean ok, boolean linked, String tier)
        {
            this.ok = ok;
            this.linked = linked;
            this.tier = tier == null ? "" : tier;
        }

        static Entitlement unreachable()
        {
            return new Entitlement(false, false, "");
        }

        static Entitlement of(boolean linked, String tier)
        {
            return new Entitlement(true, linked, tier);
        }
    }

    /**
     * POST /code with the player's uuid and name. Returns the code + link URL, or null on any failure (the caller
     * treats null as "temporarily unavailable"). Blocks; off-thread only.
     */
    static CodeResult requestCode(UUID uuid, String name)
    {
        String b = base();
        if (b.isEmpty())
            return null;
        try
        {
            JsonObject body = new JsonObject();
            body.addProperty("uuid", uuid.toString());
            body.addProperty("name", name);

            HttpRequest req = withKey(HttpRequest.newBuilder(URI.create(b + "/code"))
                    .timeout(Duration.ofSeconds(Math.max(1, SUConfig.patreonHttpTimeoutSeconds)))
                    .header("Content-Type", "application/json"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
            {
                LoggingHandler.sulog.warn("[Patreon] /code returned HTTP {}", resp.statusCode());
                return null;
            }
            JsonObject json = DataManager.getGson().fromJson(resp.body(), JsonObject.class);
            if (json == null)
                return null;
            String code = json.has("code") && !json.get("code").isJsonNull() ? json.get("code").getAsString() : null;
            String url = json.has("url") && !json.get("url").isJsonNull() ? json.get("url").getAsString() : null;
            if (url == null || url.isBlank())
                return null;
            return new CodeResult(code, url);
        }
        catch (Exception ex)
        {
            // Never include the key or the full request in the log; only the failure kind.
            LoggingHandler.sulog.warn("[Patreon] /code request failed: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * GET /entitlements/{uuid}. Returns an {@code ok} Entitlement on any 2xx (tier may be empty), or
     * {@link Entitlement#unreachable()} on timeout / network error / non-2xx, so the caller can apply the grace
     * window. Blocks; off-thread only.
     */
    static Entitlement fetchEntitlement(UUID uuid)
    {
        String b = base();
        if (b.isEmpty())
            return Entitlement.unreachable();
        try
        {
            HttpRequest req = withKey(HttpRequest.newBuilder(URI.create(b + "/entitlements/" + uuid))
                    .timeout(Duration.ofSeconds(Math.max(1, SUConfig.patreonHttpTimeoutSeconds))))
                    .GET()
                    .build();

            HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
            {
                LoggingHandler.sulog.warn("[Patreon] /entitlements returned HTTP {}", resp.statusCode());
                return Entitlement.unreachable();
            }
            JsonObject json = DataManager.getGson().fromJson(resp.body(), JsonObject.class);
            if (json == null)
                return Entitlement.unreachable();
            String tier = json.has("tier") && !json.get("tier").isJsonNull() ? json.get("tier").getAsString() : "";
            boolean linked = json.has("linked") && !json.get("linked").isJsonNull()
                    ? json.get("linked").getAsBoolean()
                    : (tier != null && !tier.isBlank());
            return Entitlement.of(linked, tier);
        }
        catch (Exception ex)
        {
            LoggingHandler.sulog.warn("[Patreon] /entitlements request failed: " + ex.getClass().getSimpleName());
            return Entitlement.unreachable();
        }
    }

    /** Outcome of POST /claim. {@code tier} is meaningful only when {@code ok}; it may still be empty (linked, no pledge). */
    static final class ClaimResult
    {
        final boolean ok;
        /** True only for a definite "that code is wrong or used up", as opposed to a transient failure. */
        final boolean rejected;
        final String tier;

        private ClaimResult(boolean ok, boolean rejected, String tier)
        {
            this.ok = ok;
            this.rejected = rejected;
            this.tier = tier == null ? "" : tier;
        }

        static ClaimResult success(String tier)
        {
            return new ClaimResult(true, false, tier);
        }

        static ClaimResult rejected()
        {
            return new ClaimResult(false, true, "");
        }

        static ClaimResult failed()
        {
            return new ClaimResult(false, false, "");
        }
    }

    /**
     * POST /claim: bind the Patreon account behind a browser-issued claim code to this player. Needs no API key (the
     * code itself is the proof, and it was shown only to whoever completed the Patreon login), so this is the path
     * that works on every server. Blocks; off-thread only.
     */
    static ClaimResult claim(UUID uuid, String name, String claimCode)
    {
        String b = base();
        if (b.isEmpty())
            return ClaimResult.failed();
        try
        {
            JsonObject body = new JsonObject();
            body.addProperty("claim", claimCode);
            body.addProperty("uuid", uuid.toString());
            body.addProperty("name", name);

            HttpRequest req = HttpRequest.newBuilder(URI.create(b + "/claim"))
                    .timeout(Duration.ofSeconds(Math.max(1, SUConfig.patreonHttpTimeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status / 100 == 2)
            {
                JsonObject json = DataManager.getGson().fromJson(resp.body(), JsonObject.class);
                String tier = json != null && json.has("tier") && !json.get("tier").isJsonNull()
                        ? json.get("tier").getAsString()
                        : "";
                return ClaimResult.success(tier);
            }
            // 400/404 mean the code is malformed, unknown or already used: a definite no, worth telling the player.
            // 429 and 5xx are transient, so they are reported as "try again" instead.
            if (status == 400 || status == 404)
                return ClaimResult.rejected();
            LoggingHandler.sulog.warn("[Patreon] /claim returned HTTP {}", status);
            return ClaimResult.failed();
        }
        catch (Exception ex)
        {
            LoggingHandler.sulog.warn("[Patreon] /claim request failed: " + ex.getClass().getSimpleName());
            return ClaimResult.failed();
        }
    }
}

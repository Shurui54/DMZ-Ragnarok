package net.shurui.shuruisutilities.patreon.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * One-click Patreon linking, client side.
 *
 * <p>The problem this solves: binding a pledge to a Minecraft account must not be something anyone can ask for on
 * anyone else's behalf, or a stranger could capture your pledge by getting you to open one link. The server-minted
 * flow solves that with a shared API key, but the key cannot ship inside a public jar. So the proof comes from the
 * player's own client instead:
 *
 * <ol>
 *   <li>Run the ordinary Mojang join handshake against a random server id. Only the real owner of the account, with a
 *       live session, can do this. The access token never leaves the client: it goes to Mojang and nowhere else.</li>
 *   <li>Hand the backend just the username and that server id. It asks Mojang whether that handshake happened, and
 *       Mojang answers with the account's canonical UUID or with nothing.</li>
 *   <li>The backend mints the same one-time code a keyed server would, and the browser half of the flow is the
 *       existing, already-proven one. The player signs in to Patreon and is bound automatically, with nothing to
 *       type and nothing to copy.</li>
 * </ol>
 *
 * <p>Everything blocking happens off the render thread; only opening the browser is posted back to it.
 */
public final class PatreonLinkClient
{
    private PatreonLinkClient() {}

    private static final int TIMEOUT_SECONDS = 15;

    /** True while a request is in flight, so a double click cannot start two handshakes. */
    private static volatile boolean busy;

    public static boolean isBusy()
    {
        return busy;
    }

    /**
     * Begin the one-click flow. On success the player's browser opens on the link page with a code already bound to
     * their account. On any failure we fall back to the browser-first flow ({@code /start}), which asks them to bring
     * a code back into the game: slower, but it always works, including for a client with no valid Mojang session.
     *
     * @param baseUrl backend base URL, as sent by the server in the cosmetics menu meta
     * @param parent  screen to return to from the link-confirm prompt
     */
    public static void begin(String baseUrl, Screen parent)
    {
        if (busy || baseUrl == null || baseUrl.isBlank())
            return;
        busy = true;

        final Minecraft mc = Minecraft.getInstance();
        final String base = trimTrailingSlashes(baseUrl.trim());

        Thread t = new Thread(() ->
        {
            String url = null;
            try
            {
                url = requestLinkUrl(mc, base);
            }
            catch (Exception ignored)
            {
                // fall through to the manual flow below
            }
            // Two outcomes. A personal link means the account was proven and the browser finishes the job on its
            // own. Otherwise we send them to the browser-first flow, which ends with a code they have to bring back
            // into the game, so the code screen is opened as the return destination: confirmLinkNow returns to
            // whatever screen it was given, so after approving the link the player lands where the code goes
            // instead of on a menu with nowhere to type it.
            final boolean auto = url != null && !url.isBlank();
            final String opened = auto ? url : base + "/start";
            mc.execute(() ->
            {
                busy = false;
                net.minecraft.client.gui.screens.Screen returnTo = auto
                        ? parent
                        : new net.shurui.shuruisutilities.client.gui.editor.PatreonCodeScreen();
                ConfirmLinkScreen.confirmLinkNow(opened, returnTo, false);
            });
        }, "SU-Patreon-Link");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Prove the account to Mojang, then trade that proof for a personal link URL. Returns null when the account
     * cannot be proven (offline session, Mojang unreachable, backend refusal), which the caller treats as "use the
     * manual flow" rather than as an error worth showing.
     */
    private static String requestLinkUrl(Minecraft mc, String base) throws Exception
    {
        User user = mc.getUser();
        if (user == null)
            return null;
        String name = user.getName();
        UUID id = user.getProfileId();
        String token = user.getAccessToken();
        if (name == null || name.isBlank() || id == null || token == null || token.isBlank())
            return null;

        // Any random string works as the server id: Mojang simply records it against the session, and the backend
        // asks about that same string moments later. Reusing one would let a stale proof be replayed, so it is fresh
        // every time.
        String serverId = UUID.randomUUID().toString().replace("-", "");

        MinecraftSessionService sessions = mc.getMinecraftSessionService();
        if (sessions == null)
            return null;
        // Throws for an offline or expired session, which is exactly the case where we want the manual fallback.
        sessions.joinServer(new GameProfile(id, name), token, serverId);

        JsonObject body = new JsonObject();
        body.addProperty("username", name);
        body.addProperty("serverId", serverId);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/begin"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2)
            return null;
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (json == null || !json.has("url") || json.get("url").isJsonNull())
            return null;
        String url = json.get("url").getAsString();
        return url.isBlank() ? null : url;
    }

    private static String trimTrailingSlashes(String s)
    {
        String out = s;
        while (out.endsWith("/"))
            out = out.substring(0, out.length() - 1);
        return out;
    }

}

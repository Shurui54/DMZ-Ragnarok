package net.shurui.shuruisutilities.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.shurui.shuruisutilities.core.config.ConfigBase;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

public class ChatConfig
{

    private static final String CATEGORY = "Chat";

    private static final String CAT_GM = "Gamemodes";

    private static final String CAT_SB = "Scoreboard";

    /** Token replaced by the player's (clickable) name in {@link #chatFormat}. */
    public static final String NAME_TOKEN = "{name}";

    /** Default chat format: rank badge (added separately) + "&lt;name&gt; " + message. */
    public static final String DEFAULT_CHAT_FORMAT = "<" + NAME_TOKEN + "> ";

    public static final String CHAT_FORMAT_HELP = "Chat name format. Use the " + NAME_TOKEN
            + " token where the player's name should go (e.g. \"<" + NAME_TOKEN + "> \"). The rank badge is added "
            + "in front automatically and the message follows after. Colours with & work.";

    private static final String MUTEDCMD_HELP = "All commands in here will be blocked if the player is muted.";

    private static final String WELCOME_MESSAGE = "Welcome messages for new players. Can be color formatted (supports script arguments)";

    private static final String LOGIN_MESSAGE = "Login message shown each time the player logs in (supports script arguments)";

    private static final String DEFAULT_WELCOME_MESSAGE = "New player @player joined the server!";

    /** The Discord the login message points people at. Shared with the main menu and multiplayer screens. */
    public static final String DISCORD_URL = "https://discord.gg/K7vFwWkKrF";

    /** The exact legacy line this suite used to ship, migrated on load. See {@link #migrateLegacyBranding}. */
    private static final String LEGACY_BRANDING_LINE = "This server is running ShuruisUtilities";

    /** The branding line as it should read now. */
    private static final String BRANDING_LINE = "This server is running DMZ Ragnarok";

    private static final List<String> DEFAULT_LOGIN_MESSAGE = new ArrayList<String>() {
        {
            add("Welcome @player.");
            add(BRANDING_LINE);
            add("For support, join our Discord: " + DISCORD_URL);
        }
    };

    /**
     * Replace the old suite branding in a login message that was saved before the merge.
     *
     * <p>Changing the DEFAULT alone would only ever reach a brand new install: every server that has run this mod
     * already has the old line written into its Chat.toml, and a default is not consulted once a value is persisted.
     * So the one EXACT legacy line is swapped in place, and the config is rewritten so the change survives.
     *
     * <p>Matched exactly, and only that line. An operator who wrote their own message keeps it verbatim, including one
     * that merely mentions the old name in passing; this only replaces the sentence this mod itself put there.
     */
    private static List<String> migrateLegacyBranding(List<String> lines)
    {
        if (lines == null)
            return null;
        // The branding line carrying the Discord as well is a SHORT-LIVED intermediate this mod itself wrote for one
        // build, before the link was cut back to the support line alone. Migrated too, or a server that ran that build
        // keeps a duplicated link forever: the legacy sentence it was matched on is already gone from its config.
        String doubled = BRANDING_LINE + " " + DISCORD_URL;
        if (!lines.contains(LEGACY_BRANDING_LINE) && !lines.contains(doubled))
            return lines;
        List<String> out = new ArrayList<>();
        for (String line : lines)
        {
            if (doubled.equals(line))
            {
                out.add(BRANDING_LINE);
                continue;
            }
            if (!LEGACY_BRANDING_LINE.equals(line))
            {
                out.add(line);
                continue;
            }
            out.add(BRANDING_LINE);
            out.add("For support, join our Discord: " + DISCORD_URL);
        }
        try
        {
            if (SUloginMessage != null)
                SUloginMessage.set(new ArrayList<>(out));
        }
        catch (Exception ignored)
        {
            // the in-memory value is already correct; failing to persist only means it migrates again next boot.
        }
        return out;
    }

    public static String gamemodeCreative;

    public static String gamemodeAdventure;

    public static String gamemodeSurvival;

    public static String chatFormat = DEFAULT_CHAT_FORMAT;

    public static String welcomeMessage;

    public static List<String> loginMessage;

    public static Set<String> mutedCommands = new HashSet<>();
    public static boolean scoreboardEnabled;

    /** Whether chat lines are written to the chat log (LogChat); applied by the chat module after each bake. */
    public static boolean logChat = true;

    static ForgeConfigSpec.ConfigValue<String> SUchatFormat;
    static ForgeConfigSpec.ConfigValue<String> SUwelcomeMessage;
    static ForgeConfigSpec.ConfigValue<List<? extends String>> SUloginMessage;
    static ForgeConfigSpec.ConfigValue<String> SUgamemodeSurvival;
    static ForgeConfigSpec.ConfigValue<String> SUgamemodeCreative;
    static ForgeConfigSpec.ConfigValue<String> SUgamemodeAdventure;
    static ForgeConfigSpec.BooleanValue SULogChat;
    static ForgeConfigSpec.ConfigValue<List<? extends String>> SUmutedCommands;
    static ForgeConfigSpec.BooleanValue SUscoreboardEnabled;

    // Update the live static field (immediate effect) and the backing config value (persistence).

    public static void setChatFormat(String v)
    {
        chatFormat = v;
        persist(SUchatFormat, v);
    }

    public static void setWelcomeMessage(String v)
    {
        welcomeMessage = v;
        persist(SUwelcomeMessage, v);
    }

    public static void setScoreboardEnabled(boolean v)
    {
        scoreboardEnabled = v;
        persist(SUscoreboardEnabled, v);
    }

    public static void setLoginMessage(List<String> v)
    {
        loginMessage = new ArrayList<>(v);
        try
        {
            if (SUloginMessage != null)
                SUloginMessage.set(new ArrayList<>(v));
        }
        catch (Exception ignored)
        {
        }
    }

    private static <T> void persist(ForgeConfigSpec.ConfigValue<T> cv, T val)
    {
        try
        {
            if (cv != null)
                cv.set(val);
        }
        catch (Exception ignored)
        {
        }
    }

    public static void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.comment("Chat configuration").push(CATEGORY);
        SUchatFormat = BUILDER.comment(CHAT_FORMAT_HELP).define("ChatFormat", DEFAULT_CHAT_FORMAT);
        SULogChat = BUILDER.comment("Log all chat messages").define("LogChat", true);

        SUwelcomeMessage = BUILDER.comment(WELCOME_MESSAGE).define("WelcomeMessage", DEFAULT_WELCOME_MESSAGE);
        SUloginMessage = BUILDER.comment(LOGIN_MESSAGE).defineList("LoginMessage", DEFAULT_LOGIN_MESSAGE,
                ConfigBase.stringValidator);
        BUILDER.pop();

        BUILDER.comment("Gamemode names").push(CAT_GM);
        SUgamemodeSurvival = BUILDER.define("Survival", "survival");
        SUgamemodeCreative = BUILDER.define("Creative", "creative");
        SUgamemodeAdventure = BUILDER.define("Adventure", "adventure");
        BUILDER.pop();

        BUILDER.push("Mute");
        SUmutedCommands = BUILDER.comment(MUTEDCMD_HELP).defineList("mutedCommands", new ArrayList<String>() {
            {
                add("me");
                add("say");
            }
        }, ConfigBase.stringValidator);
        BUILDER.pop();
        BUILDER.comment("Scoreboard").push(CAT_SB);
        SUscoreboardEnabled = BUILDER.define("Enabled", false);
        BUILDER.pop();
    }

    public static void bakeConfig(boolean reload)
    {
        chatFormat = SUchatFormat.get();
        // Migrate legacy 5-%s formats (and any format missing the {name} token) to the clean default so the
        // GUI/config edit is meaningful and old configs don't render a broken header.
        if (chatFormat == null || !chatFormat.contains(NAME_TOKEN))
        {
            if (chatFormat != null && !chatFormat.isEmpty())
                LoggingHandler.sulog.info("Chat format has no {} token; resetting to default.", NAME_TOKEN);
            chatFormat = DEFAULT_CHAT_FORMAT;
            try
            {
                SUchatFormat.set(chatFormat);
            }
            catch (Exception ignored)
            {
            }
        }

        welcomeMessage = SUwelcomeMessage.get();
        loginMessage = migrateLegacyBranding(new ArrayList<>(SUloginMessage.get()));

        gamemodeSurvival = SUgamemodeSurvival.get();
        gamemodeCreative = SUgamemodeCreative.get();
        gamemodeAdventure = SUgamemodeAdventure.get();

        mutedCommands.clear();
        mutedCommands.addAll(SUmutedCommands.get());

        scoreboardEnabled = SUscoreboardEnabled.get();

        // The chat module (in the Ragnarok Key) applies this right after baking.
        logChat = SULogChat.get();
    }

}

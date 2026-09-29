package net.shurui.shuruisutilities.util.output;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.shurui.shuruisutilities.core.misc.Translator;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.ClickEvent.Action;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

public final class ChatOutputHandler
{

    public static final char COLOR_FORMAT_CHARACTER = '\u00a7';

    public static final String CONFIG_MAIN_OUTPUT = "Output";

    static final Pattern URL_PATTERN = Pattern.compile(
            //         schema                          ipv4            OR           namespace                 port     path         ends
            //   |-----------------|        |-------------------------|  |----------------------------|    |---------| |--|   |---------------|
            "((?:(?:http|https):\\/\\/)?(?:(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|(?:[-\\w_\\.]{1,}\\.[a-z]{2,}?))(?::[0-9]{1,5})?.*?(?=[!\"\u00A7 \n]|$))",
            Pattern.CASE_INSENSITIVE);

    public static ChatFormatting chatErrorColor, chatWarningColor, chatConfirmationColor, chatNotificationColor;

    public static DiscordMessageHandlerBase discordMessageHandler = new DiscordMessageHandlerBase();

    // plain, unformatted
    public static void sendMessage(CommandSourceStack recipient, String message)
    {
        sendMessageI(recipient, Component.literal(message));
    }

    public static void sendMessage(Player recipient, String message)
    {
        sendMessageI(recipient.createCommandSourceStack(), Component.literal(message));
    }

    public static void sendMessage(CommandSourceStack recipient, MutableComponent message)
    {
        sendMessageI(recipient, message);
    }

    public static void sendMessage(Player recipient, MutableComponent message)
    {
        sendMessageI(recipient.createCommandSourceStack(), message);
    }

    // the real send; logs for connectionless fakeplayers
    public static void sendMessageI(CommandSourceStack recipient, Component message)
    {
        Entity entity = recipient.getEntity();
        // A forwarded command's stand-in has no connection, so the FakePlayer branch below would LOG this line and
        // the sender on the origin shard would never see it. Capture it instead, so it can be shipped back to them.
        if (entity instanceof net.shurui.shuruisutilities.shard.ForwardedCommandSender forwarded)
        {
            forwarded.captureFeedback(message);
            return;
        }
        if (entity instanceof FakePlayer && ((ServerPlayer) entity).connection == null)
            LoggingHandler.sulog
                    .info(String.format("Fakeplayer %s: %s", entity.getDisplayName().getString(), message.plainCopy()));
        else if (entity instanceof ServerPlayer)
        {
            recipient.sendSuccess(() -> message, false);
        }
        else
            recipient.sendSuccess(() -> message, false);
    }

    // send with color; strips formatting for non-player recipients
    public static void sendMessage(CommandSourceStack recipient, String message, ChatFormatting color)
    {
        message = formatColors(message);
        if (recipient.getEntity() instanceof Player)
        {
            MutableComponent component = Component.literal(message);
            component.withStyle(color);
            sendMessage(recipient, component);
        }
        else
            sendMessage(recipient, stripFormatting(message));
    }

    public static void sendMessage(Player recipient, String message, ChatFormatting color)
    {
        MutableComponent component = Component.literal(message);
        component.withStyle(color);
        sendMessage(recipient, component);
    }

    // to all clients
    public static void broadcast(String message)
    {
        broadcast(message, true);
    }

    public static void broadcast(String message, boolean sendToDiscord)
    {
        broadcast(Component.literal(message), sendToDiscord);
    }

    // to all clients
    public static void broadcast(Component message)
    {
        broadcast(message, true);
    }

    // to all clients, optionally mirrored to the Discord bridge
    public static void broadcast(Component message, boolean sendToDiscord)
    {
        for (ServerPlayer p : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers())
        {
        	p.sendSystemMessage(message);
        }

        if (sendToDiscord && ModuleLauncher.getModuleList().contains("DiscordBridge"))
        {
        	discordMessageHandler.sendMessage(message.getString());
        }
    }

    public static MutableComponent confirmation(String message)
    {
        return setChatColor(Component.literal(formatColors(message)), chatConfirmationColor);
    }

    public static MutableComponent notification(String message)
    {
        return setChatColor(Component.literal(formatColors(message)), chatNotificationColor);
    }

    public static MutableComponent warning(String message)
    {
        return setChatColor(Component.literal(formatColors(message)), chatWarningColor);
    }

    public static MutableComponent error(String message)
    {
        return setChatColor(Component.literal(formatColors(message)), chatErrorColor);
    }

    public static MutableComponent setChatColor(MutableComponent message, ChatFormatting color)
    {
        message.withStyle(color);
        return message;
    }

    // Per-player translatable chat path. Instead of resolving text server-side (one language
    // server-wide, the old Translator.format behaviour), we emit a translatableWithFallback
    // component: the CLIENT resolves the key in the player's own language, and falls back to the
    // English template if the key is absent client-side. The key is derived deterministically from
    // the English template by MessageKeys.keyFor, and the lang entries are generated from the same
    // method (see MessageKeyGenerator), so runtime and generated keys can never drift.
    //
    // Strings carrying manual colour codes (& or §) stay on the old server-side formatColors +
    // literal path, because the & -> § promotion happens server-side and is bypassed here.
    private static void chatTranslatable(CommandSourceStack sender, String msg, ChatFormatting color, Object... args)
    {
        if (msg == null)
        {
            return;
        }
        if (MessageKeys.hasColorCodes(msg))
        {
            // keep coloured templates on the legacy path so & codes still render
            sendMessage(sender, args.length == 0 ? msg : Translator.format(msg, args), color);
            return;
        }

        String key = MessageKeys.keyFor(msg);
        // key is derived from the raw English; the fallback is escaped so a stray % never crashes
        // TranslatableContents (the client-side lang value is escaped the same way by the generator).
        MutableComponent component = Component.translatableWithFallback(key, MessageKeys.escapeFormat(msg), args);
        component.withStyle(color);

        if (sender.getEntity() instanceof Player)
        {
            sendMessage(sender, component);
        }
        else
        {
            // console / fakeplayer: no client to resolve the key, so render the fallback plainly
            sendMessage(sender, stripFormatting(component.getString()));
        }
    }

    public static void chatError(CommandSourceStack sender, String msg, Object... args)
    {
        chatTranslatable(sender, msg, chatErrorColor, args);
    }

    public static void chatError(CommandSourceStack sender, String msg)
    {
        chatTranslatable(sender, msg, chatErrorColor);
    }

    public static void chatError(Player sender, String msg, Object... args)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatErrorColor, args);
    }

    public static void chatError(Player sender, String msg)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatErrorColor);
    }

    public static void chatConfirmation(CommandSourceStack sender, String msg, Object... args)
    {
        chatTranslatable(sender, msg, chatConfirmationColor, args);
    }

    public static void chatConfirmation(CommandSourceStack sender, String msg)
    {
        chatTranslatable(sender, msg, chatConfirmationColor);
    }

    public static void chatConfirmation(Player sender, String msg, Object... args)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatConfirmationColor, args);
    }

    public static void chatConfirmation(Player sender, String msg)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatConfirmationColor);
    }

    public static void chatWarning(CommandSourceStack sender, String msg, Object... args)
    {
        chatTranslatable(sender, msg, chatWarningColor, args);
    }

    public static void chatWarning(CommandSourceStack sender, String msg)
    {
        chatTranslatable(sender, msg, chatWarningColor);
    }

    public static void chatWarning(Player sender, String msg, Object... args)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatWarningColor, args);
    }

    public static void chatWarning(Player sender, String msg)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatWarningColor);
    }

    public static void chatNotification(CommandSourceStack sender, String msg, Object... args)
    {
        chatTranslatable(sender, msg, chatNotificationColor, args);
    }

    public static void chatNotification(CommandSourceStack sender, String msg)
    {
        chatTranslatable(sender, msg, chatNotificationColor);
    }

    public static void chatNotification(Player sender, String msg, Object... args)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatNotificationColor, args);
    }

    public static void chatNotification(Player sender, String msg)
    {
        chatTranslatable(sender.createCommandSourceStack(), msg, chatNotificationColor);
    }

    // codes after a & that get promoted to §
    private static final String COLOR_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRr";

    // & color codes -> § at display time. &0-&9/&a-&f/&k-&o/&r (case-insensitive, emitted lower), && escapes a
    // literal &. null-safe: null -> "" (long-standing contract).
    public static String formatColors(String message)
    {
        if (message == null) {
            return "";
        }
        if (message.indexOf('&') < 0) {
            return message;
        }

        StringBuilder sb = new StringBuilder(message.length());
        int len = message.length();
        for (int i = 0; i < len; i++)
        {
            char c = message.charAt(i);
            if (c == '&' && i + 1 < len)
            {
                char next = message.charAt(i + 1);
                if (next == '&')
                {
                    sb.append('&');
                    i++;
                    continue;
                }
                if (COLOR_CODES.indexOf(next) > -1)
                {
                    sb.append(COLOR_FORMAT_CHARACTER).append(Character.toLowerCase(next));
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // strip both & and § codes (for width/sorting); && collapses to a literal &. null-safe. the &-aware
    // companion to stripFormatting (which only drops applied § codes).
    public static String stripColors(String message)
    {
        if (message == null || (message.indexOf('&') < 0 && message.indexOf(COLOR_FORMAT_CHARACTER) < 0)) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message.length());
        int len = message.length();
        for (int i = 0; i < len; i++)
        {
            char c = message.charAt(i);
            if ((c == '&' || c == COLOR_FORMAT_CHARACTER) && i + 1 < len)
            {
                char next = message.charAt(i + 1);
                if (c == '&' && next == '&')
                {
                    sb.append('&');
                    i++;
                    continue;
                }
                if (COLOR_CODES.indexOf(next) > -1)
                {
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static final Pattern FORMAT_CODE_PATTERN;

    public static final char[] FORMAT_CHARACTERS = new char[ChatFormatting.values().length];

    static
    {
        for (ChatFormatting code : ChatFormatting.values())
            FORMAT_CHARACTERS[code.ordinal()] = code.toString().charAt(1);
        FORMAT_CODE_PATTERN = Pattern.compile(COLOR_FORMAT_CHARACTER + "([" + new String(FORMAT_CHARACTERS) + "])");
    }

    // drop applied § codes
    public static String stripFormatting(String message)
    {
        return FORMAT_CODE_PATTERN.matcher(message).replaceAll("");
    }

    public static MutableComponent clickChatComponent(String text, Action action, String uri)
    {
        MutableComponent component = Component.literal(ChatOutputHandler.formatColors(text));
        ClickEvent click = new ClickEvent(action, uri);
        component.withStyle((style) -> style.withClickEvent(click));
        return component;
    }

    public static void applyFormatting(Style chatStyle, Collection<ChatFormatting> formattings)
    {
        for (ChatFormatting format : formattings)
            applyFormatting(chatStyle, format);
    }

    public static void applyFormatting(Style chatStyle, ChatFormatting formatting)
    {
        switch (formatting)
        {
        case BOLD:
            chatStyle.withBold(true);
            break;
        case ITALIC:
            chatStyle.withItalic(true);
            break;
        case OBFUSCATED:
            chatStyle.withObfuscated(true);
            break;
        case STRIKETHROUGH:
            chatStyle.withStrikethrough(true);
            break;
        case UNDERLINE:
            chatStyle.withUnderlined(true);
            break;
        case RESET:
            break;
        default:
            chatStyle.withColor(formatting);
            break;
        }
    }

    // bare format-code chars (no \u00a7) -> ChatFormattings
    public static Collection<ChatFormatting> enumChatFormattings(String textFormats)
    {
        List<ChatFormatting> result = new ArrayList<>();
        for (int i = 0; i < textFormats.length(); i++)
        {
            char formatChar = textFormats.charAt(i);
            for (ChatFormatting format : ChatFormatting.values())
                if (FORMAT_CHARACTERS[format.ordinal()] == formatChar)
                {
                    result.add(format);
                    break;
                }
        }
        return result;
    }

    public static String getUnformattedMessage(MutableComponent message)
    {
        return message.plainCopy().toString();
    }

    public static String getFormattedMessage(MutableComponent message)
    {
        return message.copy().toString();
    }

    // public static String formatHtml(String message) {
    // StringBuilder sb = new StringBuilder();
    // int pos = 0;
    // int tagCount = 0;
    // Matcher matcher = FORMAT_CODE_PATTERN.matcher(message);
    // while (matcher.find()) {
    // sb.append(StringEscapeUtils.escapeHtml4(message.substring(pos, matcher.start())));
    // pos = matcher.end();
    // char formatChar = matcher.group(1).charAt(0);
    // for (ChatFormatting format : ChatFormatting.values()) {
    // if (FORMAT_CHARACTERS[format.ordinal()] == formatChar) {
    // sb.append("<span class=\"mcf");
    // sb.append(formatChar);
    // sb.append("\">");
    // tagCount++;
    // break;
    // }
    // }
    // }
    // sb.append(StringEscapeUtils.escapeHtml4(message.substring(pos, message.length())));
    // // for (; pos < message.length(); pos++)
    // // sb.append(message.charAt(pos));
    // for (int i = 0; i < tagCount; i++)
    // sb.append("</span>");
    // return sb.toString();
    // }

    public static boolean isStyleEmpty(Style style)
    {
        return !style.isBold() && !style.isItalic() && !style.isObfuscated() && !style.isStrikethrough()
                && !style.isUnderlined() && style.getColor() == null;
    }

    public static enum ChatFormat
    {

        PLAINTEXT/* , HTML */, MINECRAFT, DETAIL;

        public Object format(MutableComponent message)
        {
            switch (this)
            {
            // case HTML:
            // return ChatOutputHandler.formatHtml(message.getString());
            case MINECRAFT:
                return ChatOutputHandler.getFormattedMessage(message);
            case DETAIL:
                return message;
            default:
            case PLAINTEXT:
                return ChatOutputHandler.stripFormatting(ChatOutputHandler.getUnformattedMessage(message));
            }
        }

        public static ChatFormat fromString(String format)
        {
            try
            {
                return ChatFormat.valueOf(format.toUpperCase());
            }
            catch (IllegalArgumentException e)
            {
                return ChatFormat.PLAINTEXT;
            }
        }

    }

    // seconds -> readable string, only non-zero units, capped at weeks
    public static String formatTimeDurationReadable(long time, boolean showSeconds)
    {
        int weeks = (int) (TimeUnit.SECONDS.toDays(time) / 7);
        int days = (int) (TimeUnit.SECONDS.toDays(time) - 7 * weeks);
        long hours = TimeUnit.SECONDS.toHours(time) - (TimeUnit.SECONDS.toDays(time) * 24);
        long minutes = TimeUnit.SECONDS.toMinutes(time) - (TimeUnit.SECONDS.toHours(time) * 60);
        long seconds = TimeUnit.SECONDS.toSeconds(time) - (TimeUnit.SECONDS.toMinutes(time) * 60);

        StringBuilder sb = new StringBuilder();
        if (weeks != 0)
            sb.append(String.format("%d weeks ", weeks));
        if (days != 0)
        {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(String.format("%d days ", days));
        }
        if (hours != 0)
        {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(String.format("%d hours ", hours));
        }
        if (minutes != 0 || !showSeconds)
        {
            if (sb.length() > 0)
                if (!showSeconds)
                    sb.append("and ");
                else
                    sb.append(", ");
            sb.append(String.format("%d minutes ", minutes));
        }
        if (showSeconds)
        {
            if (sb.length() > 0)
                sb.append("and ");
            sb.append(String.format("%d seconds ", seconds));
        }

        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    // turn urls in chat into clickable links
    public static MutableComponent filterChatLinks(String text)
    {
        // matches ipv4 or domain, with or without protocol/path
        MutableComponent ichat = Component.literal("");
        Matcher matcher = URL_PATTERN.matcher(text);
        int lastEnd = 0;

        // Find all urls
        while (matcher.find())
        {
            int start = matcher.start();
            int end = matcher.end();

            // Append the previous left overs.
            ichat.append(text.substring(lastEnd, start));
            lastEnd = end;
            String url = text.substring(start, end);
            MutableComponent link = Component.literal(url);
            link.withStyle(ChatFormatting.UNDERLINE);

            try
            {
                // add a scheme or the client crashes on click
                if ((new URI(url)).getScheme() == null)
                    url = "http://" + url;
                LoggingHandler.sulog.info("Url made: " + url);

            }
            catch (URISyntaxException e)
            {
                // Bad syntax bail out!
                ichat.append(url);
                continue;
            }

            // Set the click event and append the link.
            ClickEvent click = new ClickEvent(ClickEvent.Action.OPEN_URL, url);
            link.withStyle((style) -> style.withClickEvent(click));
            ichat.append(link);
        }
        // Append the rest of the message.
        ichat.append(text.substring(lastEnd));

        return ichat;
    }

    // millis variant
    public static String formatTimeDurationReadableMilli(long time, boolean showSeconds)
    {
        return formatTimeDurationReadable(time / 1000, showSeconds);
    }

    public static void setConfirmationColor(String color)
    {
        chatConfirmationColor = ChatFormatting.getByName(color);
        if (chatConfirmationColor == null)
            chatConfirmationColor = ChatFormatting.GREEN;
    }

    public static void setErrorColor(String color)
    {
        chatErrorColor = ChatFormatting.getByName(color);
        if (chatErrorColor == null)
            chatErrorColor = ChatFormatting.RED;
    }

    public static void setNotificationColor(String color)
    {
        chatNotificationColor = ChatFormatting.getByName(color);
        if (chatNotificationColor == null)
            chatNotificationColor = ChatFormatting.AQUA;
    }

    public static void setWarningColor(String color)
    {
        chatWarningColor = ChatFormatting.getByName(color);
        if (chatWarningColor == null)
            chatWarningColor = ChatFormatting.YELLOW;
    }

    static ForgeConfigSpec.ConfigValue<String> SUchatConfirmationColor;
    static ForgeConfigSpec.ConfigValue<String> SUchatErrorColor;
    static ForgeConfigSpec.ConfigValue<String> SUchatNotificationColor;
    static ForgeConfigSpec.ConfigValue<String> SUchatWarningColor;

    public static Builder load(Builder BUILDER, boolean isReload)
    {
        BUILDER.comment("This controls the colors of the various chats output by ShuruisUtilities."
                + "\nValid output colors are as follows:"
                + "\naqua, black, blue, dark_aqua, dark_blue, dark_gray, dark_green, dark_purple, dark_red"
                + "\ngold, gray, green, light_purple, red, white, yellow").push(CONFIG_MAIN_OUTPUT);
        SUchatConfirmationColor = BUILDER.comment("Defaults to green.").define("confirmationColor", "green");
        SUchatErrorColor = BUILDER.comment("Defaults to red.").define("errorOutputColor", "red");
        SUchatNotificationColor = BUILDER.comment("Defaults to aqua.").define("notificationOutputColor", "aqua");
        SUchatWarningColor = BUILDER.comment("Defaults to yellow.").define("warningOutputColor", "yellow");
        BUILDER.pop();
        return BUILDER;
    }

    public static void bakeConfig(boolean reload)
    {
        setConfirmationColor(SUchatConfirmationColor.get());
        setErrorColor(SUchatErrorColor.get());
        setNotificationColor(SUchatNotificationColor.get());
        setWarningColor(SUchatWarningColor.get());
    }

}

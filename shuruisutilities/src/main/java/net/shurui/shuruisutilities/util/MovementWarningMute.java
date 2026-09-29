package net.shurui.shuruisutilities.util;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

// Log4j2 filter that DENYs vanilla's spammy movement-check warnings while SU is loaded.
// SU's fast hoverbikes make ServerGamePacketListenerImpl log "moved too quickly" / "moved wrongly" (+ vehicle
// variants) near-constantly. we DENY exactly those (matched by those two substrings), scoped to that logger,
// NEUTRAL otherwise.
// mirrors sdu's DmzLogAddonMuteFilter, but that logger has no dedicated LoggerConfig, so we attach to whatever
// it resolves to (usually root) and re-check the logger name in filter() so nothing else gets gagged.
public final class MovementWarningMute extends AbstractFilter {

    private static final String PACKET_LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String PACKET_LISTENER_SIMPLE = "ServerGamePacketListenerImpl";

    private static final String MOVED_TOO_QUICKLY = "moved too quickly";
    private static final String MOVED_WRONGLY = "moved wrongly";

    private static volatile boolean installed = false;

    private MovementWarningMute() {
        super(Filter.Result.DENY, Filter.Result.NEUTRAL);
    }

    // install once; repeat calls no-op
    public static synchronized void tryInstall() {
        if (installed) {
            return;
        }
        try {
            LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            // No dedicated LoggerConfig exists for the vanilla packet listener; getLoggerConfig returns the
            // closest parent (usually root). The filter self-gates on the logger name so attaching here only
            // affects the movement-warning events, never other logging on that config.
            LoggerConfig config = cfg.getLoggerConfig(PACKET_LISTENER);
            if (config == null) {
                return;
            }
            MovementWarningMute filter = new MovementWarningMute();
            filter.start();
            config.addFilter(filter);
            ctx.updateLoggers();
            installed = true;
            LoggingHandler.sulog.info("[SU] Muted vanilla movement warnings (moved too quickly/moved wrongly).");
        } catch (Throwable t) {
            LoggingHandler.sulog.debug("[{}] Could not install movement-warning filter ({}); will retry.",
                    ShuruisUtilities.MODID, t.toString());
        }
    }

    private static boolean isPacketListener(String loggerName) {
        if (loggerName == null) {
            return false;
        }
        // Exact match on the mapped name, plus a tail match to tolerate the SRG/dev-mapped variant.
        return loggerName.equals(PACKET_LISTENER) || loggerName.endsWith(PACKET_LISTENER_SIMPLE);
    }

    private static boolean isMovementWarning(String message) {
        return message != null
                && (message.contains(MOVED_TOO_QUICKLY) || message.contains(MOVED_WRONGLY));
    }

    private static Filter.Result eval(String loggerName, Message msg) {
        if (msg == null || !isPacketListener(loggerName)) {
            return Filter.Result.NEUTRAL;
        }
        // These are parameterized logs; check both the raw format string and the fully-formatted message so
        // every variant ("moved too quickly! {},{},{}", "vehicle of {} moved wrongly!", ...) is covered.
        if (isMovementWarning(msg.getFormat()) || isMovementWarning(msg.getFormattedMessage())) {
            return Filter.Result.DENY;
        }
        return Filter.Result.NEUTRAL;
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        return eval(event.getLoggerName(), event.getMessage());
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return eval(logger == null ? null : logger.getName(), msg);
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        // params are unformatted here; substring-match the raw pattern, which carries the invariant text.
        String name = logger == null ? null : logger.getName();
        if (msg == null || !isPacketListener(name)) {
            return Filter.Result.NEUTRAL;
        }
        return isMovementWarning(msg) ? Filter.Result.DENY : Filter.Result.NEUTRAL;
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        String name = logger == null ? null : logger.getName();
        if (msg == null || !isPacketListener(name)) {
            return Filter.Result.NEUTRAL;
        }
        return isMovementWarning(msg.toString()) ? Filter.Result.DENY : Filter.Result.NEUTRAL;
    }
}

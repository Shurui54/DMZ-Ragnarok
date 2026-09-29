package net.shurui.dev.sdu.compat.dmz;

import net.shurui.dev.sdu.DmzNpc;
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

/**
 * Belt-and-braces log4j2 filter that DENYs any event on DMZ's {@code dragonminez} logger whose formatted
 * message references one of our addon modids. Catches what DMZ logs about our namespaces outside
 * {@code JsonLoadReport} (handled at the source by the mixin).
 *
 * <p>DMZ installs its own {@code LoggerConfig} named {@code "dragonminez"} with a rolling file appender, but
 * the timing relative to our setup is not guaranteed. So we attach only when the <em>exact</em> config exists
 * (never a parent such as root) and retry on later lifecycle events until it does.
 */
public final class DmzLogAddonMuteFilter extends AbstractFilter {

    private static final String DMZ_LOGGER = "dragonminez";
    private static volatile boolean installed = false;

    private DmzLogAddonMuteFilter() {
        super(Filter.Result.DENY, Filter.Result.NEUTRAL);
    }

    /**
     * Install the filter on the {@code dragonminez} LoggerConfig. Idempotent; returns without effect until the
     * exact config exists.
     */
    public static synchronized void tryInstall() {
        if (installed) {
            return;
        }
        try {
            LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            // getLoggerConfig returns the closest parent if no exact config exists; only proceed on an
            // exact match so we never gag the root logger.
            LoggerConfig config = cfg.getLoggerConfig(DMZ_LOGGER);
            if (config == null || !DMZ_LOGGER.equals(config.getName())) {
                return;
            }
            DmzLogAddonMuteFilter filter = new DmzLogAddonMuteFilter();
            filter.start();
            config.addFilter(filter);
            ctx.updateLoggers();
            installed = true;
            DmzNpc.LOGGER.info("[{}] Installed DMZ log filter to mute addon-namespace JSON diagnostics.", DmzNpc.MODID);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not install DMZ log filter yet ({}); will retry.", DmzNpc.MODID, t.toString());
        }
    }

    private static Filter.Result eval(String message) {
        return (AddonNamespaceMute.matches(message) || AddonNamespaceMute.isBenignIgnored(message))
                ? Filter.Result.DENY : Filter.Result.NEUTRAL;
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        Message msg = event.getMessage();
        return eval(msg == null ? null : msg.getFormattedMessage());
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return eval(msg == null ? null : msg.getFormattedMessage());
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return eval(msg == null ? null : msg.toString());
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        // params are unformatted here; substring-match the raw pattern, good enough for our modid tokens.
        return eval(msg);
    }
}

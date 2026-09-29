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

import java.util.List;

// Log4j2 filter that DENYs two noisy vanilla console lines while SU is loaded (see the two Rules below).
// each is scoped to its own logger + matched by an invariant substring, so real errors on those loggers
// still get through. mirrors MovementWarningMute: installed once onto the resolved LoggerConfig (usually
// root, these loggers have no dedicated config) at common setup. self-gates on logger name (exact or tail,
// to tolerate SRG/dev remapping), DENYs on substring match, NEUTRAL otherwise.
public final class VanillaLogSpamMute extends AbstractFilter {

    // one mute rule: logger (exact + tail) and the substrings to DENY on it
    private record Rule(String loggerExact, String loggerTail, List<String> substrings) {

        boolean matchesLogger(String loggerName) {
            if (loggerName == null) {
                return false;
            }
            // Exact match on the mapped name, plus a tail match to tolerate the SRG/dev-mapped variant.
            return loggerName.equals(loggerExact) || loggerName.endsWith(loggerTail);
        }

        boolean matchesMessage(String message) {
            if (message == null) {
                return false;
            }
            for (String sub : substrings) {
                if (message.contains(sub)) {
                    return true;
                }
            }
            return false;
        }
    }

    // SPAM 1: vanilla LivingEntity.die() "Named entity {} died: {}" at INFO, one line per custom-named death.
    private static final Rule NAMED_ENTITY_DIED = new Rule(
            "net.minecraft.world.entity.LivingEntity",
            "LivingEntity",
            List.of("Named entity"));

    // SPAM 2: vanilla PersistentEntitySectionManager.addEntity "Trying to add entity with duplicated UUID ...".
    // Comes from vanilla/DMZ (saga transform discard-then-add), not suite code. "already" only applies to THIS
    // logger, so it stays scoped and cannot gag "already" messages elsewhere.
    private static final Rule DUPLICATED_UUID = new Rule(
            "net.minecraft.world.level.entity.PersistentEntitySectionManager",
            "PersistentEntitySectionManager",
            List.of("duplicated UUID", "already"));

    private static final List<Rule> RULES = List.of(NAMED_ENTITY_DIED, DUPLICATED_UUID);

    private static volatile boolean installed = false;

    private VanillaLogSpamMute() {
        super(Filter.Result.DENY, Filter.Result.NEUTRAL);
    }

    // install once. idempotent + fail-safe: repeat calls no-op, install failures swallowed so startup never crashes.
    public static synchronized void tryInstall() {
        if (installed) {
            return;
        }
        try {
            LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            // These vanilla loggers have no dedicated LoggerConfig; getLoggerConfig returns the closest parent
            // (usually root). The filter self-gates on the logger name so attaching here only affects the
            // targeted spam events, never other logging on that config.
            LoggerConfig config = cfg.getLoggerConfig(NAMED_ENTITY_DIED.loggerExact());
            if (config == null) {
                return;
            }
            VanillaLogSpamMute filter = new VanillaLogSpamMute();
            filter.start();
            config.addFilter(filter);
            ctx.updateLoggers();
            installed = true;
            LoggingHandler.sulog.info("[SU] Muted vanilla spam (\"Named entity died\", \"duplicated UUID\").");
        } catch (Throwable t) {
            LoggingHandler.sulog.debug("[{}] Could not install vanilla-log-spam filter ({}); will retry.",
                    ShuruisUtilities.MODID, t.toString());
        }
    }

    private static Filter.Result eval(String loggerName, Message msg) {
        if (msg == null || loggerName == null) {
            return Filter.Result.NEUTRAL;
        }
        for (Rule rule : RULES) {
            if (!rule.matchesLogger(loggerName)) {
                continue;
            }
            // These are parameterized logs; check both the raw format string and the fully-formatted message
            // so every variant is covered.
            if (rule.matchesMessage(msg.getFormat()) || rule.matchesMessage(msg.getFormattedMessage())) {
                return Filter.Result.DENY;
            }
        }
        return Filter.Result.NEUTRAL;
    }

    private static Filter.Result evalRaw(String loggerName, String msg) {
        if (msg == null || loggerName == null) {
            return Filter.Result.NEUTRAL;
        }
        for (Rule rule : RULES) {
            if (rule.matchesLogger(loggerName) && rule.matchesMessage(msg)) {
                return Filter.Result.DENY;
            }
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
        return evalRaw(logger == null ? null : logger.getName(), msg);
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return evalRaw(logger == null ? null : logger.getName(), msg == null ? null : msg.toString());
    }
}

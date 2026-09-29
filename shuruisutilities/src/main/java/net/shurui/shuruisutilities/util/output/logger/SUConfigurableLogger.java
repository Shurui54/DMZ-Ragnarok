package net.shurui.shuruisutilities.util.output.logger;

import org.apache.logging.log4j.Marker;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

public class SUConfigurableLogger
{
    // Fatal
    public void fatal(String message)
    {
        LoggingHandler.suloger.fatal(message);
    }

    public void fatal(String string, Throwable e)
    {
        LoggingHandler.suloger.fatal(string, e);
    }

    public void fatal(String text, Object... args)
    {
        LoggingHandler.suloger.fatal(text, args);
    }

    public void fatal(Marker REGISTRIES, String string, Object... args)
    {
        LoggingHandler.suloger.fatal(REGISTRIES, string, args);
    }

    // Error
    public void error(String message)
    {
        LoggingHandler.suloger.error(message);
    }

    public void error(String string, Throwable e)
    {
        LoggingHandler.suloger.error(string, e);
    }

    public void error(String text, Object... args)
    {
        LoggingHandler.suloger.error(text, args);
    }

    public void error(Marker REGISTRIES, String string, Object... args)
    {
        LoggingHandler.suloger.error(REGISTRIES, string, args);
    }

    // Warn
    public void warn(String message)
    {
        LoggingHandler.suloger.warn(message);
    }

    public void warn(String string, Throwable e)
    {
        LoggingHandler.suloger.warn(string, e);
    }

    public void warn(String text, Object... args)
    {
        LoggingHandler.suloger.warn(text, args);
    }

    public void warn(Marker REGISTRIES, String string, Object... args)
    {
        LoggingHandler.suloger.warn(REGISTRIES, string, args);
    }

    // Info
    public void info(String message)
    {
        LoggingHandler.suloger.info(message);
    }

    public void info(String string, Throwable e)
    {
        LoggingHandler.suloger.info(string, e);
    }

    public void info(String text, Object... args)
    {
        LoggingHandler.suloger.info(text, args);
    }

    public void info(Marker REGISTRIES, String string, Object... args)
    {
        LoggingHandler.suloger.info(REGISTRIES, string, args);
    }

    // Debug
    public void debug(String message)
    {
        if (ShuruisUtilities.isDebug())
        {
            LoggingHandler.suloger.info(message);
        }
        else
        {
            LoggingHandler.suloger.debug(message);
        }
    }

    public void debug(String string, Throwable e)
    {
        if (ShuruisUtilities.isDebug())
        {
            LoggingHandler.suloger.debug(string, e);
        }
        else
        {
            LoggingHandler.suloger.debug(string, e);
        }
    }

    public void debug(String text, Object... args)
    {
        if (ShuruisUtilities.isDebug())
        {
            LoggingHandler.suloger.debug(text, args);
        }
        else
        {
            LoggingHandler.suloger.debug(text, args);
        }
    }

    public void debug(Marker REGISTRIES, String string, Object... args)
    {
        if (ShuruisUtilities.isDebug())
        {
            LoggingHandler.suloger.debug(REGISTRIES, string, args);
        }
        else
        {
            LoggingHandler.suloger.debug(REGISTRIES, string, args);
        }
    }
}

package net.shurui.shuruisutilities.core.commands.registration;

import net.shurui.shuruisutilities.core.misc.Translator;

public class SUCommandParsingException extends Exception
{
    public String error;

    public SUCommandParsingException(String message)
    {
        error = Translator.translate(message);
    }

    public SUCommandParsingException(String message, Object... args)
    {
        error = Translator.format(message, args);
    }
}
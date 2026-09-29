package net.shurui.shuruisutilities.chat;

import java.util.Date;

import net.shurui.shuruisutilities.api.UserIdent;

/**
 * One mail in a player's mailbox ({@link Mails}). Persisted inside the {@code Mails} DataManager records, so its
 * fields are the file format: never rename them. Was {@code Mailer.Mail}; the mail logic lives in the Ragnarok Key.
 */
public class Mail
{

    public UserIdent sender;

    public String message;

    public Date timestamp = new Date();

    public Mail(UserIdent sender, String message)
    {
        this.sender = sender;
        this.message = message;
    }

}

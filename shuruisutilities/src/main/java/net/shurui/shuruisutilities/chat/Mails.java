package net.shurui.shuruisutilities.chat;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.api.UserIdent;

/**
 * A player's mailbox, persisted by the DataManager under the folder named after this class's SIMPLE name
 * ({@code SUData/json/Mails}), so the name must never change. Was {@code Mailer.Mails} (same simple name, same
 * fields, so the existing files load unchanged); it stays in core while the mail logic lives in the Ragnarok Key.
 */
public class Mails
{

    public UserIdent user;

    public List<Mail> mails = new ArrayList<>();

    public Mails(UserIdent user)
    {
        this.user = user;
    }

}

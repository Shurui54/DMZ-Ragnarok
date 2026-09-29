package net.shurui.shuruisutilities.api.economy;

import net.shurui.shuruisutilities.api.UserIdent;

public interface Economy
{

    public Wallet getWallet(UserIdent player);

    // singular or plural currency term for the amount
    public String currency(long amount);

    // amount + currency label
    public String toString(long amount);

}

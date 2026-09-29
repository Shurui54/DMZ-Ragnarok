package net.shurui.shuruisutilities.api.economy;

public interface Wallet
{

    public long get();

    public void set(long value);

    public void add(long amount);

    public void add(double amount);

    // enough in the wallet to cover value?
    public boolean covers(long value);

    public boolean withdraw(long value);

    @Override
    public String toString();

}

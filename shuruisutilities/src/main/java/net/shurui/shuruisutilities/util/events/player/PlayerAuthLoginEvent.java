package net.shurui.shuruisutilities.util.events.player;

import net.minecraft.world.entity.player.Player;

// player logged into AuthLogin. fired by the auth module.
public class PlayerAuthLoginEvent extends SUPlayerEvent
{

    public PlayerAuthLoginEvent(Player player)
    {
        super(player);
    }

    public static class Success extends PlayerAuthLoginEvent
    {
        public enum Source
        {
            COMMAND, AUTOLOGIN
        }

        public Source source;

        public Success(Player player, Source source)
        {
            super(player);
            this.source = source;
        }

    }

    public static class Failure extends PlayerAuthLoginEvent
    {
        public Failure(Player player)
        {
            super(player);
        }
    }

}

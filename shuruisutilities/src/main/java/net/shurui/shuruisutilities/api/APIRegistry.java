package net.shurui.shuruisutilities.api;

import net.shurui.shuruisutilities.api.UserIdent.NpcUserIdent;
import net.shurui.shuruisutilities.api.UserIdent.ServerUserIdent;
import net.shurui.shuruisutilities.api.economy.Economy;
import net.shurui.shuruisutilities.api.modules.SUModules;
import net.shurui.shuruisutilities.api.permissions.IPermissionsHelper;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;

// central access point for all SU API functions
public class APIRegistry
{

    public static final ServerUserIdent IDENT_SERVER = UserIdent.getServer("fefefefe-fefe-fefe-fefe-fefefefefefe",
            "$SERVER");

    public static final ServerUserIdent IDENT_RCON = UserIdent.getServer("fefefefe-fefe-fefe-fefe-fefefefefecc",
            "$RCON");

    public static final ServerUserIdent IDENT_CMDBLOCK = UserIdent.getServer("fefefefe-fefe-fefe-fefe-fefefefefecb",
            "$COMMANDBLOCK");

    public static final ServerUserIdent IDENT_COMMANDFAKER = UserIdent.getServer("fefefefe-fefe-fefe-fefe-fefefefefecf",
            "$COMMANDFAKER");

    public static final NpcUserIdent IDENT_NPC = UserIdent.getNpc(null);

    public static SUModules modules = new SUModules();

    public static Economy economy;

    public static IPermissionsHelper perms;

    // no-op default so signtools/auth don't NPE when JScripting is disabled (no script engine).
    // ModuleJScripting overwrites this with itself when it loads.
    public static ScriptHandler scripts = new ScriptHandler()
    {
        @Override
        public void addScriptType(String key)
        {
            /* no-op: scripting disabled */
        }

        @Override
        public boolean runEventScripts(String key, net.minecraft.commands.CommandSourceStack sender)
        {
            return false;
        }

        @Override
        public boolean runEventScripts(String key, net.minecraft.commands.CommandSourceStack sender, Object additionalData)
        {
            return false;
        }
    };

    // if you swap this handler, chain to the old one in your impl
    public static NamedWorldHandler namedWorldHandler = new NamedWorldHandler.DefaultNamedWorldHandler();

    // SU internal event-bus
    public static final IEventBus SU_EVENTBUS = MinecraftForge.EVENT_BUS;

    // posts get dropped if the mod bus is being redirected to FORGE EVENT_BUS
    public static IEventBus getSUEventBus()
    {
        return SU_EVENTBUS;
    }
}

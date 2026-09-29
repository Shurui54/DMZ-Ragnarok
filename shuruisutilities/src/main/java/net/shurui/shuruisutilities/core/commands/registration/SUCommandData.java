package net.shurui.shuruisutilities.core.commands.registration;

import java.util.List;

import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;


public class SUCommandData
{
    final private String name;
    private boolean isRegistered;
    final private ShuruisUtilitiesCommandBuilder builder;
    private List<String> aliases;
//    private String mainName;
//    private List<String> mainAliases;

    public SUCommandData(String mainName, ShuruisUtilitiesCommandBuilder commandBuilder)
    {
        name = mainName;
        isRegistered = false;
        builder = commandBuilder;
        aliases = commandBuilder.getDefaultAliases();
    }

    public SUCommandData(ShuruisUtilitiesCommandBuilder commandBuilder)
    {
        name = commandBuilder.getName();
        isRegistered = false;
        builder = commandBuilder;
        aliases = commandBuilder.getDefaultAliases();
    }

    public String getName()
    {
        return name;
    }

    public ShuruisUtilitiesCommandBuilder getBuilder()
    {
        return builder;
    }

    public void setRegistered(boolean registered)
    {
        this.isRegistered = registered;
    }

    public boolean isRegistered()
    {
        return isRegistered;
    }

	public List<String> getAliases() {
		return aliases;
	}

	public void setAliases(List<String> aliases) {
		this.aliases = aliases;
	}

//	public String getMainName() {
//		return mainName;
//	}
//
//	public void setMainName(String mainName) {
//		this.mainName = mainName;
//	}
//
//	public List<String> getMainAliases() {
//		return mainAliases;
//	}
//
//	public void setMainAliases(List<String> mainAliases) {
//		this.mainAliases = mainAliases;
//	}
}

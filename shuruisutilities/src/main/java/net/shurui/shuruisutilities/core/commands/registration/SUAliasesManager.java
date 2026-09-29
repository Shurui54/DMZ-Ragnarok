package net.shurui.shuruisutilities.core.commands.registration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;
import net.shurui.shuruisutilities.data.v2.DataManager;
import com.google.gson.annotations.Expose;

public class SUAliasesManager
{
    /**
     * Set by the Commands module (in the Ragnarok Key since S18b) when its Commands.toml version was older than the
     * current mappings, so a stale CommandAliases.json is not loaded over the new command names.
     */
    public static volatile boolean newMappings = false;

	public SUAliasesManager() {
		loadData();
	}
	private AliasesMap aliasMap = new AliasesMap();

    public void loadCommandAliases(final SUCommandData commandData)
    {
    	if(aliasMap.getList().containsKey(commandData.getName())) {
    		commandData.setAliases(new ArrayList<>(aliasMap.getList().get(commandData.getName())));
    	}
    	else {
    		aliasMap.addAliases(commandData.getName(), new ArrayList<>(commandData.getAliases()));
    	}
//    	List<String> alias = new ArrayList<>(commandData.getAliases());
//		alias.add(commandData.getName());
//		alias.sort(Comparator.naturalOrder());
//		commandData.setMainName(alias.remove(0));
//		commandData.setMainAliases(alias);
    }

    private static File getAliasFile()
    {
        return new File(ShuruisUtilities.getSUDirectory(), "CommandAliases.json");
    }

    public void loadData()
    {
    	if (getAliasFile().exists())
        {
    		if(ModuleLauncher.getModuleList().contains("Commands")) {
    			if(!newMappings) {
    				aliasMap = DataManager.load(AliasesMap.class, getAliasFile());
    			}
    			return;
    		}
    		aliasMap = DataManager.load(AliasesMap.class, getAliasFile());
        }
    }

    public void saveData()
    {
    	DataManager.save(aliasMap, getAliasFile());
    }

	protected class AliasesMap {
		@Expose(serialize = true, deserialize = true)
		private Map<String, List<String>> aliases = new HashMap<>();
		
		public Map<String, List<String>> getList(){
			return aliases;
		}
		public void addAliases(String name, List<String> alias){
			aliases.put(name, alias);
		}
	}
}

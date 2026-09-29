package net.shurui.shuruisutilities.commons;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.VersionChecker;

public abstract class BuildInfo
{

    public static final Logger subuildinfo = LogManager.getLogger("SUUpdateChecker");

    private static String BUILD_TYPE = "Release";

    private static String buildHash = "N/A";

    public static boolean needCheckVersion = false;

    protected static boolean outdated = false;
    protected static String versionLatest = "N/A";

    // NOTE: upstream SU replaced these @_..._@ tokens at build time via an Ant
    // ReplaceTokens filter. The collapsed 1.20.1 port build dropped that step, so
    // the values are hardcoded here. SU's version scheme tracks the MC major (the
    // "16" in 16.0.x for 1.16), hence 20.0.x for 1.20.
    private static final String MC_BASE_VERSION = "1.20.1";

    protected static final String BASE_VERSION = "20"; // the 20 in 20.0.x

    protected static final String MAJOR_VERSION = "0"; // the 0 in 20.0.x

    protected static int MINOR_VERSION = 0; // the x in 20.0.x

    public static void startVersionChecks(String modid)
    {
        if (needCheckVersion)
        {
        	VersionChecker.CheckResult result = VersionChecker.getResult(ModList.get().getModContainerById(modid).get().getModInfo());
            if (result != null && (result.status() == VersionChecker.Status.OUTDATED || result.status() == VersionChecker.Status.BETA_OUTDATED))
            {
            	outdated=true;
            	versionLatest = result.target().toString();
            }
        }
    }

    public static void getBuildInfo(File jarFile)
    {
        try
        {
            if (jarFile != null)
            {
                try (JarFile jar = new JarFile(jarFile))
                {
                    Manifest manifest = jar.getManifest();
                    // These attributes are injected by SU's official release build; a locally-built
                    // (port) jar won't have them, so every read must be null-safe (was an NPE on the
                    // BuildNumber.equals(...) below when the attribute was absent).
                    String buildID = manifest.getMainAttributes().getValue("BuildID");
                    if (buildID != null)
                        buildHash = buildID;
                    String buildNumber = manifest.getMainAttributes().getValue("BuildNumber");
                    try
                    {
                        MINOR_VERSION = Integer.parseInt(buildNumber);
                    }
                    catch (NumberFormatException e)
                    {
                        if ("DEV".equals(buildNumber))
                        {
                            BUILD_TYPE = "DevBuild";
                        }
                    }
                }
            }
            else
            {
                subuildinfo.error(String.format("Unable to get SU version information (dev env / %s)", BASE_VERSION));
            }
        }
        catch (IOException e1)
        {
            subuildinfo.error(String.format("Unable to get SU version information (%s)", BASE_VERSION));
        }
    }

    public static String getCurrentVersion()
    {
        return BASE_VERSION + '.' + MAJOR_VERSION + '.' + MINOR_VERSION;
    }

    public static String getBuildHash()
    {
        return buildHash;
    }

    public static String getMinecraftVersion()
    {
        return MC_BASE_VERSION;
    }

    public static String getLatestVersion()
    {
        return versionLatest;
    }

    public static boolean isOutdated()
    {
        return outdated;
    }

    public static String getBuildType()
    {
        return BUILD_TYPE;
    }

}

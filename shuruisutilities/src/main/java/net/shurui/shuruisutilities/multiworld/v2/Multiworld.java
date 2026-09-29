package net.shurui.shuruisutilities.multiworld.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.core.misc.TeleportHelper;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.ServerUtil;
import net.shurui.shuruisutilities.util.WorldUtil;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.google.gson.annotations.Expose;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;

public class Multiworld
{
	private static String internalWorldName = "feworld";

	// Namespace for multiworld dimension ids (SUNameSpace:<internalName>, e.g. dmz_ragnarok:smp / spawn / kaiow).
	// Moved from shuruisutilities to dmz_ragnarok in the dimension/biome rename stage. A pre-migration world stored
	// these dims under the old shuruisutilities namespace and its dimension save folders live under
	// dimensions/shuruisutilities/<name>; world-tools/ns-rename renames those folders to dimensions/dmz_ragnarok/<name>
	// so the existing multiworld saves keep loading. Without migration a new empty dim would be created under the new
	// namespace instead. The persisted generator/type selector strings (e.g. shuruisutilities:void) are NOT this
	// namespace and are deliberately left unchanged, since they are stored verbatim in each Multiworld's saved data.
	public static String SUNameSpace = "dmz_ragnarok";

	private String name;

	// resource path for the dimension id (shuruisutilities:<internalName>) + save folder. Sanitised world name
	// so dims are named after what the admin chose, not feworldN. Legacy worlds fall back to feworld<id>.
	private String internalName;

	private int internalID = 0;

	private String provider;

	private String chunkGenerator;

	private String dimensionType;

	private String dimensionSetting;

	private List<String> biomes = new ArrayList<>();

	private long seed;

	// imported in "void" mode: chunks from copied region files, un-imported chunks stay empty air. Exempts the
	// world from placeVoidPlatform, which would otherwise drop a stone block at 0,64,0 on imported terrain.
	private boolean importedVoid = false;

	// custom generation seed used INSTEAD of the server seed (a "terrain" import that read the source save's
	// seed from level.dat). False = server seed.
	private boolean customSeed = false;

	private String generatorOptions;

	private GameType gameType = GameType.CREATIVE;
	// null means "no difficulty was explicitly set", so this world inherits the server global difficulty at read
	// time (see getDifficulty). An explicitly-saved value (including PEACEFUL) is honored as-is. Do NOT default this
	// to PEACEFUL: GSON preserves the initializer for JSONs lacking a "difficulty" key, which would silently force
	// unset worlds to PEACEFUL and block all MONSTER-category spawns even when the server is HARD.
	private Difficulty difficulty = null;
	private boolean allowHostileCreatures =true;
	private boolean allowPeacefulCreatures = true;

	private boolean mapFeaturesEnabled = true;

	@Expose(serialize = false)
	protected boolean worldLoaded;

	@Expose(serialize = false)
	protected boolean error;

	public Multiworld(String name, String biomeProvider, String chunkGenerator, String worldType, String dimensionSetting,
			long seed) {
		this(name, biomeProvider, chunkGenerator, worldType, dimensionSetting, seed, "");
	}

	public Multiworld(String name, String biomeProvider, String chunkGenerator, String worldType, String dimensionSetting, long seed,
			String generatorOptions) {
		this.name = name;
		this.internalName = sanitizeName(name);
		this.provider = biomeProvider;
		this.dimensionType = worldType;
		this.dimensionSetting = dimensionSetting;
		this.chunkGenerator = chunkGenerator;
		this.seed = seed;
		this.setGeneratorOptions(generatorOptions);
		this.gameType = ServerLifecycleHooks.getCurrentServer().getWorldData().getGameType();
		this.difficulty = ServerLifecycleHooks.getCurrentServer().getWorldData().getDifficulty();
		this.allowHostileCreatures = true;
		this.allowPeacefulCreatures = true;
	}

	public Multiworld(String name, String biomeProvider, String chunkGenerator, String worldType, String dimensionSetting) {
		this(name, biomeProvider, chunkGenerator, worldType, dimensionSetting, new Random().nextLong());
	}

	public void removeAllPlayersFromWorld() {
		ServerLevel overworld = ServerLifecycleHooks.getCurrentServer().overworld();
		for (ServerPlayer player : ServerUtil.getPlayerList()) {
			if (player.level().dimension().location().toString().equals(getResourceName())) {
				BlockPos playerPos = player.blockPosition();
				int y = WorldUtil.placeInWorld(player.level(), playerPos.getX(),
						playerPos.getY(), playerPos.getZ());
				WarpPoint point = new WarpPoint(overworld, playerPos.getX(), y,
						playerPos.getZ(), 0, 0);
				TeleportHelper.doTeleport(player, point);
			}
		}
	}

	public void updateWorldSettings() {
		if (!worldLoaded)
			return;
		ServerLevel worldServer = getWorldServer();
		if (worldServer == null)
			return;
		// setSpawnSettings moved from ServerLevel to ServerChunkCache in 1.20.1
		worldServer.getChunkSource().setSpawnSettings(allowHostileCreatures, allowPeacefulCreatures);
	}

	public String getName() {
		return name;
	}

	/**
	 * The namespace multiworld dimensions used BEFORE the rename stage. A world created back then still has its
	 * chunks under {@code dimensions/shuruisutilities/<name>}, and always will unless somebody moves them.
	 */
	public static final String LEGACY_NAMESPACE = "shuruisutilities";

	/**
	 * Resolved once per world, then reused. Null until the first call, which is deliberate: the answer depends on
	 * the save folder, so it cannot be computed before a server exists.
	 */
	private transient String resolvedNamespace;

	public String getResourceName() {
		return resolvedNamespace()+":"+getInternalName();
	}

	/**
	 * Which namespace THIS world's dimension id actually uses.
	 *
	 * <h2>The bug this fixes</h2>
	 * {@link #SUNameSpace} was flipped from {@code shuruisutilities} to {@code dmz_ragnarok} during the rename, and
	 * the migration that was supposed to accompany it (renaming {@code dimensions/shuruisutilities/<name>} to
	 * {@code dimensions/dmz_ragnarok/<name>}) was never run on the live server. So every configured world started
	 * resolving to an id whose save folder did not exist, and Minecraft did the only thing it can with an unknown
	 * dimension: created a brand new empty one beside the real data. That is where the duplicate entries under both
	 * names come from, and why several of the strays are the identical 2.4 MB of spawn chunks and nothing else.
	 *
	 * <h2>Why this resolves instead of migrating</h2>
	 * Moving the folders would be the tidy fix and it is NOT safe to do from here: several of these dimensions now
	 * have real chunks under BOTH names (a live scan found beerus_planet, kaiow, namekow, spawn and top all
	 * populated on both sides), so a move would have to merge or discard somebody's world. Deleting the old
	 * dimension JSON is worse still: dimension definitions are baked into level.dat at world creation, so removing
	 * one makes the ENTIRE world refuse to load, not just that dimension.
	 *
	 * <p>So nothing is moved and nothing is deleted. The id simply points at whichever namespace already holds the
	 * chunks, preferring the current one. An existing world keeps loading from exactly where its data is, and a new
	 * world is created under the current namespace as intended. The duplicates already on disk stay untouched and
	 * inert; this stops any MORE being made.
	 */
	private String resolvedNamespace() {
		if (resolvedNamespace != null) {
			return resolvedNamespace;
		}
		String internal = getInternalName();
		if (internal == null || internal.isEmpty()) {
			return SUNameSpace;
		}
		try {
			MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
			if (server != null) {
				java.nio.file.Path dims = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
						.resolve("dimensions");
				// Current namespace wins when it has chunks, so a world that HAS been migrated is unaffected.
				if (hasChunks(dims.resolve(SUNameSpace).resolve(internal))) {
					resolvedNamespace = SUNameSpace;
					return resolvedNamespace;
				}
				if (hasChunks(dims.resolve(LEGACY_NAMESPACE).resolve(internal))) {
					LoggingHandler.sulog.info(
							"[Multiworld] '{}' keeps the pre-rename dimension id {}:{}, which is where its chunks are."
							+ " Nothing has been moved or deleted.", getName(), LEGACY_NAMESPACE, internal);
					resolvedNamespace = LEGACY_NAMESPACE;
					return resolvedNamespace;
				}
			}
		} catch (Throwable t) {
			// Never let a save-folder probe stop a world loading. Falling through to the current namespace is the
			// same behaviour this had before the fix.
			LoggingHandler.sulog.debug("[Multiworld] namespace probe failed for '{}': {}", internal, t.toString());
		}
		return SUNameSpace;
	}

	/** True when this dimension folder holds at least one region file, i.e. somebody has actually been there. */
	private static boolean hasChunks(java.nio.file.Path dimensionDir) {
		java.io.File region = dimensionDir.resolve("region").toFile();
		String[] files = region.isDirectory() ? region.list() : null;
		if (files == null) {
			return false;
		}
		for (String f : files) {
			if (f.endsWith(".mca") || f.endsWith(".mcr")) {
				return true;
			}
		}
		return false;
	}

	public ServerLevel getWorldServer() {
		return ServerLifecycleHooks.getCurrentServer().getLevel(getResourceLocationUnique());
	}

	public ResourceKey<Level> getResourceLocationUnique() {
		return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(getResourceName()));
	}

	public String getBiomeProvider() {
		return provider;
	}

	public List<String> getBiomes() {
		return biomes;
	}

	public int getInternalID() {
		return internalID;
	}
	public String getInternalName() {
		if (internalName != null && !internalName.isEmpty())
			return internalName;
		return internalWorldName+internalID; // legacy worlds saved before named dimensions existed
	}

	// user-chosen world name -> valid dimension resource path (a-z 0-9 . _ - /)
	public static String sanitizeName(String name) {
		if (name == null)
			return "world";
		String s = name.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("[^a-z0-9/._-]", "_");
		return s.isEmpty() ? "world" : s;
	}

	public void setInternalID(int internalID) {
		this.internalID = internalID;
	}

	public String getDimensionSetting() {
		return dimensionSetting;
	}

	public boolean isError() {
		return error;
	}

	public boolean isLoaded() {
		return worldLoaded;
	}

	public long getSeed() {
		return seed;
	}

	public void setSeed(long seed) {
		this.seed = seed;
	}

	public boolean isImportedVoid() {
		return importedVoid;
	}

	public void setImportedVoid(boolean importedVoid) {
		this.importedVoid = importedVoid;
	}

	public boolean hasCustomSeed() {
		return customSeed;
	}

	public void setCustomSeed(boolean customSeed) {
		this.customSeed = customSeed;
	}

	public GameType getGameType() {
		return gameType;
	}

	public void setGameType(GameType gameType) {
		this.gameType = gameType;
	}

	public Difficulty getDifficulty() {
		// unset (null) inherits the server global difficulty, so a legacy multiworld with no difficulty key
		// behaves like the rest of the server (HARD spawns hostiles) instead of silently running PEACEFUL. An
		// explicit value (including PEACEFUL) is returned unchanged.
		if (difficulty != null)
			return difficulty;
		return globalDifficulty();
	}

	// server global difficulty, fallback for an unset multiworld. Never returns PEACEFUL: if the server is
	// somehow unavailable, return NORMAL so hostiles can still spawn.
	private static Difficulty globalDifficulty() {
		net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server != null)
			return server.getWorldData().getDifficulty();
		return Difficulty.NORMAL;
	}

	public void setDifficulty(Difficulty difficulty) {
		this.difficulty = difficulty;
		// push it onto the live level's MultiworldLevelData so a runtime change takes effect immediately (hostile
		// spawn rules re-check level.getDifficulty()). If unloaded, the field above is authoritative and gets
		// seeded into MultiworldLevelData on next load.
		ServerLevel worldServer = getWorldServer();
		if (worldServer != null && worldServer.getLevelData() instanceof MultiworldLevelData mwData) {
			mwData.setDifficulty(difficulty);
		}
		updateWorldSettings();
	}

	public boolean isAllowHostileCreatures() {
		return allowHostileCreatures;
	}

	public void setAllowHostileCreatures(boolean allowHostileCreatures) {
		this.allowHostileCreatures = allowHostileCreatures;
		updateWorldSettings();
	}

	public boolean isAllowPeacefulCreatures() {
		return allowPeacefulCreatures;
	}

	public void setAllowPeacefulCreatures(boolean allowPeacefulCreatures) {
		this.allowPeacefulCreatures = allowPeacefulCreatures;
		updateWorldSettings();
	}

	public String getGeneratorOptions() {
		return generatorOptions;
	}

	public void setGeneratorOptions(String generatorOptions) {
		this.generatorOptions = generatorOptions;
	}

	public String getDimensionType() {
		return dimensionType;
	}

	public String getChunkGenerator() {
		return chunkGenerator;
	}

	protected void save() {
		DataManager.getInstance().save(this, getInternalName());
	}

	protected void delete() {
		DataManager.getInstance().delete(this.getClass(), getInternalName());
	}
     

	public void teleport(ServerPlayer player, boolean instant) {
		teleport(player, getWorldServer(), instant);
	}

	public static void teleport(ServerPlayer player, ServerLevel world,
			boolean instant) {
		teleport(player, world, player.position().x, player.position().y,
				player.position().z, instant);
	}

	public static void teleport(ServerPlayer player, ServerLevel world,
			double x, double y, double z, boolean instant) {
		boolean worldChange = player.level().dimension() != world.dimension();
		if (worldChange)
			displayDepartMessage(player);

		// generate the destination chunk before probing for a safe landing spot. On a fresh world the target
		// chunk isn't loaded, so every getBlockState() reads air and placeInWorld() falls back to the top of the
		// world (above the build ceiling), which canTeleportTo() then rejects as "obstructed". Generating first
		// gives placeInWorld real terrain to land on.
		ChunkPos chunkPos = new ChunkPos(new BlockPos((int) x, (int) y, (int) z));
		world.getChunk(chunkPos.x, chunkPos.z);

		y = WorldUtil.placeInWorld(world, (int) x, (int) y, (int) z);
		WarpPoint target = new WarpPoint(world.dimension().location().toString(), x, y, z,
				player.getYRot(), player.getXRot());
		if (instant)
			TeleportHelper.checkedTeleport(player, target);
		else
			TeleportHelper.doTeleport(player, target);

		if (worldChange)
			displayWelcomeMessage(player);
	}

	public static void displayDepartMessage(ServerPlayer player) {
		String msg = "Leaving " + " (" + player.level().dimension().location().getPath() + ")";
		ChatOutputHandler.sendMessage(player, msg);
	}

	public static void displayWelcomeMessage(ServerPlayer player) {
		String msg = "Entering " + " (" + player.level().dimension().location().getPath() + ")";
		ChatOutputHandler.sendMessage(player, msg);
	}
}
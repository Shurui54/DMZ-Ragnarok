package net.shurui.shuruisutilities.worldgen;

import com.mojang.serialization.Codec;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The suite's chunk generator codecs.
 *
 * <p>ONE ENTRY, AND IT CAN NEVER BE REMOVED. A dimension's generator is written into every world that has ever
 * loaded it, by name, so deleting this registration would stop those worlds opening with the same
 * {@code Failed to load level data or datapacks} that removing a noise_settings file causes. It is append only, in
 * the same way the worldgen JSON is.
 */
public final class SuChunkGenerators
{
    private SuChunkGenerators()
    {
    }

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> REGISTER =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, ShuruisUtilities.MODID);

    /** Generates the inner generator's terrain inside a box and void everywhere else. */
    public static final RegistryObject<Codec<? extends ChunkGenerator>> BOUNDED =
            REGISTER.register("bounded", () -> BoundedChunkGenerator.CODEC);
}

package net.shurui.shuruisutilities.data.v2;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.data.v2.types.BlockType;
import net.shurui.shuruisutilities.data.v2.types.ItemStackType;
import net.shurui.shuruisutilities.data.v2.types.NBTTagCompoundType;
import net.shurui.shuruisutilities.data.v2.types.UserIdentType;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.Expose;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import net.minecraft.network.chat.MutableComponent;

public class DataManager
{

    public static final String DEFAULT_GROUP = "default";

    public static interface DataType<T> extends JsonSerializer<T>, JsonDeserializer<T>
    {
        Class<T> getType();
    }

    private static DataManager instance;

    private static Gson gson;

    private static Map<Class<?>, JsonSerializer<?>> serializers = new HashMap<>();

    private static Map<Class<?>, JsonDeserializer<?>> deserializers = new HashMap<>();

    private static boolean formatsChanged;

    private static Set<String> defaultSerializationGroups = new HashSet<>(Collections.singletonList(DEFAULT_GROUP));

    private static Set<String> serializationGroups = defaultSerializationGroups;

    private File basePath;

    static
    {
        addDataType(new UserIdentType());
        addDataType(new ItemStackType());
        addDataType(new NBTTagCompoundType());
        addDataType(new BlockType());
        addDataType(MutableComponent.class, new MutableComponent.Serializer());
        // The polymorphic world border effects (written with a "type" field naming the effect class). Registered here
        // rather than by the WorldBorder module (which lives in the Ragnarok Key since S11), so core can read a
        // border record keyless (the dragon ball scatter clamp) and a record with effects always parses.
        addDataType(new net.shurui.shuruisutilities.worldborder.WorldBorderEffectType());
    }

    public DataManager(File basePath)
    {
        this.basePath = basePath;
        LoggingHandler.sulog.debug("ShuruisUtilities: Created new Datamanager Instance");
    }

    public static DataManager getInstance()
    {
        if (instance == null)
            throw new RuntimeException("Tried to access DataManager before its initialization");
        return instance;
    }

    public static void setInstance(DataManager instance)
    {
        DataManager.instance = instance;
    }

    public static void addDataType(DataType<?> type)
    {
        serializers.put(type.getType(), type);
        deserializers.put(type.getType(), type);
        formatsChanged = true;
    }

    public static void addDataType(Class<?> clazz, Object serializer)
    {
        if (serializer instanceof JsonSerializer<?>)
            serializers.put(clazz, (JsonSerializer<?>) serializer);
        if (deserializers instanceof JsonDeserializer<?>)
            deserializers.put(clazz, (JsonDeserializer<?>) serializer);
        formatsChanged = true;
    }

    public static <T> void addSerializer(Class<T> clazz, JsonSerializer<T> type)
    {
        serializers.put(clazz, type);
        formatsChanged = true;
    }

    public static <T> void addDeserializer(Class<T> clazz, JsonDeserializer<T> type)
    {
        deserializers.put(clazz, type);
        formatsChanged = true;
    }

    public void save(Object src, String key)
    {
        save(src, getTypeFile(src.getClass(), key));
    }

    public static void save(Object src, File file)
    {
        // atomic write: serialize to a sibling temp file, then move it into place. A crash or serialization
        // throw mid-write can only corrupt the throwaway temp, leaving the good target untouched (a
        // mid-serialization throw used to leave the target truncated/empty).
        File parent = file.getParentFile();
        if (parent != null)
            parent.mkdirs();
        File temp = new File(parent, file.getName() + ".tmp");
        try
        {
            try (FileWriter out = new FileWriter(temp))
            {
                toJson(src, out);
            }
            try
            {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException atomicUnsupported)
            {
                // Some filesystems can't atomically move across their internal boundaries; fall back to a plain
                // replace. Still far safer than truncating the target before writing.
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (RuntimeException | Error | IOException e)
        {
            // Failure before the move: the original file (if any) is intact. Clean up the temp file and rethrow,
            // preserving the previous RuntimeException-on-failure contract.
            try
            {
                Files.deleteIfExists(temp.toPath());
            }
            catch (IOException cleanupFailure)
            {
                LoggingHandler.sulog.error(
                        String.format("Failed to delete temp file %s after a save error", temp.getName()),
                        cleanupFailure);
            }
            LoggingHandler.sulog.error(String.format("Error saving data to %s", file.getName()), e);
            throw new RuntimeException(e);
        }
    }

    public void saveAll(Map<?, ?> dataMap)
    {
        for (Entry<?, ?> element : dataMap.entrySet())
            save(element.getValue(), element.getKey().toString());
    }

    public static void saveAll(Map<?, ?> dataMap, File path)
    {
        for (Entry<?, ?> element : dataMap.entrySet())
            save(element.getValue(), new File(path, element.getKey() + ".json"));
    }

    public boolean delete(Class<?> clazz, String key)
    {
        File file = getTypeFile(clazz, key);
        boolean deleted = file.delete();
        if (deleted)
        {
            // An intentional delete that actually removed a file. Let the shard network turn it into a tombstone so
            // the entry does not come back from its still-present config-sync row on the next poll or reboot (the
            // "deleted portals coming back" bug). Inert without the key and off the network, and it filters to the
            // synced SU data folders itself. A merely-missing file never reaches here (delete() returned false), so
            // this can never tombstone something that was not really removed.
            net.shurui.shuruisutilities.api.key.ShardHooks.get().suDataFileDeleted(clazz.getSimpleName(), key);
        }
        return deleted;
    }

    public void deleteAll(Class<?> clazz)
    {
        try
        {
            FileUtils.deleteDirectory(getTypePath(clazz));
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public boolean exists(Class<?> clazz, String key)
    {
        File file = getTypeFile(clazz, key);
        return file.exists();
    }

    public <T> Map<String, T> loadAll(Class<T> clazz)
    {
        return loadAll(clazz, getTypePath(clazz));
    }

    public static <T> Map<String, T> loadAll(Class<T> clazz, File path)
    {
        File[] files = path.exists() ? path.listFiles() : new File[0];
        Map<String, T> objects = new HashMap<>();
        if (files != null)
            for (File file : files)
                if (!file.isDirectory() && file.getName().endsWith(".json"))
                {
                    T o;
                    try
                    {
                        o = load(clazz, file);
                    }
                    catch (JsonParseException e)
                    {
                        // a single malformed entry must NOT abort the whole load (load() used to rethrow
                        // JsonParseException, hiding EVERY sibling entry, e.g. all warps gone from one bad file).
                        // Rename the corrupt file aside (never delete) and keep going.
                        quarantineCorruptFile(file, e);
                        continue;
                    }
                    if (o != null)
                    {
                        String key = file.getName().replace(".json", "");
                        objects.put(key, o);
                    }
                }
        return objects;
    }

    // move a corrupt data file aside so it stops breaking loadAll without destroying its contents. Renames
    // <name>.json -> <name>.json.broken-<timestamp> (never deletes) and logs the path so an admin can recover.
    // If the rename fails the file is left in place (loadAll already skipped it this run).
    private static void quarantineCorruptFile(File file, Exception cause)
    {
        File broken = new File(file.getParentFile(), file.getName() + ".broken-" + System.currentTimeMillis());
        boolean renamed = file.renameTo(broken);
        if (renamed)
            LoggingHandler.sulog.error(String.format(
                    "Skipping corrupt data file \"%s\"; renamed to \"%s\" and continuing with remaining entries.",
                    file.getAbsolutePath(), broken.getName()), cause);
        else
            LoggingHandler.sulog.error(String.format(
                    "Skipping corrupt data file \"%s\" (could not rename it aside); continuing with remaining entries.",
                    file.getAbsolutePath()), cause);
    }

    public <T> T load(Class<T> clazz, String key)
    {
        return load(clazz, getTypeFile(clazz, key));
    }

    public static <T> T load(Class<T> clazz, File file)
    {
        if (!file.exists())
            return null;
        try (BufferedReader br = new BufferedReader(new FileReader(file)))
        {
            T obj = getGson().fromJson(br, clazz);
            if (obj instanceof Loadable)
                ((Loadable) obj).afterLoad();
            return obj;
        }
        catch (JsonParseException e)
        {
            LoggingHandler.sulog.error(String.format("Error parsing data file \"%s\"", file.getAbsolutePath()));
            e.printStackTrace();
            throw e;
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.error(String.format("Error loading data file \"%s\"", file.getAbsolutePath()));
            e.printStackTrace();
        }
        return null;
    }

    public static <T> T load(Type t, File file)
    {
        if (!file.exists())
            return null;
        try (BufferedReader br = new BufferedReader(new FileReader(file)))
        {
            T obj = getGson().fromJson(br, t);
            if (obj instanceof Loadable)
                ((Loadable) obj).afterLoad();
            return obj;
        }
        catch (JsonParseException e)
        {
            LoggingHandler.sulog.error(String.format("Error parsing data file \"%s\"", file.getAbsolutePath()));
            e.printStackTrace();
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.error(String.format("Error loading data file \"%s\"", file.getAbsolutePath()));
            e.printStackTrace();
        }
        return null;
    }

    // Modified Copy of DateTypeAdapter
    static class SUDateAdapter extends TypeAdapter<Date>
    {
        private final java.text.DateFormat enUsFormat;
        private final DateFormat localFormat;
        private final DateFormat iso8601Format;
        // Modern JVM (Java 9+ CLDR / Java 20+) DateFormat.MEDIUM English pattern, with and
        // without the comma before the time. Kept as fixed-pattern fallbacks so a date
        // written by one JVM can always be re-parsed here.
        private final DateFormat medUsCommaFormat;
        private final DateFormat medUsFormat;
        // Fixed-pattern writer: locale/JVM independent, parseable by every reader above.
        private final DateFormat writeFormat;

        public SUDateAdapter()
        {
            this.enUsFormat = java.text.DateFormat.getDateTimeInstance(2, 2, Locale.US);
            this.localFormat = java.text.DateFormat.getDateTimeInstance(2, 2);
            this.iso8601Format = buildIso8601Format();
            this.medUsCommaFormat = new SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH);
            this.medUsFormat = new SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.ENGLISH);
            this.writeFormat = new SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.ENGLISH);
        }

        private static DateFormat buildIso8601Format()
        {
            DateFormat iso8601Format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso8601Format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return iso8601Format;
        }

        public Date read(JsonReader in) throws IOException
        {
            if (in.peek() == JsonToken.NULL)
            {
                in.nextNull();
                return null;
            }
            else
            {
                return this.deserializeToDate(in.nextString());
            }
        }

        private synchronized Date deserializeToDate(String json)
        {
            // Modern JVMs emit U+202F (narrow no-break space) / U+00A0 (no-break space)
            // between the seconds and the AM/PM marker. Normalize to a regular space so the
            // pattern parsers below can match a string written by any JVM.
            String normalized = json.replace('\u202f', ' ').replace('\u00a0', ' ');

            DateFormat[] formats = {
                this.localFormat,
                this.enUsFormat,
                this.iso8601Format,
                SUConfig.FORMAT_GSON_COMPAT,
                this.medUsCommaFormat,
                this.medUsFormat
            };
            for (DateFormat format : formats)
            {
                try
                {
                    return format.parse(normalized);
                }
                catch (ParseException ignored)
                {
                }
            }

            // Purely-numeric strings are treated as epoch millis.
            String trimmed = normalized.trim();
            if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit))
            {
                try
                {
                    return new Date(Long.parseLong(trimmed));
                }
                catch (NumberFormatException ignored)
                {
                }
            }

            // A cosmetic timestamp must never break player logins: warn and fall back to now
            // (matches the Date field defaults in PlayerInfo) instead of throwing.
            LoggingHandler.sulog.warn(String.format("Unparseable date \"%s\"; falling back to current time", json));
            return new Date();
        }

        public synchronized void write(JsonWriter out, Date value) throws IOException
        {
            if (value == null)
            {
                out.nullValue();
            }
            else
            {
                String dateFormatAsString = this.writeFormat.format(value);
                out.value(dateFormatAsString);
            }
        }
    }

    public static Gson getGson()
    {
        if (gson == null || formatsChanged)
        {
            GsonBuilder builder = new GsonBuilder();
            builder.setPrettyPrinting();
            builder.registerTypeHierarchyAdapter(Date.class, new SUDateAdapter());
            builder.setExclusionStrategies(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f)
                {
                    Expose expose = f.getAnnotation(Expose.class);
                    if (expose != null && (!expose.serialize() || !expose.deserialize()))
                        return true;

                    SerializationGroup groupAnnot = f.getAnnotation(SerializationGroup.class);
                    return groupAnnot != null && !serializationGroups.contains(groupAnnot.name());
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz)
                {
                    return false;
                }
            });

            for (Entry<Class<?>, JsonSerializer<?>> format : serializers.entrySet())
                builder.registerTypeAdapter(format.getKey(), format.getValue());
            for (Entry<Class<?>, JsonDeserializer<?>> format : deserializers.entrySet())
                builder.registerTypeAdapter(format.getKey(), format.getValue());

            gson = builder.create();
        }
        return gson;
    }

    public static String toJson(Object src, String... groups)
    {
        try
        {
            if (groups.length > 0)
                serializationGroups = new HashSet<>(Arrays.asList(groups));
            return getGson().toJson(src);
        }
        finally
        {
            serializationGroups = defaultSerializationGroups;
        }
    }

    public static <T> T fromJson(String src, Class<T> clazz)
    {
        try
        {
            return getGson().fromJson(src, clazz);
        }
        finally
        {
            serializationGroups = defaultSerializationGroups;
        }
    }

    public static <T> T fromJson(String src, Type type)
    {
        try
        {
            return getGson().fromJson(src, type);
        }
        finally
        {
            serializationGroups = defaultSerializationGroups;
        }
    }

    public static void toJson(Object src, Appendable writer, String... groups) throws JsonIOException
    {
        try
        {
            if (groups.length > 0)
                serializationGroups = new HashSet<>(Arrays.asList(groups));
            getGson().toJson(src, writer);
        }
        finally
        {
            serializationGroups = defaultSerializationGroups;
        }
    }

    public File getBasePath()
    {
        return basePath;
    }

    public File getTypePath(Class<?> clazz)
    {
        File path = new File(basePath, clazz.getSimpleName());
        path.mkdirs();
        return path;
    }

    public File getTypeFile(Class<?> clazz, String key)
    {
        return new File(getTypePath(clazz), key + ".json");
    }

}

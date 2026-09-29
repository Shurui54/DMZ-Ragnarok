package net.shurui.shuruisutilities.devtools.parity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Network channels and their registered packets.
 *
 * <p>Two passes. First every channel in Forge's {@code NetworkRegistry.instances} (id and protocol version), which
 * captures DMZ, vanilla and third-party channels as well as ours. Second, for each of the suite's own SimpleChannel
 * holders, the per-packet detail (wire index, class, direction) read from the channel's {@code IndexedMessageCodec}.
 * All reflection is defensive: Forge's networking internals are stable within 1.20.1 / 47.x, and a miss degrades to
 * an omitted line rather than a crash.
 */
final class ChannelDump {

    private ChannelDump() {}

    static String dump() {
        List<String> lines = new ArrayList<>();
        lines.addAll(allChannels());
        lines.addAll(suitePackets());
        return ParityDump.sortedBlock("network channels + packets", lines);
    }

    /** Every registered channel id + protocol version, from NetworkRegistry.instances. */
    private static List<String> allChannels() {
        List<String> out = new ArrayList<>();
        try {
            Class<?> reg = Class.forName("net.minecraftforge.network.NetworkRegistry");
            Field instancesF = reg.getDeclaredField("instances");
            instancesF.setAccessible(true);
            Object instances = instancesF.get(null);
            if (instances instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String id = String.valueOf(e.getKey());
                    Object protocol = ParityDump.call(e.getValue(), "getNetworkProtocolVersion");
                    out.add("CHANNEL " + id + " | protocol=" + protocol);
                }
            }
        } catch (Throwable t) {
            out.add("CHANNEL <error enumerating NetworkRegistry.instances: " + t + ">");
        }
        return out;
    }

    /** Per-packet detail for the suite's own SimpleChannels. */
    private static List<String> suitePackets() {
        List<String> out = new ArrayList<>();
        for (String holder : ParityDump.channelHolders()) {
            try {
                Class<?> holderClass = Class.forName(holder);
                Class<?> simpleChannel = Class.forName("net.minecraftforge.network.simple.SimpleChannel");
                Object channel = ParityDump.firstStaticFieldOfType(holderClass, simpleChannel);
                if (channel == null) {
                    // Channels registered lazily may be null if register() has not run yet; note it and move on.
                    out.add("PACKET <holder " + holder + " has no initialised SimpleChannel>");
                    continue;
                }
                String channelId = channelId(channel);
                Object codec = ParityDump.field(channel, "indexedCodec");
                Object indicies = ParityDump.field(codec, "indicies");
                if (indicies instanceof Map<?, ?> map) {
                    for (Object handler : map.values()) {
                        Object index = ParityDump.field(handler, "index");
                        Object type = ParityDump.field(handler, "messageType");
                        Object dir = ParityDump.field(handler, "networkDirection");
                        String typeName = type instanceof Class<?> c ? c.getName() : String.valueOf(type);
                        out.add("PACKET " + channelId + " | id=" + index + " | class=" + typeName
                                + " | dir=" + direction(dir));
                    }
                } else {
                    out.add("PACKET <channel " + channelId + " codec unreadable>");
                }
            } catch (ClassNotFoundException notLoaded) {
                // A module that is not part of this build simply has no holder class; that is not an error.
                out.add("PACKET <holder " + holder + " not present in this build>");
            } catch (Throwable t) {
                out.add("PACKET <error reading holder " + holder + ": " + t + ">");
            }
        }
        return out;
    }

    private static String channelId(Object simpleChannel) {
        Object instance = ParityDump.field(simpleChannel, "instance");
        Object name = ParityDump.call(instance, "getChannelName");
        return name == null ? "<unknown>" : String.valueOf(name);
    }

    private static String direction(Object networkDirection) {
        if (networkDirection instanceof Optional<?> opt) {
            return opt.isPresent() ? String.valueOf(opt.get()) : "BOTH";
        }
        return networkDirection == null ? "BOTH" : String.valueOf(networkDirection);
    }
}

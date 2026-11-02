package net.loserver.oreradar.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Snowable;
import org.bukkit.block.data.Waterlogged;

/**
 * Every block state of a target's materials, for Paper's palette check {@code Chunk#contains}
 * (DESIGN §5 step 3), which matches exact states only: a lit redstone ore or a sideways log would
 * otherwise slip through it.
 *
 * <p>Paper 1.20.6 and later list a block's states ({@code BlockType#createBlockDataStates}); that
 * method is reached by reflection because the 1.20.1 API this plugin compiles against lacks it.
 * On 1.20.1 the states are built from the default state's properties, for the properties whose
 * values the Bukkit API exposes; a material with any other property gets no pre-filter.
 */
final class TargetStates {
    /** Beyond this many states the palette checks cost more than they save. */
    private static final int MAX_STATES = 512;
    private static final Method AS_BLOCK_TYPE = method(Material.class, "asBlockType");
    private static final Method CREATE_STATES = AS_BLOCK_TYPE == null ? null
            : method(AS_BLOCK_TYPE.getReturnType(), "createBlockDataStates");

    private TargetStates() {}

    /**
     * All states of these materials, or null when they cannot all be listed (then nothing is
     * pre-filtered).
     *
     * @throws IllegalStateException if the server's state listing fails
     */
    static List<BlockData> of(Set<Material> materials) {
        List<BlockData> out = new ArrayList<>();
        for (Material m : materials) {
            List<BlockData> states = CREATE_STATES != null ? listed(m) : derived(m);
            if (states == null || out.size() + states.size() > MAX_STATES) return null;
            out.addAll(states);
        }
        return out;
    }

    private static List<BlockData> listed(Material m) {
        try {
            Collection<?> states = (Collection<?>) CREATE_STATES.invoke(AS_BLOCK_TYPE.invoke(m));
            List<BlockData> out = new ArrayList<>(states.size());
            for (Object s : states) out.add((BlockData) s);
            return out;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot list the block states of " + m, e);
        }
    }

    /** The 1.20.1 way: the cross product of each property's values, or null for an unknown property. */
    private static List<BlockData> derived(Material m) {
        BlockData base = m.createBlockData();
        List<BlockData> states = List.of(base);
        for (String property : propertyNames(base.getAsString())) {
            List<BlockData> next = new ArrayList<>();
            for (BlockData d : states) {
                List<BlockData> variants = variants(d, property);
                if (variants == null) return null;
                next.addAll(variants);
            }
            if (next.size() > MAX_STATES) return null;
            states = next;
        }
        return states;
    }

    /** "minecraft:redstone_ore[lit=false]" → ["lit"] */
    private static List<String> propertyNames(String state) {
        List<String> out = new ArrayList<>();
        int open = state.indexOf('['), close = state.lastIndexOf(']');
        if (open < 0 || close < open) return out;
        for (String kv : state.substring(open + 1, close).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) out.add(kv.substring(0, eq).trim());
        }
        return out;
    }

    private static List<BlockData> variants(BlockData d, String property) {
        List<BlockData> out = new ArrayList<>();
        switch (property) {
            case "lit" -> {
                if (!(d instanceof Lightable)) return null;
                for (boolean v : new boolean[] {false, true}) out.add(with(d, x -> ((Lightable) x).setLit(v)));
            }
            case "waterlogged" -> {
                if (!(d instanceof Waterlogged)) return null;
                for (boolean v : new boolean[] {false, true}) out.add(with(d, x -> ((Waterlogged) x).setWaterlogged(v)));
            }
            case "powered" -> {
                if (!(d instanceof Powerable)) return null;
                for (boolean v : new boolean[] {false, true}) out.add(with(d, x -> ((Powerable) x).setPowered(v)));
            }
            case "snowy" -> {
                if (!(d instanceof Snowable)) return null;
                for (boolean v : new boolean[] {false, true}) out.add(with(d, x -> ((Snowable) x).setSnowy(v)));
            }
            case "open" -> {
                if (!(d instanceof Openable)) return null;
                for (boolean v : new boolean[] {false, true}) out.add(with(d, x -> ((Openable) x).setOpen(v)));
            }
            case "axis" -> {
                if (!(d instanceof Orientable o)) return null;
                for (Axis a : o.getAxes()) out.add(with(d, x -> ((Orientable) x).setAxis(a)));
            }
            case "facing" -> {
                if (!(d instanceof Directional dir)) return null;
                for (BlockFace f : dir.getFaces()) out.add(with(d, x -> ((Directional) x).setFacing(f)));
            }
            case "age" -> {
                if (!(d instanceof Ageable a)) return null;
                for (int v = 0; v <= a.getMaximumAge(); v++) {
                    int age = v;
                    out.add(with(d, x -> ((Ageable) x).setAge(age)));
                }
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    private interface Edit {
        void apply(BlockData d);
    }

    private static BlockData with(BlockData d, Edit edit) {
        BlockData copy = d.clone();
        edit.apply(copy);
        return copy;
    }

    private static Method method(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}

package net.loserver.oreradar.client;

import java.util.Arrays;

import net.loserver.oreradar.core.geometry.OutlineGeometry;
import net.loserver.oreradar.core.net.BlockPosPacking;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.core.util.LongHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * The last scan the server sent, ready to draw: its markers cut to the player's {@code maxOutlines}
 * (nearest first, as the server sorted them) and merged into two silhouettes, the nearest vein and
 * everything else, so the renderer can draw the nearest one brighter. The geometry is built here,
 * once per packet, never per frame. Client thread.
 */
final class ScanView {
    private final ScanPacket packet;
    /** The level the scan belongs to; its positions mean nothing in another one. */
    private final ClientLevel level;
    private long[] markers;
    private OutlineGeometry nearestVein = OutlineGeometry.EMPTY;
    private OutlineGeometry others = OutlineGeometry.EMPTY;

    ScanView(ScanPacket packet, int maxOutlines, ClientLevel level) {
        this.packet = packet;
        this.level = level;
        long[] sent = packet.found() != null ? packet.found().markers() : new long[0];
        // A copy, cut to the player's cap: the packet owns its array, and dropMined edits this one.
        this.markers = Arrays.copyOf(sent, Math.min(sent.length, Math.max(0, maxOutlines)));
        rebuild();
    }

    ScanPacket packet() {
        return packet;
    }

    ScanPacket.Status status() {
        return packet.status();
    }

    boolean isFor(ClientLevel current) {
        return level == current;
    }

    /** The nearest match's position (rounded to the server's precision), or null if the server sent none. */
    BlockPos nearest() {
        ScanPacket.Found found = packet.found();
        return found != null && found.hasNearest() ? BlockPos.of(found.nearestPos()) : null;
    }

    OutlineGeometry nearestVein() {
        return nearestVein;
    }

    OutlineGeometry others() {
        return others;
    }

    boolean hasOutlines() {
        return markers.length > 0;
    }

    /** The nearest outlined block, packed (the server sorts markers nearest first). Only when {@link #hasOutlines()}. */
    long nearestMarker() {
        return markers[0];
    }

    /** How far the server looked for more matches around the nearest one, in blocks. */
    int outlineRadius() {
        return packet.found() != null ? packet.found().outlineRadius() : 0;
    }

    /**
     * Drops markers whose block the client now sees as air (the player mined it), so a mined ore
     * disappears at once instead of at the next scan. Only loaded chunks are checked: an unloaded
     * one reads as air too. Returns true if anything changed.
     */
    boolean dropMined() {
        int kept = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long marker : markers) {
            pos.set(BlockPosPacking.x(marker), BlockPosPacking.y(marker), BlockPosPacking.z(marker));
            if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) continue;
            markers[kept++] = marker;
        }
        if (kept == markers.length) return false;
        markers = Arrays.copyOf(markers, kept);
        rebuild();
        return true;
    }

    /** Splits the markers into the vein around the nearest one (blocks touching, diagonals included) and the rest. */
    private void rebuild() {
        if (markers.length == 0) {
            nearestVein = OutlineGeometry.EMPTY;
            others = OutlineGeometry.EMPTY;
            return;
        }
        LongHashSet all = new LongHashSet(markers);
        LongHashSet vein = new LongHashSet(16);
        long[] queue = new long[markers.length];
        int head = 0;
        int tail = 0;
        queue[tail++] = markers[0];
        vein.add(markers[0]);
        while (head < tail) {
            long p = queue[head++];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long n = BlockPosPacking.offset(p, dx, dy, dz);
                        if (all.contains(n) && vein.add(n)) queue[tail++] = n;
                    }
                }
            }
        }
        long[] veinBlocks = Arrays.copyOf(queue, tail);
        long[] rest = new long[markers.length - tail];
        int n = 0;
        for (long m : markers) {
            if (!vein.contains(m)) rest[n++] = m;
        }
        // The vein touches the rest at most diagonally, so building the two apart drops no shared face.
        nearestVein = OutlineGeometry.build(veinBlocks);
        others = OutlineGeometry.build(Arrays.copyOf(rest, n));
    }
}

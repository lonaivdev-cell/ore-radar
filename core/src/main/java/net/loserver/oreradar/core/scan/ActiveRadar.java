package net.loserver.oreradar.core.scan;

import java.util.function.IntFunction;

import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.net.ScanPacket;

/**
 * Which radar drives the scan (DESIGN §5): the first one that has a target, is switched on and has
 * charge, searching main hand, off hand, hotbar, then main inventory, as far as the effective
 * {@link ActiveFrom} allows. Slots use the protocol's numbering (0-35, off hand 40).
 */
public final class ActiveRadar {
    /** A slot's radar as the platform sees it. */
    public enum State {
        /** Not a radar, or a radar without a target. */
        NONE,
        OFF,
        NO_CHARGE,
        /** Has a target, is on, and has charge (or energy is disabled). */
        READY
    }

    /** @param slot -1 when there is no radar to report */
    public record Selection(int slot, State state) {
        public static final Selection NONE = new Selection(-1, State.NONE);

        public boolean active() {
            return state == State.READY;
        }

        /** The scan status for a selection that will not scan: OFF, NO_CHARGE or IDLE. */
        public ScanPacket.Status idleStatus() {
            return switch (state) {
                case OFF -> ScanPacket.Status.OFF;
                case NO_CHARGE -> ScanPacket.Status.NO_CHARGE;
                default -> ScanPacket.Status.IDLE;
            };
        }
    }

    private ActiveRadar() {}

    /** Slots in search order. @param selectedHotbarSlot the main hand's slot, 0-8 */
    public static int[] searchOrder(int selectedHotbarSlot, ActiveFrom limit) {
        if (selectedHotbarSlot < 0 || selectedHotbarSlot >= RadarProtocol.HOTBAR_SIZE) {
            throw new IllegalArgumentException("selected hotbar slot " + selectedHotbarSlot);
        }
        int size = switch (limit) {
            case HANDS -> 2;
            case HOTBAR -> 1 + RadarProtocol.HOTBAR_SIZE;
            case INVENTORY -> 1 + RadarProtocol.INVENTORY_SIZE;
        };
        int[] out = new int[size];
        int n = 0;
        out[n++] = selectedHotbarSlot;
        out[n++] = RadarProtocol.OFFHAND_SLOT;
        int end = limit == ActiveFrom.HOTBAR ? RadarProtocol.HOTBAR_SIZE : limit == ActiveFrom.INVENTORY ? RadarProtocol.INVENTORY_SIZE : 0;
        for (int slot = 0; slot < end; slot++) {
            if (slot != selectedHotbarSlot) out[n++] = slot;
        }
        return out;
    }

    /**
     * The first READY radar in search order. If none is ready, the first radar with a target that
     * is OFF or out of charge, so the player can be told why; else {@link Selection#NONE}.
     */
    public static Selection select(int selectedHotbarSlot, ActiveFrom limit, IntFunction<State> stateOfSlot) {
        Selection fallback = Selection.NONE;
        for (int slot : searchOrder(selectedHotbarSlot, limit)) {
            State s = stateOfSlot.apply(slot);
            if (s == State.READY) return new Selection(slot, s);
            if (s != null && s != State.NONE && fallback == Selection.NONE) fallback = new Selection(slot, s);
        }
        return fallback;
    }
}

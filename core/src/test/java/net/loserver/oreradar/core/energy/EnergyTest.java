package net.loserver.oreradar.core.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.loserver.oreradar.core.config.ServerConfig;

class EnergyTest {
    static final ChargingStation.Settings STATION = ChargingStation.Settings.of(ServerConfig.defaults(false));

    @Test
    void chargeOnTheItem() {
        assertEquals(36000, ChargeMath.read(null, 36000), "a missing charge means full");
        assertEquals(0, ChargeMath.read(-5, 36000));
        assertEquals(36000, ChargeMath.read(99999, 36000));
        assertEquals(13, ChargeMath.barWidth(36000, 36000));
        assertEquals(7, ChargeMath.barWidth(18000, 36000));
        assertEquals(0, ChargeMath.barWidth(0, 36000));
        assertEquals(0x00FFFF, ChargeMath.barColor(36000, 36000), "full is cyan");
        assertEquals(0xFF0000, ChargeMath.barColor(0, 36000), "empty is red");
        assertEquals(0x00FF00, ChargeMath.barColor(24000, 36000), "two thirds is green");
        assertEquals(60, ChargeMath.minutesLeft(36000, 10), "the defaults last an hour");
        assertEquals(52, ChargeMath.minutesLeft(31200, 10));
        assertEquals(1, ChargeMath.minutesLeft(1, 10));
        assertEquals(0, ChargeMath.minutesLeft(0, 10));
        assertEquals(-1, ChargeMath.minutesLeft(100, 0));
    }

    @Test
    void itemEnergyAcceptsWholeUnitsOnly() {
        assertEquals(100, ChargeMath.receive(105, 10, 1000));
        assertEquals(0, ChargeMath.receive(9, 10, 1000), "less than one unit of charge");
        assertEquals(30, ChargeMath.receive(1000, 10, 3), "limited by the room left");
        assertEquals(0, ChargeMath.receive(1000, 10, 0));
        assertEquals(0, ChargeMath.receive(-5, 10, 10));
        assertEquals(360000L, ChargeMath.toEnergy(36000, 10));
        assertEquals(Integer.MAX_VALUE, ChargeMath.clampToInt(ChargeMath.toEnergy(10_000_000, 1_000_000)));
    }

    @Test
    void drainIsHandedOutEveryFiveSecondsWithFractionsCarried() {
        ChargeDrain d = new ChargeDrain();
        int total = 0;
        for (int t = 1; t <= 99; t++) assertEquals(0, d.tick(false, 10));
        assertEquals(49, d.pending());
        assertEquals(50, d.tick(false, 10), "10/s for 5 s");
        assertEquals(0, d.pending());

        ChargeDrain odd = new ChargeDrain();
        for (int t = 0; t < 600; t++) total += odd.tick(false, 7);
        assertEquals(7 * 30, total, "7/s for 30 s, nothing lost to rounding");

        ChargeDrain creative = new ChargeDrain();
        for (int t = 0; t < 200; t++) assertEquals(0, creative.tick(true, 10));
        assertEquals(0, creative.pending());

        ChargeDrain flushed = new ChargeDrain();
        for (int t = 0; t < 30; t++) flushed.tick(false, 10);
        assertEquals(15, flushed.flush());
        assertEquals(0, flushed.pending());
        flushed.tick(false, 10);
        flushed.reset();
        assertEquals(0, flushed.pending());

        assertEquals(0, ChargeDrain.apply(30, 50));
        assertEquals(20, ChargeDrain.apply(70, 50));
    }

    @Test
    void stationBurnsFuelOnlyWhenItFits() {
        ChargingStation s = new ChargingStation();
        ChargingStation.TickResult r = s.tick(STATION, 36000, -1);
        assertTrue(r.consumeFuel());
        assertEquals(36000, s.buffer());
        s.setBuffer(190_000, STATION);
        assertFalse(s.tick(STATION, 36000, -1).consumeFuel(), "a redstone block would overflow the buffer");
        assertTrue(s.tick(STATION, 4000, -1).consumeFuel());
        assertEquals(194_000, s.buffer());
        assertFalse(s.tick(STATION, 0, -1).consumeFuel());
    }

    @Test
    void stationChargesTheRadarAtItsRate() {
        ChargingStation s = new ChargingStation();
        s.setBuffer(1000, STATION);
        ChargingStation.TickResult r = s.tick(STATION, 0, 35_900);
        assertTrue(r.charging());
        assertEquals(36000, r.radarCharge(), "stops at the radar's capacity");
        assertEquals(900, s.buffer());
        r = s.tick(STATION, 0, 0);
        assertEquals(200, r.radarCharge(), "chargeRatePerTick");
        assertEquals(700, s.buffer());
        r = s.tick(STATION, 0, 36000);
        assertFalse(r.charging(), "a full radar is not lit");
        s.setBuffer(0, STATION);
        assertFalse(s.tick(STATION, 0, 0).charging());
        assertEquals(-1, s.tick(STATION, 0, -1).radarCharge());
        assertTrue(ChargingStation.canExtractRadar(36000, 36000));
        assertFalse(ChargingStation.canExtractRadar(35999, 36000));
    }

    @Test
    void stationConvertsEnergyKeepingFractionsAndCapsInputPerTick() {
        ChargingStation s = new ChargingStation();
        assertEquals(5, s.receiveEnergy(STATION, 5, 10, false));
        assertEquals(0, s.buffer(), "half a unit so far");
        assertEquals(5, s.receiveEnergy(STATION, 5, 10, false));
        assertEquals(1, s.buffer(), "small pushes still charge");

        assertEquals(19_990, s.receiveEnergy(STATION, 1_000_000, 10, true), "simulate: what is left of 2000 charge this tick");
        assertEquals(1, s.buffer(), "simulate changes nothing");
        assertEquals(19_990, s.receiveEnergy(STATION, 1_000_000, 10, false));
        assertEquals(2000, s.buffer());
        assertEquals(0, s.receiveEnergy(STATION, 1000, 10, false), "maxInputPerTick reached");
        s.tick(STATION, 0, -1);
        assertEquals(20_000, s.receiveEnergy(STATION, 1_000_000, 10, false), "a new tick, a new allowance");

        ChargingStation full = new ChargingStation();
        full.setBuffer(199_999, STATION);
        assertEquals(4, full.receiveEnergy(STATION, 100, 4, false), "only one unit of room left (TR Energy ratio 4)");
        assertEquals(200_000, full.buffer());
        assertEquals(0, full.receiveEnergy(STATION, 100, 4, false));
        assertEquals(800_000L, full.energyStored(4));
        assertEquals(2_000_000L, ChargingStation.maxEnergyStored(STATION, 10));
    }

    @Test
    void comparatorFollowsTheBuffer() {
        ChargingStation s = new ChargingStation();
        assertEquals(0, s.comparatorLevel(STATION));
        s.setBuffer(1, STATION);
        assertEquals(1, s.comparatorLevel(STATION));
        s.setBuffer(100_000, STATION);
        assertEquals(8, s.comparatorLevel(STATION));
        s.setBuffer(199_999, STATION);
        assertEquals(14, s.comparatorLevel(STATION));
        s.setBuffer(200_000, STATION);
        assertEquals(15, s.comparatorLevel(STATION));
        s.setBuffer(5_000_000, STATION);
        assertEquals(200_000, s.buffer(), "restored values are clamped");
        assertThrows(IllegalArgumentException.class, () -> new ChargingStation.Settings(0, 1, 1, 1));
    }

    @Test
    void aSmallerBufferAfterAReloadClampsOnTheNextTick() {
        ChargingStation s = new ChargingStation();
        s.setBuffer(150_000, STATION);
        ChargingStation.Settings smaller = new ChargingStation.Settings(100_000, 200, 2000, 36000);
        s.tick(smaller, 0, -1);
        assertEquals(100_000, s.buffer());
    }
}

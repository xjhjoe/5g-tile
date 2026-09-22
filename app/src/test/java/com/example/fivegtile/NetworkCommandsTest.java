package com.example.fivegtile;

import org.junit.Test;

import static org.junit.Assert.*;

public class NetworkCommandsTest {
    @Test public void detectsNrInSystemOutput() {
        assertTrue(NetworkCommands.hasNr("GSM|LTE|NR\n"));
        assertTrue(NetworkCommands.hasNr("NR"));
        assertFalse(NetworkCommands.hasNr("GSM|LTE"));
    }

    @Test public void parsesAndroid17HumanReadableNetworkTypeNames() {
        String android17 = "GPRS|EDGE|UMTS|CDMA|CDMA - EvDo rev. 0|CDMA - EvDo rev. A"
                + "|CDMA - 1xRTT|HSDPA|HSUPA|HSPA|CDMA - EvDo rev. B|LTE"
                + "|CDMA - eHRPD|HSPA+|GSM|LTE_CA";
        long mask = NetworkCommands.parseMask(android17);
        assertFalse((mask & NetworkCommands.NR_BIT) != 0);
        assertEquals(Long.parseLong("01001111101111111111", 2), mask);
    }

    @Test public void preservesExistingNetworkTypesWhenEnablingAndDisablingNr() {
        long original = NetworkCommands.parseMask("GSM|UMTS|LTE|LTE_CA|IWLAN|1xRTT");
        long enabled = targetMask(NetworkCommands.set5g(1, original, true));
        assertEquals(original | NetworkCommands.NR_BIT, enabled);
        assertEquals(original, targetMask(NetworkCommands.set5g(1, enabled, false)));
        assertTrue(NetworkCommands.set5g(1, original, true).contains("-s 1 "));
    }

    @Test public void matchesPreviouslyVerifiedDeviceMasks() {
        long baseline = NetworkCommands.parseMask(
                "GPRS|EDGE|UMTS|CDMA|EVDO_0|EVDO_A|1xRTT|HSDPA|HSUPA|HSPA"
                        + "|EVDO_B|LTE|EHRPD|HSPAP|GSM|LTE_CA");
        assertEquals(Long.parseLong("01001111101111111111", 2), baseline);
        assertEquals(Long.parseLong("11001111101111111111", 2),
                targetMask(NetworkCommands.set5g(0, baseline, true)));
    }

    @Test public void errorsAndEmptyRepliesCannotBeTreatedAs5gOff() {
        for (String invalid : new String[]{null, "", " ", "UNKNOWN", "LTE|",
                "Permission denied", "error: NR unavailable", "LTE|unrecognized",
                "get-allowed-network-types-for-users: No valid subscription found."}) {
            assertThrows(IllegalArgumentException.class, () -> NetworkCommands.parseMask(invalid));
        }
    }

    @Test public void parsesXiaomiMasterSwitchAndEffectiveState() {
        assertEquals(Boolean.TRUE, NetworkCommands.parseXiaomiFiveGSwitch("1\n"));
        assertEquals(Boolean.FALSE, NetworkCommands.parseXiaomiFiveGSwitch("0"));
        assertNull(NetworkCommands.parseXiaomiFiveGSwitch("null"));
        assertNull(NetworkCommands.parseXiaomiFiveGSwitch(" "));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCommands.parseXiaomiFiveGSwitch("unexpected"));

        assertTrue(NetworkCommands.isEffective5gEnabled("LTE|NR", "1"));
        assertFalse(NetworkCommands.isEffective5gEnabled("LTE|NR", "0"));
        assertFalse(NetworkCommands.isEffective5gEnabled("LTE", "1"));
        // Devices without this vendor key keep the original NR-only behavior.
        assertTrue(NetworkCommands.isEffective5gEnabled("LTE|NR", "null"));

        assertEquals("settings get global fiveg_user_enable",
                NetworkCommands.getXiaomiFiveGSwitch());
        assertEquals("settings put global fiveg_user_enable 1",
                NetworkCommands.setXiaomiFiveGSwitch(true));
        assertEquals("settings put global fiveg_user_enable 0",
                NetworkCommands.setXiaomiFiveGSwitch(false));
    }

    @Test public void refusesToDisableEveryRadioTypeOrUseInvalidSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> NetworkCommands.set5g(0, NetworkCommands.NR_BIT, false));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.get(-1));
        assertThrows(IllegalArgumentException.class, () -> NetworkCommands.get(2));
    }

    private static long targetMask(String command) {
        return Long.parseLong(command.substring(command.lastIndexOf(' ') + 1), 2);
    }
}

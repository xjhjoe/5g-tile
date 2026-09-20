package com.example.fivegtile;

import java.util.Locale;

final class NetworkCommands {
    static final long NR_BIT = 1L << 19;

    private NetworkCommands() {}

    static String get(int slot) {
        checkSlot(slot);
        return "cmd phone get-allowed-network-types-for-users -s " + slot;
    }

    static String set5g(int slot, long currentMask, boolean enable) {
        checkSlot(slot);
        long target = enable ? currentMask | NR_BIT : currentMask & ~NR_BIT;
        if (currentMask <= 0 || target == 0) {
            throw new IllegalArgumentException("网络类型为空，请先在系统设置选择自动网络模式");
        }
        return "cmd phone set-allowed-network-types-for-users -s " + slot
                + " " + Long.toBinaryString(target);
    }

    static long parseMask(String result) {
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalArgumentException("系统未返回网络类型；请检查 SIM 卡和 Shizuku");
        }

        long mask = 0;
        for (String part : result.trim().split("\\|", -1)) {
            String type = part.trim().toUpperCase(Locale.ROOT);
            int bit = bitForType(type);
            if (bit < 0) {
                String detail = result.trim();
                if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
                throw new IllegalArgumentException("无法识别网络类型：" + detail);
            }
            mask |= 1L << bit;
        }
        return mask;
    }

    static boolean hasNr(String result) {
        return (parseMask(result) & NR_BIT) != 0;
    }

    private static int bitForType(String type) {
        switch (type) {
            case "GPRS": return 0;
            case "EDGE": return 1;
            case "UMTS": return 2;
            case "CDMA": return 3;

            // Android's TelephonyManager.getNetworkTypeName() returns these
            // human-readable strings, not the internal enum constant names.
            case "CDMA - EVDO REV. 0":
            case "EVDO_0":
                return 4;
            case "CDMA - EVDO REV. A":
            case "EVDO_A":
                return 5;
            case "CDMA - 1XRTT":
            case "1XRTT":
                return 6;

            case "HSDPA": return 7;
            case "HSUPA": return 8;
            case "HSPA": return 9;
            case "IDEN": return 10;

            case "CDMA - EVDO REV. B":
            case "EVDO_B":
                return 11;
            case "LTE": return 12;

            case "CDMA - EHRPD":
            case "EHRPD":
                return 13;

            case "HSPA+":
            case "HSPAP":
                return 14;

            case "GSM": return 15;
            case "TD_SCDMA": return 16;
            case "IWLAN": return 17;
            case "LTE_CA": return 18;
            case "NR": return 19;
            default: return -1;
        }
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot > 1) {
            throw new IllegalArgumentException("请选择 SIM 1 或 SIM 2");
        }
    }
}

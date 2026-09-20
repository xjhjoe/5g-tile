package com.example.fivegtile;

import java.util.Locale;

final class NetworkCommands {
    static final long NR_BIT = 1L << 19;
    private static final String[] TYPES = {
            "GPRS", "EDGE", "UMTS", "CDMA", "EVDO_0", "EVDO_A", "1XRTT",
            "HSDPA", "HSUPA", "HSPA", "IDEN", "EVDO_B", "LTE", "EHRPD",
            "HSPAP", "GSM", "TD_SCDMA", "IWLAN", "LTE_CA", "NR"
    };

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
            int bit = -1;
            for (int i = 0; i < TYPES.length; i++) {
                if (TYPES[i].equals(type)) {
                    bit = i;
                    break;
                }
            }
            if (bit == -1) {
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

    private static void checkSlot(int slot) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("请选择 SIM 1 或 SIM 2");
    }
}

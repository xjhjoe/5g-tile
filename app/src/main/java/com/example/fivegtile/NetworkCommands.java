package com.example.fivegtile;

final class NetworkCommands {
    static final String MASK_4G_AUTO = "01001111101111111111";
    static final String MASK_5G_AUTO = "11001111101111111111";

    private NetworkCommands() {}

    static String get(int slot) {
        return "cmd phone get-allowed-network-types-for-users -s " + slot;
    }

    static String set5g(int slot, boolean enable) {
        String mask = enable ? MASK_5G_AUTO : MASK_4G_AUTO;
        return "cmd phone set-allowed-network-types-for-users -s " + slot + " " + mask;
    }

    static boolean hasNr(String result) {
        if (result == null) return false;
        for (String part : result.split("\\|")) {
            if ("NR".equals(part.trim())) return true;
        }
        return false;
    }
}

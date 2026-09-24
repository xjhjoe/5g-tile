package com.tile.screenoff;

import android.content.Context;

public class ScreenOffUserService extends IScreenOff.Stub {
    private static final int STATE_OFF = 0;
    private static final int STATE_ON = 1;
    private static final int STATE_SPECIAL = 2;

    private int state = STATE_ON;

    public ScreenOffUserService() {}

    public ScreenOffUserService(Context context) {}

    @Override
    public synchronized void setPowerMode(boolean turnOff) {
        if (turnOff) {
            if (state == STATE_ON) {
                android.os.IBinder display = ScreenController.getBuiltInDisplay();
                if (display == null) {
                    throw new IllegalStateException("无法获取内部显示器");
                }
                ScreenController.setDisplayPowerMode(display, ScreenController.POWER_MODE_OFF);
                state = STATE_SPECIAL;
            }
        } else {
            if (state == STATE_SPECIAL) {
                android.os.IBinder display = ScreenController.getBuiltInDisplay();
                if (display == null) {
                    throw new IllegalStateException("无法获取内部显示器");
                }
                ScreenController.setDisplayPowerMode(display, ScreenController.POWER_MODE_NORMAL);
                state = STATE_ON;
            }
        }
    }

    @Override
    public synchronized void updateNowScreenState(boolean isScreenOn) {
        state = isScreenOn ? STATE_ON : STATE_OFF;
    }

    @Override
    public synchronized int getNowScreenState() {
        return state;
    }

    @Override
    public void closeAndExit() {
        destroy();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}

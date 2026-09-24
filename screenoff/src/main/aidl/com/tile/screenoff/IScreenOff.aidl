package com.tile.screenoff;

interface IScreenOff {
    void destroy() = 16777114;
    void setPowerMode(boolean turnOff) = 1;
    void updateNowScreenState(boolean isScreenOn) = 2;
    int getNowScreenState() = 3;
    void closeAndExit() = 4;
}

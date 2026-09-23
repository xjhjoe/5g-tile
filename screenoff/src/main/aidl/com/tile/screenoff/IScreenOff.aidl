package com.tile.screenoff.a17;

interface IScreenOff {

    void setPowerMode(boolean turnOff);

    void updateNowScreenState(boolean isScreenOn);

    int getNowScreenState();

    void closeAndExit();

}
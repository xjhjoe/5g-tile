package com.tile.screenoff;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

final class ScreenOffBridge {
    interface Listener {
        void onServiceChanged(IScreenOff service);
    }

    private static final Object LOCK = new Object();
    private static final long BIND_RETRY_MS = 1500;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile IScreenOff service;
    private static Binding connection;
    private static long bindStarted;
    private static Context appContext;

    static {
        Shizuku.addBinderDeadListener(() -> {
            clearService();
            notifyListeners();
        });
        Shizuku.addBinderReceivedListener(() -> {
            Context context = appContext;
            if (context != null) ensureBound(context);
        });
    }

    private ScreenOffBridge() {}

    static void addListener(Listener listener) {
        if (listener == null) return;
        LISTENERS.addIfAbsent(listener);
        listener.onServiceChanged(current());
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void warmUp(Context context) {
        appContext = context.getApplicationContext();
        if (isShizukuReady()) ensureBound(appContext);
    }

    static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static IScreenOff current() {
        IScreenOff current = service;
        try {
            if (current != null && current.asBinder().pingBinder()) return current;
        } catch (Throwable ignored) {}
        return null;
    }

    static boolean awaitReady(Context context, long timeoutMs) {
        warmUp(context);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (current() != null) return true;
            if (!isShizukuReady()) return false;
            synchronized (LOCK) {
                try {
                    LOCK.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return current() != null;
    }

    private static void ensureBound(Context context) {
        synchronized (LOCK) {
            if (current() != null) return;
            long now = SystemClock.elapsedRealtime();
            if (connection != null && now - bindStarted < BIND_RETRY_MS) return;

            Binding previous = connection;
            connection = new Binding();
            bindStarted = now;
            service = null;

            try {
                if (previous != null) {
                    Shizuku.unbindUserService(args(context), previous, false);
                }
                Shizuku.bindUserService(args(context), connection);
            } catch (Throwable ignored) {
                clearService();
            }
        }
    }

    private static Shizuku.UserServiceArgs args(Context context) {
        return new Shizuku.UserServiceArgs(new ComponentName(context, ScreenOffUserService.class))
                .daemon(true)
                .processNameSuffix("screenoff")
                .tag("screenoff-controller")
                .version(1);
    }

    private static void clearService() {
        synchronized (LOCK) {
            service = null;
            bindStarted = -BIND_RETRY_MS;
            LOCK.notifyAll();
        }
    }

    private static void notifyListeners() {
        IScreenOff current = current();
        for (Listener listener : LISTENERS) {
            try {
                listener.onServiceChanged(current);
            } catch (Throwable ignored) {}
        }
    }

    private static final class Binding implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) {
                if (connection != this) return;
                service = IScreenOff.Stub.asInterface(binder);
                LOCK.notifyAll();
            }
            notifyListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                if (connection != this) return;
                clearService();
            }
            notifyListeners();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            onServiceDisconnected(name);
        }
    }
}

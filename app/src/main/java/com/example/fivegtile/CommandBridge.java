package com.example.fivegtile;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;

import rikka.shizuku.Shizuku;

final class CommandBridge {
    private static final Object LOCK = new Object();
    private static volatile ICommandService service;
    private static volatile boolean binding;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) {
                service = ICommandService.Stub.asInterface(binder);
                binding = false;
                LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                service = null;
                binding = false;
                LOCK.notifyAll();
            }
        }
    };

    private CommandBridge() {}

    static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isServiceReady() {
        ICommandService current = service;
        try {
            return current != null && current.asBinder().pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static void warmUp(Context context) {
        if (!isShizukuReady()) return;
        ensureBound(context.getApplicationContext());
    }

    static boolean awaitReady(Context context, long timeoutMs) {
        Context app = context.getApplicationContext();
        long deadline = SystemClock.uptimeMillis() + timeoutMs;

        while (SystemClock.uptimeMillis() < deadline) {
            if (isServiceReady()) return true;

            if (isShizukuReady()) {
                ensureBound(app);
                if (isServiceReady()) return true;
            }

            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0) break;
            synchronized (LOCK) {
                try {
                    LOCK.wait(Math.min(remaining, 150));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return isServiceReady();
    }

    static String exec(Context context, String command) throws Exception {
        if (!awaitReady(context, 6000)) {
            if (!isShizukuReady()) {
                throw new IllegalStateException("Shizuku 未连接或未授权");
            }
            throw new IllegalStateException("Shizuku 后台服务连接超时");
        }

        ICommandService current = service;
        try {
            return current.exec(command);
        } catch (Throwable first) {
            clearService();
            if (!awaitReady(context, 3500)) throw asException(first);
            return service.exec(command);
        }
    }

    static int remoteUid(Context context) throws Exception {
        if (!awaitReady(context, 6000)) return -1;
        try {
            return service.uid();
        } catch (Throwable first) {
            clearService();
            if (!awaitReady(context, 3500)) throw asException(first);
            return service.uid();
        }
    }

    private static void ensureBound(Context context) {
        synchronized (LOCK) {
            if (isServiceReady() || binding) return;
            binding = true;
        }

        try {
            Shizuku.bindUserService(args(context), CONNECTION);
        } catch (Throwable t) {
            synchronized (LOCK) {
                binding = false;
                service = null;
                LOCK.notifyAll();
            }
        }
    }

    private static Shizuku.UserServiceArgs args(Context context) {
        return new Shizuku.UserServiceArgs(new ComponentName(context, CommandUserService.class))
                .daemon(true)
                .processNameSuffix("fiveg")
                .tag("fiveg-command-daemon")
                .version(1);
    }

    private static void clearService() {
        synchronized (LOCK) {
            service = null;
            binding = false;
            LOCK.notifyAll();
        }
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Exception) return (Exception) t;
        return new Exception(t);
    }
}

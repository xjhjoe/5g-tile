package com.example.fivegtile;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.SystemClock;

import rikka.shizuku.Shizuku;

final class CommandBridge {
    private static final Object LOCK = new Object();
    private static final long BIND_RETRY_MS = 1500;
    private static volatile ICommandService service;
    private static Binding connection;
    private static long bindStarted;

    static {
        Shizuku.addBinderDeadListener(CommandBridge::clearService);
    }

    private static final class Binding implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) {
                if (connection != this) return;
                service = ICommandService.Stub.asInterface(binder);
                LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                if (connection != this) return;
                clearService();
            }
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

    private CommandBridge() {}

    static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isServiceReady() {
        ICommandService current = service;
        return current != null && current.asBinder().pingBinder();
    }

    static void warmUp(Context context) {
        if (isShizukuReady()) ensureBound(context.getApplicationContext());
    }

    static boolean awaitReady(Context context, long timeoutMs) {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isShizukuReady()) {
                if (isServiceReady()) return true;
                ensureBound(context.getApplicationContext());
                if (isServiceReady()) return true;
            } else if (SystemClock.elapsedRealtime() - started >= 600) {
                // Allow cold-process Binder delivery, but fail promptly if Shizuku is stopped.
                return false;
            }
            synchronized (LOCK) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try {
                    LOCK.wait(Math.min(remaining, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return isShizukuReady() && isServiceReady();
    }

    static String exec(Context context, String command) throws Exception {
        return exec(context, command, SystemClock.elapsedRealtime() + 6500);
    }

    static String exec(Context context, String command, long deadline) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            ICommandService current = requireService(context, deadline);
            try {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new IllegalStateException("操作超时，请点按重试");
                return current.execWithTimeout(command, Math.min(remaining, 3000));
            } catch (DeadObjectException e) {
                invalidate(current);
                if (attempt == 1) throw e;
                // A command failure is not evidence that the connection died.
            }
        }
        throw new IllegalStateException("后台服务已断开");
    }

    static int remoteUid(Context context) throws Exception {
        return requireService(context, SystemClock.elapsedRealtime() + 3000).uid();
    }

    private static ICommandService requireService(Context context, long deadline) {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0 || !awaitReady(context, Math.min(remaining, 3000))) {
            throw new IllegalStateException(isShizukuReady()
                    ? "后台服务连接超时，请点按重试" : "Shizuku 未运行或未授权，请打开应用检查");
        }
        ICommandService current = service;
        if (current == null) throw new IllegalStateException("后台服务已断开，请点按重试");
        return current;
    }

    private static void ensureBound(Context context) {
        synchronized (LOCK) {
            if (isServiceReady()) return;
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
            } catch (RuntimeException e) {
                clearService();
            }
        }
    }

    private static Shizuku.UserServiceArgs args(Context context) {
        return new Shizuku.UserServiceArgs(new ComponentName(context, CommandUserService.class))
                .daemon(true)
                .processNameSuffix("fiveg")
                .tag("fiveg-command-daemon")
                // Change this when remote code or the AIDL contract changes.
                .version(6);
    }

    private static void invalidate(ICommandService expected) {
        synchronized (LOCK) {
            if (service == expected) clearService();
        }
    }

    private static void clearService() {
        synchronized (LOCK) {
            service = null;
            // Retain the connection for unbind(false), but expire the pending bind.
            bindStarted = -BIND_RETRY_MS;
            LOCK.notifyAll();
        }
    }
}

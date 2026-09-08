package com.example.fivegtile;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

final class ShizukuShell {
    private static Method newProcessMethod;

    private ShizukuShell() {}

    static boolean isReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean awaitReady(long timeoutMs) {
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (isReady()) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        return isReady();
    }

    static String exec(String command) throws Exception {
        if (!Shizuku.pingBinder()) {
            throw new IllegalStateException("Shizuku 未连接");
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("未授权 Shizuku");
        }

        Process p = null;
        try {
            p = (Process) getNewProcessMethod().invoke(
                    null,
                    new String[]{"sh", "-c", command},
                    null,
                    null
            );
            if (p == null) throw new IllegalStateException("无法创建 Shizuku shell");
            String stdout = readAll(p.getInputStream());
            String stderr = readAll(p.getErrorStream());
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException("exit=" + code + " " + stderr.trim());
            }
            return stdout.trim();
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static synchronized Method getNewProcessMethod() throws Exception {
        if (newProcessMethod == null) {
            newProcessMethod = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
        }
        return newProcessMethod;
    }

    private static String readAll(InputStream input) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }
}

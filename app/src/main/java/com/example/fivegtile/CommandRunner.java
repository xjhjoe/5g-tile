package com.example.fivegtile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Drain output while the process runs, and bound both time and retained output. */
final class CommandRunner {
    static final int MAX_OUTPUT_BYTES = 16 * 1024;

    private CommandRunner() {}

    static String run(String[] command, long timeoutMs) throws Exception {
        if (timeoutMs <= 0) throw new TimeoutException("操作超时，请点按重试");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        FutureTask<String> output = new FutureTask<>(() -> drain(process.getInputStream()));
        Thread reader = new Thread(output, "fiveg-command-output");
        reader.setDaemon(true);
        reader.start();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("系统命令超时，请点按重试");
            }
            String result = output.get(Math.max(1, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS).trim();
            if (process.exitValue() != 0) {
                throw new IOException("exit=" + process.exitValue() + " " + result);
            }
            return result;
        } finally {
            process.destroyForcibly();
            output.cancel(true);
            // 'exec cmd ...' leaves no shell child holding this pipe open.
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
        }
    }

    private static String drain(InputStream input) throws IOException {
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        boolean truncated = false;
        try (InputStream stream = input) {
            while ((count = stream.read(buffer)) != -1) {
                int keep = Math.min(count, MAX_OUTPUT_BYTES - saved.size());
                if (keep > 0) saved.write(buffer, 0, keep);
                if (keep < count) truncated = true;
            }
        }
        String result = new String(saved.toByteArray(), StandardCharsets.UTF_8);
        return truncated ? result + "\n[output truncated]" : result;
    }
}

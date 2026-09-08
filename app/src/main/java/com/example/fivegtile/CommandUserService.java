package com.example.fivegtile;

import android.content.Context;
import android.os.Process;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class CommandUserService extends ICommandService.Stub {
    public CommandUserService() {}

    public CommandUserService(Context context) {}

    @Override
    public String exec(String command) throws RemoteException {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(6, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RemoteException("命令执行超时");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }

            int code = process.exitValue();
            if (code != 0) {
                throw new RemoteException("exit=" + code + " " + output.toString().trim());
            }
            return output.toString().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteException("命令被中断");
        } catch (RemoteException e) {
            throw e;
        } catch (Throwable t) {
            RemoteException e = new RemoteException(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            e.initCause(t);
            throw e;
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override
    public int uid() {
        return Process.myUid();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}

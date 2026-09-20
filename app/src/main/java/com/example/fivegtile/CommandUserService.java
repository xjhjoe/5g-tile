package com.example.fivegtile;

import android.content.Context;
import android.os.Process;

public class CommandUserService extends ICommandService.Stub {
    public CommandUserService() {}

    public CommandUserService(Context context) {}

    @Override
    public String exec(String command) {
        return execWithTimeout(command, 3000);
    }

    @Override
    public String execWithTimeout(String command, long timeoutMs) {
        try {
            return CommandRunner.run(new String[]{"/system/bin/sh", "-c", "exec " + command},
                    Math.min(timeoutMs, 3000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令被中断");
        } catch (Exception e) {
            // Binder transports this as an application error, not a dead connection.
            throw new IllegalStateException(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
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

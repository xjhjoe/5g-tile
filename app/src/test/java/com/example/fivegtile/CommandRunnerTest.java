package com.example.fivegtile;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class CommandRunnerTest {
    @Test public void readsSuccessfulOutput() throws Exception {
        assertEquals("LTE|NR", run("printf 'LTE|NR\\n'", 2000));
    }

    @Test public void reportsCommandFailure() {
        IOException error = assertThrows(IOException.class,
                () -> run("printf 'permission denied' >&2; exit 7", 2000));
        assertTrue(error.getMessage().contains("exit=7"));
        assertTrue(error.getMessage().contains("permission denied"));
    }

    @Test public void drainsOutputLargerThanPipeWithoutDeadlock() throws Exception {
        String result = run("i=0; while [ $i -lt 20000 ]; do printf '0123456789';"
                + " i=$((i+1)); done", 4000);
        assertTrue(result.endsWith("[output truncated]"));
        assertTrue(result.length() < CommandRunner.MAX_OUTPUT_BYTES + 100);
    }

    @Test public void killsTimedOutCommandAndAllowsNextCommand() throws Exception {
        long started = System.nanoTime();
        assertThrows(TimeoutException.class, () -> run("exec sleep 5", 100));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
        assertEquals("ok", run("printf ok", 2000));
    }

    @Test public void rejectsExpiredDeadlineBeforeStartingProcess() {
        assertThrows(TimeoutException.class,
                () -> CommandRunner.run(new String[]{"nonexistent-command"}, 0));
    }

    private static String run(String script, long timeoutMs) throws Exception {
        return CommandRunner.run(new String[]{"/bin/sh", "-c", script}, timeoutMs);
    }
}

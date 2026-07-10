package com.app.netcut;



import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single long-lived `su` process used to run the netcut binary.
 *
 * Changes vs. previous version:
 *  - exec() now has a timeout (default 30 s). A true timeout is enforced via
 *    a reader thread + join(); if the command hangs, an IOException is thrown.
 *  - finalize() removed (deprecated & unreliable). Call close() explicitly.
 *  - Stderr drainer is now a proper daemon thread with a clear name.
 */
public class PersistentRootShell implements Closeable {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final Object lock = new Object();
    private Process suProcess;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private BufferedReader stderr;
    private Thread stderrDrainer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public interface StderrListener {
        void onStderr(String line);
    }

    private final StderrListener stderrListener;

    public PersistentRootShell(StderrListener stderrListener) throws IOException {
        this.stderrListener = stderrListener;
        start();
    }

    private void start() throws IOException {
        suProcess = new ProcessBuilder("su")
                .redirectErrorStream(false)
                .start();

        stdin  = new BufferedWriter(new OutputStreamWriter(suProcess.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(suProcess.getInputStream(),  StandardCharsets.UTF_8));
        stderr = new BufferedReader(new InputStreamReader(suProcess.getErrorStream(),  StandardCharsets.UTF_8));

        stderrDrainer = new Thread(() -> {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    if (stderrListener != null) stderrListener.onStderr(line);
                }
            } catch (IOException ignored) {
                // shell closed — expected during shutdown
            }
        }, "PersistentRootShell-stderr");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        ShellResult r = exec("id");
        if (r.exitCode != 0) {
            throw new IOException("su shell started but 'id' failed: rc=" + r.exitCode
                    + " out=" + r.joinedStdout());
        }
    }

    public static class ShellResult {
        public final int exitCode;
        public final List<String> stdout;

        public ShellResult(int exitCode, List<String> stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout;
        }

        public String joinedStdout() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < stdout.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(stdout.get(i));
            }
            return sb.toString();
        }
    }

    /** Execute with the default 30 s timeout. */
    public ShellResult exec(String command) throws IOException {
        return exec(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Execute a command and wait for its exit-code token.
     *
     * @param timeoutMs max time to wait for the command to finish.
     * @throws IOException on timeout, stream closure, or write failure.
     */
    public ShellResult exec(String command, long timeoutMs) throws IOException {
        synchronized (lock) {
            ensureOpen();

            final String token = "__RC_TOKEN_"
                    + UUID.randomUUID().toString().replace("-", "") + "__";

            try {
                stdin.write(command);
                stdin.write("\n");
                stdin.write("printf '" + token + ":%s\\n' \"$?\"\n");
                stdin.flush();
            } catch (IOException e) {
                throw new IOException("Failed to write command to su shell", e);
            }

            final List<String> out = new ArrayList<>();
            final AtomicReference<ShellResult> resultRef = new AtomicReference<>();
            final AtomicReference<IOException> errorRef  = new AtomicReference<>();

            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = stdout.readLine()) != null) {
                        if (line.startsWith(token + ":")) {
                            String rcStr = line.substring((token + ":").length()).trim();
                            int rc;
                            try { rc = Integer.parseInt(rcStr); }
                            catch (NumberFormatException nfe) { rc = 99999; }
                            resultRef.set(new ShellResult(rc, out));
                            return;
                        }
                        out.add(line);
                    }
                    // Stream closed before we saw the token
                    errorRef.set(new EOFException("su shell stdout closed unexpectedly"));
                } catch (IOException e) {
                    errorRef.set(e);
                }
            }, "PersistentRootShell-reader");
            reader.setDaemon(true);
            reader.start();

            try {
                reader.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for command result", e);
            }

            if (reader.isAlive()) {
                // Timed out. The reader thread is a daemon and will die when
                // the process is closed; we don't need to forcibly kill it.
                reader.interrupt();
                throw new IOException("Command timed out after " + timeoutMs + " ms: " + command);
            }

            IOException err = errorRef.get();
            if (err != null) throw err;

            ShellResult result = resultRef.get();
            if (result == null) {
                throw new EOFException("su shell stdout closed unexpectedly");
            }
            return result;
        }
    }

    public String execCapture(String command) throws IOException {
        return exec(command).joinedStdout();
    }

    public boolean isAlive() {
        if (closed.get() || suProcess == null) return false;
        try {
            suProcess.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public void destroy() {
        close();
    }

    private void ensureOpen() throws IOException {
        if (closed.get() || suProcess == null || !isAlive()) {
            throw new IOException("Persistent su shell is not alive");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lock) {
            try {
                if (stdin != null) {
                    stdin.write("exit\n");
                    stdin.flush();
                }
            } catch (IOException ignored) {}

            try { if (stdin  != null) stdin.close();  } catch (IOException ignored) {}
            try { if (stdout != null) stdout.close(); } catch (IOException ignored) {}
            try { if (stderr != null) stderr.close(); } catch (IOException ignored) {}

            if (stderrDrainer != null) stderrDrainer.interrupt();

            if (suProcess != null) {
                try { suProcess.destroy(); } catch (Exception ignored) {}
            }
        }
    }
}
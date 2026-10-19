package com.genymobile.scrcpy;

import com.genymobile.scrcpy.util.Ln;

import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/** Keeps a device-side process alive to clean up even if the server is killed. */
public final class CleanUp {
    private static final int PENDING_CHANGE_DISPLAY_POWER = 1;

    private int pendingChanges;
    private boolean pendingRestoreDisplayPower;
    private final Thread thread;
    private final CountDownLatch ready = new CountDownLatch(1);
    private IOException startupError;
    private boolean firstSession;
    private boolean interrupted;

    private CleanUp(Options options) {
        thread = new Thread(() -> runCleanUp(options), "cleanup");
        thread.start();
    }

    public static CleanUp start(Options options) throws IOException {
        CleanUp cleanUp = new CleanUp(options);
        try {
            cleanUp.ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting cleanup process", e);
        }
        if (cleanUp.startupError != null) {
            throw cleanUp.startupError;
        }
        return cleanUp;
    }

    public synchronized void interrupt() {
        interrupted = true;
        notify();
    }

    public void join() throws InterruptedException {
        thread.join();
    }

    public boolean isFirstSession() {
        return firstSession;
    }

    public boolean isKeepActive() throws IOException {
        return SessionState.isKeepActive();
    }

    private void runCleanUp(Options options) {
        String[] cmd = {
                "app_process", "/", CleanUp.class.getName(),
                String.valueOf(options.getCleanup()),
                String.valueOf(options.getShowTouches()),
                String.valueOf(options.getStayAwake()),
                String.valueOf(options.getKeepActive()),
                String.valueOf(options.getScreenOffTimeout()),
                String.valueOf(options.getDisplayId()),
                String.valueOf(options.getDisplayImePolicy()),
                String.valueOf(options.getPowerOffScreenOnClose()),
        };
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.environment().put("CLASSPATH", Server.SERVER_PATH);
            Process process = builder.start();
            int status = process.getInputStream().read();
            if (status != 1 && status != 2) {
                throw new IOException("Cleanup process could not register the session");
            }
            firstSession = status == 1;
            ready.countDown();
            OutputStream out = process.getOutputStream();
            while (true) {
                int localPendingChanges;
                boolean localPendingRestoreDisplayPower;
                synchronized (this) {
                    while (!interrupted && pendingChanges == 0) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            throw new AssertionError("Clean up thread MUST NOT be interrupted");
                        }
                    }
                    if (interrupted) {
                        break;
                    }
                    localPendingChanges = pendingChanges;
                    localPendingRestoreDisplayPower = pendingRestoreDisplayPower;
                    pendingChanges = 0;
                }
                if ((localPendingChanges & PENDING_CHANGE_DISPLAY_POWER) != 0) {
                    out.write(localPendingRestoreDisplayPower ? 1 : 0);
                    out.flush();
                }
            }
        } catch (IOException e) {
            if (ready.getCount() != 0) {
                startupError = e;
                ready.countDown();
            } else {
                Ln.e("Clean up I/O exception", e);
            }
        }
    }

    public synchronized void setRestoreDisplayPower(boolean restoreDisplayPower) {
        pendingRestoreDisplayPower = restoreDisplayPower;
        pendingChanges |= PENDING_CHANGE_DISPLAY_POWER;
        notify();
    }

    public static void unlinkSelf() {
        try {
            new File(Server.SERVER_PATH).delete();
        } catch (Exception e) {
            Ln.e("Could not unlink server", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void prepareMainLooper() {
        Looper.prepareMainLooper();
    }

    public static void main(String... args) {
        try {
            Os.setsid();
        } catch (ErrnoException e) {
            Ln.e("setsid() failed", e);
        }

        prepareMainLooper();
        Workarounds.apply();

        boolean cleanup = Boolean.parseBoolean(args[0]);
        boolean showTouches = Boolean.parseBoolean(args[1]);
        boolean stayAwake = Boolean.parseBoolean(args[2]);
        boolean keepActive = Boolean.parseBoolean(args[3]);
        int screenOffTimeout = Integer.parseInt(args[4]);
        int displayId = Integer.parseInt(args[5]);
        int displayImePolicy = Integer.parseInt(args[6]);
        boolean powerOffScreen = Boolean.parseBoolean(args[7]);
        int pid = Os.getpid();

        try {
            boolean firstSession = SessionState.register(pid, cleanup, showTouches, stayAwake, keepActive, screenOffTimeout, displayId, displayImePolicy,
                    powerOffScreen);
            FileOutputStream ready = new FileOutputStream(FileDescriptor.out);
            ready.write(firstSession ? 1 : 2);
            ready.flush();
        } catch (IOException e) {
            Ln.e("Could not register scrcpy session", e);
            return;
        }

        boolean restoreDisplayPower = false;
        try {
            int msg;
            while ((msg = System.in.read()) != -1) {
                assert msg == 0 || msg == 1;
                restoreDisplayPower = msg != 0;
            }
        } catch (IOException e) {
            // Expected when the server is dead
        }

        if (cleanup) {
            unlinkSelf();
        }
        try {
            SessionState.unregister(pid, displayId, cleanup, restoreDisplayPower);
        } catch (IOException e) {
            Ln.e("Could not clean up scrcpy session", e);
        }
        System.exit(0);
    }
}

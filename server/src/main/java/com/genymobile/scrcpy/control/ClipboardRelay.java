package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.Server;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.StringUtils;
import com.genymobile.scrcpy.wrappers.ClipboardManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ClipboardRelay {
    private static final int MAX_TEXT_LENGTH = DeviceMessageWriter.CLIPBOARD_TEXT_MAX_LENGTH;

    private final LocalServerSocket serverSocket;
    private final DeviceMessageSender sender;
    private volatile LocalSocket socket;
    private Thread thread;

    public ClipboardRelay(int scid, DeviceMessageSender sender) throws IOException {
        serverSocket = new LocalServerSocket(socketName(scid));
        this.sender = sender;
    }

    private static String socketName(int scid) {
        return String.format("scrcpy_clipboard_%08x", scid);
    }

    public void start() {
        thread = new Thread(() -> {
            try (LocalSocket accepted = serverSocket.accept()) {
                if (accepted.getPeerCredentials().getUid() != Process.SHELL_UID) {
                    throw new IOException("Clipboard helper has unexpected UID");
                }
                socket = accepted;
                Ln.i("Clipboard helper connected");
                DataInputStream input = new DataInputStream(accepted.getInputStream());
                while (!Thread.currentThread().isInterrupted()) {
                    int length = input.readInt();
                    if (length < 0 || length > MAX_TEXT_LENGTH) {
                        throw new IOException("Invalid clipboard message length: " + length);
                    }
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    sender.send(DeviceMessage.createClipboard(new String(bytes, StandardCharsets.UTF_8)));
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Ln.w("Clipboard helper disconnected", e);
                }
            }
        }, "clipboard-relay");
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
        try {
            serverSocket.close();
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            Ln.w("Could not close clipboard helper socket", e);
        }
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        if (Os.getuid() == 0) {
            Os.setuid(Process.SHELL_UID);
        }
        if (args.length != 1 || Process.myUid() != Process.SHELL_UID) {
            throw new IllegalArgumentException("Clipboard helper requires shell UID and a session ID");
        }
        int scid = (int) Long.parseLong(args[0], 16);
        Server.prepareMainLooper();
        Workarounds.apply();
        ClipboardManager clipboard = ServiceManager.getClipboardManager();
        if (clipboard == null) {
            throw new IOException("No clipboard manager");
        }

        LocalSocket connection;
        for (int attempt = 0; ; ++attempt) {
            connection = new LocalSocket();
            try {
                connection.connect(new LocalSocketAddress(socketName(scid)));
                break;
            } catch (IOException e) {
                connection.close();
                if (attempt == 99) {
                    throw e;
                }
                SystemClock.sleep(100);
            }
        }

        try (LocalSocket socket = connection) {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            clipboard.addPrimaryClipChangedListener(() -> {
                try {
                    CharSequence value = clipboard.getText();
                    if (value != null) {
                        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                        int length = StringUtils.getUtf8TruncationIndex(bytes, MAX_TEXT_LENGTH);
                        synchronized (output) {
                            output.writeInt(length);
                            output.write(bytes, 0, length);
                            output.flush();
                        }
                    }
                } catch (IOException e) {
                    Ln.d("Clipboard helper disconnected: " + e.getMessage());
                    Looper.getMainLooper().quitSafely();
                }
            });
            Thread watcher = new Thread(() -> {
                try {
                    socket.getInputStream().read();
                } catch (IOException e) {
                    // The main server closed the socket.
                }
                Looper.getMainLooper().quitSafely();
            }, "clipboard-watch");
            watcher.setDaemon(true);
            watcher.start();
            Ln.i("Clipboard helper ready");
            Looper.loop();
        }
        System.exit(0);
    }
}

package com.genymobile.scrcpy;

import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.Settings;
import com.genymobile.scrcpy.util.SettingsException;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.os.BatteryManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.Properties;

/** Coordinates device settings and exit actions across scrcpy servers. */
final class SessionState {
    private static final String PATH = "/data/local/tmp/scrcpy-session.state";
    private static final String ACTIVE = "active.";
    private static final String BOOT_ID = "boot_id";
    private static final String KEEP_ACTIVE = "keep_active";
    private static final String OLD = "old.";
    private static final String POWER_OFF = "power_off.";
    private static final String RESTORE_POWER = "restore_power.";

    private SessionState() {
    }

    static boolean register(int pid, boolean cleanup, boolean showTouches, boolean stayAwake, boolean keepActive, int screenOffTimeout, int displayId,
            int displayImePolicy, boolean powerOffScreen) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(PATH, "rw"); FileLock ignored = file.getChannel().lock()) {
            Properties state = read(file);
            String bootId = readBootId();
            if (bootId != null && state.containsKey(BOOT_ID) && !bootId.equals(state.getProperty(BOOT_ID))) {
                for (String key : state.stringPropertyNames()) {
                    if (key.startsWith(ACTIVE) || key.startsWith(POWER_OFF) || key.startsWith(RESTORE_POWER)) {
                        state.remove(key);
                    }
                }
            }
            removeDeadSessions(state);
            boolean firstSession = !hasActiveSessions(state);
            if (firstSession) {
                restore(state);
                state.clear();
                state.setProperty(KEEP_ACTIVE, String.valueOf(keepActive));
            }
            if (firstSession && cleanup) {
                if (showTouches) {
                    changeSetting(state, "show_touches", Settings.TABLE_SYSTEM, "show_touches", "1");
                }
                if (stayAwake) {
                    int stayOn = BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB
                            | BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    changeSetting(state, "stay_on_while_plugged_in", Settings.TABLE_GLOBAL, "stay_on_while_plugged_in", String.valueOf(stayOn));
                }
                if (screenOffTimeout != -1) {
                    changeSetting(state, "screen_off_timeout", Settings.TABLE_SYSTEM, "screen_off_timeout", String.valueOf(screenOffTimeout));
                }
                if (displayId > 0 && displayImePolicy != -1) {
                    String key = OLD + "ime." + displayId;
                    try {
                        int oldPolicy = ServiceManager.getWindowManager().getDisplayImePolicy(displayId);
                        if (oldPolicy != displayImePolicy) {
                            state.setProperty(key, state.getProperty(key, String.valueOf(oldPolicy)));
                            ServiceManager.getWindowManager().setDisplayImePolicy(displayId, displayImePolicy);
                        }
                    } catch (RuntimeException e) {
                        Ln.e("Could not change display IME policy", e);
                    }
                }
                if (powerOffScreen) {
                    state.setProperty(POWER_OFF + targetDisplayId(displayId), "true");
                }
            }
            state.setProperty(ACTIVE + pid, "true");
            if (bootId != null) {
                state.setProperty(BOOT_ID, bootId);
            }
            write(file, state);
            return firstSession;
        }
    }

    static void unregister(int pid, int displayId, boolean cleanup, boolean restoreDisplayPower) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(PATH, "rw"); FileLock ignored = file.getChannel().lock()) {
            Properties state = read(file);
            if (cleanup && restoreDisplayPower) {
                state.setProperty(RESTORE_POWER + targetDisplayId(displayId), "true");
            }
            state.remove(ACTIVE + pid);
            removeDeadSessions(state);
            if (!hasActiveSessions(state)) {
                restore(state);
                state.clear();
            }
            write(file, state);
        }
    }

    static boolean isKeepActive() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(PATH, "rw"); FileLock ignored = file.getChannel().lock()) {
            return Boolean.parseBoolean(read(file).getProperty(KEEP_ACTIVE));
        }
    }

    private static void changeSetting(Properties state, String key, String table, String name, String value) {
        try {
            String previous = Settings.getAndPutValue(table, name, value);
            if (previous != null && !previous.equals(value)) {
                String originalKey = OLD + key;
                state.setProperty(originalKey, state.getProperty(originalKey, previous));
            }
        } catch (SettingsException e) {
            Ln.e("Could not change " + name, e);
        }
    }

    private static void restore(Properties state) {
        restoreSetting(state, "show_touches", Settings.TABLE_SYSTEM);
        restoreSetting(state, "stay_on_while_plugged_in", Settings.TABLE_GLOBAL);
        restoreSetting(state, "screen_off_timeout", Settings.TABLE_SYSTEM);
        for (String key : state.stringPropertyNames()) {
            if (key.startsWith(OLD + "ime.")) {
                try {
                    int displayId = Integer.parseInt(key.substring((OLD + "ime.").length()));
                    ServiceManager.getWindowManager().setDisplayImePolicy(displayId, Integer.parseInt(state.getProperty(key)));
                } catch (RuntimeException e) {
                    Ln.e("Could not restore display IME policy", e);
                }
            }
        }
        for (String key : state.stringPropertyNames()) {
            if (key.startsWith(POWER_OFF) || key.startsWith(RESTORE_POWER)) {
                try {
                    int displayId = Integer.parseInt(key.substring(key.indexOf('.') + 1));
                    if (key.startsWith(POWER_OFF)) {
                        Device.powerOffScreen(displayId);
                    } else if (!state.containsKey(POWER_OFF + displayId)) {
                        Device.setDisplayPower(displayId, true);
                    }
                } catch (RuntimeException e) {
                    Ln.e("Could not restore display power", e);
                }
            }
        }
    }

    private static void restoreSetting(Properties state, String name, String table) {
        String value = state.getProperty(OLD + name);
        if (value != null) {
            try {
                Settings.putValue(table, name, value);
            } catch (SettingsException e) {
                Ln.e("Could not restore " + name, e);
            }
        }
    }

    private static void removeDeadSessions(Properties state) {
        for (String key : state.stringPropertyNames()) {
            if (key.startsWith(ACTIVE)) {
                try {
                    Os.kill(Integer.parseInt(key.substring(ACTIVE.length())), 0);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.ESRCH) {
                        state.remove(key);
                    }
                } catch (NumberFormatException e) {
                    state.remove(key);
                }
            }
        }
    }

    private static boolean hasActiveSessions(Properties state) {
        for (String key : state.stringPropertyNames()) {
            if (key.startsWith(ACTIVE)) {
                return true;
            }
        }
        return false;
    }

    private static int targetDisplayId(int displayId) {
        return displayId != Device.DISPLAY_ID_NONE ? displayId : 0;
    }

    private static String readBootId() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties read(RandomAccessFile file) throws IOException {
        Properties state = new Properties();
        long length = file.length();
        if (length > 65536) {
            throw new IOException("Invalid session state size");
        }
        if (length > 0) {
            byte[] data = new byte[(int) length];
            file.seek(0);
            file.readFully(data);
            state.load(new ByteArrayInputStream(data));
        }
        return state;
    }

    private static void write(RandomAccessFile file, Properties state) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        state.store(data, null);
        file.seek(0);
        file.write(data.toByteArray());
        file.setLength(file.getFilePointer());
        file.getFD().sync();
    }
}

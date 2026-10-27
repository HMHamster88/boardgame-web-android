package com.hmhamster.boardgameweb;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the single Node.js runtime instance for the app.
 *
 * Node.js Mobile can only be started once per process and cannot be restarted
 * after it has exited, so all callers share the {@link #started} guard.
 */
public final class NodeRuntime {
    private static final String TAG = "BoardgameNode";
    private static final String PREFS_NAME = "boardgame_web_node";
    private static final String PREF_LAST_APK_UPDATE = "last_apk_update";
    private static final String ASSET_PROJECT_DIR = "nodejs-project";
    private static final int MAX_OUTPUT_CHARS = 200_000;

    public static final int SERVER_PORT = 8000;
    public static final String SERVER_URL = "http://127.0.0.1:" + SERVER_PORT + "/";

    public static final int STREAM_STDOUT = 1;
    public static final int STREAM_STDERR = 2;
    /** Local console echo, never produced by the native layer. */
    public static final int STREAM_COMMAND = 3;

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile boolean exited = false;
    private static volatile int exitCode = 0;

    private static final Object OUTPUT_LOCK = new Object();
    private static final ArrayDeque<OutputChunk> outputChunks = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<OutputListener> outputListeners =
            new CopyOnWriteArrayList<>();
    private static int outputCharCount = 0;

    private static final ExecutorService stdinExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "boardgame-node-stdin");
        thread.setDaemon(true);
        return thread;
    });

    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("node");
    }

    private NodeRuntime() {
    }

    public interface OutputListener {
        void onOutput(int stream, String text);
    }

    public static final class OutputChunk {
        public final int stream;
        public final String text;

        OutputChunk(int stream, String text) {
            this.stream = stream;
            this.text = text;
        }
    }

    public static native int startNodeWithArguments(String[] arguments);

    public static native boolean writeToNodeStdin(String text);

    public static void start(Context context) {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> runNode(appContext), "boardgame-node");
        thread.start();
    }

    public static boolean hasExited() {
        return exited;
    }

    public static int getExitCode() {
        return exitCode;
    }

    /**
     * Registers a listener and atomically returns the output captured so far.
     * The listener is guaranteed not to miss output produced after this call.
     */
    public static List<OutputChunk> addOutputListenerAndSnapshot(OutputListener listener) {
        synchronized (OUTPUT_LOCK) {
            if (listener != null) {
                outputListeners.addIfAbsent(listener);
            }
            return new ArrayList<>(outputChunks);
        }
    }

    public static void removeOutputListener(OutputListener listener) {
        outputListeners.remove(listener);
    }

    public static void clearOutputBuffer() {
        synchronized (OUTPUT_LOCK) {
            outputChunks.clear();
            outputCharCount = 0;
        }
    }

    /**
     * Sends a line to the core module's CLI (stdin). Writes happen on a
     * background thread so a blocked pipe can never block the UI.
     */
    public static boolean sendCommand(String command) {
        if (command == null) {
            return false;
        }

        final String line = command.trim();
        if (line.isEmpty() || exited) {
            return false;
        }

        stdinExecutor.execute(() -> {
            if (!writeToNodeStdin(line)) {
                Log.w(TAG, "Could not write command to Node.js stdin: " + line);
            }
        });

        return true;
    }

    private static void runNode(Context context) {
        try {
            File filesDir = context.getFilesDir();
            File nodeProjectDir = new File(filesDir, ASSET_PROJECT_DIR);

            if (wasApkUpdated(context) || !nodeProjectDir.isDirectory()) {
                Log.i(TAG, "Extracting Node.js project to " + nodeProjectDir);
                deleteRecursively(nodeProjectDir);
                copyAssetFolder(context.getAssets(), ASSET_PROJECT_DIR, nodeProjectDir);
                saveLastUpdateTime(context);
            }

            File mainScript = new File(nodeProjectDir, "main.mjs");
            if (!mainScript.isFile()) {
                throw new IOException("Node.js entry point is missing: " + mainScript);
            }

            setEnvironmentVariable("BOARDGAME_APP_DATA_DIR", filesDir.getAbsolutePath());
            setEnvironmentVariable("BOARDGAME_PORT", String.valueOf(SERVER_PORT));
            setEnvironmentVariable("HOME", filesDir.getAbsolutePath());
            setEnvironmentVariable("TMPDIR", context.getCacheDir().getAbsolutePath());
            setEnvironmentVariable("NODE_ENV", "production");
            setEnvironmentVariable("CHECK_FOR_UPDATES", "true");

            Log.i(TAG, "Starting Node.js: " + mainScript);
            int result = startNodeWithArguments(new String[]{"node", mainScript.getAbsolutePath()});
            exitCode = result;
            Log.i(TAG, "Node.js exited with code " + result);
        } catch (Throwable error) {
            exitCode = -1;
            Log.e(TAG, "Failed to start Node.js", error);
        } finally {
            exited = true;
        }
    }

    private static void setEnvironmentVariable(String name, String value) {
        try {
            Os.setenv(name, value, true);
        } catch (ErrnoException | RuntimeException error) {
            Log.w(TAG, "Could not set environment variable " + name, error);
        }
    }

    private static boolean wasApkUpdated(Context context) {
        long lastUpdateTime = getLastUpdateTime(context);
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getLong(PREF_LAST_APK_UPDATE, 0L) != lastUpdateTime;
    }

    private static void saveLastUpdateTime(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit()
                .putLong(PREF_LAST_APK_UPDATE, getLastUpdateTime(context))
                .apply();
    }

    private static long getLastUpdateTime(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.lastUpdateTime;
        } catch (Exception error) {
            Log.w(TAG, "Could not read package update time", error);
            return 1L;
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }

        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }

        if (!file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }

    private static void copyAssetFolder(AssetManager assets, String assetPath, File destination)
            throws IOException {
        String[] children = assets.list(assetPath);

        if (children == null || children.length == 0) {
            copyAssetFile(assets, assetPath, destination);
            return;
        }

        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Could not create directory " + destination);
        }

        for (String child : children) {
            copyAssetFolder(
                    assets,
                    assetPath + "/" + child,
                    new File(destination, child)
            );
        }
    }

    private static void copyAssetFile(AssetManager assets, String assetPath, File destination)
            throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create directory " + parent);
        }

        try (InputStream input = assets.open(assetPath);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    /**
     * Called from the native stdout/stderr forwarding threads.
     */
    @SuppressWarnings("unused")
    private static void onNativeOutput(int stream, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        String text = new String(data, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        if (text.isEmpty()) {
            return;
        }

        List<OutputListener> listeners;
        synchronized (OUTPUT_LOCK) {
            outputChunks.addLast(new OutputChunk(stream, text));
            outputCharCount += text.length();

            while (outputCharCount > MAX_OUTPUT_CHARS && !outputChunks.isEmpty()) {
                outputCharCount -= outputChunks.removeFirst().text.length();
            }

            listeners = new ArrayList<>(outputListeners);
        }

        for (OutputListener listener : listeners) {
            try {
                listener.onOutput(stream, text);
            } catch (RuntimeException error) {
                Log.w(TAG, "Console output listener failed", error);
            }
        }
    }
}

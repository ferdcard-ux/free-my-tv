package io.freemytv;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Persistent, bounded diagnostics. Records are local and readable only through the shell-only provider. */
final class AppLog {
    private AppLog() {}

    private static final String TAG = "FreeMyTV";
    private static final String LOG_FILE = "diagnostics.log";
    private static final String OLD_FILE = "diagnostics.log.1";
    private static final String LOCK_FILE = "diagnostics.lock";
    private static final String EXIT_MARKER = "diagnostics-exits";
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_ROWS = 5000;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?token|watchmode[_-]?key|tmdb[_-]?key|plex[_-]?token"
                    + "|token|secret|password|authorization|cookie)"
                    + "(\\s*[:=]\\s*[\"']?)[^\\s&,\"'<>]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Object FRAME_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "fmt-diagnostics");
        thread.setDaemon(true);
        return thread;
    });

    private static Context app;
    private static String process = "unknown";
    private static boolean crashHandlerInstalled;
    private static boolean frameFlushScheduled;
    private static int slowFrameCount, frameGapCount, delayedInputCount;
    private static long maxFrameMs, maxDrawMs, maxSwapMs, droppedFrames, maxGapMs, maxInputDelayMs;

    private interface FileOperation<T> {
        T run(File directory) throws IOException;
    }

    static synchronized void init(Context context) {
        app = context.getApplicationContext();
        process = Build.VERSION.SDK_INT >= 28 ? android.app.Application.getProcessName() : app.getPackageName();
        if (process == null || process.isEmpty()) process = app.getPackageName();
        try {
            File directory = new File(app.getFilesDir(), "diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("couldn't create diagnostics directory");
            }
        } catch (IOException e) {
            Log.e(TAG, "diagnostics storage unavailable", e);
        }
        w("process started");
        installCrashHandler();
    }

    static void w(String message) {
        write("INFO", message);
    }

    static void mainThreadStall(long millis) {
        if (millis >= 3000) write("STALL", "main thread delayed " + millis + " ms");
    }

    static void frameMetrics(long totalNanos, long drawNanos, long swapNanos, int dropped) {
        long total = TimeUnit.NANOSECONDS.toMillis(totalNanos);
        if (total < 50 && dropped <= 0) return;
        synchronized (FRAME_LOCK) {
            slowFrameCount++;
            maxFrameMs = Math.max(maxFrameMs, total);
            maxDrawMs = Math.max(maxDrawMs, TimeUnit.NANOSECONDS.toMillis(drawNanos));
            maxSwapMs = Math.max(maxSwapMs, TimeUnit.NANOSECONDS.toMillis(swapNanos));
            droppedFrames += Math.max(0, dropped);
            scheduleFrameFlush();
        }
    }

    static void frameGap(long millis) {
        if (millis < 150) return;
        synchronized (FRAME_LOCK) {
            frameGapCount++;
            maxGapMs = Math.max(maxGapMs, millis);
            scheduleFrameFlush();
        }
    }

    static void delayedInput(long millis) {
        if (millis < 250) return;
        synchronized (FRAME_LOCK) {
            delayedInputCount++;
            maxInputDelayMs = Math.max(maxInputDelayMs, millis);
            scheduleFrameFlush();
        }
    }

    private static void scheduleFrameFlush() {
        if (frameFlushScheduled) return;
        frameFlushScheduled = true;
        WRITER.schedule(AppLog::flushPerformance, 2, TimeUnit.SECONDS);
    }

    private static void flushPerformance() {
        final String summary;
        synchronized (FRAME_LOCK) {
            summary = "slow frames=" + slowFrameCount + ", max=" + maxFrameMs + " ms, draw=" + maxDrawMs
                    + " ms, swap=" + maxSwapMs + " ms, dropped=" + droppedFrames
                    + "; frame gaps=" + frameGapCount + ", max=" + maxGapMs + " ms"
                    + "; delayed inputs=" + delayedInputCount + ", max=" + maxInputDelayMs + " ms";
            slowFrameCount = frameGapCount = delayedInputCount = 0;
            maxFrameMs = maxDrawMs = maxSwapMs = droppedFrames = maxGapMs = maxInputDelayMs = 0;
            frameFlushScheduled = false;
        }
        write("PERF", summary);
    }

    private static void installCrashHandler() {
        synchronized (AppLog.class) {
            if (crashHandlerInstalled) return;
            crashHandlerInstalled = true;
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                recordCrash(thread, error);
            } catch (RuntimeException e) {
                Log.e(TAG, "couldn't record uncaught exception", e);
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void recordCrash(Thread thread, Throwable error) {
        StringBuilder message = new StringBuilder("uncaught ")
                .append(error.getClass().getName()).append(" on ").append(thread.getName());
        Throwable cause = error.getCause();
        if (cause != null) message.append("; cause ").append(cause.getClass().getName());
        long now = System.currentTimeMillis();
        List<String> rows = new ArrayList<>();
        rows.add(line(now, "CRASH", process, clean(message.toString())));
        Log.i(TAG, "CRASH: " + clean(message.toString()));
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, 32); i++) {
            String frame = clean(frames[i].toString());
            rows.add(line(now, "STACK", process, frame));
            Log.i(TAG, "STACK: " + frame);
        }
        persist(app, rows, true);
    }

    static void collectHistoricalExits(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        Context appContext = context.getApplicationContext();
        WRITER.execute(() -> collectHistoricalExitsInBackground(appContext));
    }

    private static void collectHistoricalExitsInBackground(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return;
        try {
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 32);
            if (exits == null || exits.isEmpty()) return;
            Collections.sort(exits, Comparator.comparingLong(ApplicationExitInfo::getTimestamp));
            withFileLock(context, directory -> {
                File marker = new File(directory, EXIT_MARKER);
                long seenThrough = readLong(marker);
                long newest = seenThrough;
                List<String> rows = new ArrayList<>();
                for (ApplicationExitInfo exit : exits) {
                    long timestamp = exit.getTimestamp();
                    if (timestamp <= seenThrough) continue;
                    newest = Math.max(newest, timestamp);
                    if (interestingExit(exit.getReason())) {
                        rows.add(line(timestamp, "EXIT", exit.getProcessName(), exit.getPid(),
                                "reason=" + exitReason(exit.getReason()) + ", status=" + exit.getStatus()
                                        + ", importance=" + exit.getImportance() + ", pss=" + exit.getPss()
                                        + ", rss=" + exit.getRss()));
                    }
                }
                if (!rows.isEmpty()) {
                    appendRows(directory, rows, true);
                }
                if (newest > seenThrough) {
                    try (FileOutputStream out = new FileOutputStream(marker)) {
                        out.write(Long.toString(newest).getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                    }
                }
                return null;
            });
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "couldn't collect historical process exits", e);
        }
    }

    private static String exitReason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native_crash";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low_memory";
            default: return "reason_" + reason;
        }
    }

    private static boolean interestingExit(int reason) {
        return reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_LOW_MEMORY
                || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
    }

    private static void write(String level, String message) {
        String safe = redact(message);
        Log.i(TAG, level + ": " + safe);
        Context context = app;
        if (context == null) return;
        List<String> rows = new ArrayList<>(1);
        rows.add(line(System.currentTimeMillis(), level, process, safe));
        WRITER.execute(() -> persist(context, rows, false));
    }

    private static void persist(Context context, List<String> rows, boolean sync) {
        try {
            withFileLock(context, directory -> {
                appendRows(directory, rows, sync);
                return null;
            });
        } catch (IOException e) {
            Log.e(TAG, "couldn't write diagnostics", e);
        }
    }

    private static String line(long epoch, String level, String processName, String message) {
        return line(epoch, level, processName, Process.myPid(), message);
    }

    private static String line(long epoch, String level, String processName, int pid, String message) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date(epoch));
        return epoch + "\t" + time + "\t" + level + "\t" + clean(processName) + "\t" + pid
                + "\t" + clean(message) + "\n";
    }

    private static String clean(String value) {
        String safe = redact(value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        return safe.length() > 4096 ? safe.substring(0, 4096) + " [truncated]" : safe;
    }

    private static String redact(String value) {
        if (value == null) return "";
        return BEARER.matcher(SECRET.matcher(value).replaceAll("$1$2[redacted]")).replaceAll("Bearer [redacted]");
    }

    private static <T> T withFileLock(Context context, FileOperation<T> operation) throws IOException {
        synchronized (FILE_LOCK) {
            File directory = new File(context.getFilesDir(), "diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("couldn't create diagnostics directory");
            }
            File lockFile = new File(directory, LOCK_FILE);
            try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = lock.getChannel();
                 FileLock ignored = channel.lock()) {
                return operation.run(directory);
            }
        }
    }

    private static void appendRows(File directory, List<String> rows, boolean sync) throws IOException {
        File current = new File(directory, LOG_FILE);
        long incomingBytes = 0;
        List<byte[]> encoded = new ArrayList<>(rows.size());
        for (String row : rows) {
            byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
            encoded.add(bytes);
            incomingBytes += bytes.length;
        }
        if (current.exists() && current.length() + incomingBytes > MAX_FILE_BYTES) {
            File old = new File(directory, OLD_FILE);
            if (old.exists() && !old.delete()) throw new IOException("couldn't rotate old diagnostics");
            if (current.exists() && !current.renameTo(old)) throw new IOException("couldn't rotate diagnostics");
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) {
            for (byte[] bytes : encoded) out.write(bytes);
            if (sync) out.getFD().sync();
        }
    }

    private static long readLong(File file) {
        if (!file.isFile()) return 0;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 32)];
            int count = in.read(bytes);
            return count > 0 ? Long.parseLong(new String(bytes, 0, count, StandardCharsets.UTF_8).trim()) : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    static List<String[]> read(Context context, int requestedLimit, long since) throws IOException {
        final int limit = Math.max(1, Math.min(MAX_ROWS, requestedLimit));
        return withFileLock(context, directory -> {
            List<String[]> found = new ArrayList<>();
            readFile(new File(directory, OLD_FILE), since, found);
            readFile(new File(directory, LOG_FILE), since, found);
            Collections.sort(found, Comparator.comparingLong(row -> Long.parseLong(row[1])));
            if (found.size() > limit) return new ArrayList<>(found.subList(found.size() - limit, found.size()));
            return found;
        });
    }

    private static void readFile(File file, long since, List<String[]> found) throws IOException {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String row;
            while ((row = reader.readLine()) != null) {
                String[] fields = row.split("\t", 6);
                if (fields.length != 6) continue;
                long epoch;
                try {
                    epoch = Long.parseLong(fields[0]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (epoch < since) continue;
                found.add(new String[]{fields[1], fields[0], fields[2], fields[3], fields[4], fields[5]});
            }
        }
    }
}

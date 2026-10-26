package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Spanish Bing wallpaper feed and its ten-image offline cache. */
final class BingWallpapers {
    static final class Entry {
        final String id, title, copyright;

        Entry(String id, String title, String copyright) {
            this.id = id;
            this.title = title;
            this.copyright = copyright;
        }
    }

    private static final class CachedIndex {
        final long indexModified, indexLength, directoryModified, checkedAt;
        final String error;
        final List<Entry> entries;

        CachedIndex(long indexModified, long indexLength, long directoryModified, long checkedAt,
                String error, List<Entry> entries) {
            this.indexModified = indexModified;
            this.indexLength = indexLength;
            this.directoryModified = directoryModified;
            this.checkedAt = checkedAt;
            this.error = error;
            this.entries = entries;
        }
    }

    private static final int MAX_IMAGES = 10;
    private static final long CHECK_INTERVAL = 30 * 60_000L;
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private static final int MAX_IMAGE = 20 * 1024 * 1024;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean refreshing;
    private static final List<Runnable> waiting = new ArrayList<>();
    private static CachedIndex cachedIndex;

    private BingWallpapers() {}

    private static File directory(Context c) {
        return new File(c.getFilesDir(), "bing-wallpapers");
    }

    private static File indexFile(Context c) {
        return new File(c.getFilesDir(), "bing-wallpapers.json");
    }

    private static JSONObject readIndex(Context c) {
        File file = indexFile(c);
        if (!file.isFile()) return new JSONObject();
        try (InputStream in = new java.io.FileInputStream(file)) {
            return new JSONObject(read(in, MAX_RESPONSE));
        } catch (Exception e) {
            AppLog.w("Bing wallpapers: couldn't read cache index: " + e.getMessage());
            return new JSONObject();
        }
    }

    private static void writeIndex(Context c, JSONObject index) throws IOException {
        File target = indexFile(c);
        File temp = new File(c.getFilesDir(), "bing-wallpapers-" + android.os.Process.myPid() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(index.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            synchronized (BingWallpapers.class) {
                cachedIndex = null;
            }
        } catch (IOException e) {
            if (!temp.delete()) AppLog.w("Bing wallpapers: couldn't remove temporary cache index");
            throw e;
        }
    }

    static List<Entry> entries(Context c) {
        return snapshot(c).entries;
    }

    static List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        for (Entry entry : entries(c)) {
            Recs.Pick pick = new Recs.Pick();
            pick.id = out.size();
            pick.kind = "bing";
            pick.photo = image(c, entry.id);
            pick.title = entry.title;
            pick.meta = entry.copyright;
            out.add(pick);
        }
        return out;
    }

    static String status(Context c) {
        CachedIndex index = snapshot(c);
        int count = index.entries.size();
        if (!index.error.isEmpty()) return count == 0 ? Ui.tr("Couldn't update Bing images.") : Ui.tr("Bing images are saved; update failed.");
        return count == 0 ? Ui.tr("No Bing images saved yet.") : Ui.trf("Bing images saved: {n}/10", "n", count);
    }

    private static CachedIndex snapshot(Context c) {
        File indexFile = indexFile(c), directory = directory(c);
        long indexModified = indexFile.isFile() ? indexFile.lastModified() : 0;
        long indexLength = indexFile.isFile() ? indexFile.length() : 0;
        long directoryModified = directory.isDirectory() ? directory.lastModified() : 0;
        synchronized (BingWallpapers.class) {
            if (cachedIndex != null && cachedIndex.indexModified == indexModified && cachedIndex.indexLength == indexLength
                    && cachedIndex.directoryModified == directoryModified) return cachedIndex;
            JSONObject index = readIndex(c);
            List<Entry> entries = new ArrayList<>();
            JSONArray saved = index.optJSONArray("entries");
            if (saved != null) {
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject item = saved.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id"), title = item.optString("title");
                    if (id.matches("[0-9a-f]{64}") && !title.isEmpty() && image(c, id).isFile()) {
                        entries.add(new Entry(id, title, item.optString("copyright")));
                    }
                }
            }
            cachedIndex = new CachedIndex(indexModified, indexLength, directoryModified, index.optLong("checkedAt"),
                    index.optString("error", ""), entries);
            return cachedIndex;
        }
    }

    static void refresh(Context context, Runnable done) {
        refresh(context, false, done);
    }

    static void refresh(Context context, boolean force, Runnable done) {
        Context app = context.getApplicationContext();
        if (!force && System.currentTimeMillis() - snapshot(app).checkedAt < CHECK_INTERVAL) {
            if (done != null) main.post(done);
            return;
        }
        synchronized (BingWallpapers.class) {
            if (refreshing) {
                if (done != null) waiting.add(done);
                return;
            }
            refreshing = true;
        }
        if (done != null) waiting.add(done);
        worker.execute(() -> {
            String error = "";
            try {
                refreshNow(app);
            } catch (Exception | OutOfMemoryError e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("Bing wallpapers: update failed: " + error);
            }
            JSONObject index = readIndex(app);
            try {
                index.put("checkedAt", System.currentTimeMillis()).put("error", error);
                writeIndex(app, index);
            } catch (org.json.JSONException | IOException e) {
                AppLog.w("Bing wallpapers: couldn't save update status: " + e.getMessage());
            }
            List<Runnable> callbacks;
            synchronized (BingWallpapers.class) {
                refreshing = false;
                callbacks = new ArrayList<>(waiting);
                waiting.clear();
            }
            for (Runnable callback : callbacks) main.post(callback);
        });
    }

    private static void refreshNow(Context c) throws Exception {
        String body = get("https://peapix.com/bing/feed?country=es&n=" + MAX_IMAGES, MAX_RESPONSE);
        JSONArray feed = new JSONArray(body);
        List<Entry> next = new ArrayList<>();
        if (!directory(c).isDirectory() && !directory(c).mkdirs()) throw new IOException("couldn't create the Bing image cache");
        for (int i = 0; i < feed.length() && next.size() < MAX_IMAGES; i++) {
            JSONObject item = feed.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("fullUrl");
            String title = item.optString("title").trim();
            String copyright = item.optString("copyright").trim();
            if (title.isEmpty() || url.isEmpty()) continue;
            Uri parsed = Uri.parse(url);
            if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null
                    || !(parsed.getHost().equals("img.peapix.com") || parsed.getHost().endsWith(".peapix.com"))) {
                AppLog.w("Bing wallpapers: skipped unsupported image URL");
                continue;
            }
            String id = digest(url);
            File target = image(c, id);
            if (!target.isFile()) download(c, url, target);
            next.add(new Entry(id, title, copyright));
        }
        if (next.isEmpty()) throw new IOException("Bing returned no usable images");
        Set<String> ids = new HashSet<>();
        for (Entry entry : next) ids.add(entry.id);
        for (Entry cached : entries(c)) {
            if (next.size() == MAX_IMAGES) break;
            if (ids.add(cached.id)) next.add(cached);
        }

        JSONArray saved = new JSONArray();
        for (Entry entry : next) {
            saved.put(new JSONObject().put("id", entry.id).put("title", entry.title).put("copyright", entry.copyright));
        }
        try {
            writeIndex(c, new JSONObject().put("entries", saved).put("checkedAt", System.currentTimeMillis()).put("error", ""));
        } catch (org.json.JSONException e) {
            throw new IOException("couldn't save Bing image titles", e);
        }
        File[] cached = directory(c).listFiles();
        for (File f : cached == null ? new File[0] : cached) {
            if (!f.getName().endsWith(".jpg")) continue;
            boolean keep = false;
            for (Entry entry : next) if (f.equals(image(c, entry.id))) { keep = true; break; }
            if (!keep && !f.delete()) AppLog.w("Bing wallpapers: couldn't remove old image " + f.getName());
        }
    }

    private static void download(Context c, String url, File target) throws IOException {
        File downloaded = new File(target.getPath() + ".download");
        File encoded = new File(target.getPath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Bing image returned HTTP " + code);
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(downloaded)) {
                byte[] buffer = new byte[16384];
                long bytes = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > MAX_IMAGE) throw new IOException("Bing image is larger than 20 MB");
                    out.write(buffer, 0, count);
                }
            }
            Bitmap image = Photos.decode(downloaded, Photos.W, Photos.H);
            if (image == null) throw new IOException("Bing image could not be decoded");
            try (FileOutputStream out = new FileOutputStream(encoded)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 86, out)) throw new IOException("couldn't save Bing image");
            } finally {
                image.recycle();
            }
            if (!encoded.renameTo(target)) throw new IOException("couldn't publish Bing image");
        } finally {
            if (connection != null) connection.disconnect();
            if (downloaded.exists() && !downloaded.delete()) AppLog.w("Bing wallpapers: couldn't remove downloaded image");
            if (encoded.exists() && !encoded.delete()) AppLog.w("Bing wallpapers: couldn't remove partial image");
        }
    }

    private static String get(String address, int maxBytes) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setInstanceFollowRedirects(false);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Peapix returned HTTP " + code);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > maxBytes) throw new IOException("Peapix response is too large");
                    out.write(buffer, 0, count);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }

        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in, int maxBytes) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > maxBytes) throw new IOException("Peapix cache index is too large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static File image(Context c, String id) {
        return new File(directory(c), "bing-" + id + ".jpg");
    }

    private static String digest(String value) throws NoSuchAlgorithmException {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}

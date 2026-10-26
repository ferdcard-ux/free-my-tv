package io.freemytv;

import android.content.ClipData;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Images selected from the TV's storage for the screensaver or the home-screen theme. */
final class WallpaperImages {
    interface ImportListener { void done(int imported, String error); }

    private static final int MAX_IMAGES = 100;
    private static final long MAX_INPUT_BYTES = 50L * 1024 * 1024;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Bitmap themeBitmap;
    private static String themeKey = "";
    private static String themeLoading = "";
    private static long filesStamp = -1;
    private static List<File> listedFiles = new ArrayList<>();

    private WallpaperImages() {}

    private static File directory(Context c) {
        return new File(c.getFilesDir(), "wallpaper-images");
    }

    static synchronized List<File> files(Context c) {
        File dir = directory(c);
        long stamp = dir.isDirectory() ? dir.lastModified() : 0;
        if (stamp != filesStamp) {
            File[] found = dir.listFiles((folder, name) -> name.endsWith(".jpg") && !name.startsWith("theme-"));
            List<File> out = new ArrayList<>();
            if (found != null) Collections.addAll(out, found);
            Collections.sort(out);
            listedFiles = out;
            filesStamp = stamp;
        }
        return listedFiles;
    }

    static List<Recs.Pick> picks(Context c) {
        List<File> files = files(c);
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Recs.Pick pick = new Recs.Pick();
            pick.id = i;
            pick.kind = "wallpaper";
            pick.photo = files.get(i);
            out.add(pick);
        }
        return out;
    }

    static File themeFile(Context c) {
        String name = Prefs.themeImage();
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) return null;
        File image = new File(directory(c), name);
        return image.isFile() ? image : null;
    }

    static String fileLabel(File file) {
        String name = file.getName();
        if (name.startsWith("theme-")) return Ui.tr("Selected image");
        if (name.endsWith(".jpg")) name = name.substring(0, name.length() - 4);
        if (name.matches("[0-9]{13}-[0-9]+")) return Ui.tr("My Photos");
        int first = name.indexOf('-');
        int second = first < 0 ? -1 : name.indexOf('-', first + 1);
        if (first == 13 && second > first) name = name.substring(second + 1);
        return name.isEmpty() ? Ui.tr("Selected image") : name;
    }

    static void clearTheme(Context c) {
        File old = themeFile(c);
        Prefs.setThemeImage("");
        invalidateTheme();
        if (old != null && !old.delete()) AppLog.w("theme image: couldn't remove " + old.getName());
    }

    static Bitmap themeImage(Context c, Runnable ready) {
        File file = themeFile(c);
        if (file == null) {
            synchronized (WallpaperImages.class) {
                themeKey = "";
                themeBitmap = null;
                themeLoading = "";
            }
            return null;
        }
        String key = file.getPath() + ":" + file.lastModified() + ":" + file.length();
        synchronized (WallpaperImages.class) {
            if (key.equals(themeKey)) return themeBitmap;
            if (key.equals(themeLoading)) return null;
            themeLoading = key;
        }
        worker.execute(() -> {
            Bitmap decoded = null, readyBitmap = null;
            try {
                int width = Math.min(960, Backdrop.W), height = Math.min(540, Backdrop.H);
                decoded = Photos.decode(file, width, height, 1);
                if (decoded == null) throw new IOException("image could not be decoded");
                readyBitmap = Backdrop.gpu(decoded);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("theme image: couldn't load " + file.getName() + ": " + e.getMessage());
            } finally {
                if (decoded != null && decoded != readyBitmap && !decoded.isRecycled()) decoded.recycle();
            }
            Bitmap result = readyBitmap;
            synchronized (WallpaperImages.class) {
                if (key.equals(themeLoading)) {
                    themeBitmap = result;
                    themeKey = key;
                    themeLoading = "";
                }
            }
            main.post(ready);
        });
        return null;
    }

    static synchronized void invalidateTheme() {
        themeKey = "";
        themeLoading = "";
        themeBitmap = null;
    }

    static void importScreensaver(Context c, List<Uri> uris, ImportListener listener) {
        importImages(c, uris, false, listener);
    }

    static void importTheme(Context c, Uri uri, ImportListener listener) {
        List<Uri> uris = new ArrayList<>();
        if (uri != null) uris.add(uri);
        importImages(c, uris, true, listener);
    }

    private static void importImages(Context context, List<Uri> uris, boolean theme, ImportListener listener) {
        Context app = context.getApplicationContext();
        List<Uri> selected = new ArrayList<>(uris);
        worker.execute(() -> {
            int count = 0;
            String error = "";
            File dir = directory(app);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                error = "couldn't create the image folder";
            } else {
                int room = theme ? 1 : Math.max(0, MAX_IMAGES - files(app).size());
                if (selected.size() > room) error = "image limit reached";
                for (int i = 0; i < selected.size() && i < room; i++) {
                    Uri uri = selected.get(i);
                    File source = new File(app.getCacheDir(), "wallpaper-import-" + android.os.Process.myPid() + "-" + i);
                    File part = null;
                    try {
                        copy(app, uri, source);
                        Bitmap image = Photos.decode(source, Photos.W, Photos.H, theme ? 1 : Photos.MIN_SIDE);
                        if (image == null) throw new IOException("image is too small or unreadable");
                        String name = theme ? "theme-" + System.currentTimeMillis() + ".jpg"
                                : imageName(app, uri, i);
                        File target = new File(dir, name);
                        part = new File(dir, name + ".part");
                        try (FileOutputStream out = new FileOutputStream(part)) {
                            if (!image.compress(Bitmap.CompressFormat.JPEG, 86, out)) {
                                throw new IOException("couldn't encode the image");
                            }
                        } finally {
                            image.recycle();
                        }
                        if (!part.renameTo(target)) throw new IOException("couldn't save the image");
                        if (theme) {
                            String previous = Prefs.themeImage();
                            Prefs.setThemeImage(name);
                            invalidateTheme();
                            if (!previous.isEmpty() && !previous.equals(name)) {
                                File old = new File(dir, previous);
                                if (old.isFile() && !old.delete()) AppLog.w("theme image: couldn't remove " + old.getName());
                            }
                        }
                        count++;
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        error = e.getMessage() == null ? e.toString() : e.getMessage();
                        AppLog.w((theme ? "theme image" : "screensaver images") + ": couldn't import " + uri + ": " + error);
                    } finally {
                        if (!source.delete() && source.exists()) AppLog.w("wallpaper images: couldn't remove temporary import");
                        if (part != null && part.exists() && !part.delete()) AppLog.w("wallpaper images: couldn't remove partial image");
                    }
                }
            }
            int imported = count;
            String message = error;
            main.post(() -> listener.done(imported, message));
        });
    }

    static List<Uri> selectedUris(android.content.Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null && !out.contains(uri)) out.add(uri);
            }
        }
        Uri single = data.getData();
        if (single != null && !out.contains(single)) out.add(single);
        return out;
    }

    private static void copy(Context c, Uri uri, File destination) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destination)) {
            if (in == null) throw new IOException("storage provider returned no image");
            byte[] buffer = new byte[16384];
            long copied = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                copied += count;
                if (copied > MAX_INPUT_BYTES) throw new IOException("image is larger than 50 MB");
                out.write(buffer, 0, count);
            }
        }
    }

    private static String imageName(Context c, Uri uri, int index) {
        String display = "Image";
        try (Cursor cursor = c.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) display = name;
            }
        } catch (RuntimeException e) {
            AppLog.w("wallpaper images: couldn't read image name: " + e.getMessage());
        }
        int dot = display.lastIndexOf('.');
        if (dot > 0) display = display.substring(0, dot);
        display = display.replaceAll("[^A-Za-z0-9._-]+", "-");
        if (display.length() > 48) display = display.substring(0, 48);
        return String.format(Locale.ROOT, "%013d-%02d-%s.jpg", System.currentTimeMillis(), index, display);
    }
}

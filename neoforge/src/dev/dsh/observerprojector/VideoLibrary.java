package dev.dsh.observerprojector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 本地视频索引库。
 *
 * <p>扫一次目录，把文件名、大小、修改时间记下来，存成一份缓存。下次启动先读缓存，
 * 再在后台核对大小和修改时间 —— 没变的就不用重新 stat。
 *
 * <p>只索引元数据，不解码。至于时长和分辨率，那要跑 ffmpeg 才知道，代价不小，
 * 等真正需要（比如在界面上排序）时再做，现在不做。
 */
final class VideoLibrary {

    /** 索引里的一条。 */
    static final class Entry {
        final String name;
        final long size;
        final long modified;

        Entry(String name, long size, long modified) {
            this.name = name;
            this.size = size;
            this.modified = modified;
        }

        /** 界面上显示的一行。 */
        String display() {
            return name + "  (" + humanSize(size) + ")";
        }

        String humanSize(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
            }
            if (bytes < 1024L * 1024 * 1024) {
                return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
            }
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    private static final String[] EXTENSIONS = {".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v"};

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static volatile boolean scanning;
    private static long lastScan;

    private VideoLibrary() {
    }

    static File dir() {
        return new File("D:\\dsh-build\\observer-projector\\videos");
    }

    private static File cacheFile() {
        return new File(dir(), ".library.json");
    }

    static boolean isScanning() {
        return scanning;
    }

    static List<Entry> entries() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }

    static int count() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }

    /** 找同名的一条，给共享来的 file: 标识用。 */
    static Entry byName(String name) {
        synchronized (ENTRIES) {
            for (Entry e : ENTRIES) {
                if (e.name.equals(name)) {
                    return e;
                }
            }
        }
        return null;
    }

    /** 上次扫描距今超过 30 秒才允许再扫，避免每帧都往磁盘跑。 */
    static boolean shouldRescan() {
        return !scanning && System.currentTimeMillis() - lastScan > 30_000L;
    }

    /** 读缓存。启动时调一次。 */
    static void loadCache() {
        File f = cacheFile();
        if (!f.isFile()) {
            return;
        }
        try {
            String txt = java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(txt).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("entries");
            if (arr == null) {
                return;
            }
            List<Entry> loaded = new ArrayList<>();
            for (var el : arr) {
                JsonObject o = el.getAsJsonObject();
                loaded.add(new Entry(
                        o.get("name").getAsString(),
                        o.get("size").getAsLong(),
                        o.get("modified").getAsLong()));
            }
            synchronized (ENTRIES) {
                ENTRIES.clear();
                ENTRIES.addAll(loaded);
            }
            ObserverProjector.LOG.info("[OP] 视频库缓存: {} 条", loaded.size());
        } catch (Exception e) {
            ObserverProjector.LOG.warn("[OP] 视频库缓存读不了: {}", e.toString());
        }
    }

    /** 扫目录，重建索引，写回缓存。会阻塞，别在渲染线程直接叫。 */
    static void rescan() {
        if (scanning) {
            return;
        }
        scanning = true;
        try {
            File d = dir();
            File[] list = d.listFiles();
            List<Entry> found = new ArrayList<>();
            if (list != null) {
                for (File f : list) {
                    if (!f.isFile()) {
                        continue;
                    }
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    boolean video = false;
                    for (String ext : EXTENSIONS) {
                        if (n.endsWith(ext)) {
                            video = true;
                            break;
                        }
                    }
                    // 缓存文件自己也算进来就太蠢了
                    if (video && !f.getName().startsWith(".")) {
                        found.add(new Entry(f.getName(), f.length(), f.lastModified()));
                    }
                }
            }
            Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
            synchronized (ENTRIES) {
                ENTRIES.clear();
                ENTRIES.addAll(found);
            }
            lastScan = System.currentTimeMillis();
            ObserverProjector.LOG.info("[OP] 视频库重建: {} 条", found.size());
            saveCache(found);
        } finally {
            scanning = false;
        }
    }

    private static void saveCache(List<Entry> entries) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("v", 1);
            JsonArray arr = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.name);
                o.addProperty("size", e.size);
                o.addProperty("modified", e.modified);
                arr.add(o);
            }
            root.add("entries", arr);
            java.nio.file.Files.writeString(cacheFile().toPath(), root.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            ObserverProjector.LOG.warn("[OP] 视频库缓存写不进: {}", e.toString());
        }
    }
}

package dev.dsh.observerprojector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 本地视频索引库。
 *
 * <p>两种来源：一是投影专用的那个 videos 目录，二是自动搜索整台机器。后者照搬
 * dshmusic 的扫描策略 —— 广度优先、深度封顶、每盘一份时间预算、跳过系统目录、
 * 读不了的目录直接跳过继续。不这么做的话整盘扫描会卡几十秒，还会撞进
 * Windows / Program Files 那种地方。
 *
 * <p>只索引元数据，不解码。时长和分辨率要跑 ffmpeg 才知道，代价不小，等真需要再做。
 */
final class VideoLibrary {

    /** 索引里的一条。 */
    static final class Entry {
        final String name;
        final String path;
        final long size;
        final long modified;

        Entry(String name, String path, long size, long modified) {
            this.name = name;
            this.path = path == null ? "" : path;
            this.size = size;
            this.modified = modified;
        }

        /** 界面上显示的一行。 */
        String display() {
            return name + "  (" + humanSize(size) + ")";
        }

        File file() {
            return path.isEmpty() ? new File(dir(), name) : new File(path);
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

    /**
     * 整盘扫描时要跳过的目录名。
     *
     * <p>不是"这些目录里不会有视频"，而是"进去找的代价远大于收益"：
     * 系统目录动辄几万个文件，Program Files 里全是 dll，$Recycle.Bin 和 System Volume
     * Information 还会弹权限错误。
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            "windows", "program files", "program files (x86)", "programdata",
            "$recycle.bin", "system volume information", "recovery", "perflogs",
            "appdata", "node_modules", ".git", ".gradle", ".m2", "cache", "temp", "tmp",
            "winsxs", "driverstore", "installer");

    /** 深度上限。整盘下到 6 层已经能覆盖绝大多数人的存放习惯。 */
    private static final int MAX_DEPTH = 6;

    /** 每个盘最多花这么久，超了就收工。整盘扫描不能无限期占着线程。 */
    private static final long BUDGET_PER_ROOT_MS = 2500L;

    /** 全局上限，防止挂三个盘以上时总时间失控。 */
    private static final long BUDGET_TOTAL_MS = 12000L;

    /** 单个目录最多列这么多条，防止某个巨目录把时间预算一次吃光。 */
    private static final int MAX_ENTRIES_PER_DIR = 4000;

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static volatile boolean scanning;
    private static volatile int scannedFiles;
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

    static int scannedFiles() {
        return scannedFiles;
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

    /** 按名字找一条，给共享来的 file: 标识用。 */
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

    static boolean shouldRescan() {
        return !scanning && System.currentTimeMillis() - lastScan > 60_000L;
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
                        o.has("path") ? o.get("path").getAsString() : "",
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

    /** 重建索引：先扫专用目录（一定有），再自动搜整台机器。会阻塞，别在渲染线程叫。 */
    static void rescan() {
        if (scanning) {
            return;
        }
        scanning = true;
        scannedFiles = 0;
        long started = System.currentTimeMillis();
        try {
            List<Entry> found = new ArrayList<>();
            Set<String> seenPaths = new java.util.HashSet<>();

            // ① 专用目录：整个扫完，不受时间预算限制
            collectInto(dir(), 0, Long.MAX_VALUE, found, seenPaths);

            // ② 自动搜索：所有盘，广度优先 + 深度封顶 + 时间预算
            File[] roots = File.listRoots();
            if (roots != null) {
                long totalDeadline = started + BUDGET_TOTAL_MS;
                // D 盘优先。总时间预算只有 12 秒，扫到后面用完了就收工，
                // 而 D 盘最可能是放视频的地方 —— 把它排到第一个，预算优先给它。
                java.util.List<File> ordered = new java.util.ArrayList<>(java.util.Arrays.asList(roots));
                ordered.sort((a, b) -> {
                    boolean da = a.getPath().toUpperCase(Locale.ROOT).startsWith("D");
                    boolean db = b.getPath().toUpperCase(Locale.ROOT).startsWith("D");
                    if (da == db) {
                        return a.getPath().compareToIgnoreCase(b.getPath());
                    }
                    return da ? -1 : 1;
                });
                for (File root : ordered) {
                    if (root == null || !root.isDirectory()) {
                        continue;
                    }
                    long deadline = Math.min(System.currentTimeMillis() + BUDGET_PER_ROOT_MS, totalDeadline);
                    collectInto(root, 0, deadline, found, seenPaths);
                    if (System.currentTimeMillis() >= totalDeadline) {
                        ObserverProjector.LOG.info("[OP] 自动搜索达到总时间预算，收工");
                        break;
                    }
                }
            }

            // 同一个文件可能被两条路径都扫到（软链接、目录重叠），按绝对路径去重
            Collections.sort(found, Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
            synchronized (ENTRIES) {
                ENTRIES.clear();
                ENTRIES.addAll(found);
            }
            lastScan = System.currentTimeMillis();
            ObserverProjector.LOG.info("[OP] 视频库重建: {} 条（看了 {} 个文件，用时 {} ms）",
                    found.size(), scannedFiles, System.currentTimeMillis() - started);
            saveCache(found);
        } finally {
            scanning = false;
        }
    }

    /** 广度优先往下走，到点就停。 */
    private static void collectInto(File start, int depth, long deadline, List<Entry> out, Set<String> seen) {
        java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        queue.add(start);
        depths.add(depth);

        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                return;
            }
            File d = queue.poll();
            Integer dep = depths.poll();
            int currentDepth = dep == null ? 0 : dep;

            File[] list;
            try {
                list = d.listFiles();
            } catch (Exception e) {
                continue;   // 权限不足之类的，跳过这个目录继续
            }
            if (list == null) {
                continue;
            }
            int n = 0;
            for (File f : list) {
                if (++n > MAX_ENTRIES_PER_DIR) {
                    break;
                }
                try {
                    if (f.isDirectory()) {
                        if (currentDepth < MAX_DEPTH && !skipDir(f)) {
                            queue.add(f);
                            depths.add(currentDepth + 1);
                        }
                        continue;
                    }
                    if (!f.isFile()) {
                        continue;
                    }
                    scannedFiles++;
                    if (!isVideo(f.getName())) {
                        continue;
                    }
                    String path = f.getAbsolutePath();
                    if (seen.add(path.toLowerCase(Locale.ROOT))) {
                        out.add(new Entry(f.getName(), path, f.length(), f.lastModified()));
                    }
                } catch (Exception ignored) {
                    // 单个文件读不了不影响整体
                }
            }
        }
    }

    private static boolean skipDir(File d) {
        String n = d.getName().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) {
            return false;   // 盘符根目录
        }
        if (SKIP_DIRS.contains(n) || n.startsWith("$") || n.startsWith(".")) {
            return true;
        }
        // 隐藏 + 系统属性的目录一律不进
        try {
            if (d.isHidden()) {
                return true;
            }
        } catch (Exception ignored) {
            // 判断不了就当它不隐藏
        }
        return false;
    }

    private static boolean isVideo(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static void saveCache(List<Entry> entries) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("v", 2);
            JsonArray arr = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.name);
                o.addProperty("path", e.path);
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

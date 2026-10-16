package dev.dsh.observerprojector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import net.minecraft.client.Minecraft;

/**
 * 投影机的设置，改一次存一次。
 *
 * <p>没有 OK/Cancel —— 每个按钮按下去就落盘。这类"调参数看效果"的东西，攒一个确认再
 * 一起写只会让人忘了自己改过什么。
 *
 * <p>用 java.util.Properties 而不是 json：没有依赖，坏了一行也不会毁掉整个文件。
 */
final class ProjectorConfig {

    /** 分辨率档位。0 是默认，越大越清晰也越吃上传带宽。 */
    // 分辨率档位：从 512x288 到 1280x720。后两档每帧 1.3MB / 2.7MB，
    // 上传和逐像素处理都会变重 —— 卡就把上传帧率调低，或者退回来。
    static final int[][] RES = {{512, 288}, {768, 432}, {1024, 576}, {1280, 720}};

    // ---- 投影几何 ----
    static double throwDeg = 40.0;
    static int minD = 5;
    static int maxD = 15;

    // ---- 色差：每个通道单独的像素偏移。正数往外、负数往内，0 = 不分离。
    // ---- 默认全 0：默认状态颜色是准的，想要镜头感自己拧。
    static int aberR;
    static int aberG;
    static int aberB;

    /** 自检发现红蓝反了就自动置上。 */
    static boolean swapRB = true;

    /**
     * 每通道增益。颜色自调整向导的产物 —— 用眼睛看、按结果反推修正量，
     * 而不是靠推断到底哪一段偏了。默认 1.0 表示不修正。
     */
    static float gainR = 1.0f;
    static float gainG = 1.0f;
    static float gainB = 1.0f;

    /** 上次用的网络流地址，省得每次重打。 */
    /** 音量，线性 0~1。1.0 = 不额外衰减。 */
    static float volume = 1.0f;

    /**
     * 用自发光渲染类型（beaconBeam）而不是实体类型（entityCutoutNoCull）。
     *
     * 实体类型每帧采样世界光照贴图，而那张贴图带时刻色调 —— 黄昏整幅偏暖，
     * 看起来就是"蒙了层红遮罩"。自发光不采它，投影仪本来就该是自己发光的。
     */
    static boolean emissive;

    /**
     * 遮挡（beta）：逐格判断从投影仪能不能看见，看不见的格子不画。
     * 关掉时整幅一个 quad（最快）。
     */
    static boolean occlusion;
    static String lastSearch = "";
    static String lastUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";

    /**
     * 逐格遮挡。false（默认）= 整幅一个 quad，UV 直接 0→1，从原理上不可能错位；
     * true = 每格一个 quad 各自取贴图上的对应小块，有遮挡但容易出对齐问题。
     */
    static boolean perCell;

    /** 画面方向微调：投影反过来或镜像时用它，不用改代码。 */
    static boolean flipH;
    static boolean flipV;

    // ---- 输出 ----
    static int resIndex = 0;
    static int uploadFps = 20;

    // ---- 开关 ----
    static boolean textOn = true;
    static boolean beamOn = true;
    static boolean borderOn = true;
    static int beamDensity = 5;

    private static boolean loaded;

    private ProjectorConfig() {
    }

    static int texW() {
        return RES[Math.max(0, Math.min(RES.length - 1, resIndex))][0];
    }

    static int texH() {
        return RES[Math.max(0, Math.min(RES.length - 1, resIndex))][1];
    }

    static String resLabel() {
        return texW() + "×" + texH();
    }

    private static File file() {
        Minecraft mc = Minecraft.getInstance();
        File dir = new File(mc == null ? "." : mc.gameDirectory.getAbsolutePath(), "config");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return new File("observerprojector.properties");
        }
        return new File(dir, "observerprojector.properties");
    }

    static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = file();
        if (!f.isFile()) {
            return;
        }
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            ObserverProjector.LOG.warn("[OP] 配置读不出来: {}", e.getMessage());
            return;
        }
        throwDeg = dbl(p, "throwDeg", throwDeg);
        minD = num(p, "minD", minD);
        maxD = num(p, "maxD", maxD);
        aberR = num(p, "aberR", aberR);
        aberG = num(p, "aberG", aberG);
        aberB = num(p, "aberB", aberB);
        swapRB = bool(p, "swapRB", swapRB);
        gainR = flt(p, "gainR", gainR);
        gainG = flt(p, "gainG", gainG);
        gainB = flt(p, "gainB", gainB);
        lastUrl = p.getProperty("lastUrl", lastUrl);
        lastSearch = p.getProperty("lastSearch", lastSearch);
        volume = flt(p, "volume", volume);
        emissive = bool(p, "emissive", emissive);
        occlusion = bool(p, "occlusion", occlusion);
        flipH = bool(p, "flipH", flipH);
        flipV = bool(p, "flipV", flipV);
        perCell = bool(p, "perCell", perCell);
        resIndex = num(p, "resIndex", resIndex);
        uploadFps = num(p, "uploadFps", uploadFps);
        textOn = bool(p, "textOn", textOn);
        beamOn = bool(p, "beamOn", beamOn);
        borderOn = bool(p, "borderOn", borderOn);
        beamDensity = num(p, "beamDensity", beamDensity);
        ObserverProjector.LOG.info("[OP] 配置已载入: res={} throw={} aber=({},{},{})",
                resLabel(), throwDeg, aberR, aberG, aberB);
    }

    static synchronized void save() {
        Properties p = new Properties();
        p.setProperty("throwDeg", String.valueOf(throwDeg));
        p.setProperty("minD", String.valueOf(minD));
        p.setProperty("maxD", String.valueOf(maxD));
        p.setProperty("aberR", String.valueOf(aberR));
        p.setProperty("aberG", String.valueOf(aberG));
        p.setProperty("aberB", String.valueOf(aberB));
        p.setProperty("swapRB", String.valueOf(swapRB));
        p.setProperty("gainR", String.valueOf(gainR));
        p.setProperty("gainG", String.valueOf(gainG));
        p.setProperty("gainB", String.valueOf(gainB));
        p.setProperty("lastUrl", lastUrl == null ? "" : lastUrl);
        p.setProperty("lastSearch", lastSearch == null ? "" : lastSearch);
        p.setProperty("volume", String.valueOf(volume));
        p.setProperty("emissive", String.valueOf(emissive));
        p.setProperty("occlusion", String.valueOf(occlusion));
        p.setProperty("flipH", String.valueOf(flipH));
        p.setProperty("flipV", String.valueOf(flipV));
        p.setProperty("perCell", String.valueOf(perCell));
        p.setProperty("resIndex", String.valueOf(resIndex));
        p.setProperty("uploadFps", String.valueOf(uploadFps));
        p.setProperty("textOn", String.valueOf(textOn));
        p.setProperty("beamOn", String.valueOf(beamOn));
        p.setProperty("borderOn", String.valueOf(borderOn));
        p.setProperty("beamDensity", String.valueOf(beamDensity));
        try (FileOutputStream out = new FileOutputStream(file())) {
            p.store(out, "observerprojector settings");
        } catch (Exception e) {
            ObserverProjector.LOG.warn("[OP] 配置存不下去: {}", e.getMessage());
        }
    }

    private static float flt(Properties p, String k, float def) {
        try {
            return Float.parseFloat(p.getProperty(k, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int num(Properties p, String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double dbl(Properties p, String k, double def) {
        try {
            return Double.parseDouble(p.getProperty(k, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        try {
            return Boolean.parseBoolean(p.getProperty(k, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
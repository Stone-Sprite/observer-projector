package dev.dsh.observerprojector;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 视频 + 音频播放。本地文件和网络流走**同一套** reader / 贴图管线，只有 ffmpeg 参数不同。
 *
 * <p>画面：收帧在后台线程（阻塞 IO 不能放渲染线程），写贴图在渲染线程（GL 不是线程安全的），
 * 中间用"最新一帧"槽位交接。丢帧是特性，视频要实时不要完整。
 *
 * <p>声音：走当前用户的 SourceDataLine，然后自己算空间化 —— 音量按到投影仪的距离衰减，
 * 左右声道按相对你朝向的角度做平衡。这不是物理正确的 HRTF，但"声音从投影仪那边传过来"
 * 完全能表达。走 MC 自己的声音引擎才是真 3D，可那要求音频是资源包里的静态文件。
 *
 * <p>本地 vs 网络的三处差异：本地加 -re（按原速吐），网络流不加（流本身是实时的）；
 * 网络流加 reconnect；带 Referer 时必须用 -headers（B站 CDN 少了它直接 403）。
 */
final class VideoPlayer {

    static int W = 512;
    static int H = 288;

    private static final int SAMPLE_RATE = 44100;
    /** 超过这个距离就没声了。 */
    private static final double MAX_HEAR = 32.0;

    private static Process vproc;
    private static Process aproc;
    private static Thread reader;
    private static Thread audioReader;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Object LOCK = new Object();

    private static byte[] latest;
    private static long produced;
    private static long consumed;
    private static String status = "未播放";
    private static String current = "";
    /** 网络流的 Referer。音频那条进程也得带，不然 B站 CDN 403 没声音。 */
    private static String streamReferer;

    private static SourceDataLine line;
    private static FloatControl gainCtrl;
    private static FloatControl balCtrl;
    private static float curGainDb = -80f;
    private static float curBalance;

    private VideoPlayer() {
    }


    /** 当前在播内容的**可共享标识**：net:BV... / net:https://... / file:名字。 */
    private static String shareId;

    static String shareId() {
        return shareId;
    }

    static void setShareId(String id) {
        shareId = id;
    }

    /**
     * 播放一个共享来的源标识。
     *
     * <p>三种前缀各有各的取法：B站短号自己解析（所以发送方的 CDN 地址永远不会外传）、
     * 直播/直链直接播、本地文件要求自己也有同名文件。
     *
     * @return 是否接受了这个源（B站解析是异步的，这里只表示已经受理）
     */
    static boolean playSource(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (id.startsWith("file:")) {
            File f = new File(new File("D:\\dsh-build\\observer-projector\\videos"), id.substring(5));
            if (!f.isFile()) {
                return false;
            }
            play(f);
            return true;
        }
        if (id.startsWith("net:BV")) {
            final String bvid = id.substring(4);
            new Thread(() -> {
                try {
                    long cid = ProviderSearch.cidOf(bvid);
                    String url = ProviderSearch.videoUrl(bvid, cid);
                    if (url != null) {
                        Minecraft.getInstance().execute(() -> {
                            setShareId(id);
                            playUrl(url, ProviderSearch.REFERER);
                        });
                    }
                } catch (Exception e) {
                    ObserverProjector.LOG.warn("[OP] 共享源解析失败: {}", e.toString());
                }
            }, "op-share-resolve").start();
            return true;
        }
        if (id.startsWith("net:")) {
            setShareId(id);
            playUrl(id.substring(4), null);
            return true;
        }
        return false;
    }
    static String status() {
        return status;
    }

    static String current() {
        return current;
    }

    static boolean playing() {
        return RUNNING.get();
    }

    static long producedCount() {
        synchronized (LOCK) {
            return produced;
        }
    }

    static long consumedCount() {
        synchronized (LOCK) {
            return consumed;
        }
    }

    static int frameBytes() {
        return W * H * 3;
    }

    /**
     * 找 ffmpeg。会把每个候选路径的可见性打进日志 —— 之前"起不来"却没线索，就是因为这里
     * 悄悄回退成了裸名 "ffmpeg"，而 PATH 里没有它。
     */
    private static String ffmpeg() {
        String[] cand = {
                "D:\\hiyori\\resources\\tools\\ffmpeg.exe",
                "D:\\dsh-build\\tools\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
        };
        for (String c : cand) {
            if (new File(c).isFile()) {
                return c;
            }
        }
        ObserverProjector.LOG.warn("[OP] 没找到 ffmpeg，回退裸名（PATH 里通常没有）");
        return "ffmpeg";
    }

    private static String filterChain() {
        return "scale=" + W + ":" + H + ":force_original_aspect_ratio=decrease,"
                + "pad=" + W + ":" + H + ":(ow-iw)/2:(oh-ih)/2";
    }

    // ============================ 本地文件 ============================

    static void play(File file) {
        stop();
        if (file == null || !file.isFile()) {
            status = "文件不存在";
            return;
        }
        current = file.getName();
        shareId = "file:" + file.getName();
        W = ProjectorConfig.texW();
        H = ProjectorConfig.texH();
        RUNNING.set(true);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg());
        cmd.add("-loglevel"); cmd.add("error");
        cmd.add("-re");                       // 本地按原速吐，不要一口气全读进来
        cmd.add("-i"); cmd.add(file.getAbsolutePath());
        cmd.add("-an");
        cmd.add("-f"); cmd.add("rawvideo");
        cmd.add("-pix_fmt"); cmd.add("rgb24");
        cmd.add("-vf"); cmd.add(filterChain());
        cmd.add("-r"); cmd.add("20");
        cmd.add("-");
        try {
            ObserverProjector.LOG.info("[OP] 启动本地播放: {}", file.getName());
            vproc = new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            ObserverProjector.LOG.error("[OP] 画面 ffmpeg 启动失败: {}", e.toString(), e);
            status = "ffmpeg 起不来: " + e;
            RUNNING.set(false);
            return;
        }
        startReaders();
        status = "播放中";
        ObserverProjector.LOG.info("[OP] 开始播放: {} 尺寸={}x{}", file.getName(), W, H);
    }

    // ============================ 网络流 ============================

    static void playUrl(String url) {
        startStream(url, null);
    }

    /** 带 Referer。B站 CDN 少了它直接 403，所以这个是必需的而不是可选的。 */
    static void playUrl(String url, String referer) {
        startStream(url, referer);
    }

    private static void startStream(String url, String referer) {
        stop();
        if (url == null || url.trim().isEmpty()) {
            status = "地址是空的";
            return;
        }
        current = url;
        if (shareId == null || !shareId.startsWith("net:BV")) {
            shareId = "net:" + url;
        }
        // 这一行是音频能不能出声的关键：startReaders 里音频那条 ffmpeg 靠它拿
        // Referer。之前漏了这句，streamReferer 恒为 null，音频进程被 B站 CDN 403
        // —— 表现就是"有画面没声音"。
        streamReferer = referer;
        ProjectorConfig.lastUrl = url;
        ProjectorConfig.save();
        W = ProjectorConfig.texW();
        H = ProjectorConfig.texH();
        RUNNING.set(true);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg());
        cmd.add("-loglevel"); cmd.add("error");
        // 网络流也加 -re：录播流（B站视频、直链 mp4）不加它 ffmpeg 会拼命解码，
        // 实测 6 秒产出 760 帧约 127fps，看画面就是倍速。直播流加它也无害，
        // 分片本来就是实时到的。
        cmd.add("-re");
        cmd.add("-reconnect"); cmd.add("1");
        cmd.add("-reconnect_streamed"); cmd.add("1");
        cmd.add("-reconnect_delay_max"); cmd.add("5");
        if (referer != null) {
            cmd.add("-headers");
            cmd.add("Referer: " + referer + "\r\nUser-Agent: " + ProviderSearch.UA + "\r\n");
        }
        cmd.add("-i"); cmd.add(url);
        cmd.add("-an");
        cmd.add("-f"); cmd.add("rawvideo");
        cmd.add("-pix_fmt"); cmd.add("rgb24");
        cmd.add("-vf"); cmd.add(filterChain());
        cmd.add("-r"); cmd.add("20");
        cmd.add("-");
        try {
            ObserverProjector.LOG.info("[OP] 启动网络流: {}", url);
            vproc = new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            ObserverProjector.LOG.error("[OP] 网络流 ffmpeg 启动失败: {}", e.toString(), e);
            status = "网络流起不来: " + e;
            RUNNING.set(false);
            return;
        }
        startReaders();
        status = "播放网络中";
        ObserverProjector.LOG.info("[OP] 网络流已启动: {}", url);
    }

    // ============================ reader ============================

    /**
     * 画面 + 声音两条 reader。
     *
     * <p>顺序很讲究：**音频进程先起**。之前先起画面、读完第一帧才去 spawn 音频，
     * 两条 ffmpeg 各自去拉同一个流，网络的起手快慢不一样 —— 表现就是音画有概率错开。
     * 音频那条也补了 -re（之前只有画面有），让 ffmpeg 按原速吐而不是能多快多快。
     */
    private static void startReaders() {
        List<String> acmd = new ArrayList<>();
        acmd.add(ffmpeg());
        acmd.add("-loglevel"); acmd.add("error");
        acmd.add("-re");
        acmd.add("-reconnect"); acmd.add("1");
        acmd.add("-reconnect_streamed"); acmd.add("1");
        acmd.add("-reconnect_delay_max"); acmd.add("5");
        acmd.add("-reconnect_at_eof"); acmd.add("1");
        if (streamReferer != null) {
            // 音频进程同样要 Referer —— 少了它 B站 CDN 直接 403，表现是"有画面没声音"。
            acmd.add("-headers");
            acmd.add("Referer: " + streamReferer + "\r\nUser-Agent: " + ProviderSearch.UA + "\r\n");
        }
        acmd.add("-i"); acmd.add(current);
        acmd.add("-vn");
        acmd.add("-f"); acmd.add("s16le");
        acmd.add("-ac"); acmd.add("2");
        acmd.add("-ar"); acmd.add(String.valueOf(SAMPLE_RATE));
        acmd.add("-");
        try {
            aproc = new ProcessBuilder(acmd).start();
        } catch (Exception e) {
            ObserverProjector.LOG.warn("[OP] 音频起不来: {}", e.toString());
        }

        final Process vp = vproc;
        Thread err = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(vp.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    ObserverProjector.LOG.warn("[OP] ffmpeg: {}", line);
                }
            } catch (Exception ignored) {
            }
        }, "op-video-err");
        err.setDaemon(true);
        err.start();

        reader = new Thread(() -> readVideo(vproc.getInputStream()), "op-video");
        reader.setDaemon(true);
        reader.start();


        // 音频那条的 stderr 也必须有人读。没人读的话管道写满，ffmpeg 就卡在写 stderr
        // 上出不来了 —— 表现是"有概率没声音"，概率取决于那个流吐多少日志。
        if (aproc != null) {
            final Process ap = aproc;
            Thread aerr = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(ap.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        ObserverProjector.LOG.debug("[OP] audio ffmpeg: {}", line);
                    }
                } catch (Exception ignored) {
                }
            }, "op-audio-err");
            aerr.setDaemon(true);
            aerr.start();
        }

        if (aproc != null) {
            audioReader = new Thread(() -> readAudio(aproc.getInputStream()), "op-audio");
            audioReader.setDaemon(true);
            audioReader.start();
        }
    }

    private static void readVideo(InputStream in) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(in, 1 << 16))) {
            byte[] buf = new byte[W * H * 3];
            while (RUNNING.get()) {
                dis.readFully(buf);
                synchronized (LOCK) {
                    latest = buf.clone();
                    produced++;
                }
            }
        } catch (Exception e) {
            if (RUNNING.get()) {
                status = "画面结束";
            }
        } finally {
            ObserverProjector.LOG.info("[OP] 读帧线程退出: 累计产出={}", produced);
            RUNNING.set(false);
        }
    }

    private static void readAudio(InputStream in) {
        AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
        try (SourceDataLine l = (SourceDataLine) AudioSystem.getLine(
                new DataLine.Info(SourceDataLine.class, fmt))) {
            l.open(fmt, 32768);
            l.start();
            line = l;
            acquireControls(l);
            byte[] buf = new byte[8192];
            int n;
            while (RUNNING.get() && (n = in.read(buf)) >= 0) {
                l.write(buf, 0, n);
            }
            l.drain();
        } catch (Exception e) {
            ObserverProjector.LOG.warn("[OP] 音频输出失败: {}", e.getMessage());
        } finally {
            line = null;
        }
    }

    private static void acquireControls(SourceDataLine l) {
        try {
            if (l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                gainCtrl = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
            }
        } catch (Exception ignored) {
            gainCtrl = null;
        }
        try {
            if (l.isControlSupported(FloatControl.Type.BALANCE)) {
                balCtrl = (FloatControl) l.getControl(FloatControl.Type.BALANCE);
            }
        } catch (Exception ignored) {
            balCtrl = null;
        }
    }

    /**
     * 空间化。渲染线程每帧调用。音量按距离衰减，左右按声源在相机左右轴上的投影做平衡。
     * 两个控制值都做平滑，不然玩家一转头就"啪"地跳一下。
     */
    static void updateSpatial(Vec3 camPos, Vec3 look, Vec3 srcPos) {
        if (!RUNNING.get()) {
            return;
        }
        double dist = camPos.distanceTo(srcPos);
        // 线性音量：用户音量 × 距离衰减，都走线性再转 dB。
        float vol = (float) Math.max(0.0, 1.0 - dist / MAX_HEAR) * ProjectorConfig.volume;
        float db = vol <= 0.001f ? -80f : (float) (20.0 * Math.log10(vol));

        double dx = srcPos.x - camPos.x;
        double dz = srcPos.z - camPos.z;
        double len = Math.hypot(dx, dz);
        float bal = 0f;
        if (len > 0.01) {
            double fx = look.x;
            double fz = look.z;
            double flen = Math.hypot(fx, fz);
            if (flen > 0.01) {
                fx /= flen;
                fz /= flen;
                bal = (float) ((dx / len) * fz + (dz / len) * -fx);
            }
        }
        curGainDb = curGainDb + (db - curGainDb) * 0.25f;
        curBalance = curBalance + (bal - curBalance) * 0.25f;
        FloatControl g = gainCtrl;
        if (g != null) {
            try {
                g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), curGainDb)));
            } catch (Exception ignored) {
            }
        }
        FloatControl b = balCtrl;
        if (b != null) {
            try {
                b.setValue(Math.max(b.getMinimum(), Math.min(b.getMaximum(), curBalance)));
            } catch (Exception ignored) {
            }
        }
    }

    /** 渲染线程调用。拿上一次之后的新帧；没有新帧就返回 null。 */
    static byte[] takeLatest() {
        synchronized (LOCK) {
            if (latest == null || produced == consumed || latest.length != W * H * 3) {
                return null;
            }
            consumed = produced;
            return latest;
        }
    }

    static void stop() {
        RUNNING.set(false);
        kill(vproc);
        kill(aproc);
        vproc = null;
        aproc = null;
        reader = null;
        audioReader = null;
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.flush();
            } catch (Exception ignored) {
            }
        }
        line = null;
        gainCtrl = null;
        balCtrl = null;
        curGainDb = -80f;
        curBalance = 0f;
        synchronized (LOCK) {
            latest = null;
            produced = 0;
            consumed = 0;
        }
        status = "已停止";
        current = "";
    }

    /** 必须杀干净，否则会攒一堆僵尸 ffmpeg。 */
    private static void kill(Process p) {
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

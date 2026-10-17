package dev.dsh.observerprojector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 观察者投影机的界面：播放 / 颜色 / 高级 三个选项卡。
 *
 * <p>分三个页不是为了好看，是为了**每页行数少** —— 一页十几行必然溢出窗口。之前把所有选项
 * 堆在高级页，滚动又做不干净（标签不跟着动、控件画出面板外），拆页是最省事的根治办法。
 *
 * <p>颜色页有滑块 + 预览：预览用的是和实际渲染同一个 displayArgb()，所见即所得。
 */
final class ProjectorScreen extends Screen {

    private static final int PANEL = 0xF0100010;
    private static final int EDGE = 0xDD8AB6D6;
    private static final int TITLE = 0xEEFFFFFF;
    private static final int BODY = 0xFFEEEEEE;
    private static final int DIM = 0xFFA3A3A3;
    private static final int ACCENT = 0xFF8AB6D6;
    private static final int GREEN = 0xFF55DD55;

    private static final int PAD = 10;
    private static final int BTN_H = 18;
    private static final int GAP = 6;
    private static final int ROW_H = 12;
    private static final int HEADER_H = 48;
    private static final int TAB_H = 16;
    private static final int LBL_W = 88;
    private static final int SMALL_W = 24;

    /** 服务商。mode 就是这个数组的下标。 */
    private static final String[] PROVIDERS = {"本地", "B站", "CCTV"};

    /**
     * CCTV 频道。地址是 m3u8 直播源。
     *
     * <p>注意：我试过的几个央视公开源在这台机器上都解析不了（DNS 直接失败），
     * 所以这里留空 —— 把能用的 m3u8 填进来即可，格式是 名称,地址。
     */
    private static final String[][] CCTV_CHANNELS = {
            {"CCTV-1 综合", "http://74.91.26.218:82/live/cctv1hd.m3u8"},
            {"CCTV-2 财经", "http://74.91.26.218:82/live/cctv2hd.m3u8"},
            {"CCTV-3 综艺", "http://74.91.26.218:82/live/cctv3hd.m3u8"},
            {"CCTV-13 新闻", "http://74.91.26.218:82/live/cctv13hd.m3u8"},
            {"CCTV+ 1", "https://cd-live-stream.news.cctvplus.com/live/smil:CHANNEL1.smil/playlist.m3u8"},
    };

    private static final File VIDEO_DIR = new File("D:\\dsh-build\\observer-projector\\videos");

    private final List<File> files = new ArrayList<>();
    private int selected;
    private int tab;

    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int rightX, rightW;
    private EditBox urlBox;
    private EditBox searchBox;
    private EditBox cmdBox;
    private EditBox targetBox;
    private int shareTargetStart;
    /** 0 = 本地文件，1 = B站搜索结果。列表按它显示。 */
    private int mode;
    private final List<ProviderSearch.Result> results = new ArrayList<>();
    private String searchMsg = "";
    private int rowTop;
    private int previewY;

    ProjectorScreen() {
        super(Component.literal("观察者投影机"));
        ProjectorConfig.load();
    }

    private static int text(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private int ctrlX() {
        return rightX + LBL_W + GAP;
    }

    private int ctrlW() {
        return rightW - LBL_W - GAP;
    }

    private void scanFiles() {
        files.clear();
        File[] list = VIDEO_DIR.listFiles();
        if (list == null) {
            return;
        }
        java.util.Arrays.sort(list);
        for (File f : list) {
            if (!f.isFile()) {
                continue;
            }
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm")
                    || n.endsWith(".mov") || n.endsWith(".avi")) {
                files.add(f);
            }
        }
    }

    private void touched() {
        ProjectorConfig.save();
        ProjectorRenderer.throwDeg = ProjectorConfig.throwDeg;
    }

    private Button toggle(String label, Runnable flip, int x, int y, int w) {
        return Button.builder(Component.literal(label), b -> {
            flip.run();
            touched();
            rebuildWidgets();
        }).bounds(x, y, w, BTN_H).build();
    }

    @Override
    protected void init() {
        scanFiles();
        panelW = Math.min(width - 20, 430);
        // 高级页内容 4 行 stepper + 3 行开关 + 1 行按钮 = 192px，加表头 48 还剩边距。
        panelH = Math.min(height - 20, 268);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        listX = panelX + PAD;
        listY = panelY + HEADER_H;
        listW = (int) ((panelW - PAD * 3) * 0.50);
        listH = panelH - HEADER_H - PAD;
        rightX = listX + listW + PAD;
        rightW = panelX + panelW - PAD - rightX;
        rowTop = listY;

        String[] tabs = {"播放", "颜色", "高级", "共享"};
        int tabW = 48;
        for (int i = 0; i < tabs.length; i++) {
            final int t = i;
            addRenderableWidget(Button.builder(Component.literal(tabs[i]), b -> {
                tab = t;
                rebuildWidgets();
            }).bounds(panelX + PAD + i * (tabW + 2), panelY + 4, tabW, TAB_H).build());
        }

        if (tab == 0) {
            initPlay();
        } else if (tab == 1) {
            initColor();
        } else if (tab == 2) {
            initAdvanced();
        } else {
            initShare();
        }
    }

    /** 播放页：左列表 + 右控制。不再有"服务商" —— 那三个字下面根本没有实现。 */
    private void initPlay() {
        int y = listY;
        int third = Math.max(1, (rightW - GAP * 2) / 3);
        addRenderableWidget(Button.builder(Component.literal("▶ 播放"), b -> {
            if (rowCount() == 0) {
                return;
            }
            playSelected();
        }).bounds(rightX, y, third, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("■ 停止"), b -> VideoPlayer.stop())
                .bounds(rightX + third + GAP, y, third, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(rightX + (third + GAP) * 2, y, rightW - (third + GAP) * 2, BTN_H).build());
        y += BTN_H + GAP;

        // ---- 列表上方一行：服务商切换 + 搜索框 + 搜 ----
        // 顺序很重要：先 new 再 setMaxLength/setValue。之前那版把 new 写在后面，
        // 右键一开界面就 NPE —— 而编译期查不出"字段没赋值"。
        addRenderableWidget(Button.builder(Component.literal(PROVIDERS[mode]), b -> {
            mode = (mode + 1) % PROVIDERS.length;
            selected = rowCount() > 0 ? 0 : -1;
            rebuildWidgets();
        }).bounds(listX, listY - 24, 46, 18).build());

        searchBox = new EditBox(font, listX + 50, listY - 24, listW - 50 - 46, 18, Component.literal("搜B站"));
        searchBox.setMaxLength(80);
        searchBox.setValue(ProjectorConfig.lastSearch == null ? "" : ProjectorConfig.lastSearch);
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.literal("搜"), b -> doSearch())
                .bounds(listX + listW - 44, listY - 24, 44, 18).build());

        // ---- 右栏：网络流地址 ----
        urlBox = new EditBox(font, rightX, y, rightW, BTN_H, Component.literal("流地址"));
        urlBox.setMaxLength(512);
        urlBox.setValue(ProjectorConfig.lastUrl == null ? "" : ProjectorConfig.lastUrl);
        addRenderableWidget(urlBox);
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("▶ 播放这个网络流"), b -> {
            ProjectorConfig.lastUrl = urlBox.getValue();
            VideoPlayer.playUrl(urlBox.getValue());
        }).bounds(rightX, y, rightW, BTN_H).build());
        y += BTN_H + GAP;

        rowTop = y;
    }

    /** 颜色页：滑块 + 预览。预览用实际渲染同一个函数算色，所见即所得。 */
    private void initColor() {
        rightX = listX;
        rightW = panelW - PAD * 2;
        rowTop = listY;

        previewY = listY;
        rowTop = listY + 34;      // 给预览留位置

        addRenderableWidget(new GainSlider(ctrlX(), rowTop, ctrlW(), BTN_H,
                0.4f, 2.0f, ProjectorConfig.gainR, "R 增益", v -> ProjectorConfig.gainR = v));
        rowTop += BTN_H + GAP;
        addRenderableWidget(new GainSlider(ctrlX(), rowTop, ctrlW(), BTN_H,
                0.4f, 2.0f, ProjectorConfig.gainG, "G 增益", v -> ProjectorConfig.gainG = v));
        rowTop += BTN_H + GAP;
        addRenderableWidget(new GainSlider(ctrlX(), rowTop, ctrlW(), BTN_H,
                0.4f, 2.0f, ProjectorConfig.gainB, "B 增益", v -> ProjectorConfig.gainB = v));
        rowTop += BTN_H + GAP;
        addRenderableWidget(new GainSlider(ctrlX(), rowTop, ctrlW(), BTN_H,
                0f, 1f, ProjectorConfig.volume, "音量", v -> ProjectorConfig.volume = v));
        rowTop += BTN_H + GAP;
        addRenderableWidget(new GainSlider(ctrlX(), rowTop, ctrlW(), BTN_H,
                -16f, 16f, ProjectorConfig.aberR, "色差强度", v -> {
                    // 一根滑块同时给三个通道 —— 要的是"调色差"，不是逐通道微调
                    int i = Math.round(v);
                    ProjectorConfig.aberR = i;
                    ProjectorConfig.aberG = 0;
                    ProjectorConfig.aberB = -i;
                }));
        rowTop += BTN_H + GAP + 2;

        int half = (rightW - GAP) / 2;
        addRenderableWidget(Button.builder(Component.literal("归零"), b -> {
            ProjectorConfig.gainR = 1f;
            ProjectorConfig.gainG = 1f;
            ProjectorConfig.gainB = 1f;
            ProjectorConfig.aberR = 0;
            ProjectorConfig.aberG = 0;
            ProjectorConfig.aberB = 0;
            touched();
            rebuildWidgets();
        }).bounds(rightX, rowTop, half, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("颜色自调整向导…"), b ->
                minecraft.setScreen(new CalibrationScreen(this)))
                .bounds(rightX + half + GAP, rowTop, rightW - half - GAP, BTN_H).build());
        rowTop += BTN_H + GAP;
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(rightX, rowTop, rightW, BTN_H).build());
    }

    /**
     * 高级页。两列固定：左标签列（LBL_W 宽）、右控件列。
     *
     * <p>每行只放两个宽度确定的控件，行高只有一个来源 —— 之前反复挤在一起，都是因为
     * 用字符串替换往已有行里塞东西，控件越加越多而位置是手写的。
     */
    private void initAdvanced() {
        rightX = listX;
        rightW = panelW - PAD * 2;
        rowTop = listY;

        stepper("分辨率", () -> ProjectorConfig.resIndex =
                Math.max(0, ProjectorConfig.resIndex - 1), () -> ProjectorConfig.resIndex =
                Math.min(ProjectorConfig.RES.length - 1, ProjectorConfig.resIndex + 1));
        stepper("上传帧率", () -> ProjectorConfig.uploadFps = Math.max(5, ProjectorConfig.uploadFps - 5),
                () -> ProjectorConfig.uploadFps = Math.min(60, ProjectorConfig.uploadFps + 5));
        // 投射角用滑块，不用 ± 按钮。5 度一级太粗，而一两点五度一级要点上百次
        // —— 滑块连续可调，想要多细有多细，这也是它在这里唯一合适的形态。
        addRenderableWidget(new GainSlider(ctrlX(), rowTop, ctrlW(), BTN_H,
                20f, 70f, (float) ProjectorConfig.throwDeg, "投射角", v -> {
                    ProjectorConfig.throwDeg = v;
                    ProjectorRenderer.throwDeg = v;
                }));
        rowTop += BTN_H + GAP;
        stepper("光锥密度", () -> ProjectorConfig.beamDensity = Math.max(1, ProjectorConfig.beamDensity - 1),
                () -> ProjectorConfig.beamDensity = Math.min(12, ProjectorConfig.beamDensity + 1));

        int half = (ctrlW() - GAP) / 2;
        int lx = ctrlX();
        int rx = ctrlX() + half + GAP;
        int rw = ctrlW() - half - GAP;

        addRenderableWidget(toggle(ProjectorConfig.emissive ? "自发光：开" : "自发光：关",
                () -> ProjectorConfig.emissive = !ProjectorConfig.emissive, lx, rowTop, half));
        addRenderableWidget(toggle(ProjectorConfig.occlusion ? "遮挡β：开" : "遮挡β：关",
                () -> {
                    ProjectorConfig.occlusion = !ProjectorConfig.occlusion;
                    if (ProjectorConfig.occlusion) {
                        ObserverProjector.chat(minecraft,
                                "§e遮挡(beta) 已开：逐格按视线判断，被方块或生物挡住的格子不画。");
                        ObserverProjector.chat(minecraft,
                                "§7beta 提示：格边可能有缝，投影仪离墙太近或角度太斜时更明显。");
                    }
                }, rx, rowTop, rw));
        rowTop += BTN_H + GAP;

        addRenderableWidget(toggle(ProjectorConfig.flipH ? "水平翻转：开" : "水平翻转：关",
                () -> ProjectorConfig.flipH = !ProjectorConfig.flipH, lx, rowTop, half));
        addRenderableWidget(toggle(ProjectorConfig.flipV ? "垂直翻转：开" : "垂直翻转：关",
                () -> ProjectorConfig.flipV = !ProjectorConfig.flipV, rx, rowTop, rw));
        rowTop += BTN_H + GAP;

        addRenderableWidget(toggle(ProjectorConfig.swapRB ? "红蓝：翻转" : "红蓝：默认",
                () -> ProjectorConfig.swapRB = !ProjectorConfig.swapRB, lx, rowTop, half));
        addRenderableWidget(toggle(ProjectorConfig.textOn ? "待机文字：开" : "待机文字：关",
                () -> ProjectorConfig.textOn = !ProjectorConfig.textOn, rx, rowTop, rw));
        rowTop += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("恢复默认"), b -> {
            ProjectorConfig.throwDeg = 40.0;
            ProjectorConfig.resIndex = 0;
            ProjectorConfig.uploadFps = 20;
            ProjectorConfig.gainR = 1f;
            ProjectorConfig.gainG = 1f;
            ProjectorConfig.gainB = 1f;
            ProjectorConfig.aberR = 0;
            ProjectorConfig.aberG = 0;
            ProjectorConfig.aberB = 0;
            ProjectorConfig.volume = 1f;
            ProjectorConfig.swapRB = true;
            ProjectorConfig.flipH = false;
            ProjectorConfig.flipV = false;
            ProjectorConfig.textOn = true;
            ProjectorConfig.beamOn = true;
            ProjectorConfig.borderOn = true;
            ProjectorConfig.beamDensity = 5;
            ProjectorConfig.emissive = false;
            ProjectorConfig.occlusion = false;
            touched();
            rebuildWidgets();
        }).bounds(rightX, rowTop, half, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(rightX + half + GAP, rowTop, rightW - half - GAP, BTN_H).build());
    }

    private void stepper(String label, Runnable minus, Runnable plus) {
        int cx = ctrlX();
        int cw = ctrlW();
        addRenderableWidget(toggle("−", minus, cx, rowTop, SMALL_W));
        addRenderableWidget(toggle("+", plus, cx + cw - SMALL_W, rowTop, SMALL_W));
        rowTop += BTN_H + GAP;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, EDGE);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, EDGE);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, EDGE);
        g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, EDGE);

        String head = "♪ 观察者投影机    镜头：" + ObserverProjector.lens.getName();
        g.drawString(font, head, panelX + panelW - PAD - font.width(head), panelY + 8, text(ACCENT), false);

        if (tab == 0) {
            renderPlay(g, mouseX, mouseY);
        } else if (tab == 1) {
            renderColor(g);
        } else if (tab == 2) {
            renderAdvanced(g);
        } else {
            renderShare(g);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    /**
     * B站搜索。网络请求要几秒，绝不能放在渲染线程 —— 开线程去做，回来再切回主线程填列表。
     */
    private void doSearch() {
        String kw = searchBox == null ? "" : searchBox.getValue().trim();
        if (kw.isEmpty()) {
            return;
        }
        ProjectorConfig.lastSearch = kw;
        ProjectorConfig.save();
        searchMsg = "搜索中…";
        new Thread(() -> {
            try {
                List<ProviderSearch.Result> r = ProviderSearch.bilibili(kw);
                minecraft.execute(() -> {
                    results.clear();
                    results.addAll(r);
                    mode = 1;
                    selected = r.isEmpty() ? -1 : 0;
                    searchMsg = r.isEmpty() ? "没搜到" : ("B站 " + r.size() + " 条");
                });
            } catch (Exception e) {
                ObserverProjector.LOG.warn("[OP] B站搜索失败: {}", e.toString());
                minecraft.execute(() -> searchMsg = "搜索失败: " + e.getMessage());
            }
        }, "op-search").start();
    }

    /** 选中一条搜索结果 → 解析真实流地址 → 带 Referer 播。 */
    private void playSelectedResult() {
        if (selected < 0 || selected >= results.size()) {
            ObserverProjector.chat(minecraft, "§e先选一条搜索结果");
            return;
        }
        ProviderSearch.Result r = results.get(selected);
        new Thread(() -> {
            try {
                long cid = ProviderSearch.cidOf(r.bvid);
                String url = ProviderSearch.videoUrl(r.bvid, cid);
                if (url == null) {
                    ObserverProjector.chat(minecraft, "§c这条拿不到播放地址（可能是会员专享）");
                    return;
                }
                minecraft.execute(() -> VideoPlayer.playUrl(url, ProviderSearch.REFERER));
            } catch (Exception e) {
                ObserverProjector.LOG.warn("[OP] 取流失败: {}", e.toString());
                ObserverProjector.chat(minecraft, "§c取流失败: " + e.getMessage());
            }
        }, "op-resolve").start();
    }

    /** 播放当前选中的那一项，按模式分流。双击和按钮都走这里。 */
    private void playSelected() {
        if (mode == 2) {
            // CCTV：遥控式，点一下就切台
            if (selected < 0 || selected >= CCTV_CHANNELS.length) {
                return;
            }
            String url = CCTV_CHANNELS[selected][1];
            if (url == null || url.isEmpty()) {
                ObserverProjector.chat(minecraft, "§e这个频道还没配地址");
                return;
            }
            VideoPlayer.playUrl(url, null);
            return;
        }
        if (mode == 1) {
            playSelectedResult();
        } else if (!files.isEmpty()) {
            if (selected < 0 || selected >= files.size()) {
                selected = 0;
            }
            VideoPlayer.play(files.get(selected));
        } else {
            ObserverProjector.chat(minecraft, "§e视频列表是空的");
        }
    }

    /** 当前列表里第 i 项的显示名。 */
    private String rowName(int i) {
        if (mode == 2) {
            if (i < 0 || i >= CCTV_CHANNELS.length) {
                return "";
            }
            String u = CCTV_CHANNELS[i][1];
            return CCTV_CHANNELS[i][0] + (u == null || u.isEmpty() ? "  （未配置地址）" : "");
        }
        if (mode == 1) {
            if (i < 0 || i >= results.size()) {
                return "";
            }
            ProviderSearch.Result r = results.get(i);
            return r.title + "  [" + r.duration + "]";
        }
        if (i < 0 || i >= files.size()) {
            return "";
        }
        return files.get(i).getName();
    }

    private int rowCount() {
        return (mode == 1 ? results.size() : (mode == 2 ? CCTV_CHANNELS.length : files.size()));
    }

    private void renderPlay(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0x40000000);
        g.drawString(font, (mode == 1 ? "B站结果（" + results.size() + "）  " + searchMsg : (mode == 2 ? "CCTV 频道（遥控式：单击切台）" : "视频文件（" + files.size() + "）")), listX, listY - 11, text(DIM), false);

        int rowY = listY + 1;
        for (int i = 0; i < rowCount(); i++) {
            if (rowY + ROW_H > listY + listH) {
                g.drawString(font, "…", listX + 2, rowY + 2, text(DIM), false);
                break;
            }
            boolean hover = mouseX >= listX && mouseX <= listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (i == selected) {
                g.fill(listX, rowY, listX + listW, rowY + ROW_H, 0x608AB6D6);
            } else if (hover) {
                g.fill(listX, rowY, listX + listW, rowY + ROW_H, 0x40FFFFFF);
            }
            String name = (i == selected ? "▶ " : "□ ") + rowName(i);
            String shown = font.width(name) > listW - 6
                    ? font.plainSubstrByWidth(name, listW - 12) + "…" : name;
            g.drawString(font, shown, listX + 2, rowY + 2, text(hover || i == selected ? TITLE : BODY), false);
            rowY += ROW_H;
        }
        if (rowCount() == 0) {
            g.drawString(font, "目录是空的", listX + 2, listY + 3, text(DIM), false);
            g.drawString(font, font.plainSubstrByWidth(VIDEO_DIR.getAbsolutePath(), listW),
                    listX + 2, listY + 15, text(DIM), false);
        }

        int y = rightX > 0 ? listY + BTN_H + GAP : listY;
        g.drawString(font, "状态：" + VideoPlayer.status(), rightX, y,
                text(VideoPlayer.playing() ? GREEN : DIM), false);
        y += ROW_H;
        if (VideoPlayer.playing()) {
            g.drawString(font, font.plainSubstrByWidth(VideoPlayer.current(), rightW), rightX, y, text(BODY), false);
            y += ROW_H;
        }
        String geo = ProjectorRenderer.lastDistance > 0
                ? "距离 " + ProjectorRenderer.lastDistance + " 格 · "
                  + ProjectorRenderer.lastCols + "×" + ProjectorRenderer.lastRows + " 格画面"
                : "没找到投影面（需要 " + ProjectorConfig.minD + "~" + ProjectorConfig.maxD + " 格）";
        g.drawString(font, geo, rightX, y, text(ACCENT), false);
    }

    /**
     * 颜色页：先一块预览，再四根滑块。
     *
     * <p>预览里的五块色卡走的是 ProjectorRenderer.displayArgb() —— 和墙上实际渲染同一个
     * 函数，所以这里看到的颜色就是墙上的颜色。自己另算一套的话预览就没意义了。
     */
    private void renderColor(GuiGraphics g) {
        g.drawString(font, "预览（就是墙上会显示的颜色）", rightX, previewY - 11, text(DIM), false);
        int[][] swatches = {{255, 255, 255}, {128, 128, 128}, {255, 0, 0}, {0, 255, 0}, {0, 0, 255}};
        int sw = 28;
        for (int i = 0; i < swatches.length; i++) {
            int x = rightX + i * (sw + 4);
            g.fill(x, previewY, x + sw, previewY + sw, 0xFF000000);
            g.fill(x + 1, previewY + 1, x + sw - 1, previewY + sw - 1,
                    ProjectorRenderer.displayArgb(swatches[i][0], swatches[i][1], swatches[i][2]));
        }

        int y = previewY + 34;
        y = rowLabel(g, y, "R 增益", String.format(Locale.ROOT, "%.2f", ProjectorConfig.gainR));
        y = rowLabel(g, y, "G 增益", String.format(Locale.ROOT, "%.2f", ProjectorConfig.gainG));
        y = rowLabel(g, y, "B 增益", String.format(Locale.ROOT, "%.2f", ProjectorConfig.gainB));
        rowLabel(g, y, "色差强度", String.valueOf(ProjectorConfig.aberR));
    }

    /** 共享页：把当前在播的东西通过服务器私聊告诉队友。 */
    private void initShare() {
        rightX = listX;
        rightW = panelW - PAD * 2;
        rowTop = listY;

        int half = (ctrlW() - GAP) / 2;
        int lx = ctrlX();
        int rx = ctrlX() + half + GAP;
        int rw = ctrlW() - half - GAP;

        addRenderableWidget(toggle(ShareChannel.enabled() ? "共享：开" : "共享：关",
                () -> ShareChannel.setEnabled(!ShareChannel.enabled()), listX, rowTop, rightW / 2 - GAP));
        addRenderableWidget(Button.builder(Component.literal("立即共享当前"), b -> {
            if (ShareChannel.shareCurrent()) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("§a已共享给：" + ShareChannel.targetsLine()), false);
                }
            } else if (ShareChannel.targets().isEmpty()) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("§e还没加共享目标"), false);
                }
            } else if (!ShareChannel.lastError().isEmpty()) {
                // 拒绝发送也要说清楚，不然按钮点下去像坏了
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("§c" + ShareChannel.lastError()), false);
                }
            } else {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("§7当前内容没变化，不需要重复共享"), false);
                }
            }
        }).bounds(listX + rightW / 2, rowTop, rightW / 2, BTN_H).build());
        rowTop += BTN_H + GAP + 4;

        // 私聊指令：各服不一样，所以要能改
        cmdBox = new EditBox(font, ctrlX(), rowTop, ctrlW() - 80, BTN_H, Component.literal("私聊指令"));
        cmdBox.setMaxLength(20);
        cmdBox.setValue(ShareChannel.command());
        addRenderableWidget(cmdBox);
        addRenderableWidget(Button.builder(Component.literal("设定"), b -> {
            ShareChannel.setCommand(cmdBox.getValue());
        }).bounds(ctrlX() + ctrlW() - 76, rowTop, 76, BTN_H).build());
        rowTop += BTN_H + GAP;
        // 快捷指令：各服常见的那几个一键切换，省得手打
        int qw = Math.max(24, (ctrlW() - GAP * 2) / 3);
        int qx = ctrlX();
        int shown = 0;
        for (String cmd : ShareChannel.COMMON_COMMANDS) {
            if (shown >= 3) {
                break;
            }
            final String c = cmd;
            addRenderableWidget(Button.builder(Component.literal(cmd), b -> {
                ShareChannel.setCommand(c);
                rebuildWidgets();
            }).bounds(qx, rowTop, qw, BTN_H).build());
            qx += qw + GAP;
            shown++;
        }
        rowTop += BTN_H + GAP;

        // 共享目标：和 dshmusic 一样，用一个独立的选人界面
        addRenderableWidget(Button.builder(
                Component.literal("选择共享的玩家 (" + ShareChannel.targets().size() + ")"),
                b -> minecraft.setScreen(new PlayerPickerScreen(this)))
                .bounds(ctrlX(), rowTop, ctrlW(), BTN_H).build());
        rowTop += BTN_H + GAP;
    }

    /** 共享页标注。 */
    private void renderShare(GuiGraphics g) {
        int y = rowLabel(g, listY, "共享", ShareChannel.enabled() ? "开" : "关");
        y = rowLabel(g, y, "私聊指令", ShareChannel.command());
        y = rowLabel(g, y, "目标", ShareChannel.targetsLine());
        g.drawString(font, "换内容时才发一条短消息，不是每帧都发",
                ctrlX(), rowTop + 4, text(DIM), false);
    }

    /** 高级页标注。顺序必须和 initAdvanced() 建控件的顺序完全一致。 */
    private void renderAdvanced(GuiGraphics g) {
        int y = rowLabel(g, listY, "分辨率", ProjectorConfig.resLabel());
        y = rowLabel(g, y, "上传帧率", ProjectorConfig.uploadFps + " fps");
        y = rowLabel(g, y, "投射角", (int) ProjectorConfig.throwDeg + "°");
        y = rowLabel(g, y, "光锥密度", String.valueOf(ProjectorConfig.beamDensity));
        rowLabel(g, y, "自发光 / 遮挡β", ProjectorConfig.emissive ? "发光" : "普通");
    }

    private int rowLabel(GuiGraphics g, int top, String name, String value) {
        int ty = top + (BTN_H - 8) / 2;
        g.drawString(font, font.plainSubstrByWidth(name, LBL_W), rightX, ty, text(DIM), false);
        int cx = ctrlX();
        int mid = cx + ctrlW() / 2 - font.width(value) / 2;
        g.drawString(font, value, Math.max(cx + SMALL_W + 4, mid), ty, text(BODY), false);
        return top + BTN_H + GAP;
    }

    /**
     * 列表点击：单击选中，**双击直接播**。
     *
     * <p>双击才播是"切台"的手感 —— 之前必须选中再跑去点右边的播放按钮，切一个流要两步，
     * 而且看不出哪一步没生效。
     */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (tab == 0 && mx >= listX && mx <= listX + listW && my >= listY && my < listY + listH) {
            int i = (int) ((my - listY - 1) / ROW_H);
            if (i >= 0 && i < rowCount()) {
                selected = i;
                // 播放手势按服务商分：
                //   B站  → 双击（解析要一两秒，单击只是选中，免得误触）
                //   其他 → 单击直接播（遥控式，点一下就换台）
                if (mode == 1 ? doubleClick : true) {
                    playSelected();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 一根滑块。拖动即改配置即落盘，没有确认按钮。 */
    static final class GainSlider extends AbstractSliderButton {

        private final String name;
        private final float min;
        private final float max;
        private final Consumer<Float> setter;

        GainSlider(int x, int y, int w, int h, float min, float max, float value,
                   String name, Consumer<Float> setter) {
            super(x, y, w, h, Component.literal(name), (value - min) / (max - min));
            this.name = name;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        private float current() {
            return (float) (min + (max - min) * this.value);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(name + "   " + String.format(Locale.ROOT, "%.2f", current())));
        }

        @Override
        protected void applyValue() {
            setter.accept(current());
            ProjectorConfig.save();
        }
    }

    /**
     * 颜色自调整向导。
     *
     * <p>思路是让**眼睛**闭环。前几轮一直在用日志推断"哪一段把颜色搞偏了"，但那种自证式
     * 检查（自己写、自己读）永远通过，证明不了真实字节顺序。这里换成：投一个已知纯色，
     * 问用户看到什么，按答案反推修正量 —— 不管偏色出在哪一段，反推的量都是对的。
     */
    static final class CalibrationScreen extends Screen {

        private static final int[][] STEPS = {
                {255, 255, 255}, {128, 128, 128}, {255, 0, 0}, {0, 255, 0}, {0, 0, 255}};
        private static final String[] NAMES = {"纯白", "中灰", "纯红", "纯绿", "纯蓝"};
        private static final String[] OPT = {"正常", "偏红", "偏绿", "偏蓝", "偏黄", "偏紫"};
        private static final float[][] FIX = {
                {1f, 1f, 1f}, {0.90f, 1f, 1f}, {1f, 0.90f, 1f}, {1f, 1f, 0.90f},
                {0.95f, 0.95f, 1f}, {0.95f, 1f, 0.95f}};

        private static final int W = 330;
        private static final int H = 160;

        private final Screen parent;
        private int step;
        private int px, py;

        CalibrationScreen(Screen parent) {
            super(Component.literal("颜色自调整"));
            this.parent = parent;
        }

        private void applyStep() {
            int[] c = STEPS[Math.min(step, STEPS.length - 1)];
            ProjectorRenderer.setCalibration(c[0], c[1], c[2]);
        }

        @Override
        protected void init() {
            px = (width - W) / 2;
            py = (height - H) / 2;
            if (step >= STEPS.length) {
                finish();
                return;
            }
            applyStep();

            int cols = 3;
            int bw = (W - PAD * 2 - GAP * (cols - 1)) / cols;
            for (int i = 0; i < OPT.length; i++) {
                final int k = i;
                int cx = px + PAD + (i % cols) * (bw + GAP);
                int cy = py + 72 + (i / cols) * (BTN_H + GAP);
                addRenderableWidget(Button.builder(Component.literal(OPT[i]), b -> {
                    ProjectorConfig.gainR = clampG(ProjectorConfig.gainR * FIX[k][0]);
                    ProjectorConfig.gainG = clampG(ProjectorConfig.gainG * FIX[k][1]);
                    ProjectorConfig.gainB = clampG(ProjectorConfig.gainB * FIX[k][2]);
                    ProjectorConfig.save();
                    step++;
                    rebuildWidgets();
                }).bounds(cx, cy, bw, BTN_H).build());
            }
            addRenderableWidget(Button.builder(Component.literal("结束并返回"), b -> finish())
                    .bounds(px + PAD, py + H - PAD - BTN_H, W - PAD * 2, BTN_H).build());
        }

        private void finish() {
            ProjectorRenderer.setCalibration(-1, -1, -1);
            ProjectorConfig.save();
            minecraft.setScreen(parent);
        }

        private static float clampG(float v) {
            return Math.max(0.4f, Math.min(2.0f, v));
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            g.fill(px, py, px + W, py + H, 0xF0100010);
            g.fill(px, py, px + W, py + 1, 0xDD8AB6D6);
            g.fill(px, py + H - 1, px + W, py + H, 0xDD8AB6D6);

            g.drawString(font, "颜色自调整   " + (step + 1) + "/" + STEPS.length,
                    px + PAD, py + 8, text(TITLE), false);
            g.drawString(font, "投影上此刻显示的是【" + NAMES[Math.min(step, NAMES.length - 1)] + "】",
                    px + PAD, py + 26, text(ACCENT), false);
            g.drawString(font, "你看到的是什么颜色？", px + PAD, py + 40, text(BODY), false);

            // 预览：向导投的那个色，经当前增益之后会是什么样子
            int[] c = STEPS[Math.min(step, STEPS.length - 1)];
            g.fill(px + W - PAD - 30, py + 22, px + W - PAD, py + 52, 0xFF000000);
            g.fill(px + W - PAD - 29, py + 23, px + W - PAD - 1, py + 51,
                    ProjectorRenderer.displayArgb(c[0], c[1], c[2]));

            g.drawString(font, "当前增益 " + String.format(Locale.ROOT, "R=%.2f G=%.2f B=%.2f",
                            ProjectorConfig.gainR, ProjectorConfig.gainG, ProjectorConfig.gainB),
                    px + PAD, py + 56, text(DIM), false);
            super.render(g, mouseX, mouseY, partial);
        }

        @Override
        public void removed() {
            ProjectorRenderer.setCalibration(-1, -1, -1);
            ProjectorConfig.save();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /**
     * 选共享目标。和 dshmusic 的 PlayerPickerScreen 同一套逻辑：
     * 列出服务器上在线的人，点一下切换，配合全选/清空。
     */
    static final class PlayerPickerScreen extends Screen {

        private static final int W = 300;
        private static final int H = 220;

        private final Screen parent;
        private int px, py;

        PlayerPickerScreen(Screen parent) {
            super(Component.literal("选择共享的玩家"));
            this.parent = parent;
        }

        private static java.util.List<String> online() {
            java.util.List<String> out = new java.util.ArrayList<>();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return out;
            }
            // 用实体列表而不是 connection.getOnlinePlayers()：后者的名字要过
            // GameProfile，而 authlib 的 4.x 有 getName()、10.x 改成了 name()（Record）。
            // 编译用的 classpath 是 4.x、运行时装的是 10.x，于是编译通过、运行 NoSuchMethodError。
            // level.players() 拿的是 MC 自己的 Player 实体，getName() 返回 Component，
            // 全程不碰 authlib，两个版本都成立。
            try {
                for (var p : mc.level.players()) {
                    String n = p.getName().getString();
                    if (n != null && !n.isEmpty()) {
                        out.add(n);
                    }
                }
            } catch (Exception ignored) {
            }
            out.sort(String::compareToIgnoreCase);
            return out;
        }

        @Override
        protected void init() {
            px = (width - W) / 2;
            py = (height - H) / 2;
            java.util.List<String> players = online();

            int columns = 2;
            int cw = (W - PAD * 2 - GAP) / columns;
            int row = 0;
            for (String n : players) {
                if (py + 30 + (row / columns) * (BTN_H + 3) > py + H - 50) {
                    break;
                }
                boolean on = ShareChannel.targets().contains(n);
                int cx = px + PAD + (row % columns) * (cw + GAP);
                int cy = py + 30 + (row / columns) * (BTN_H + 3);
                addRenderableWidget(Button.builder(
                        Component.literal((on ? "§a✓ " : "§7○ ") + n), b -> {
                            if (ShareChannel.targets().contains(n)) {
                                ShareChannel.removeTarget(n);
                            } else {
                                ShareChannel.addTarget(n);
                            }
                            rebuildWidgets();
                        }).bounds(cx, cy, cw, BTN_H).build());
                row++;
            }

            int bw = (W - PAD * 2 - GAP * 2) / 3;
            int by = py + H - 42;
            addRenderableWidget(Button.builder(Component.literal("全选"), b -> {
                for (String n : online()) {
                    ShareChannel.addTarget(n);
                }
                rebuildWidgets();
            }).bounds(px + PAD, by, bw, BTN_H).build());
            addRenderableWidget(Button.builder(Component.literal("清空"), b -> {
                for (String n : ShareChannel.targets()) {
                    ShareChannel.removeTarget(n);
                }
                rebuildWidgets();
            }).bounds(px + PAD + bw + GAP, by, bw, BTN_H).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> minecraft.setScreen(parent))
                    .bounds(px + PAD + (bw + GAP) * 2, by, bw, BTN_H).build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            g.fill(px, py, px + W, py + H, 0xF0100010);
            g.fill(px, py, px + W, py + 1, 0xDD8AB6D6);
            g.fill(px, py + H - 1, px + W, py + H, 0xDD8AB6D6);
            g.drawString(font, "选择共享的玩家", px + PAD, py + 8, text(TITLE), false);
            String sub = "已选 " + ShareChannel.targets().size() + " 人 · 指令 " + ShareChannel.command();
            g.drawString(font, sub, px + PAD, py + 20, text(DIM), false);
            if (online().isEmpty()) {
                g.drawString(font, "当前没有其他在线玩家", px + PAD, py + 40, text(DIM), false);
            }
            super.render(g, mouseX, mouseY, partial);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

}

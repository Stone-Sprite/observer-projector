package dev.dsh.observerprojector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 看着侦测器时显示的读数。
 *
 * <p>位置和外观照搬 dshmusic 的 MusicHud —— 水平居中、底部往上 42 像素、宽度取屏幕的
 * 三分之一封顶。同一套黄铜边框、同样的两行布局，两个 mod 的读数在同一个位置，
 * 同时用的时候不会打架。
 *
 * <p>只在准星真的对着那台已就位的侦测器时出现，平时不占屏幕。
 *
 * <p>画的部分是一个静态方法，两个 loader 各套一层薄薄的事件适配就行 —— 免得同一个
 * 布局在两个文件里各写一遍再慢慢跑偏。
 */
final class ProjectorHud {

    private static final int PANEL = 0xF0251A0E;
    private static final int TITLE = 0xEEFFFFFF;
    private static final int BODY = 0xFFEEEEEE;
    private static final int DIM = 0xFFA3A3A3;
    private static final int ACCENT = 0xFF8AB6D6;
    private static final int GREEN = 0xCC20CC20;
    private static final int BAR_BG = 0xFF2A1030;
    private static final int BAR_FG = 0xFF5FF0FF;

    /** 黄铜边框，四色调 —— 一体色的金块看着像黄色方块，加高光和阴影才像机床做的铜件。 */
    private static final int BRASS_DARK = 0xFF301E0E;
    private static final int BRASS = 0xFFD7AA5E;
    private static final int BRASS_HI = 0xFFFFEB8C;
    private static final int BRASS_SHADOW = 0xFF724731;

    private static final int BEZEL = 2;

    /** 最宽不超过屏幕这么大比例，免得挡视线。 */
    private static final double MAX_WIDTH_FRACTION = 0.34;
    private static final int MIN_WIDTH = 104;
    private static final int MAX_WIDTH = 168;
    private static final int HEIGHT = 27;
    /** 面板底边距屏幕底边多少像素。 */
    private static final int BOTTOM_GAP = 42;

    /** 缓冲条按这个帧数算满。 */
    private static final int BUFFER_FULL = 120;

    private ProjectorHud() {
    }

    /** GuiGraphics.drawString() 读颜色的字节序和 fill() 是反的。 */
    private static int text(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /** 准星现在是不是对着那台已就位的侦测器。 */
    private static boolean lookingAtProjector(Minecraft mc) {
        if (ObserverProjector.active == null || mc.player == null) {
            return false;
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos p = bhr.getBlockPos();
        return p.equals(ObserverProjector.active);
    }

    /** 两个 loader 的 HUD 事件都调这一个。 */
    static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        if (!lookingAtProjector(mc)) {
            return;
        }

        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();

        int w = Mth.clamp((int) (guiW * MAX_WIDTH_FRACTION), MIN_WIDTH, MAX_WIDTH);
        w = Math.min(w, guiW - 8);
        int x = guiW / 2 - w / 2;
        int y = guiH - BOTTOM_GAP - HEIGHT;

        // 黄铜边框：外描边、本体、左上高光、右下阴影，最后四角铆钉
        int b = BEZEL;
        g.fill(x - b - 1, y - b - 1, x + w + b + 1, y + HEIGHT + b + 1, BRASS_DARK);
        g.fill(x - b, y - b, x + w + b, y + HEIGHT + b, BRASS);
        g.fill(x, y, x + w, y + HEIGHT, PANEL);
        g.fill(x - b, y - b, x + w + b, y - b + 1, BRASS_HI);
        g.fill(x - b, y - b, x - b + 1, y + HEIGHT + b, BRASS_HI);
        g.fill(x - b, y + HEIGHT + b - 1, x + w + b, y + HEIGHT + b, BRASS_SHADOW);
        g.fill(x + w + b - 1, y - b, x + w + b, y + HEIGHT + b, BRASS_SHADOW);
        for (int cx : new int[]{x - b, x + w + b - 2}) {
            for (int cy : new int[]{y - b, y + HEIGHT + b - 2}) {
                g.fill(cx, cy, cx + 2, cy + 2, BRASS_HI);
            }
        }

        int pad = 4;
        int inner = w - pad * 2;
        boolean playing = VideoPlayer.playing();

        // 第一行：左内容名，右状态
        if (!playing) {
            String idle = "◎ 投影仪 已就位 · 索引 " + VideoLibrary.count() + " 个视频";
            g.drawString(mc.font, fit(mc, idle, inner), x + pad, y + 4, text(ACCENT), false);
        } else {
            String name = VideoPlayer.current();
            if (name == null || name.isEmpty()) {
                name = VideoPlayer.status();
            }
            String state = "▶";
            int stateW = mc.font.width(state);
            g.drawString(mc.font, fit(mc, name, inner - stateW - 4), x + pad, y + 4, text(BODY), false);
            g.drawString(mc.font, state, x + w - pad - stateW, y + 4, text(GREEN), false);
        }

        // 第二行：缓冲深度条。没有时长可显示 —— 这是流，所以显示的"进度"是
        // 已经解出来、还没被渲染吃掉多少帧，也就是缓冲有多深。
        int row2 = y + 15;
        long produced = VideoPlayer.producedCount();
        long consumed = VideoPlayer.consumedCount();
        long buffered = Math.max(0, produced - consumed);
        String left = playing ? ("缓冲 " + buffered + " 帧") : VideoPlayer.status();
        String right = playing ? (VideoPlayer.producedCount() + " 帧") : "";
        int lw = mc.font.width(left);
        int rw = mc.font.width(right);
        g.drawString(mc.font, fit(mc, left, inner), x + pad, row2, text(DIM), false);
        if (!right.isEmpty()) {
            g.drawString(mc.font, right, x + w - pad - rw, row2, text(DIM), false);
        }

        int barX = x + pad + lw + 3;
        int barW = w - pad * 2 - lw - rw - 6;
        if (playing && barW > 6) {
            g.fill(barX, row2 + 2, barX + barW, row2 + 5, BAR_BG);
            int filled = (int) Math.round(barW * Mth.clamp((double) buffered / BUFFER_FULL, 0, 1));
            if (filled > 0) {
                g.fill(barX, row2 + 2, barX + filled, row2 + 5, BAR_FG);
            }
        }
    }

    /** 超宽就截断加省略号。 */
    private static String fit(Minecraft mc, String s, int width) {
        if (s == null || width <= 0) {
            return "";
        }
        if (mc.font.width(s) <= width) {
            return s;
        }
        String out = s;
        while (out.length() > 1 && mc.font.width(out + "…") > width) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}

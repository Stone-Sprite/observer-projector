package dev.dsh.observerprojector;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * NeoForge 的 HUD 薄壳。
 *
 * <p>布局和绘制都在 {@link ProjectorHud} 的静态方法里，这个类只负责接到 NeoForge 的
 * GuiLayer 接口上 —— 两个 loader 的接口不一样，但画法只能有一份，不然迟早跑偏。
 */
final class ProjectorHudLayer implements GuiLayer {

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        ProjectorHud.render(g);
    }
}
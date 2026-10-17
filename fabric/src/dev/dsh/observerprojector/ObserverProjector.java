package dev.dsh.observerprojector;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 观察者投影机 · demo
 *
 * <p>搭建方式：侦测器放在栅栏上，侦测器「屁股」（背面）贴一个按钮。
 * 右键侦测器打开 GUI，投影从「脸」那一面射出去。
 *
 * <p>朝向是查游戏数据定死的，不是猜的：
 * <ul>
 *   <li>模型里 {@code facing=north} 时 {@code observer_front} 贴图朝北，
 *       而 front 就是「有两只眼睛的那张脸」</li>
 *   <li>只有 {@code observer_back} 有 {@code _on} 变体（中间一个亮红点），
 *       说明只有背面会因通电变样 —— 背面才是输出面</li>
 * </ul>
 * 所以：{@code FACING} = 脸 = 侦测面 = 镜头方向；{@code FACING.getOpposite()} = 屁股 = 按钮位置。
 */
public final class ObserverProjector implements ClientModInitializer {

    public static final String MODID = "observerprojector";

    /** 诊断用：画面出不来时全靠它定位。 */
    static final org.slf4j.Logger LOG = LogUtils.getLogger();

    /** 总开关。 */
    static final KeyMapping TOGGLE = new KeyMapping(
            "key.observerprojector.toggle",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F6,
            KeyMapping.Category.MISC);

    /** F6 的边沿检测，和 diamondhud 一个路子：isDown() 是状态，一次按下只有一个边沿。 */
    private static boolean toggleWasDown;

    /** 右键的边沿检测。 */
    private static boolean useWasDown;

    /** 当前选中的投影机（右键记住的那个）。demo 只支持一台。 */
    static BlockPos active;
    /** 镜头的朝向（= 侦测器的 FACING）。 */
    static Direction lens = Direction.NORTH;
    /** 总开关状态。 */
    static boolean enabled = true;

    /** 调试用的提示文案，右上角显示。 */
    static String status = "右键栅栏上的侦测器（屁股贴按钮）";

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(TOGGLE);
        WorldRenderEvents.AFTER_ENTITIES.register(ProjectorRenderer::render);
        ClientTickEvents.END_CLIENT_TICK.register(ObserverProjector::onEndTick);
        // 接收共享消息：解出来是投影仪的就跟着播
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String note = ShareChannel.handleIncoming(message.getString(), null);
            if (note != null) {
                Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(note), false);
                }
            }
        });
    }

    private static void onEndTick(Minecraft mc) {
        // ---- F6 开关 ----
        boolean down = TOGGLE.isDown();
        if (down && !toggleWasDown) {
            enabled = !enabled;
            say(mc, "观察者投影机已" + (enabled ? "开启" : "关闭"));
        }
        toggleWasDown = down;

        if (mc.player == null || mc.level == null || mc.screen != null) {
            useWasDown = false;
            return;
        }

        // ---- 右键侦测器 ----
        boolean use = mc.options.keyUse.isDown();
        if (use && !useWasDown) {
            tryOpen(mc);
        }
        useWasDown = use;
    }

    /**
     * 右键点到一个结构合法的侦测器就开 GUI。
     *
     * <p>侦测器本身没有 use 动作，所以这次点击就算传到服务端也是空转，不需要 mixin 拦截。
     */
    private static void tryOpen(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ObserverBlock)) {
            return;
        }
        Direction facing = state.getValue(ObserverBlock.FACING);
        Direction butt = facing.getOpposite();

        boolean onFence = mc.level.getBlockState(pos.below()).getBlock() instanceof FenceBlock;
        boolean hasButton = mc.level.getBlockState(pos.relative(butt)).getBlock() instanceof ButtonBlock;

        if (!onFence) {
            say(mc, "§c侦测器下面不是栅栏");
            return;
        }
        if (!hasButton) {
            say(mc, "§c侦测器屁股上没有按钮");
            return;
        }

        active = pos.immutable();
        lens = facing;
        enabled = true;
            // 就位提示按需求从聊天栏去掉 —— 方块/朝向那些属于调试信息
        mc.setScreen(new ProjectorScreen());
    }

    /** 走聊天栏而不是物品栏上方那一行 —— 那一行两秒就没了，看不见报的什么错。 */
    static void chat(Minecraft mc, String text) {
        LOG.info("[OP] chat: {}", text);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§b[投影机] §f" + text), false);
        }
    }

    static void say(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§b[投影机] §f" + text), true);
        }
    }
}

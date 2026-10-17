package dev.dsh.observerprojector;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 观察者投影机 · demo （NeoForge 端）
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
 *
 * <p>NeoForge 这边比 Fabric 多一个好处：{@code InputEvent.InteractionKeyMappingTriggered}
 * 能真正把右键拦下来（{@code setCanceled(true)}），所以点击压根不会传到世界里。
 */
@Mod(value = ObserverProjector.MODID, dist = Dist.CLIENT)
public final class ObserverProjector {

    public static final String MODID = "observerprojector";

    /** 诊断用。画面没出来时全靠它定位卡在哪一步。 */
    static final Logger LOG = LogUtils.getLogger();

    /** 总开关。 */
    static final KeyMapping TOGGLE = new KeyMapping(
            "key.observerprojector.toggle",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F6,
            KeyMapping.Category.MISC);

    /** F6 的边沿检测：isDown() 是状态，一次按下只有一个边沿。 */
    private static boolean toggleWasDown;

    /** 当前选中的投影机。demo 只支持一台。 */
    static BlockPos active;
    /** 镜头朝向（= 侦测器的 FACING）。 */
    static Direction lens = Direction.NORTH;
    /** 总开关状态。 */
    static boolean enabled = true;

    public ObserverProjector(IEventBus modBus) {
        modBus.addListener(ObserverProjector::onRegisterKeys);
        modBus.addListener(ObserverProjector::onRegisterLayers);
        NeoForge.EVENT_BUS.register(ObserverProjector.class);
        initVideoLibrary();
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean down = TOGGLE.isDown();
        if (down && !toggleWasDown) {
            enabled = !enabled;
            say(mc, "观察者投影机已" + (enabled ? "开启" : "关闭"));
        }
        toggleWasDown = down;
    }

    /**
     * 右键点到一个结构合法的侦测器就开 GUI。
     *
     * <p>NeoForge 能 cancel，所以这次点击不会传到世界 —— 比 Fabric 那边干净。
     */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LOG.info("[OP] use triggered: player={} level={} screen={} hit={}",
                mc.player != null, mc.level != null, mc.screen != null, mc.hitResult);
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            chat(mc, "§e没有指向方块（hitResult = " + mc.hitResult + "）");
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        LOG.info("[OP] block={} at {} state={}", state.getBlock(), pos, state);
        if (!(state.getBlock() instanceof ObserverBlock)) {
            return;
        }
        Direction facing = state.getValue(ObserverBlock.FACING);
        Direction butt = facing.getOpposite();

        BlockState below = mc.level.getBlockState(pos.below());
        BlockState onButt = mc.level.getBlockState(pos.relative(butt));
        boolean onFence = below.getBlock() instanceof FenceBlock;
        boolean hasButton = onButt.getBlock() instanceof ButtonBlock;
        LOG.info("[OP] facing={} butt={} below={}(fence={}) onButt={}(button={})",
                facing, butt, below.getBlock(), onFence, onButt.getBlock(), hasButton);

        if (!onFence) {
            chat(mc, "§c侦测器下面不是栅栏，是 " + below.getBlock());
            return;
        }
        if (!hasButton) {
            chat(mc, "§c侦测器屁股（" + butt + " 方向）上没有按钮，是 " + onButt.getBlock());
            return;
        }

        event.setCanceled(true);
        active = pos.immutable();
        lens = facing;
        enabled = true;
        LOG.info("[OP] projector armed at {} lens={}", active, lens);
            // 就位提示按需求从聊天栏去掉 —— 方块/朝向那些属于调试信息
        mc.setScreen(new ProjectorScreen());
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent.AfterEntities event) {
        ProjectorRenderer.render(event);
    }

    static void say(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§b[投影机] §f" + text), true);
        }
    }

    /** 走聊天栏而不是物品栏上方那一行 —— 那一行两秒就没了，看不见报的什么错。 */
    static void chat(Minecraft mc, String text) {
        LOG.info("[OP] chat: {}", text);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§b[投影机] §f" + text), false);
        }
    }

    /**
     * 接收共享消息。解出来是投影仪的东西就跟着播。
     *
     * <p>用聊天栏当传输，任何服务器都能用，不需要插件也不需要中转。
     */
    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        String note = ShareChannel.handleIncoming(event.getMessage().getString(), null);
        if (note != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(note), false);
            }
        }
    }

    /**
     * 注册 HUD。位置和外观照搬 dshmusic 的 MusicHud —— 两个 mod 的读数在同一处，
     * 同时用的时候不会打架。
     */
    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_LEVEL,
                Identifier.fromNamespaceAndPath(MODID, "projector_hud"),
                new ProjectorHudLayer());
    }

    /** 启动时读视频库缓存，然后后台重建一次索引。 */
    private static void initVideoLibrary() {
        VideoLibrary.loadCache();
        new Thread(() -> {
            try {
                VideoLibrary.rescan();
            } catch (Exception e) {
                LOG.warn("[OP] 视频库重建失败: {}", e.toString());
            }
        }, "op-library").start();
    }
}

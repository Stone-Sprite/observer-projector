package dev.dsh.observerprojector;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 投影的几何和渲染。
 *
 * <p><b>整幅一个 quad。</b>不做逐格遮挡了 —— 逐格那套要么用浮点反推格子（正好落在方块
 * 边界上，取墙还是取空气看运气），要么自己维护锚点，两个方向都是自找麻烦。整幅方案
 * 从原理上就不可能错位：一个四边形，UV 直接 0→1。
 *
 * <p>画面走贴图：先用 NativeImage 逐像素画好，再交给 DynamicTexture 上传，最后配带纹理的
 * RenderType 画出来。这是 MCEF / WebDisplays / Create: Cinema 的共同做法，也是以后接
 * 视频帧唯一顺的路。
 */
final class ProjectorRenderer {

    /** 投射角，高级页里的「投射角」改的就是它。 */
    static double throwDeg = 40.0;

    /** 给界面显示的上一帧结果。 */
    static int lastDistance = -1;
    static int lastCols;
    static int lastRows;

    private static final double ASPECT = 16.0 / 9.0;

    private static final int DVD_R = 0x0B;
    private static final int DVD_G = 0x0B;
    private static final int DVD_B = 0xA8;
    private static final int BEAM_RGB = 0x7FC8FF;

    private static int TEX_W = 512;
    private static int TEX_H = 288;
    private static final Identifier TEX_ID =
            Identifier.fromNamespaceAndPath("observerprojector", "projected_screen");

    private static DynamicTexture screen;
    private static int screenW;
    /** 上次建图时的 swapRB，开关一翻就得重画待机画面 */
    private static boolean screenSwap;
    private static int screenH;
    private static int[] frameBuf;
    private static int[] baseBuf;
    /** 自调整向导正在显示的纯色；-1 表示不在校准。 */
    static int calibR = -1;
    static int calibG = -1;
    static int calibB = -1;

    private static long lastBeamSpawn;
    private static long lastBorderSpawn;
    private static long lastVideoUpload;
    private static long lastVideoDiag;
    private static long lastSizeDiag;
    private static long lastDiag;
    private static long lastPoseDiag;

    private ProjectorRenderer() {
    }

    /**
     * 按 setPixelABGR 的约定打包：它收 0xAABBGGRR，低字节是 R。
     *
     * <p>日志实测：写 0xFF0B0BA8 之后 getPixel 读回 0xFFA80B0B（getPixel 返回 0xAARRGGBB，
     * 两个 API 的字节约定相反）。所以蓝值放高位，否则整幅发红。
     */
    private static int pack(int r, int g, int b) {
        // swapRB 是界面上的实时开关。日志证明 CPU 侧写进贴图的字节是对的，但屏幕上看到的
        // 偏红 —— 说明 GPU 采样那一段的通道顺序和 setPixelABGR 的假设不一致。这个从日志
        // 判断不了，只能给个开关现场翻。
        if (ProjectorConfig.swapRB) {
            return 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
        }
        return 0xFF000000 | (clamp255(b) << 16) | (clamp255(g) << 8) | clamp255(r);
    }

    /**
     * 给定原始 RGB，返回**屏幕上最终会显示的颜色**（已应用增益和红蓝通道设置）。
     *
     * <p>界面预览和实际渲染共用这一个函数 —— 预览要是自己另算一套，看到的和墙上的就对不上。
     */
    static int displayArgb(int r, int g, int b) {
        int rr = clamp255((int) (r * ProjectorConfig.gainR));
        int gg = clamp255((int) (g * ProjectorConfig.gainG));
        int bb = clamp255((int) (b * ProjectorConfig.gainB));
        int packed = pack(rr, gg, bb);
        // pack 出来是 ABGR，这里转成 fill() 要的 ARGB
        int a = (packed >>> 24) & 0xFF;
        int c1 = packed & 0xFF;
        int c2 = (packed >> 8) & 0xFF;
        int c3 = (packed >> 16) & 0xFF;
        return (a << 24) | (c3 << 16) | (c2 << 8) | c1;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 懒建贴图。分辨率变了要重建 —— 尺寸是建的时候定死的，不能就地改。 */
    private static void ensureTexture(Minecraft mc) {
        ProjectorConfig.load();
        TEX_W = ProjectorConfig.texW();
        TEX_H = ProjectorConfig.texH();
        throwDeg = ProjectorConfig.throwDeg;
        if (screen != null && screenW == TEX_W && screenH == TEX_H && screenSwap == ProjectorConfig.swapRB) {
            return;
        }
        if (screen != null) {
            // 必须先 release。直接 close() 只释放 GL 贴图，TextureManager 里还留着旧引用，
            // 下一次按 id 取就是野指针 —— 调分辨率当场崩。
            mc.getTextureManager().release(TEX_ID);
            screen = null;
        }
        screenW = TEX_W;
        screenH = TEX_H;
        // 用内置构造，不自己指定 Format。
        // 上一版我改成显式 Format.RGBA + setPixelABGR，结果两套字节约定混用：偏红没解决，
        // 反而多出画面左右抖动。1.21.11 的纹理系统改过（过滤由 GpuSampler 管，没有 setFilter），
        // 自己拼格式风险大。颜色交给 swapRB 和增益滑块解决。
        screen = new DynamicTexture("observerprojector_screen", TEX_W, TEX_H, false);
        // 关掉线性过滤和 mipmap。默认设置下，一张每帧整体换内容的贴图会被 mipmap 采样
        // 带偏 —— 表现就是画面左右漂、边缘糊，像信号不好的老电视。投影贴图不需要这两样。
        frameBuf = new int[TEX_W * TEX_H];
        baseBuf = new int[TEX_W * TEX_H];
        // 正在播视频就别画待机画面 —— 否则重建这一刻会盖掉视频帧，和下一帧的视频
        // 交替出现，看起来就是画面在闪。
        if (!VideoPlayer.playing()) {
            drawIdleScreen();
        }
        mc.getTextureManager().register(TEX_ID, screen);
    }

    /** 没有画面输入时的待机画面：DVD 蓝底 + 扫描线 + 顶部彩条。 */
    private static void drawIdleScreen() {
        for (int y = 0; y < TEX_H; y++) {
            for (int x = 0; x < TEX_W; x++) {
                int r = DVD_R;
                int g = DVD_G;
                int b = DVD_B;
                if ((y % 4) == 0) {
                    r += 8;
                    g += 8;
                    b += 18;
                }
                // 四边压暗：越靠边越黑，做出显像管那圈暗角
                int edge = Math.min(Math.min(x, y), Math.min(TEX_W - 1 - x, TEX_H - 1 - y));
                if (edge < 8) {
                    int k = 40 + edge * 8;
                    r = r * k / 100;
                    g = g * k / 100;
                    b = b * k / 100;
                }
                baseBuf[y * TEX_W + x] = 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        int[] bars = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF};
        for (int i = 0; i < bars.length; i++) {
            int x0 = i * (TEX_W / bars.length);
            int x1 = (i + 1) * (TEX_W / bars.length);
            for (int y = 0; y < TEX_H / 12; y++) {
                for (int x = x0; x < x1; x++) {
                    baseBuf[y * TEX_W + x] = 0xFF000000 | bars[i];
                }
            }
        }
        writeTexture(screen.getPixels(), baseBuf);
    }

    static void render(RenderLevelStageEvent.AfterEntities event) {
        long nowMs = System.currentTimeMillis();
        lastDistance = -1;
        lastCols = 0;
        lastRows = 0;

        if (!ObserverProjector.enabled || ObserverProjector.active == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ensureTexture(mc);

        // 从播到停的那一瞬间，把待机画面重新画一遍 —— 否则贴图会停在最后一帧。
        // ensureTexture 只在尺寸/通道变化时重建，播放结束它不会自己重画。
        boolean nowPlaying = VideoPlayer.playing();
        if (wasPlaying && !nowPlaying && screen != null && !ProjectorConfig.occlusion) {
            drawIdleScreen();
            screen.upload();
        }
        wasPlaying = nowPlaying;

        BlockPos origin = ObserverProjector.active;
        Direction lens = ObserverProjector.lens;

        // ---- 沿镜头找第一面不透光的墙 ----
        BlockPos cursor = origin.relative(lens);
        int d = -1;
        for (int i = 1; i <= ProjectorConfig.maxD; i++) {
            BlockState s = mc.level.getBlockState(cursor);
            if (s.canOcclude()) {
                d = i;
                break;
            }
            cursor = cursor.relative(lens);
        }
        if (d < ProjectorConfig.minD) {
            return;
        }
        lastDistance = d;

        // ---- 画面尺寸：h = 2·d·tan(θ/2)，w = h × 宽高比 ----
        double h = 2.0 * d * Math.tan(Math.toRadians(throwDeg) / 2.0);
        double w = h * ASPECT;
        int cols = Math.max(1, (int) Math.round(w));
        int rows = Math.max(1, (int) Math.round(h));
        lastCols = cols;
        lastRows = rows;

        Direction right = lens.getAxis() == Direction.Axis.Y ? Direction.EAST : lens.getClockWise();
        Direction up = lens.getAxis() == Direction.Axis.Y ? Direction.SOUTH : Direction.UP;

        // 画面贴在墙朝镜头那一面（-lens 侧）
        Vec3 wallCenter = new Vec3(
                cursor.getX() + 0.5 - lens.getStepX() * 0.5,
                cursor.getY() + 0.5 - lens.getStepY() * 0.5,
                cursor.getZ() + 0.5 - lens.getStepZ() * 0.5);
        Vec3 eye = new Vec3(origin.getX() + 0.5 + lens.getStepX() * 0.5,
                origin.getY() + 0.5 + lens.getStepY() * 0.5,
                origin.getZ() + 0.5 + lens.getStepZ() * 0.5);

        PoseStack pose = event.getPoseStack();
        MultiBufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();

        // 1.21.9+ 之后姿态矩阵是单位阵，相机位移不再预先塞进去，自己减掉
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        pose.pushPose();
        pose.translate((float) -cam.x, (float) -cam.y, (float) -cam.z);


        // ---- 视频帧：20fps 节流，取最新一帧 ----

        // ---- 视频帧：按配置的帧率节流，取最新一帧写进贴图 ----
        // 这段上次被自动清理 debug 日志时误删了 —— 结果就是"一播放就没画面"：
        // 贴图再也收不到帧，停在旧内容上。
        if (nowMs - lastVideoUpload >= 1000L / Math.max(1, ProjectorConfig.uploadFps)) {
            byte[] frame = VideoPlayer.takeLatest();
            if (frame != null && frame.length >= TEX_W * TEX_H * 3) {
                lastVideoUpload = nowMs;
                for (int i = 0; i < TEX_W * TEX_H; i++) {
                    int r = frame[i * 3] & 0xFF;
                    int g = frame[i * 3 + 1] & 0xFF;
                    int b = frame[i * 3 + 2] & 0xFF;
                    frameBuf[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                writeTexture(screen.getPixels(), frameBuf);
                screen.upload();
            }
        }
        // 校准中：每帧按当前增益重画纯色，这样按一下选项立刻能看到变化
        if (calibR >= 0) {
            writeTexture(screen.getPixels(), baseBuf);
            screen.upload();
        }

        // 诊断：视频管道状态。断了哪一段一眼看出。

        // 尺寸对账：贴图和帧必须一样宽，不然每行的起点对不上 —— 那正是"左右抖动"。

        // ---- 整幅一个 quad ----
        //
        // 用自发光（emissive）而不是 entityCutoutNoCull：后者是实体渲染类型，会采样世界的
        // lightmap，而黄昏/夜晚的 lightmap 本身就是暖色 —— 画面整体发红、翻红蓝通道也没用，
        // 因为偏的是整体色相不是通道顺序。自发光不采 lightmap，投影仪本来就该是自己发光的。
        // 回到 entityCutoutNoCull：eyes 那一版画面直接不显示了。
        // 偏红的根源是它要采样世界 lightmap（带时刻色调），那件事改用纯方块光来绕：
        // quadSpan 里 light = pack(15, 0)，天空光置 0，就不受白天/黄昏/夜晚影响了。
        // 两个类型差在"要不要采样世界光照贴图"：
        //   entityCutoutNoCull  实体类型 → 采 lightmap → 黄昏整幅暖色（红遮罩）
        //   beaconBeam          自发光    → 不采 → 颜色就是贴图本身的颜色
        // 做成开关是为了不用反复重编译就能各试一次。
        VertexConsumer vc = buffers.getBuffer(ProjectorConfig.emissive
                ? RenderTypes.beaconBeam(TEX_ID, true)
                : RenderTypes.entityCutoutNoCull(TEX_ID));
        Matrix4f mat = pose.last().pose();
        float u0 = ProjectorConfig.flipH ? 1f : 0f;
        float u1 = ProjectorConfig.flipH ? 0f : 1f;
        float v0 = ProjectorConfig.flipV ? 1f : 0f;
        float v1 = ProjectorConfig.flipV ? 0f : 1f;
        if (ProjectorConfig.occlusion) {
            // beta：逐格判断可见性，被挡的格子不画。每格就是一个 1x1 的 quad，
            // UV 取整幅图里对应的那一小块 —— 复用同一个 quadSpan，不用另写绘制。
            Vec3 rv = new Vec3(right.getStepX(), right.getStepY(), right.getStepZ());
            Vec3 uv = new Vec3(up.getStepX(), up.getStepY(), up.getStepZ());
            // 每个"屏幕方块"一个编号，UV 完全由编号推出来 ——
            // 编号是唯一的映射来源，不依赖循环嵌套的顺序，组装不会错位。
            //   编号 index = cy * cols + cx
            //   反推列号 cx = index % cols，行号 cy = index / cols
            int total = cols * rows;
            for (int index = 0; index < total; index++) {
                int cx = index % cols;
                int cy = index / cols;
                Vec3 cc = wallCenter
                        .add(rv.scale(cx - (cols - 1) / 2.0))
                        .add(uv.scale(cy - (rows - 1) / 2.0));
                if (!cellVisible(mc, eye, cc)) {
                    continue;
                }
                float cu0 = u0 + (u1 - u0) * cx / cols;
                float cu1 = u0 + (u1 - u0) * (cx + 1) / cols;
                float cv0 = v0 + (v1 - v0) * cy / rows;
                float cv1 = v0 + (v1 - v0) * (cy + 1) / rows;
                quadSpan(vc, mat, cc, lens, right, up, 1, 1, cu0, cu1, cv0, cv1);
            }
        } else {
            quadSpan(vc, mat, wallCenter, lens, right, up, cols, rows, u0, u1, v0, v1);
        }

        // ---- 待机文字 ----
        drawNoSignal(mc, pose, buffers, lens, wallCenter, h, cols, rows);

        pose.popPose();

        if (buffers instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }

        // ---- 空间音频（粒子已按需求去掉）----
        // 音符盒当 HiFi 音响：在投影仪周围找最近的音符盒，声源就放它那儿。
        // 找不到就用投影仪本身的位置，行为退化成原来那样。
        Vec3 look = mc.player == null ? new Vec3(0, 0, 1) : mc.player.getLookAngle();
        BlockPos speaker = null;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-4, -3, -4), origin.offset(4, 3, 4))) {
            if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.NOTE_BLOCK)) {
                speaker = p.immutable();
                break;
            }
        }
        Vec3 audioSrc = speaker == null
                ? new Vec3(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5)
                : new Vec3(speaker.getX() + 0.5, speaker.getY() + 0.5, speaker.getZ() + 0.5);
        VideoPlayer.updateSpatial(cam, look, audioSrc);
    }

    /**
     * 这一格从投影仪看得见吗（beta）。
     *
     * <p>两件事：沿射线每 0.25 格采样，撞到不透光方块就算被挡；再用实体碰撞盒和射线
     * 求交，站中间的人/怪也会挡。逐格画是 9x5 = 45 个小 quad，开销可以忽略。
     */
    private static boolean cellVisible(Minecraft mc, Vec3 eye, Vec3 cell) {
        Vec3 dir = cell.subtract(eye);
        double len = dir.length();
        if (len < 0.05) {
            return true;
        }
        Vec3 step = dir.scale(1.0 / len);
        for (double t = 0.6; t < len - 0.6; t += 0.25) {
            Vec3 p = eye.add(step.scale(t));
            if (mc.level.getBlockState(BlockPos.containing(p.x, p.y, p.z)).canOcclude()) {
                return false;
            }
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(eye, cell).inflate(0.6);
        for (net.minecraft.world.entity.Entity e : mc.level.getEntities((net.minecraft.world.entity.Entity) null, box)) {
            if (e.isSpectator() || e.isInvisible()) {
                continue;
            }
            if (e.getBoundingBox().inflate(0.05).clip(eye, cell).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /** 整幅四边形：覆盖 cols×rows 的矩形，UV 一次性给全。 */
    private static void quadSpan(VertexConsumer vc, Matrix4f mat, Vec3 center, Direction lens,
                                 Direction right, Direction up,
                                 int cols, int rows, float u0, float u1, float v0, float v1) {
        double hw = cols / 2.0;
        double hh = rows / 2.0;
        // center 已经在墙面上了，这里只留一点点躲 z-fighting —— 之前多减半格，
        // 表现是"画面浮在墙前面大约九个像素"。
        double e = 0.02;
        double ox = -lens.getStepX() * e;
        double oy = -lens.getStepY() * e;
        double oz = -lens.getStepZ() * e;

        Vec3 p00 = center.add(-hw * right.getStepX() - hh * up.getStepX(),
                -hw * right.getStepY() - hh * up.getStepY(),
                -hw * right.getStepZ() - hh * up.getStepZ()).add(ox, oy, oz);
        Vec3 p10 = center.add(hw * right.getStepX() - hh * up.getStepX(),
                hw * right.getStepY() - hh * up.getStepY(),
                hw * right.getStepZ() - hh * up.getStepZ()).add(ox, oy, oz);
        Vec3 p11 = center.add(hw * right.getStepX() + hh * up.getStepX(),
                hw * right.getStepY() + hh * up.getStepY(),
                hw * right.getStepZ() + hh * up.getStepZ()).add(ox, oy, oz);
        Vec3 p01 = center.add(-hw * right.getStepX() + hh * up.getStepX(),
                -hw * right.getStepY() + hh * up.getStepY(),
                -hw * right.getStepZ() + hh * up.getStepZ()).add(ox, oy, oz);

        // 纯方块光（block 15, sky 0）：天空光那份带时刻色调，投影仪不需要它。
        // 全亮：方块光 15 + 天空光 15。
        // 不要用 pack(15, 0)：那等于只取方块光，而方块光的白点是暖橙（火把色），
        // 整幅会被乘成一片红 —— 我上一版"修红"就是这么把红修出来的。
        // 白天 pack(15,15) 是纯白；黄昏那份暖色是 lightmap 本身的性质，
        // 要彻底去掉得换自发光渲染类型。
        int light = 0xF000F0;
        float nX = -lens.getStepX();
        float nY = -lens.getStepY();
        float nZ = -lens.getStepZ();
        vertex(vc, mat, p00, u0, v1, nX, nY, nZ, light);
        vertex(vc, mat, p10, u1, v1, nX, nY, nZ, light);
        vertex(vc, mat, p11, u1, v0, nX, nY, nZ, light);
        vertex(vc, mat, p01, u0, v0, nX, nY, nZ, light);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, Vec3 p, float u, float v,
                               float nx, float ny, float nz, int light) {
        vc.addVertex(mat, (float) p.x, (float) p.y, (float) p.z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(0)
                .setLight(light)
                .setNormal(nx, ny, nz);
    }

    /**
     * 把 ARGB 底图带色差写进贴图。
     *
     * <p>色差沿**半径方向**分离：从画面中心往外的方向，红往外、蓝往内。之前那版只改了 x
     * （{@code idx(x+shift, y)}，y 没动），那是横向拖影不是镜头色差 —— 镜头/显像管的色差
     * 是绕中心辐射的。
     *
     * <p>偏移量乘半径的平方：中心干净、越靠边越明显。整幅都糊只是"画质差"，只有边缘糊才像镜头。
     */
    private static void writeTexture(NativeImage img, int[] base) {
        // 自调整向导在校准中：整幅铺它给的纯色，不走画面内容
        if (calibR >= 0) {
            int r = (int) (calibR * ProjectorConfig.gainR);
            int g = (int) (calibG * ProjectorConfig.gainG);
            int b = (int) (calibB * ProjectorConfig.gainB);
            int v = pack(r, g, b);
            for (int y = 0; y < TEX_H; y++) {
                for (int x = 0; x < TEX_W; x++) {
                    img.setPixelABGR(x, y, v);
                }
            }
            return;
        }
        // 直通快路：色差全 0 且增益全 1 时逐像素照搬，不算 hypot、不做采样。
        // 这是绝大多数时间走的路 —— hypot 每像素两次是这个函数最贵的东西，
        // 之前即使色差为 0 也照算，白烧 147k 次开方 × 每秒 20 次。
        boolean plain = ProjectorConfig.aberR == 0 && ProjectorConfig.aberG == 0
                && ProjectorConfig.aberB == 0
                && ProjectorConfig.gainR == 1f && ProjectorConfig.gainG == 1f
                && ProjectorConfig.gainB == 1f;
        if (plain) {
            for (int y = 0; y < TEX_H; y++) {
                int row = y * TEX_W;
                for (int x = 0; x < TEX_W; x++) {
                    int c = base[row + x];
                    img.setPixelABGR(x, y, pack((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF));
                }
            }
            return;
        }

        double cx = (TEX_W - 1) / 2.0;
        double cy = (TEX_H - 1) / 2.0;
        double maxR = Math.hypot(cx, cy);
        for (int y = 0; y < TEX_H; y++) {
            for (int x = 0; x < TEX_W; x++) {
                double dx = x - cx;
                double dy = y - cy;
                double len = Math.hypot(dx, dy);
                double rad = len / maxR;
                double ux = len < 1e-6 ? 0 : dx / len;
                double uy = len < 1e-6 ? 0 : dy / len;
                double k = rad * rad;
                int r = (base[sample(x, y, ux, uy, ProjectorConfig.aberR * k)] >> 16) & 0xFF;
                int g = (base[sample(x, y, ux, uy, ProjectorConfig.aberG * k)] >> 8) & 0xFF;
                int b = base[sample(x, y, ux, uy, ProjectorConfig.aberB * k)] & 0xFF;
                // 三通道增益：自调整就是改这三个数
                img.setPixelABGR(x, y, pack(r, g, b));
            }
        }
    }

    /** 校准：整幅铺一个纯色，供眼睛判断。传 -1 退出校准。 */
    static void setCalibration(int r, int g, int b) {
        calibR = r;
        calibG = g;
        calibB = b;
    }

    /** 校准中也要刷新贴图（不然看不到换的颜色）。 */
    static void refreshCalibration(Minecraft mc) {
        if (screen == null || calibR < 0) {
            return;
        }
        writeTexture(screen.getPixels(), baseBuf);
        screen.upload();
    }

    private static int sample(int x, int y, double ux, double uy, double k) {
        int sx = (int) Math.round(x + ux * k);
        int sy = (int) Math.round(y + uy * k);
        sx = Math.max(0, Math.min(TEX_W - 1, sx));
        sy = Math.max(0, Math.min(TEX_H - 1, sy));
        return sy * TEX_W + sx;
    }

    /**
     * 待机文字：经典 DVD 播放机的「无信号」。
     *
     * <p>会像真的 DVD 屏保那样对角漂移、碰到边缘反弹 —— 位置每帧挪一点，用的是一小块
     * 贴图之外的几何，代价可以忽略（不像逐像素重画整张图）。
     *
     * <p>朝向：这台文字是贴在墙**朝镜头**那一面的。之前那组 switch 的角度整体偏了 180°，
     * 结果是贴在墙背面、要绕到后面才看得清 —— 所以在 switch 前先补一个 Y 轴 180°。
     */
    private static void drawNoSignal(Minecraft mc, PoseStack pose, MultiBufferSource buffers,
                                     Direction lens, Vec3 center, double h, int cols, int rows) {
        if (!ProjectorConfig.textOn || cols < 3 || rows < 2) {
            return;
        }
        Font font = mc.font;
        Component text = Component.literal("DVD \u7121\u8A0A\u865F");

        // 撞角动画：归一化坐标 -1..1，每帧按速度挪，碰边就反向
        // 按真实时间推，不是按帧。之前每帧加固定量，帧率一变速度就跟着变
        // —— 高帧率下这块标志会到处乱窜。
        long nowMs = System.currentTimeMillis();
        float dt = dvdLast == 0L ? 0f : Math.min(0.1f, (nowMs - dvdLast) / 1000f);
        dvdLast = nowMs;
        dvdX += dvdVx * dt;
        dvdY += dvdVy * dt;
        if (dvdX > 0.72f) {
            dvdX = 0.72f;
            dvdVx = -dvdVx;
        } else if (dvdX < -0.72f) {
            dvdX = -0.72f;
            dvdVx = -dvdVx;
        }
        if (dvdY > 0.62f) {
            dvdY = 0.62f;
            dvdVy = -dvdVy;
        } else if (dvdY < -0.62f) {
            dvdY = -0.62f;
            dvdVy = -dvdVy;
        }

        pose.pushPose();
        pose.translate(center.x - lens.getStepX() * 0.15,
                center.y - lens.getStepY() * 0.15,
                center.z - lens.getStepZ() * 0.15);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        switch (lens) {
            case SOUTH -> pose.mulPose(Axis.YP.rotationDegrees(180));
            case EAST -> pose.mulPose(Axis.YP.rotationDegrees(90));
            case WEST -> pose.mulPose(Axis.YP.rotationDegrees(270));
            case UP -> pose.mulPose(Axis.XP.rotationDegrees(90));
            case DOWN -> pose.mulPose(Axis.XP.rotationDegrees(-90));
            default -> { }
        }
        float s = (float) (h / 5.0 / 9.0);
        pose.scale(-s, -s, s);
        // 在文字的像素空间里偏移：屏幕半宽/半高 × 归一化位置
        pose.translate(dvdX * TEX_W / 2f, dvdY * TEX_H / 2f, 0f);
        Matrix4f m = pose.last().pose();
        font.drawInBatch(text, -font.width(text) / 2f, -4f, 0xFFFFFFFF, false, m,
                buffers, Font.DisplayMode.NORMAL, 0, 0xF000F0);
        pose.popPose();
    }

    // DVD 屏保的漂移状态
    private static float dvdX;
    private static long dvdLast;
    private static float dvdY;
    private static float dvdVx = 0.20f;
    private static float dvdVy = 0.14f;

    /** 上一次渲染时是否在播。用来检测"从播到停"的边沿。 */
    private static boolean wasPlaying;

    /** 屏幕自检：写已知基准色读回来，判定通道顺序。 */
    static void runSelfTest(Minecraft mc) {
        ProjectorConfig.load();
        TEX_W = ProjectorConfig.texW();
        TEX_H = ProjectorConfig.texH();
        if (screen != null) {
            mc.getTextureManager().release(TEX_ID);
            screen = null;
        }
        screenW = TEX_W;
        screenH = TEX_H;
        // 用内置构造，不自己指定 Format。
        // 上一版我改成显式 Format.RGBA + setPixelABGR，结果两套字节约定混用：偏红没解决，
        // 反而多出画面左右抖动。1.21.11 的纹理系统改过（过滤由 GpuSampler 管，没有 setFilter），
        // 自己拼格式风险大。颜色交给 swapRB 和增益滑块解决。
        screen = new DynamicTexture("observerprojector_screen", TEX_W, TEX_H, false);
        // 关掉线性过滤和 mipmap。默认设置下，一张每帧整体换内容的贴图会被 mipmap 采样
        // 带偏 —— 表现就是画面左右漂、边缘糊，像信号不好的老电视。投影贴图不需要这两样。
        frameBuf = new int[TEX_W * TEX_H];
        baseBuf = new int[TEX_W * TEX_H];
        NativeImage img = screen.getPixels();

        // 用 pack() 写纯红，再读回来。getPixel 返回 0xAARRGGBB，红应该在 >>16 的字节上。
        img.setPixelABGR(0, 0, pack(255, 0, 0));
        int back = img.getPixel(0, 0);
        boolean needSwap = ((back >> 16) & 0xFF) != 255;
        ProjectorConfig.swapRB = needSwap;
        ProjectorConfig.save();

        // 测试画面：上三分之三色条 + 四角黄十字（看得见的定位点）
        for (int y = 0; y < TEX_H; y++) {
            for (int x = 0; x < TEX_W; x++) {
                int r;
                int g;
                int b;
                if (y < TEX_H / 3) {
                    r = 255; g = 0; b = 0;
                } else if (y < TEX_H * 2 / 3) {
                    r = 0; g = 255; b = 0;
                } else {
                    r = 0; g = 0; b = 255;
                }
                if (Math.abs(x - TEX_W / 2) < 2 || Math.abs(y - TEX_H / 2) < 2) {
                    r = 255; g = 255; b = 255;
                }
                img.setPixelABGR(x, y, pack(r, g, b));
            }
        }
        int[][] corners = {{0, 0}, {TEX_W - 1, 0}, {0, TEX_H - 1}, {TEX_W - 1, TEX_H - 1}};
        for (int[] c : corners) {
            for (int d = -8; d <= 8; d++) {
                for (int t = -1; t <= 1; t++) {
                    put(img, c[0] + d, c[1] + t, 255, 255, 0);
                    put(img, c[0] + t, c[1] + d, 255, 255, 0);
                }
            }
        }
        screen.upload();
        mc.getTextureManager().register(TEX_ID, screen);
        ObserverProjector.chat(mc, (needSwap ? "§e自检完成：红蓝反了，已自动纠正" : "§a自检完成：通道正确")
                + " · §7测试画面已投到墙上（四角黄十字是定位点）");
    }

    private static void put(NativeImage img, int x, int y, int r, int g, int b) {
        if (x >= 0 && x < TEX_W && y >= 0 && y < TEX_H) {
            img.setPixelABGR(x, y, pack(r, g, b));
        }
    }
}

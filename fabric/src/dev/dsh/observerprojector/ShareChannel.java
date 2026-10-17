package dev.dsh.observerprojector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;

/**
 * 把当前在播的东西共享给服务器上的其他人 —— 走服务器的私聊指令。
 *
 * <p>和 dshmusic 的同步通道是同一套做法，只是把「曲子」换成了「视频源」。
 *
 * <p>流传的是一小段加密文本，不是视频。发送方封上"我在放什么"，接收方解开之后
 * 自己去取同一个东西：
 *
 * <ul>
 *   <li>{@code net:BV1...} —— B站视频。接收方自己解析、自己取流，所以既不转发任何内容，
 *       也**不会**把发送方那个绑了 IP、会过期的 CDN 地址传出去。</li>
 *   <li>{@code net:https://...} —— 直播流或直链，接收方直接播同一个地址。</li>
 *   <li>{@code file:名字.mp4} —— 本地文件。接收方只有自己也有这个文件才放得了。</li>
 * </ul>
 *
 * <p>传输用服务器的私聊指令，因为各服不一样（{@code /w}、{@code /msg}、{@code /tell}），
 * 所以做成可配置的。用聊天意味着任何服务器都能用，不需要插件也不需要中转，
 * 代价是每次换内容发一条短消息，而不是每 tick 发。
 */
final class ShareChannel {

    /** 各服常见的私聊指令，给界面做一键选择；别的也可以手打。 */
    static final String[] COMMON_COMMANDS = {"/w", "/msg", "/tell", "/m", "/pm", "/whisper"};

    static final String DEFAULT_COMMAND = "/w";

    /**
     * 一条私聊命令的载荷上限。
     *
     * <p>超了服务器会把这条当非法包处理，直接踢人 —— 客户端看到的是
     * {@code SocketException: Connection ...}，玩家莫名其妙掉线。
     * 加密之后不能安全截断（会破坏认证标签），所以只能整条放弃。
     */
    private static final int MAX_TOKEN = 220;

    /** 上次共享失败的原因，给界面显示用。成功时清空。 */
    private static String lastError = "";

    static String lastError() {
        return lastError;
    }

    private static final Set<String> TARGETS = new LinkedHashSet<>();

    private static volatile boolean enabled;
    private static volatile String command = DEFAULT_COMMAND;

    /** 上次广播过的源标识，只在真的变了的时候发。 */
    private static String lastSent = "";

    private ShareChannel() {
    }

    static boolean enabled() {
        return enabled;
    }

    static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            lastSent = "";
        }
        ProjectorConfig.save();
    }

    static String command() {
        return command;
    }

    static void setCommand(String cmd) {
        command = cmd == null || cmd.trim().isEmpty() ? DEFAULT_COMMAND : cmd.trim();
        ProjectorConfig.save();
    }

    static List<String> targets() {
        return new ArrayList<>(TARGETS);
    }

    static void addTarget(String name) {
        if (name != null && !name.trim().isEmpty()) {
            TARGETS.add(name.trim());
            ProjectorConfig.save();
        }
    }

    static void removeTarget(String name) {
        TARGETS.remove(name);
        ProjectorConfig.save();
    }

    static String targetsLine() {
        return TARGETS.isEmpty() ? "（还没加人）" : String.join(", ", TARGETS);
    }

    /**
     * 把当前在播的源广播出去。内容没变就不发 —— 不是每 tick 刷屏。
     *
     * @return 是否真的发出去了
     */
    static boolean shareCurrent() {
        String id = VideoPlayer.shareId();
        if (id == null || id.isEmpty() || id.equals(lastSent)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || TARGETS.isEmpty()) {
            return false;
        }
        // 载荷格式：{"n":名称,"a":作者,"d":时长,"i":短号,"t":时间戳}
        // 字段名照 dshmusic 用，短的优先 —— 一条聊天行装不下太多。
        // 短号是可选的校验依据：有它接收方能放同一段，没有就只能显示文字。
        StringBuilder sb = new StringBuilder();
        sb.append("{\"v\":1");
        appendField(sb, "n", VideoPlayer.shareName());
        appendField(sb, "a", VideoPlayer.shareAuthor());
        appendField(sb, "d", VideoPlayer.shareDuration());
        appendField(sb, "i", VideoPlayer.shareBv());
        sb.append(",\"t\":").append(System.currentTimeMillis() / 1000L).append("}");
        String json = sb.toString();
        String token = ShareCrypto.seal(json);
        if (token == null) {
            return false;
        }
        // 长度红线。私聊命令是一条聊天行，服务器对它的长度有限制；超过的被当
        // 非法包处理，直接踢人（客户端看到的是 SocketException: Connection ...）。
        // 加密之后不能安全截断（会破坏认证标签），所以只能整条放弃 ——
        // 宁可不共享，也不能把玩家踢下线。
        if (token.length() > MAX_TOKEN) {
            ObserverProjector.LOG.warn("[OP] 共享载荷过长（{} 字符，上限 {}），放弃发送：{}",
                    token.length(), MAX_TOKEN, id);
            lastError = "共享内容压缩后仍有 " + token.length() + " 字符，超过 " + MAX_TOKEN + " 的上限";
            return false;
        }
        // sendCommand 会自己补上开头的斜杠，所以传 "/w" 会变成 "//w" 被服务器拒掉
        String bare = command.startsWith("/") ? command.substring(1) : command;
        for (String who : TARGETS) {
            mc.getConnection().sendCommand(bare + " " + who + " " + token);
        }
        lastSent = id;
        lastError = "";
        ObserverProjector.LOG.info("[OP] 共享给 {}: {}", TARGETS, id);
        return true;
    }

    /**
     * 收到一行聊天。是共享消息就解出来并开始播。
     *
     * @param raw  已去掉格式的聊天正文
     * @param from 发送者名字，未知时为 null
     * @return 一句给用户看的说明，或 null（普通聊天 / 不是我们的消息 / 解不开）
     */
    static String handleIncoming(String raw, String from) {
        if (!enabled || raw == null || !raw.contains(ShareCrypto.MARK)) {
            return null;
        }
        String json = ShareCrypto.open(raw);
        if (json == null) {
            return null;   // 没有标记、密钥不对、或者行被截断
        }
        String name = field(json, "n");
        String author = field(json, "a");
        String duration = field(json, "d");
        String bv = field(json, "i");
        String readable = describe(name, author, duration);
        if (bv == null || bv.isEmpty()) {
            // 没有短号：只把信息念出来，放不了
            return (from == null ? "对方" : from) + " 在放：" + readable;
        }
        String id = "net:" + bv;
        // 别人发的，自己这边不应该被回送
        lastSent = id;
        boolean ok = VideoPlayer.playSource(id);
        String who = from == null ? "对方" : from;
        if (!ok) {
            return "无法播放 " + readable;
        }
        return "跟随 " + who + " 播放：" + readable;
    }


    /** 只有值非空才写这个字段，空字段白占长度。 */
    private static void appendField(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sb.append(",\"").append(key).append("\":\"").append(escape(value)).append("\"");
    }


    /** 名称 · 作者 · 时长，空的部分跳过。 */
    private static String describe(String name, String author, String duration) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{name, author, duration}) {
            if (part != null && !part.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(part);
            }
        }
        return sb.length() == 0 ? "未知内容" : sb.toString();
    }

    private static String field(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k);
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) {
            return null;
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return json.substring(q1 + 1, q2);
    }

    /**
     * 清理源标识。引号和反斜杠直接去掉而不是转义 —— 载荷只由本类读写，
     * 而且有认证加密兜底，去掉就够保证我们发出的那个引号一定是收尾引号。
     */
    private static String escape(String s) {
        return s.replace("\\", "").replace("\"", "").replace("\n", " ").replace("\r", " ");
    }
}

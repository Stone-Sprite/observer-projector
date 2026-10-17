package dev.dsh.observerprojector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 把共享载荷包成一段不可读的短文本，好从聊天栏里挤过去。
 *
 * <p>AES-256-GCM，每条消息一个随机 nonce，base64url 编码，前面加标记方便接收方识别。
 * 移植自 dshmusic 的同名类，只换了密钥材料和标记 —— 两个 mod 的载荷不会互相误解。
 *
 * <p>说清楚这是什么、不是什么：密钥是从一个写死在 jar 里的常量派生的，所以**任何拿到
 * 这个 jar 的人都能解密**。它只是让旁边看聊天栏的玩家看不懂，并让消息有固定的紧凑形状
 * —— 是混淆，不是安全边界。
 *
 * <p>真正起作用的是认证标签：截断或损坏的聊天行会认证失败被丢弃，而不是被当成垃圾解析。
 */
final class ShareCrypto {

    /** 标记，接收方先用它筛行再尝试解密。 */
    static final String MARK = "[OPJ]";

    private static final String KEY_MATERIAL = "observerprojector/shared-payload/v1";
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SecretKeySpec KEY = new SecretKeySpec(sha256(KEY_MATERIAL), "AES");

    private ShareCrypto() {
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** @return 标记 + base64url(nonce || ciphertext || tag)，失败返回 null。 */
    static String seal(String plain) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return MARK + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            ObserverProjector.LOG.warn("[OP] seal failed: {}", e.toString());
            return null;
        }
    }

    /**
     * @param text 任意聊天行，标记可以出现在任何位置
     * @return 明文；没有标记、密钥不对、行被截断时返回 null
     */
    static String open(String text) {
        if (text == null) {
            return null;
        }
        int at = text.indexOf(MARK);
        if (at < 0) {
            return null;
        }
        String body = text.substring(at + MARK.length()).trim();
        int end = 0;
        while (end < body.length()) {
            char ch = body.charAt(end);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            if (!ok) {
                break;
            }
            end++;
        }
        if (end < NONCE_LEN + 1) {
            return null;
        }
        try {
            byte[] all = Base64.getUrlDecoder().decode(body.substring(0, end));
            if (all.length <= NONCE_LEN) {
                return null;
            }
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(all, 0, nonce, 0, NONCE_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] pt = c.doFinal(all, NONCE_LEN, all.length - NONCE_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}

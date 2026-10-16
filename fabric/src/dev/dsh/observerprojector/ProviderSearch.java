package dev.dsh.observerprojector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站取流。移植自 dshmusic 的 ProviderSearch，逻辑和踩过的坑都照搬。
 *
 * <p>搜索走 {@code wbi/search/type}：老的 {@code search/type} 对没有浏览器会话的请求回 412，
 * 而 wbi 那条对普通请求正常返回 —— 所以这里**不需要 WBI 签名**。
 *
 * <p>流走 {@code fnval=1} 的 durl（渐进式 MP4）：视频轨也在里面，但它是普通 MP4 采样表，
 * ffmpeg 直接能读。{@code fnval=16} 的 DASH 是分片 MP4，对解码器不友好。
 *
 * <p>JSON 用正则抠字段，不引依赖 —— 只需要四五个字段，加个 JSON 库不划算。
 */
final class ProviderSearch {

    static final class Result {
        final String bvid;
        final String title;
        final String author;
        final String duration;

        Result(String bvid, String title, String author, String duration) {
            this.bvid = bvid;
            this.title = title;
            this.author = author;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return title + "  [" + duration + "]";
        }
    }

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final String REFERER = "https://www.bilibili.com/";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ProviderSearch() {
    }

    static List<Result> bilibili(String keyword) throws IOException, InterruptedException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/type"
                + "?search_type=video&page=1&keyword=" + enc(keyword);
        String body = get(url);
        List<Result> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\"bvid\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}").matcher(body);
        while (m.find() && out.size() < 40) {
            String item = m.group();
            String title = str(item, "title");
            if (title.isEmpty()) {
                continue;
            }
            out.add(new Result(m.group(1), clean(title), str(item, "author"), str(item, "duration")));
        }
        return out;
    }

    static long cidOf(String bvid) throws IOException, InterruptedException {
        String body = get("https://api.bilibili.com/x/web-interface/view?bvid=" + enc(bvid));
        Matcher c = Pattern.compile("\"cid\"\\s*:\\s*(\\d+)").matcher(body);
        return c.find() ? Long.parseLong(c.group(1)) : 0L;
    }

    /** 拿可播的流地址。durl 优先，退而求其次取 dash 的 baseUrl。 */
    static String videoUrl(String bvid, long cid) throws IOException, InterruptedException {
        String body = get("https://api.bilibili.com/x/player/playurl?fnval=1&fourk=1"
                + "&bvid=" + enc(bvid) + "&cid=" + cid);
        String durl = arrayAfter(body, "\"durl\"");
        if (durl != null) {
            Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"").matcher(durl);
            if (m.find()) {
                return tidy(m.group(1));
            }
        }
        String dash = arrayAfter(body, "\"dash\"");
        if (dash != null) {
            Matcher m = Pattern.compile("\"baseUrl\"\\s*:\\s*\"(https?://[^\"]+)\"").matcher(dash);
            if (m.find()) {
                return tidy(m.group(1));
            }
        }
        return null;
    }

    private static String tidy(String url) {
        return unescape(url.replace("\\u0026", "&").replace("\\/", "/"));
    }

    private static String arrayAfter(String body, String key) {
        int k = body.indexOf(key);
        if (k < 0) {
            return null;
        }
        int open = body.indexOf('[', k + key.length());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return body.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body() == null ? "" : resp.body();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        return m.find() ? unescape(m.group(1)) : "";
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n', 't' -> sb.append(' ');
                case 'r' -> { }
                case '"' -> sb.append('"');
                case '/' -> sb.append('/');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append(n);
                        }
                    } else {
                        sb.append(n);
                    }
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    private static String clean(String s) {
        String t = s.replaceAll("<[^>]+>", "");
        t = t.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#39;", "'").replace("&nbsp;", " ");
        return t.trim();
    }
}
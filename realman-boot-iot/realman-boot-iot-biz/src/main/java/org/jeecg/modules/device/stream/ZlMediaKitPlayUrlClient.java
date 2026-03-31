package org.jeecg.modules.device.stream;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * ZLMediaKit：HTTPS 调用 {@code getMediaList}，确认流在线后生成 HLS 播放地址。
 *
 * <p>与设备侧 C++ 一致：短超时；可通过 {@code device.stream.insecure-ssl} 关闭证书校验（内网自签，生产请谨慎）。
 */
@Slf4j
@Component
@RefreshScope
public class ZlMediaKitPlayUrlClient {

    @Value("${device.stream.host:172.16.44.66}")
    private String host;

    @Value("${device.stream.port.pull:8080}")
    private String pullPort;

    @Value("${device.stream.port.play:8082}")
    private String playPort;

    @Value("${device.stream.app:live}")
    private String app;

    @Value("${device.stream.secret:DvNBLZ961zAIqWrdjgkdcZ9ZJVGJuhhu}")
    private String secret;

    @Value("${device.stream.url:/index/api/getMediaList?secret=}")
    private String url;
    @Value("${device.stream.playUrl:http://172.16.44.66:8082/index/api/webrtc?app=live&type=play&stream=}")
    private String playUrl;

    @Value("${device.stream.insecure-ssl:true}")
    private boolean insecureSsl;

    private OkHttpClient httpClient;

    @PostConstruct
    void initClient() throws Exception {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS);
        if (insecureSsl) {
            TrustManager[] trustAll = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            b.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAll[0])
                    .hostnameVerifier((hostname, session) -> true);
        }
        this.httpClient = b.build();
    }

    /**
     * 请求 {@code https://{host}:{pullPort}{url}{secret}&stream=...}，成功且 {@code data} 非空时返回 HLS URL。
     *
     * @param stream query 参数，与设备上报流标识一致
     * @return HLS 地址；失败返回 {@code null}
     */
    public String resolveHlsPlayUrlIfStreamOnline(String stream) {
        if (stream == null || stream.isBlank() || httpClient == null) {
            return null;
        }
        String streamTrim = stream.trim();
        try {
            String pathAndQuery = url
                    + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                    + "&stream="
                    + URLEncoder.encode(streamTrim, StandardCharsets.UTF_8);
            String fullUrl = "https://" + host + ":" + pullPort + pathAndQuery;
            Request request = new Request.Builder().url(fullUrl).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                // OkHttp 的 body 只能读一次：必须先 string() 再记录文本，禁止对 ResponseBody 做 JSON 序列化（会得到 {}）
                String body = response.body() != null ? response.body().string() : "";
                log.debug("query_zlmediakit_stream | stream={}, url={}, code={}, body={}",streamTrim,response.request().url(),response.code(),body);
                if (!response.isSuccessful()) {
                    log.error("query_zlmediakit_stream | stream={}, zlmediakit异常返回码 {}, 响应: {}", streamTrim, response.code(), body);
                    return null;
                }
                JSONObject json = JSON.parseObject(body);
                if (json == null || json.getIntValue("code") != 0) {
                    log.warn("query_zlmediakit_stream | stream={}, getMediaList业务码非0, 响应: {}", streamTrim, body);
                    return null;
                }
                JSONArray data = json.getJSONArray("data");
                if (data == null || data.isEmpty()) {
                    log.warn("query_zlmediakit_stream | stream={}, 无在线流 data 为空", streamTrim);
                    return null;
                }
                return buildHlsPlayUrl(streamTrim);
            }
        } catch (Exception e) {
            log.error("query_zlmediakit_stream | stream={}, 连接zlmediakit失败", streamTrim, e);
            return null;
        }
    }

    /**
     * {@code https://host:pullPort/app/{streamKey}/hls.m3u8}，{@code streamKey} 为上报串路径最后一段。
     */
    public String buildHlsPlayUrl(String streamRaw) {
        String streamKey = lastPathSegment(streamRaw);
        if (streamKey.isBlank()) {
            streamKey = streamRaw.trim();
        }
        HttpUrl base = HttpUrl.parse(playUrl + streamKey);
        if (base == null) {
            return null;
        }
        return base.newBuilder()
                .build()
                .toString();
    }

    private static String lastPathSegment(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.trim();
        int q = t.indexOf('?');
        if (q >= 0) {
            t = t.substring(0, q);
        }
        int slash = t.lastIndexOf('/');
        if (slash >= 0 && slash < t.length() - 1) {
            return t.substring(slash + 1);
        }
        return t;
    }
}

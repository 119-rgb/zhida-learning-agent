package com.zhida.agent.tool.web;

import com.zhida.agent.common.config.ZhidaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Component
public class SafeWebReader {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final RestClient restClient;
    private final ZhidaProperties properties;

    @Autowired
    public SafeWebReader(ZhidaProperties properties) {
        this(properties, com.zhida.agent.tool.HttpSupport.client());
    }

    SafeWebReader(ZhidaProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public WebPageContent read(String rawUrl) {
        URI uri = validatePublicHttpUrl(rawUrl);
        String html = com.zhida.agent.tool.HttpSupport.retry(() -> restClient.get()
                .uri(uri)
                .exchange((request,response) -> {
                    if (response.getStatusCode().is3xxRedirection()) throw new IllegalArgumentException("暂不读取重定向网页，请提供最终公开地址。");
                    if (response.getStatusCode().isError()) throw org.springframework.web.client.HttpClientErrorException.create(
                            response.getStatusCode(),"网页读取失败",response.getHeaders(),new byte[0],java.nio.charset.StandardCharsets.UTF_8);
                    byte[] bytes=response.getBody().readNBytes(2*1024*1024+1);
                    if(bytes.length>2*1024*1024) throw new IllegalArgumentException("网页超过2MB读取限制");
                    return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                }));

        if (html == null || html.isBlank()) {
            return new WebPageContent("", uri.toString(), "网页没有可读取的正文。", false);
        }

        Document document = Jsoup.parse(html, uri.toString());
        document.select("script,style,noscript,svg,nav,footer").remove();
        String text = document.body() == null ? "" : document.body().text();
        int limit = Math.max(1_000, properties.getWebReader().getMaxContentLength());
        boolean truncated = text.length() > limit;
        String content = truncated ? text.substring(0, limit) : text;

        return new WebPageContent(document.title(), uri.toString(), content, truncated);
    }

    URI validatePublicHttpUrl(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("网页地址格式不正确。", exception);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme) || uri.getHost() == null || uri.getUserInfo()!=null) {
            throw new IllegalArgumentException("只允许读取公开的 HTTP/HTTPS 网页。");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("不允许读取本机或内网地址。");
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                byte[] bytes=address.getAddress();
                if(bytes.length==16 && (bytes[0] & 0xfe)==0xfc) throw new IllegalArgumentException("不允许读取内网地址");
                if(bytes.length==4 && ((bytes[0]&255)==0 || ((bytes[0]&255)==100 && (bytes[1]&255)>=64 && (bytes[1]&255)<=127)))
                    throw new IllegalArgumentException("不允许读取保留地址");
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("不允许读取本机或内网地址。");
                }
            }
        }
        catch (IllegalArgumentException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("无法解析网页地址。", exception);
        }

        return uri;
    }
}

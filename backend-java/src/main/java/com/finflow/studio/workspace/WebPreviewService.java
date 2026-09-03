package com.finflow.studio.workspace;

import com.finflow.studio.workflow.WorkflowDefinitionService;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WebPreviewService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private final WorkflowDefinitionService workflows;
    private final Map<String, CachedPreview> cache = new ConcurrentHashMap<>();

    public WebPreviewService(WorkflowDefinitionService workflows) {
        this.workflows = workflows;
    }

    public WorkspaceModels.WebPreview get(String projectId, String resourceId, boolean refresh) {
        var node = webResource(projectId, resourceId);
        var config = node.config() == null ? Map.<String, Object>of() : node.config();
        var url = Objects.toString(config.get("url"), "").trim();
        var title = Objects.toString(config.getOrDefault("title", node.name()), node.name());
        validateUrlSyntax(url);

        var curatedSummary = Objects.toString(config.get("sourceSummary"), "").trim();
        var curatedHighlights = stringList(config.get("sourceHighlights"));
        if (!refresh && (!curatedSummary.isBlank() || !curatedHighlights.isEmpty())) {
            return new WorkspaceModels.WebPreview(title, url, host(url), curatedSummary, curatedHighlights,
                    List.of(), "CURATED", Objects.toString(config.get("verifiedAt"), ""), "");
        }

        var cached = cache.get(url);
        if (!refresh && cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.preview();
        }
        try {
            validatePublicAddress(url);
            var document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; FinFlowStudio/1.0; +https://github.com/noah990127/finflow-studio)")
                    .timeout(12_000)
                    .maxBodySize(3 * 1024 * 1024)
                    .followRedirects(true)
                    .get();
            document.select("script,style,noscript,svg,nav,footer,form,aside,[aria-hidden=true]").remove();
            var root = first(document.selectFirst("article"), document.selectFirst("main"), document.body());
            var pageTitle = document.title().isBlank() ? title : document.title();
            var paragraphs = root.select("p").stream().map(Element::text).map(String::trim)
                    .filter(text -> text.length() >= 35).distinct().limit(18).toList();
            var summary = paragraphs.stream().limit(2).reduce((left, right) -> left + "\n" + right).orElse("");
            var highlights = paragraphs.stream().skip(2).limit(5).toList();
            var sections = extractSections(root);
            var preview = new WorkspaceModels.WebPreview(pageTitle, url, host(url), summary, highlights,
                    sections, "LIVE", "", Instant.now().toString());
            cache.put(url, new CachedPreview(Instant.now(), preview));
            return preview;
        } catch (Exception error) {
            if (!curatedSummary.isBlank() || !curatedHighlights.isEmpty()) {
                return new WorkspaceModels.WebPreview(title, url, host(url), curatedSummary, curatedHighlights,
                        List.of(), "CURATED", Objects.toString(config.get("verifiedAt"), ""), "");
            }
            throw new IllegalStateException("这个网站暂时无法读取，可在新窗口打开原网页");
        }
    }

    public WorkspaceModels.WebEmbedStatus embedStatus(String projectId, String resourceId, String studioOrigin) {
        var node = webResource(projectId, resourceId);
        var config = node.config() == null ? Map.<String, Object>of() : node.config();
        var url = Objects.toString(config.get("url"), "").trim();
        validateUrlSyntax(url);
        try {
            validatePublicAddress(url);
            var response = requestHeaders(url, Connection.Method.HEAD);
            if (response.statusCode() == 405 || response.statusCode() == 501) {
                response = requestHeaders(url, Connection.Method.GET);
            }
            if (blocksEmbedding(response.header("X-Frame-Options"),
                    response.header("Content-Security-Policy"), response.url().toString(), studioOrigin)) {
                return new WorkspaceModels.WebEmbedStatus("BLOCKED", "该网站的安全策略不允许在 Studio 内显示原网页");
            }
            return new WorkspaceModels.WebEmbedStatus("ALLOWED", "");
        } catch (Exception error) {
            return new WorkspaceModels.WebEmbedStatus("UNKNOWN", "暂时无法确认该网站是否允许在 Studio 内显示");
        }
    }

    static boolean blocksEmbedding(String frameOptions, String contentSecurityPolicy,
                                   String targetUrl, String studioOrigin) {
        var xFrame = Objects.toString(frameOptions, "").toLowerCase();
        if (xFrame.contains("deny")) return true;
        if (xFrame.contains("sameorigin") && !sameOrigin(targetUrl, studioOrigin)) return true;

        var csp = Objects.toString(contentSecurityPolicy, "");
        for (var directive : csp.split(";")) {
            var trimmed = directive.trim();
            if (!trimmed.toLowerCase().startsWith("frame-ancestors")) continue;
            var sources = trimmed.substring("frame-ancestors".length()).trim().split("\\s+");
            for (var source : sources) {
                if (source.equals("*") || source.equalsIgnoreCase(studioOrigin)) return false;
                if (source.equalsIgnoreCase("'self'") && sameOrigin(targetUrl, studioOrigin)) return false;
            }
            return true;
        }
        return false;
    }

    private Connection.Response requestHeaders(String url, Connection.Method method) throws java.io.IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; FinFlowStudio/1.0; +https://github.com/noah990127/finflow-studio)")
                .timeout(6_000)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .maxBodySize(method == Connection.Method.HEAD ? 0 : 32 * 1024)
                .method(method)
                .execute();
    }

    private com.finflow.studio.workflow.WorkflowModels.NodeDefinition webResource(String projectId, String resourceId) {
        return workflows.list(projectId).stream()
                .flatMap(workflow -> workflow.nodes().stream())
                .filter(item -> item.id().equals(resourceId)
                        && item.type() == com.finflow.studio.workflow.WorkflowModels.NodeType.LINK_INPUT)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("网站资料不存在"));
    }

    private static boolean sameOrigin(String left, String right) {
        if (right == null || right.isBlank()) return false;
        try {
            var leftUri = URI.create(left);
            var rightUri = URI.create(right);
            return leftUri.getScheme().equalsIgnoreCase(rightUri.getScheme())
                    && leftUri.getHost().equalsIgnoreCase(rightUri.getHost())
                    && effectivePort(leftUri) == effectivePort(rightUri);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private List<WorkspaceModels.WebPreviewSection> extractSections(Element root) {
        var result = new ArrayList<WorkspaceModels.WebPreviewSection>();
        for (var heading : root.select("h1,h2,h3")) {
            var texts = new ArrayList<String>();
            var cursor = heading.nextElementSibling();
            while (cursor != null && !cursor.tagName().matches("h[1-3]") && texts.size() < 3) {
                if (cursor.tagName().equals("p") && cursor.text().trim().length() >= 35) texts.add(cursor.text().trim());
                cursor = cursor.nextElementSibling();
            }
            if (!heading.text().isBlank() && !texts.isEmpty()) {
                result.add(new WorkspaceModels.WebPreviewSection(heading.text().trim(), texts));
                if (result.size() == 6) break;
            }
        }
        return result;
    }

    private void validateUrlSyntax(String value) {
        var uri = URI.create(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) throw new IllegalArgumentException("网站地址无效");
    }

    private void validatePublicAddress(String value) {
        try {
            var uri = URI.create(value);
            for (var address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("工作台不会读取本机或内网地址");
                }
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("网站地址无法解析");
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(Objects::toString).map(String::trim).filter(item -> !item.isBlank()).limit(8).toList();
    }

    private String host(String url) { return URI.create(url).getHost().replaceFirst("^www\\.", ""); }

    @SafeVarargs
    private final <T> T first(T... values) {
        for (var value : values) if (value != null) return value;
        throw new IllegalStateException("网页没有正文");
    }

    private record CachedPreview(Instant createdAt, WorkspaceModels.WebPreview preview) { }
}

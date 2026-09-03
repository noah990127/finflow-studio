package com.finflow.studio.workspace;

import com.finflow.studio.workflow.WorkflowDefinitionService;
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
        var node = workflows.list(projectId).stream()
                .flatMap(workflow -> workflow.nodes().stream())
                .filter(item -> item.id().equals(resourceId)
                        && item.type() == com.finflow.studio.workflow.WorkflowModels.NodeType.LINK_INPUT)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("网站资料不存在"));
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

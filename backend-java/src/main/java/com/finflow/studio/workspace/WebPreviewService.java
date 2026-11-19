package com.finflow.studio.workspace;

import com.finflow.studio.workflow.WorkflowDefinitionService;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        var cacheKey = resourceId + "|" + url;
        var cached = cache.get(cacheKey);
        if (!refresh && cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.preview();
        }
        try {
            validatePublicAddress(url);
            var document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; FinBTPStudio/1.0; +https://github.com/noah990127/finflow-studio)")
                    .timeout(12_000)
                    .maxBodySize(3 * 1024 * 1024)
                    .followRedirects(true)
                    .get();
            document.select("script,style,noscript,svg,nav,footer,aside,[aria-hidden=true]").remove();
            var root = first(document.selectFirst("article"), document.selectFirst("main"), document.body());
            var pageTitle = document.title().isBlank() ? title : document.title();
            var extracted = extractReadablePage(document, root);
            var summary = curatedSummary.isBlank() ? extracted.summary() : curatedSummary;
            var highlights = merge(curatedHighlights, extracted.highlights(), 12);
            var verifiedAt = Objects.toString(config.get("verifiedAt"), "");
            var previewMode = curatedSummary.isBlank() && curatedHighlights.isEmpty() ? "LIVE" : "CURATED";
            var preview = new WorkspaceModels.WebPreview(pageTitle, url, host(url), summary,
                    extracted.facts(), highlights, extracted.sections(), extracted.links(),
                    previewMode, verifiedAt, Instant.now().toString());
            cache.put(cacheKey, new CachedPreview(Instant.now(), preview));
            return preview;
        } catch (Exception error) {
            if (!curatedSummary.isBlank() || !curatedHighlights.isEmpty()) {
                return new WorkspaceModels.WebPreview(title, url, host(url), curatedSummary, List.of(),
                        curatedHighlights, List.of(), List.of(), "CURATED",
                        Objects.toString(config.get("verifiedAt"), ""), "");
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
            return new WorkspaceModels.WebEmbedStatus("UNKNOWN",
                    "Studio 服务暂时无法连接该网站，可能受到网络限制或被目标网站拒绝访问");
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
                .userAgent("Mozilla/5.0 (compatible; FinBTPStudio/1.0; +https://github.com/noah990127/finflow-studio)")
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

    static ExtractedPage extractReadablePage(Document document, Element root) {
        var blocks = new ArrayList<String>();
        for (var element : root.select("p,li,blockquote,table tr")) {
            var text = element.tagName().equals("tr")
                    ? String.join(" ｜ ", element.select("th,td").eachText()) : element.text();
            addUnique(blocks, normalized(text, 600), element.tagName().matches("li|tr") ? 5 : 12);
        }

        var summaryParts = new ArrayList<String>();
        var description = metaContent(document, "meta[name=description],meta[property=og:description],meta[name=twitter:description]");
        addUnique(summaryParts, normalized(description, 500), 12);
        for (var paragraph : root.select("p")) {
            addUnique(summaryParts, normalized(paragraph.text(), 500), 50);
            if (summaryParts.size() >= 3) break;
        }
        var summary = String.join("\n", summaryParts);
        if (summary.isBlank()) summary = blocks.stream().limit(3).reduce((left, right) -> left + "\n" + right).orElse("");

        var highlights = blocks.stream()
                .filter(text -> informationScore(text) >= 2)
                .sorted(Comparator.comparingInt(WebPreviewService::informationScore).reversed())
                .map(WebPreviewService::cleanHighlight).distinct().limit(10).toList();
        if (highlights.isEmpty()) highlights = blocks.stream().limit(8).toList();

        return new ExtractedPage(summary, extractFacts(document, root, blocks), highlights,
                extractSections(root), extractLinks(root));
    }

    private static List<WorkspaceModels.WebPreviewFact> extractFacts(Document document, Element root,
                                                                       List<String> blocks) {
        var facts = new ArrayList<WorkspaceModels.WebPreviewFact>();
        addFact(facts, "发布时间", metaContent(document,
                "meta[property=article:published_time],meta[name=date],meta[itemprop=datePublished]"));
        addFact(facts, "更新时间", metaContent(document,
                "meta[property=article:modified_time],meta[itemprop=dateModified],meta[name=last-modified]"));

        for (var time : root.select("time")) {
            var value = normalized(firstNonBlank(time.text(), time.attr("datetime")), 220);
            addFact(facts, "时间", value);
            if (facts.size() >= 4) break;
        }
        for (var term : root.select("dl dt")) {
            var detail = term.nextElementSibling();
            if (detail != null && detail.tagName().equals("dd")) {
                addFact(facts, normalized(term.text(), 40), normalized(detail.text(), 260));
            }
            if (facts.size() >= 10) break;
        }

        var patterns = new LinkedHashMap<String, List<String>>();
        patterns.put("报名方式", List.of("报名", "申请", "注册", "预约", "registration", "register", "apply", "sign up"));
        patterns.put("截止时间", List.of("截止", "deadline", "closes", "closing date"));
        patterns.put("活动时间", List.of("活动时间", "演出时间", "举办时间", "日程", "schedule", "event date"));
        patterns.put("地点", List.of("地点", "地址", "会场", "venue", "location", "address"));
        patterns.put("费用", List.of("费用", "票价", "免费", "price", "fee", "ticket"));
        patterns.put("参与对象", List.of("参与对象", "适用对象", "资格", "eligibility", "who can"));
        patterns.put("主办方", List.of("主办", "承办", "organizer", "hosted by"));
        patterns.put("联系方式", List.of("联系方式", "联系电话", "邮箱", "contact", "email", "phone"));
        var labelCounts = new LinkedHashMap<String, Integer>();
        for (var block : blocks) {
            var lower = block.toLowerCase();
            for (var entry : patterns.entrySet()) {
                if (entry.getValue().stream().noneMatch(lower::contains)) continue;
                if (labelCounts.getOrDefault(entry.getKey(), 0) >= 2) continue;
                addFact(facts, entry.getKey(), normalized(block, 300));
                labelCounts.merge(entry.getKey(), 1, Integer::sum);
                break;
            }
            if (facts.size() >= 14) break;
        }
        return facts.stream().distinct().limit(14).toList();
    }

    private static List<WorkspaceModels.WebPreviewLink> extractLinks(Element root) {
        var candidates = new ArrayList<LinkCandidate>();
        for (var anchor : root.select("a[href]")) {
            var url = anchor.absUrl("href");
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
            var rawLabel = normalized(firstNonBlank(anchor.text(), anchor.attr("title")), 300);
            var label = cleanHighlight(rawLabel);
            if (label.length() < 2) continue;
            var score = linkScore(rawLabel + " " + url);
            if (score > 0) candidates.add(new LinkCandidate(label, url, score));
        }
        var seen = new LinkedHashSet<String>();
        return candidates.stream().sorted(Comparator.comparingInt(LinkCandidate::score).reversed())
                .filter(item -> seen.add(item.url())).limit(12)
                .map(item -> new WorkspaceModels.WebPreviewLink(item.label(), item.url())).toList();
    }

    private static List<WorkspaceModels.WebPreviewSection> extractSections(Element root) {
        var result = new ArrayList<WorkspaceModels.WebPreviewSection>();
        for (var heading : root.select("h1,h2,h3")) {
            var texts = new ArrayList<String>();
            var cursor = heading.nextElementSibling();
            while (cursor != null && !cursor.tagName().matches("h[1-3]") && texts.size() < 8) {
                if (cursor.tagName().matches("p|li|blockquote")) {
                    addUnique(texts, normalized(cursor.text(), 800), 18);
                } else if (cursor.tagName().equals("table")) {
                    for (var row : cursor.select("tr")) {
                        addUnique(texts, normalized(String.join(" ｜ ", row.select("th,td").eachText()), 800), 8);
                        if (texts.size() >= 8) break;
                    }
                } else {
                    for (var content : cursor.select("p,li,blockquote")) {
                        addUnique(texts, normalized(content.text(), 800), 18);
                        if (texts.size() >= 8) break;
                    }
                }
                cursor = cursor.nextElementSibling();
            }
            if (!heading.text().isBlank() && !texts.isEmpty()) {
                result.add(new WorkspaceModels.WebPreviewSection(normalized(heading.text(), 120), List.copyOf(texts)));
                if (result.size() == 12) break;
            }
        }
        return result;
    }

    private static int informationScore(String value) {
        var lower = value.toLowerCase();
        var score = value.matches(".*[0-9０-９].*") ? 2 : 0;
        for (var keyword : List.of("时间", "日期", "地点", "报名", "截止", "费用", "免费", "资格", "主办",
                "contact", "register", "apply", "deadline", "date", "location", "price", "fee")) {
            if (lower.contains(keyword)) score += 2;
        }
        if (value.length() >= 40 && value.length() <= 260) score++;
        return score;
    }

    private static int linkScore(String value) {
        var lower = value.toLowerCase();
        var score = 0;
        for (var keyword : List.of("报名", "申请", "注册", "预约", "购票", "register", "apply", "sign-up", "signup", "ticket")) {
            if (lower.contains(keyword)) score += 5;
        }
        if (lower.contains("立即购票")) score += 5;
        if (lower.contains("/performance/show_")) score += 3;
        for (var keyword : List.of("日程", "详情", "指南", "通知", "下载", "schedule", "detail", "guide", "download", ".pdf", ".doc")) {
            if (lower.contains(keyword)) score += 2;
        }
        return score;
    }

    private static String metaContent(Document document, String selector) {
        var element = document.selectFirst(selector);
        return element == null ? "" : firstNonBlank(element.attr("content"), element.attr("datetime"), element.text());
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String normalized(String value, int maxLength) {
        var text = Objects.toString(value, "").replaceAll("\\s+", " ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1).trim() + "…";
    }

    private static String cleanHighlight(String value) {
        var matcher = java.util.regex.Pattern.compile(
                "^(.{4,160}?)演出时间[:：]\\s*(\\d{4}[.\\-/年]\\d{1,2}[.\\-/月]\\d{1,2}日?[^\\d]{0,10}\\d{1,2}:\\d{2})")
                .matcher(value);
        return matcher.find() ? normalized(matcher.group(1) + " 演出时间：" + matcher.group(2), 300) : value;
    }

    private static void addUnique(List<String> values, String value, int minimumLength) {
        if (value.length() >= minimumLength && !values.contains(value)) values.add(value);
    }

    private static void addFact(List<WorkspaceModels.WebPreviewFact> facts, String label, String value) {
        var cleanLabel = normalized(label, 40);
        var cleanValue = normalized(value, 300);
        if (cleanLabel.equals("活动时间")) cleanValue = cleanHighlight(cleanValue);
        if (cleanLabel.isBlank() || cleanValue.isBlank()) return;
        var fact = new WorkspaceModels.WebPreviewFact(cleanLabel, cleanValue);
        if (!facts.contains(fact)) facts.add(fact);
    }

    private static <T> List<T> merge(List<T> preferred, List<T> extracted, int limit) {
        var merged = new LinkedHashSet<T>();
        merged.addAll(preferred);
        merged.addAll(extracted);
        return merged.stream().limit(limit).toList();
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

    record ExtractedPage(String summary, List<WorkspaceModels.WebPreviewFact> facts,
                         List<String> highlights, List<WorkspaceModels.WebPreviewSection> sections,
                         List<WorkspaceModels.WebPreviewLink> links) { }

    private record LinkCandidate(String label, String url, int score) { }
}

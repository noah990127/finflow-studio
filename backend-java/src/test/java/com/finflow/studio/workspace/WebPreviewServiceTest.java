package com.finflow.studio.workspace;

import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPreviewServiceTest {
    @Test
    void recognizesCommonFrameBlockingHeaders() {
        assertTrue(WebPreviewService.blocksEmbedding("DENY", "", "https://source.example/page", "http://127.0.0.1:5174"));
        assertTrue(WebPreviewService.blocksEmbedding("SAMEORIGIN", "", "https://source.example/page", "http://127.0.0.1:5174"));
        assertTrue(WebPreviewService.blocksEmbedding("", "default-src 'self'; frame-ancestors 'none'", "https://source.example/page", "http://127.0.0.1:5174"));
        assertTrue(WebPreviewService.blocksEmbedding("", "frame-ancestors 'self' https://trusted.example", "https://source.example/page", "http://127.0.0.1:5174"));
    }

    @Test
    void permitsPagesWithoutRestrictionsOrWithMatchingOrigin() {
        assertFalse(WebPreviewService.blocksEmbedding("", "default-src 'self'", "https://source.example/page", "http://127.0.0.1:5174"));
        assertFalse(WebPreviewService.blocksEmbedding("SAMEORIGIN", "", "http://127.0.0.1:5174/page", "http://127.0.0.1:5174"));
        assertFalse(WebPreviewService.blocksEmbedding("", "frame-ancestors http://127.0.0.1:5174", "https://source.example/page", "http://127.0.0.1:5174"));
    }

    @Test
    void quickReaderKeepsOperationalFactsTablesAndActionLinks() {
        var document = Jsoup.parse("""
                <html><head><title>深圳文化活动</title>
                <meta name="description" content="深圳秋季文化活动报名指南，汇总时间、地点和报名方式。">
                <meta property="article:published_time" content="2026-09-17">
                </head><body><main>
                <h1>城市艺术周</h1>
                <p>本届城市艺术周将在多个公共文化场馆举行，覆盖展览、讲座和亲子工作坊。</p>
                <ul><li>活动时间：2026年9月20日至10月8日</li><li>地点：深圳市当代艺术馆</li>
                <li>报名方式：9月18日18:00前在线预约，公益场次免费。</li></ul>
                <table><tr><th>场次</th><th>余量</th></tr><tr><td>主题讲座</td><td>80</td></tr></table>
                <a href="/events/register">立即报名</a><a href="/files/guide.pdf">下载活动指南</a>
                </main></body></html>
                """, "https://culture.example/");

        var page = WebPreviewService.extractReadablePage(document, document.selectFirst("main"));

        assertTrue(page.summary().contains("报名指南"));
        assertTrue(page.facts().stream().anyMatch(item -> item.label().equals("发布时间") && item.value().contains("2026-09-17")));
        assertTrue(page.facts().stream().anyMatch(item -> item.label().equals("活动时间") && item.value().contains("9月20日")));
        assertTrue(page.facts().stream().anyMatch(item -> item.label().equals("地点") && item.value().contains("当代艺术馆")));
        assertTrue(page.highlights().stream().anyMatch(item -> item.contains("在线预约")));
        assertTrue(page.sections().stream().flatMap(item -> item.paragraphs().stream()).anyMatch(item -> item.contains("主题讲座") && item.contains("80")));
        assertTrue(page.links().stream().anyMatch(item -> item.label().contains("报名") && item.url().equals("https://culture.example/events/register")));
        assertTrue(page.links().stream().anyMatch(item -> item.url().endsWith("guide.pdf")));
    }
}

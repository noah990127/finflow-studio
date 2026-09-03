package com.finflow.studio.workspace;

import org.junit.jupiter.api.Test;

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
}

package com.finflow.studio.knowledge;

import com.finflow.studio.deliverable.DeliverableService;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Locale;

@RestController
@RequestMapping("/api/native-documents")
public class NativeDocumentController {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final KnowledgeService files;
    private final DeliverableService deliverables;
    public NativeDocumentController(KnowledgeService files, DeliverableService deliverables) {
        this.files = files; this.deliverables = deliverables;
    }
    public record Document(String id, String name, String format, int version, String content, boolean editable) {}
    public record Save(String content, int expectedVersion) {}

    private String format(String name, String mediaType) {
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || "markdown".equalsIgnoreCase(mediaType)
                || "text/markdown".equalsIgnoreCase(mediaType)) return "markdown";
        throw new IllegalArgumentException("该文件尚不支持原生文档编辑");
    }
    @GetMapping("/{kind}/{id}")
    public Document readVersion(@PathVariable String kind, @PathVariable String id, @RequestParam(required = false) Integer requestedVersion) throws IOException {
        String name, type; int version; java.nio.file.Path path;
        if ("files".equals(kind)) {
            var file = files.get(id); name = file.name(); type = file.mediaType(); version = file.currentVersion();
            format(name, type); if (requestedVersion != null) { if (requestedVersion < 1 || requestedVersion > version) throw new IllegalArgumentException("版本不存在"); version = requestedVersion; } path = files.filePath(id, version);
        } else if ("deliverables".equals(kind)) {
            var file = deliverables.get(id); name = file.name(); type = file.format(); version = file.currentVersion();
            format(name, type); if (requestedVersion != null) { if (requestedVersion < 1 || requestedVersion > version) throw new IllegalArgumentException("版本不存在"); version = requestedVersion; } path = deliverables.path(id, version);
        } else throw new IllegalArgumentException("不支持的文档来源");
        if (Files.size(path) > MAX_BYTES) throw new IllegalArgumentException("文档超过2MB，请下载后编辑；不会截断原文");
        return new Document(id, name, format(name, type), version, Files.readString(path, StandardCharsets.UTF_8), requestedVersion == null);
    }
    public Document read(String kind, String id) throws IOException { return readVersion(kind, id, null); }
    @PutMapping("/{kind}/{id}")
    public Document save(@PathVariable String kind, @PathVariable String id, @RequestBody Save request) throws IOException {
        var current = read(kind, id);
        if (request.content() == null || request.content().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("文档内容必须在2MB以内");
        if (current.version() != request.expectedVersion()) throw new IllegalStateException("文档已有新版本，你的草稿已保留，请先查看最新版本");
        if (current.content().equals(request.content())) return current;
        var bytes = request.content().getBytes(StandardCharsets.UTF_8);
        int version;
        if ("files".equals(kind)) version = files.createTextVersion(id, request.expectedVersion(), bytes).currentVersion();
        else version = deliverables.createEditedVersion(id, request.expectedVersion(), bytes).currentVersion();
        return new Document(id, current.name(), current.format(), version, request.content(), true);
    }
}

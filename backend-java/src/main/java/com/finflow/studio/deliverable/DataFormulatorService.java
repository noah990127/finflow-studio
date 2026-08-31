package com.finflow.studio.deliverable;

import com.finflow.studio.data.ExtractJobService;
import com.finflow.studio.knowledge.KnowledgeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DataFormulatorService {
    private final DeliverableService deliverables;
    private final KnowledgeService knowledge;
    private final ExtractJobService extracts;
    private final DataFormulatorClient client;
    private final String publicUrl;

    public DataFormulatorService(DeliverableService deliverables, KnowledgeService knowledge,
                                 ExtractJobService extracts, DataFormulatorClient client,
                                 @Value("${finflow.data-formulator.url:http://127.0.0.1:5567}") String publicUrl) {
        this.deliverables = deliverables;
        this.knowledge = knowledge;
        this.extracts = extracts;
        this.client = client;
        this.publicUrl = publicUrl.replaceAll("/$", "");
    }

    public Map<String, Object> sync(String deliverableId) {
        var deliverable = deliverables.get(deliverableId);
        if (!"financial_report".equals(deliverable.format())) {
            throw new IllegalArgumentException("当前输出件不是财务报告");
        }
        if (!client.online()) {
            throw new IllegalStateException("Data Formulator 尚未启动，财务报告不能降级为纯文本");
        }

        var workspaceId = "finflow_" + deliverable.id().replace("-", "");
        client.ensureWorkspace(workspaceId, deliverable.name());
        var imported = new ArrayList<Map<String, Object>>();
        for (var file : knowledge.list(deliverable.projectId())) {
            if (!"READY".equals(file.status()) || !supported(file.name())) continue;
            importOne(workspaceId, imported, "file_" + shortId(file.id()) + "_" + baseName(file.name()),
                    knowledge.filePath(file.id(), file.currentVersion()), file.name(), "文件");
        }
        for (var job : extracts.list(deliverable.projectId())) {
            if (!"SUCCEEDED".equals(job.status()) || !supported(job.outputName())) continue;
            importOne(workspaceId, imported, "data_" + shortId(job.id()) + "_" + baseName(job.outputName()),
                    extracts.outputPath(job.id()), job.outputName(), "采集结果");
        }
        if (imported.isEmpty()) {
            throw new IllegalStateException("项目中没有可导入 Data Formulator 的 CSV、Excel、TSV 或 JSON 数据");
        }
        client.syncWorkspaceState(workspaceId, deliverable.name());

        var result = new LinkedHashMap<String, Object>();
        result.put("status", "READY");
        result.put("engine", "Microsoft Data Formulator 0.7");
        result.put("url", publicUrl + "/?workspace=" + workspaceId);
        result.put("workspaceId", workspaceId);
        result.put("tables", imported);
        result.put("message", "数据已真实载入 Data Formulator 工作区，可直接制作图表和交互报告");
        return result;
    }

    private void importOne(String workspaceId, List<Map<String, Object>> imported, String tableName, Path path,
                           String originalName, String sourceType) {
        var result = client.importFile(workspaceId, tableName, path, originalName);
        imported.add(Map.of(
                "name", result.getOrDefault("table_name", tableName),
                "rowCount", result.getOrDefault("row_count", 0),
                "source", originalName,
                "sourceType", sourceType));
    }

    private boolean supported(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return List.of(".csv", ".tsv", ".xlsx", ".xls", ".json").stream().anyMatch(lower::endsWith);
    }

    private String baseName(String name) {
        var dot = name.lastIndexOf('.');
        var value = dot > 0 ? name.substring(0, dot) : name;
        value = value.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]", "_");
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private String shortId(String id) { return id.substring(0, Math.min(8, id.length())).replace("-", ""); }
}

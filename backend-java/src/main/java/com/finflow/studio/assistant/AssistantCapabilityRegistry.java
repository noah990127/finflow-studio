package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.RiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

public final class AssistantCapabilityRegistry {
    private static final Map<String, Capability> CAPABILITIES = new LinkedHashMap<>();

    static {
        register("workspace.inspect", "workspace", "查看当前工作", "读取项目、页面、所选内容和可用资源", "READ", RiskLevel.READ_ONLY, "never",
                List.of("project_id", "resource_id", "page", "goal"));
        register("workspace.navigate", "workspace", "打开工作区域", "打开项目概览、数据、工作流或当前内容", "READ", RiskLevel.READ_ONLY, "never",
                List.of("target", "project_id", "workflow_id", "workflow_name", "resource_id", "goal"));
        register("workspace.select", "workspace", "选择工作区对象", "在界面中选择项目、文件、数据集、工作流、输出件或面板", "READ", RiskLevel.READ_ONLY, "never",
                List.of("target_id", "target_type", "panel"));
        register("assistant.respond", "assistant", "直接回答", "结合当前上下文回答问题或说明缺少的信息", "READ", RiskLevel.READ_ONLY, "never",
                List.of("goal", "reason"));
        register("assistant.analyze_context", "assistant", "分析当前内容", "分析项目已有资料或当前选中的内容", "READ", RiskLevel.READ_ONLY, "never",
                List.of("project_id", "resource_id", "resource_name", "goal"));
        register("project.list", "project", "查看项目", "列出当前用户可访问的项目", "READ", RiskLevel.READ_ONLY, "never",
                List.of("query"));
        register("project.open", "project", "打开项目", "在左侧工作区打开指定项目", "READ", RiskLevel.READ_ONLY, "never",
                List.of("project_id"));
        register("project.create_workspace", "project", "创建项目", "创建新的个人项目空间", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_name", "topic", "description"));
        register("project.rename", "project", "重命名项目", "修改项目名称", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "new_name"));
        register("project.delete", "project", "删除项目", "删除项目及其工作区内容", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("project_id"));
        register("folder.create", "folder", "创建文件夹", "在工作区中创建资料、数据或输出文件夹", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "parent_id", "name", "group"));
        register("folder.rename", "folder", "重命名文件夹", "修改工作区文件夹名称", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("folder_id", "new_name"));
        register("folder.move", "folder", "移动文件夹", "将文件夹移动到另一个工作区位置", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("folder_id", "target_parent_id"));
        register("folder.delete", "folder", "删除文件夹", "删除文件夹或将其移入回收流程", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("folder_id"));
        register("resource.upload", "resource", "上传文件", "向当前项目添加用户提供的文件", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "folder_id", "file_name", "resource_type"));
        register("resource.add", "resource", "添加资源", "添加网页、外部链接或已有对象到工作区", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "name", "resource_type", "url"));
        register("resource.open", "resource", "打开资源", "在工作区打开文件、数据集、知识或输出件", "READ", RiskLevel.READ_ONLY, "never",
                List.of("resource_id"));
        register("resource.read", "resource", "读取资源", "读取资源元数据和可用预览内容", "READ", RiskLevel.READ_ONLY, "never",
                List.of("resource_id", "range", "goal"));
        register("resource.edit", "resource", "编辑资源", "编辑文件或资源内容，保留版本与审计", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("resource_id", "patch", "expected_version"));
        register("resource.rename", "resource", "重命名资源", "修改文件、数据集或输出件名称", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("resource_id", "new_name"));
        register("resource.move", "resource", "移动资源", "将资源移动到指定文件夹或分组", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("resource_id", "target_folder_id", "target_group"));
        register("resource.delete", "resource", "删除资源", "删除文件、数据集、知识或输出件", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("resource_id"));
        register("knowledge.discover_external_sources", "knowledge", "查找资料", "搜索公开资料入口并保留来源", "READ", RiskLevel.READ_ONLY, "never",
                List.of("topic", "max_sources"));
        register("knowledge.add", "knowledge", "添加知识", "把文件、网页或文本加入项目知识库", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "resource_id", "text", "source"));
        register("knowledge.search", "knowledge", "搜索知识", "搜索项目知识库并返回可引用证据", "READ", RiskLevel.READ_ONLY, "never",
                List.of("project_id", "query", "resource_ids", "limit"));
        register("knowledge.read", "knowledge", "读取知识片段", "读取知识库条目、原文摘录和定位信息", "READ", RiskLevel.READ_ONLY, "never",
                List.of("citation_id", "resource_id", "range"));
        register("knowledge.parse", "knowledge", "解析知识文件", "从文件中解析文本、章节和可检索片段", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("resource_id", "parser_options"));
        register("knowledge.extract_table", "knowledge", "抽取文档表格", "从 PDF、Word、PPT 或网页中提取结构化表格并保留定位", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("resource_id", "pages", "table_hint", "target_dataset_name"));
        register("dataset.add_source", "dataset", "添加数据源", "登记数据库、API 或文件数据入口", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "source_type", "name", "connection"));
        register("dataset.connect", "dataset", "连接数据源", "测试并保存数据库或 API 数据源连接", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("project_id", "source_id", "credentials_ref"));
        register("dataset.import", "dataset", "导入数据", "从文件、数据库或 API 导入数据集", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("source_id", "query", "target_name"));
        register("dataset.query", "dataset", "查询数据", "使用只读 SQL 或分析请求查询数据集", "READ", RiskLevel.READ_ONLY, "never",
                List.of("dataset_id", "sql", "analysis_request"));
        register("dataset.extract", "dataset", "抽取数据", "从资源或知识表格中抽取数据集", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("resource_id", "schema", "target_name"));
        register("dataset.create", "dataset", "创建数据集", "创建新的结构化数据集", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "name", "schema", "rows"));
        register("dataset.transform", "dataset", "转换数据", "生成并执行透明可复核的数据加工", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("dataset_id", "requirements", "script", "target_name"));
        register("dataset.open", "dataset", "打开数据集", "在数据面板打开指定数据集", "READ", RiskLevel.READ_ONLY, "never",
                List.of("dataset_id"));
        register("dataset.delete", "dataset", "删除数据集", "删除数据集或导入结果", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("dataset_id"));
        register("workflow.initialize", "workflow", "建立项目工作流", "为新项目建立首条工作流", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("topic", "goal", "include_analysis", "output_formats"));
        register("workflow.prepare", "workflow", "创建工作流", "在当前项目中新建可编排的工作流", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "goal", "resource_id", "resource_type", "resource_name", "output_formats"));
        register("workflow.open", "workflow", "打开工作流", "在画布中打开指定工作流", "READ", RiskLevel.READ_ONLY, "never",
                List.of("project_id", "workflow_id", "workflow_name"));
        register("workflow.edit", "workflow", "编辑工作流", "修改工作流名称、描述、节点或配置", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("workflow_id", "patch", "expected_version"));
        register("workflow.add_selected_resource", "workflow", "把内容加入工作流", "将当前选择的资源添加为工作流输入", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "resource_id", "resource_type", "resource_name"));
        register("workflow.add_node", "workflow", "添加工作流节点", "向工作流添加输入、加工、分析、确认或输出节点", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("workflow_id", "node_type", "name", "config", "position"));
        register("workflow.remove_node", "workflow", "移除工作流节点", "从工作流删除指定节点并检查连线影响", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("workflow_id", "node_id", "expected_version"));
        register("workflow.connect", "workflow", "连接工作流节点", "在两个工作流节点之间建立连线", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("workflow_id", "source_node_id", "target_node_id"));
        register("workflow.run", "workflow", "运行工作流", "执行已保存工作流并返回运行产物", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("workflow_id", "parameters"));
        register("workflow.save_version", "workflow", "保存工作流版本", "为当前工作流保存可审计版本", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("workflow_id", "message"));
        register("workflow.add_data_transform", "workflow", "增加数据加工", "加入透明、可查看和可复核的数据加工草稿", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "resource_id", "resource_type", "resource_name", "goal"));
        register("workflow.add_outputs", "workflow", "增加输出成果", "按用户明确指定的格式添加输出节点", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "goal", "output_formats", "resource_id", "resource_type", "resource_name"));
        register("dataset.profile", "dataset", "检查数据", "读取字段、规模和数据质量概况", "READ", RiskLevel.READ_ONLY, "never",
                List.of("resource_id", "goal"));
        register("deliverable.create", "deliverable", "创建交付件", "创建 PPT、Word、PDF、Mermaid、HTML 或交互报告", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("project_id", "format", "title", "content", "citations"));
        register("deliverable.open", "deliverable", "打开交付件", "在输出面板打开交付件", "READ", RiskLevel.READ_ONLY, "never",
                List.of("deliverable_id"));
        register("deliverable.edit", "deliverable", "编辑交付件", "修改已有输出件内容并保存版本", "WRITE", RiskLevel.CREATE_VERSION, "always",
                List.of("deliverable_id", "patch", "expected_version"));
        register("deliverable.export", "deliverable", "导出交付件", "导出用户可下载或分享的文件", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("deliverable_id", "format", "destination"));
        register("deliverable.delete", "deliverable", "删除交付件", "删除输出件或导出文件", "WRITE", RiskLevel.DESTRUCTIVE_OR_EXTERNAL, "always",
                List.of("deliverable_id"));
    }

    private AssistantCapabilityRegistry() { }

    private static void register(String id, String category, String title, String description, String mode,
                                 RiskLevel risk, String confirmationRequirement, List<String> arguments) {
        CAPABILITIES.put(id, new Capability(id, category, title, description, mode, risk,
                risk.name().toLowerCase(Locale.ROOT), confirmationRequirement, arguments,
                Map.of("type", "object", "required", arguments), Map.of("type", "object")));
    }

    public static Optional<Capability> find(String id) { return Optional.ofNullable(CAPABILITIES.get(id)); }

    public static List<Map<String, Object>> search(String query) {
        var normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return CAPABILITIES.values().stream()
                .filter(capability -> normalized.isBlank()
                        || capability.id().toLowerCase(Locale.ROOT).contains(normalized)
                        || capability.category().toLowerCase(Locale.ROOT).contains(normalized)
                        || capability.title().toLowerCase(Locale.ROOT).contains(normalized)
                        || capability.description().toLowerCase(Locale.ROOT).contains(normalized)
                        || capability.arguments().stream().anyMatch(arg -> arg.toLowerCase(Locale.ROOT).contains(normalized)))
                .limit(30)
                .map(AssistantCapabilityRegistry::toMap)
                .toList();
    }

    public static List<Map<String, Object>> catalog() {
        return CAPABILITIES.values().stream().map(AssistantCapabilityRegistry::toMap).toList();
    }

    private static Map<String, Object> toMap(Capability capability) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", capability.id());
        value.put("category", capability.category());
        value.put("title", capability.title());
        value.put("description", capability.description());
        value.put("mode", capability.mode());
        value.put("risk", capability.risk().name());
        value.put("riskLabel", capability.riskLabel());
        value.put("requiresConfirmation", capability.risk().requiresConfirmation());
        value.put("confirmationRequirement", capability.confirmationRequirement());
        value.put("arguments", capability.arguments());
        value.put("inputSchema", capability.inputSchema());
        value.put("outputSchema", capability.outputSchema());
        return value;
    }

    public record Capability(String id, String category, String title, String description, String mode, RiskLevel risk,
                             String riskLabel, String confirmationRequirement, List<String> arguments,
                             Map<String, Object> inputSchema, Map<String, Object> outputSchema) { }
}

package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.RiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AssistantCapabilityRegistry {
    private static final Map<String, Capability> CAPABILITIES = new LinkedHashMap<>();

    static {
        register("workspace.inspect", "查看当前工作", "读取项目、页面、所选内容和可用资源", "READ", RiskLevel.READ_ONLY,
                List.of("project_id", "resource_id", "page", "goal"));
        register("workspace.navigate", "打开工作区域", "打开项目概览、数据、工作流或当前内容", "READ", RiskLevel.READ_ONLY,
                List.of("target", "resource_id"));
        register("assistant.respond", "直接回答", "结合当前上下文回答问题或说明缺少的信息", "READ", RiskLevel.READ_ONLY,
                List.of("goal", "reason"));
        register("assistant.analyze_context", "分析当前内容", "分析项目已有资料或当前选中的内容", "READ", RiskLevel.READ_ONLY,
                List.of("project_id", "resource_id", "resource_name", "goal"));
        register("project.create_workspace", "创建项目", "创建新的个人项目空间", "WRITE", RiskLevel.CREATE_VERSION,
                List.of("project_name", "topic", "description"));
        register("knowledge.discover_external_sources", "查找资料", "搜索公开资料入口并保留来源", "READ", RiskLevel.READ_ONLY,
                List.of("topic", "max_sources"));
        register("workflow.initialize", "建立项目工作流", "为新项目建立首条工作流", "WRITE", RiskLevel.CREATE_VERSION,
                List.of("topic", "goal", "include_analysis", "output_formats"));
        register("workflow.prepare", "创建工作流", "在当前项目中新建可编排的工作流", "WRITE", RiskLevel.CREATE_VERSION,
                List.of("project_id", "goal", "resource_id", "resource_type", "resource_name", "output_formats"));
        register("workflow.add_selected_resource", "把内容加入工作流", "将当前选择的资源添加为工作流输入", "WRITE", RiskLevel.CREATE_VERSION,
                List.of("project_id", "resource_id", "resource_type", "resource_name"));
        register("workflow.add_data_transform", "增加数据加工", "加入透明、可查看和可复核的数据加工草稿", "WRITE", RiskLevel.CREATE_VERSION,
                List.of("project_id", "resource_id", "resource_type", "resource_name", "goal"));
        register("workflow.add_outputs", "增加输出成果", "按用户明确指定的格式添加输出节点", "WRITE", RiskLevel.CREATE_VERSION,
                List.of("project_id", "goal", "output_formats", "resource_id", "resource_type", "resource_name"));
        register("dataset.profile", "检查数据", "读取字段、规模和数据质量概况", "READ", RiskLevel.READ_ONLY,
                List.of("resource_id", "goal"));
    }

    private AssistantCapabilityRegistry() { }

    private static void register(String id, String title, String description, String mode, RiskLevel risk, List<String> arguments) {
        CAPABILITIES.put(id, new Capability(id, title, description, mode, risk, arguments));
    }

    public static Optional<Capability> find(String id) { return Optional.ofNullable(CAPABILITIES.get(id)); }

    public static List<Map<String, Object>> catalog() {
        return CAPABILITIES.values().stream().map(capability -> Map.<String, Object>of(
                "id", capability.id(), "title", capability.title(), "description", capability.description(),
                "mode", capability.mode(), "risk", capability.risk().name(), "arguments", capability.arguments())).toList();
    }

    public record Capability(String id, String title, String description, String mode, RiskLevel risk, List<String> arguments) { }
}

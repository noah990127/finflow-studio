package com.finflow.studio.workflow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow-node-types")
public class WorkflowNodeRegistryController {

    @GetMapping("/variables")
    public Map<String, List<WorkflowVariables.Field>> variables() {
        var result = new java.util.LinkedHashMap<String, List<WorkflowVariables.Field>>();
        for (var type : WorkflowModels.NodeType.values()) result.put(type.name(), WorkflowVariables.outputs(type));
        return result;
    }

    @GetMapping
    public List<NodeTypeDescriptor> list() {
        return List.of(
                node("RESOURCE", "项目内容", "把项目中的数据或资料带入工作流", "studio", "READ_ONLY", List.of(), List.of("resource")),
                node("ACQUIRE", "获取内容", "从数据库、数据服务或网页获取内容", "studio", "READ_ONLY", List.of(), List.of("resource")),
                node("PROCESS", "加工数据", "根据要求生成透明脚本并加工结构化数据", "worker", "WRITE_PROJECT", List.of("resource"), List.of("dataset")),
                node("AGENT_TASK", "开放任务", "由 Agent 组合资料、数据和工具完成难以预先枚举的任务", "deep-agents", "POLICY_CONTROLLED", List.of("resource", "dataset"), List.of("analysis", "source_snapshot")),
                node("TOOL", "使用工具", "调用已接入的 Skill 或工具能力", "studio-mcp", "POLICY_CONTROLLED", List.of("any"), List.of("any")),
                node("CONTROL", "流程控制", "按条件组织执行链路", "studio", "WRITE_PROJECT", List.of("any"), List.of("any")),
                node("SUB_WORKFLOW", "运行工作流", "复用当前项目中的另一条工作流", "studio", "WRITE_PROJECT", List.of("any"), List.of("run")),
                node("OUTPUT", "生成成果", "生成报告、演示文稿、文档或图形", "worker", "WRITE_PROJECT", List.of("analysis", "dataset"), List.of("deliverable"))
        );
    }

    private NodeTypeDescriptor node(String type, String title, String description, String executor,
                                    String risk, List<String> inputs, List<String> outputs) {
        return new NodeTypeDescriptor(type, title, description, executor, risk, inputs, outputs,
                Map.of("type", "object", "additionalProperties", true));
    }

    public record NodeTypeDescriptor(String type, String title, String description, String executor,
                                     String risk, List<String> accepts, List<String> produces,
                                     Map<String, Object> configSchema) { }
}

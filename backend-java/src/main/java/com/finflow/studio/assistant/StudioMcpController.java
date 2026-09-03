package com.finflow.studio.assistant;

import com.finflow.studio.project.ProjectService;
import com.finflow.studio.workflow.WorkflowDefinitionService;
import com.finflow.studio.workflow.WorkflowRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/mcp")
public class StudioMcpController {
    private final ProjectService projects;
    private final WorkflowDefinitionService workflows;
    private final WorkflowRunService runs;
    private final AgentMemoryService memory;

    public StudioMcpController(ProjectService projects, WorkflowDefinitionService workflows,
                               WorkflowRunService runs, AgentMemoryService memory) {
        this.projects = projects;
        this.workflows = workflows;
        this.runs = runs;
        this.memory = memory;
    }

    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return List.of(
                tool("project.list", "查看项目", "READ_ONLY", List.of()),
                tool("workflow.list", "查看项目工作流", "READ_ONLY", List.of("projectId")),
                tool("workflow.get", "读取工作流", "READ_ONLY", List.of("workflowId")),
                tool("run.get_status", "查看运行状态", "READ_ONLY", List.of("runId")),
                tool("memory.search", "读取项目和个人偏好", "READ_ONLY", List.of("projectId")),
                tool("workflow.propose_patch", "提出工作流修改建议", "DRAFT_ONLY", List.of("workflowId", "patch")),
                tool("workflow.run", "运行工作流", "CREATE_VERSION", List.of("workflowId")),
                tool("run.cancel", "停止运行", "DESTRUCTIVE_OR_EXTERNAL", List.of("runId"))
        );
    }

    @PostMapping("/call")
    public ToolResult call(@RequestBody ToolCall request) {
        var definition = tools().stream().filter(item -> item.name().equals(request.name())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP 工具不存在"));
        if (!"READ_ONLY".equals(definition.risk()) && (request.confirmationToken() == null || request.confirmationToken().isBlank())) {
            return new ToolResult(false, Map.of(), "该操作需要用户确认", true, definition.risk());
        }
        var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
        Object output = switch (request.name()) {
            case "project.list" -> projects.list();
            case "workflow.list" -> workflows.list(required(args, "projectId"));
            case "workflow.get" -> workflows.get(required(args, "workflowId"));
            case "run.get_status" -> runs.get(required(args, "runId"));
            case "memory.search" -> memory.list(Objects.toString(args.get("projectId"), ""));
            case "workflow.run" -> runs.start(required(args, "workflowId"));
            case "run.cancel" -> runs.cancel(required(args, "runId"));
            case "workflow.propose_patch" -> Map.of("accepted", true, "message", "建议已登记，应用前仍需进行版本与结构检查");
            default -> throw new IllegalArgumentException("MCP 工具尚未实现");
        };
        return new ToolResult(true, Map.of("result", output), "", false, definition.risk());
    }

    private ToolDefinition tool(String name, String title, String risk, List<String> arguments) {
        return new ToolDefinition(name, title, risk, arguments);
    }
    private String required(Map<String, Object> args, String key) {
        var value = Objects.toString(args.get(key), "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("缺少参数：" + key);
        return value;
    }

    public record ToolDefinition(String name, String title, String risk, List<String> arguments) { }
    public record ToolCall(String name, Map<String, Object> arguments, String confirmationToken, String idempotencyKey) { }
    public record ToolResult(boolean success, Map<String, Object> content, String error,
                             boolean confirmationRequired, String risk) { }
}

package com.finflow.studio.assistant;

import com.finflow.studio.knowledge.tacit.TacitWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
final class TacitAgentTools {
    static final Set<String> TOOLS = Set.of("tacit.workspace", "tacit.preview_source", "tacit.extract",
            "tacit.save_batch", "tacit.finalize", "tacit.generate_flow", "tacit.build_candidate",
            "tacit.generate_cqs", "tacit.validate_ontology");
    static final List<String> SECTIONS = List.of("scope", "sources", "data_objects", "data_tables", "rules",
            "processes", "gaps", "acceptance_cases");
    static final List<String> ARTIFACTS = List.of("business_report", "gap_report", "flows", "candidates",
            "cqs", "walkthrough");

    private final TacitWorkspaceService workspaces;
    TacitAgentTools(TacitWorkspaceService workspaces) { this.workspaces = workspaces; }

    Map<String, Object> execute(AssistantModels.PlanStep step, String projectId, TacitAgentTask stage) {
        AssistantToolContracts.validate(step.tool(), step.arguments());
        if (!stage.permits(step.tool())) throw new IllegalArgumentException("当前消息没有授权这个知识整理阶段");
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("请先打开业务资料所在的项目");
        var args = new LinkedHashMap<>(step.arguments());
        if ("tacit.workspace".equals(step.tool())) {
            int offset = ((Number) args.getOrDefault("offset", 0)).intValue();
            var workspace = workspaces.workspace(projectId);
            var section = Objects.toString(args.get("section"), "");
            if (SECTIONS.contains(section)) {
                long revision = ((Number) workspace.get("revision")).longValue();
                var reply = workspaces.executeAgent(projectId, stage.task(), "read_section", revision, step.id(),
                        Map.of("section", section, "offset", offset));
                return Map.of("revision", revision, "section", section, "result", reply.get("result"));
            }
            return project(workspace, section, offset);
        }
        var expected = ((Number) args.remove("expected_revision")).longValue();
        var action = step.tool().substring("tacit.".length());
        var reply = workspaces.executeAgent(projectId, stage.task(), action, expected, step.id(), args);
        var workspace = reply.get("workspace") instanceof Map<?, ?> state ? state : Map.of();
        var result = new LinkedHashMap<String, Object>();
        result.put("revision", workspace.get("revision"));
        result.put("result", reply.get("result"));
        if (workspace.get("issues") instanceof List<?> issues) {
            result.put("issue_count", issues.size());
            result.put("issues", issues.subList(0, Math.min(5, issues.size())));
        }
        return result;
    }

    String verifiedOutcome(TacitAgentTask stage, String projectId, List<AssistantModels.PlanStep> steps) {
        record Outcome(String tool, String artifact, String name) {}
        var expected = switch (stage) {
            case ORGANIZE, CLARIFY -> new Outcome("tacit.finalize", "business_report", "业务梳理报告和缺口报告");
            case FLOW -> new Outcome("tacit.generate_flow", "flows", "业务流程图");
            case BUILD -> new Outcome("tacit.build_candidate", "candidates", "本体候选");
            case CQ -> new Outcome("tacit.generate_cqs", "cqs", "业务能力问题");
            case VERIFY -> new Outcome("tacit.validate_ontology", "walkthrough", "建模校核记录");
            default -> throw new IllegalArgumentException("当前为只读阶段");
        };
        if (steps.stream().noneMatch(s -> expected.tool().equals(s.tool()) && "SUCCEEDED".equals(s.status())))
            throw new IllegalStateException("本轮尚未生成" + expected.name() + "。请在项目知识页核对资料、范围和校验问题。");
        var workspace = workspaces.workspace(projectId);
        var artifacts = workspace.get("artifacts") instanceof Map<?, ?> map ? map : Map.of();
        var item = artifacts.get(expected.artifact()) instanceof Map<?, ?> map ? map : Map.of();
        var status = switch (Objects.toString(item.get("status"), "")) {
            case "draft" -> "待复核草稿";
            case "pending" -> "待人工审核";
            case "ready" -> "已生成，可查看";
            case "approved" -> "已审核";
            default -> throw new IllegalStateException(expected.name() + "已过期或退回，请核对最新资料后重做本阶段。");
        };
        int issues = workspace.get("issues") instanceof List<?> list ? list.size() : 0;
        return expected.name() + "已保存至项目知识页（台账版本 " + workspace.get("revision") + "），状态为"
                + status + "。当前台账有 " + issues + " 项校验问题。";
    }

    /** Page whole CQ/ledger records; the agent needs their sources, semantics and review state together. */
    static Map<String, Object> project(Map<String, Object> workspace, String section, int offset) {
        var spec = workspace.get("spec") instanceof Map<?, ?> data ? data : Map.of();
        var artifacts = workspace.get("artifacts") instanceof Map<?, ?> data ? data : Map.of();
        var out = new LinkedHashMap<String, Object>();
        out.put("revision", workspace.get("revision"));
        out.put("completion", spec.get("completion"));
        if (section.isEmpty()) {
            out.put("selected_sources", workspace.get("source_ids"));
            out.put("extraction", workspace.get("extraction"));
            out.put("issue_count", workspace.get("issues") instanceof List<?> list ? list.size() : 0);
            var counts = new LinkedHashMap<String, Integer>();
            for (var key : SECTIONS) if (spec.get(key) instanceof List<?> list) counts.put(key, list.size());
            out.put("counts", counts);
            var statuses = new LinkedHashMap<String, Object>();
            artifacts.forEach((key, value) -> {
                if (value instanceof Map<?, ?> artifact) statuses.put(key.toString(), artifact.get("status"));
            });
            out.put("artifact_status", statuses);
            return out;
        }
        out.put("section", section);
        Object value;
        if (ARTIFACTS.contains(section)) {
            var artifact = artifacts.get(section) instanceof Map<?, ?> data ? data : Map.of();
            value = artifact.get("content");
            out.put("status", artifact.get("status"));
        } else {
            value = SECTIONS.contains(section) ? spec.get(section) : workspace.get(section);
        }
        if (value instanceof List<?> list) {
            var start = Math.min(offset, list.size());
            var end = start + Math.min(5, list.size() - start);
            out.put("items", list.subList(start, end));
            out.put("total", list.size());
            out.put("next_offset", end < list.size() ? end : null);
        } else if (value instanceof String document) {
            int total = document.codePointCount(0, document.length());
            int start = Math.min(offset, total);
            int end = start + Math.min(8000, total - start);
            out.put("content", document.substring(document.offsetByCodePoints(0, start), document.offsetByCodePoints(0, end)));
            out.put("next_offset", end < total ? end : null);
            out.put("total", total);
        } else {
            out.put("content", value);
        }
        return out;
    }
}

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
            "tacit.generate_cqs", "tacit.validate_ontology", "tacit.search_context", "tacit.align_policies",
            "tacit.read_fields", "tacit.read_clauses", "tacit.edit_candidates", "tacit.check_candidates", "tacit.submit_candidates", "tacit.propose_scenarios");
    static final List<String> SECTIONS = List.of("scope", "sources", "data_objects", "data_tables", "rules",
            "processes", "gaps", "acceptance_cases");
    static final List<String> ARTIFACTS = List.of("business_report", "gap_report", "flows", "candidates",
            "cqs", "walkthrough", "alignments", "scenarios");

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
        var outcome = outcome(stage);
        if (outcome != null && (outcome.tool().equals(step.tool()) || (stage == TacitAgentTask.BUILD
                && Set.of("tacit.edit_candidates","tacit.submit_candidates").contains(step.tool())))) {
            var artifacts = workspace.get("artifacts") instanceof Map<?,?> items ? items : Map.of();
            var artifact = artifacts.get(outcome.artifact()) instanceof Map<?,?> item ? item : Map.of();
            var details = reply.get("result") instanceof Map<?,?> item ? item : Map.of();
            var digest = Boolean.TRUE.equals(details.get("already_applied")) ? "" : Objects.toString(artifact.get("digest"), "");
            boolean finalPage = switch (stage) {
                case CQ -> Boolean.TRUE.equals(details.get("done"));
                case ALIGN -> "ready".equals(artifact.get("status")) && details.get("next_offset") == null;
                default -> true;
            };
            result.put("artifact_receipt", Map.of("artifact", outcome.artifact(), "digest", digest, "final_page", finalPage));
        }
        if (workspace.get("issues") instanceof List<?> issues) {
            result.put("issue_count", issues.size());
            result.put("issues", issues.subList(0, Math.min(5, issues.size())));
        }
        return result;
    }

    static String discussionOutcome(String summary) {
        var claim = java.util.regex.Pattern.compile("(?:^|[。！!，,\\n])\\s*(?:我|我们|本轮|这次|目前|已经|现)?(?:已(?:经)?|成功)(?:为你|帮你)?(?:创建|完成|提交|批准|审核通过|保存|生成|更新|修改|构建|绘制|修订|写入)");
        return claim.matcher(summary).find() ? "本轮按只读方式处理，未写入知识台账。可在项目知识页查看已有产物和审核状态。" : summary;
    }

    private record Outcome(String tool, String artifact, String name) {}

    private static Outcome outcome(TacitAgentTask stage) {
        return switch (stage) {
            case ORGANIZE, CLARIFY -> new Outcome("tacit.finalize", "business_report", "业务梳理报告和缺口报告");
            case FLOW -> new Outcome("tacit.generate_flow", "flows", "业务流程图");
            case ALIGN -> new Outcome("tacit.align_policies", "alignments", "政策对齐清单");
            case EDIT -> new Outcome("tacit.edit_candidates", "candidates", "候选修改");
            case SUBMIT -> new Outcome("tacit.submit_candidates", "candidates", "提交审核的候选版本");
            case SCENARIOS -> new Outcome("tacit.propose_scenarios", "scenarios", "验收场景建议");
            case CHECK -> new Outcome("tacit.check_candidates", "candidates", "候选校验结果");
            case BUILD -> new Outcome("tacit.build_candidate", "candidates", "本体候选");
            case CQ -> new Outcome("tacit.generate_cqs", "cqs", "业务能力问题");
            case VERIFY -> new Outcome("tacit.validate_ontology", "walkthrough", "建模校核记录");
            default -> null;
        };
    }

    String verifiedOutcome(TacitAgentTask stage, String projectId, List<AssistantModels.PlanStep> steps) {
        return verifiedOutcome(stage, projectId, steps, null);
    }

    String verifiedOutcome(TacitAgentTask stage, String projectId, List<AssistantModels.PlanStep> steps, Object receipt) {
        var expected = outcome(stage);
        if (expected == null) throw new IllegalArgumentException("当前为只读阶段");
        if (steps.stream().noneMatch(s -> expected.tool().equals(s.tool()) && "SUCCEEDED".equals(s.status())))
            throw new IllegalStateException("本轮尚未生成" + expected.name() + "。请在项目知识页核对资料、范围和校验问题。");
        var workspace = workspaces.workspace(projectId);
        var artifacts = workspace.get("artifacts") instanceof Map<?, ?> map ? map : Map.of();
        var item = artifacts.get(expected.artifact()) instanceof Map<?, ?> map ? map : Map.of();
        if (receipt instanceof Map<?,?> saved) {
            var fingerprint = Objects.toString(saved.get("digest"), "");
            if (fingerprint.isBlank() || !fingerprint.equals(item.get("digest")) || !expected.artifact().equals(saved.get("artifact")))
                throw new IllegalStateException("当前" + expected.name() + "与本轮操作的版本不一致，请重新核对。");
            if (!Boolean.TRUE.equals(saved.get("final_page")))
                throw new IllegalStateException("本轮" + expected.name() + "尚未完成所有分页，请从台账进度继续。");
        }
        if (stage == TacitAgentTask.CQ) {
            var progress = item.get("progress") instanceof Map<?,?> details ? details : Map.of();
            if (!Boolean.TRUE.equals(progress.get("done")))
                throw new IllegalStateException("能力问题生成尚未完成。剩余 " + Objects.toString(progress.get("remaining_chunks"), "未知")
                        + " 个材料片段，请用最新台账版本继续生成。");
            if (!(progress.get("generated_questions") instanceof Number count) || count.longValue() <= 0
                    || !(item.get("content") instanceof List<?> questions) || questions.isEmpty())
                throw new IllegalStateException("资料已处理，未生成有来源的能力问题。请核对所选资料和业务范围。");
        }
        if (stage == TacitAgentTask.ALIGN && !"ready".equals(item.get("status")))
            throw new IllegalStateException("条款对齐尚未覆盖所有页，请继续读取 next_offset。");
        if (stage == TacitAgentTask.SUBMIT && !Set.of("pending", "approved").contains(Objects.toString(item.get("status"), "")))
            throw new IllegalStateException("候选已被修改或退回，请检查当前版本。");
        if (stage == TacitAgentTask.CHECK) {
            if (!Set.of("draft","pending","approved","rejected").contains(Objects.toString(item.get("status"), "")))
                throw new IllegalStateException("候选已改变，请重新校验当前候选。");
            long revision = ((Number) workspace.get("revision")).longValue();
            var current = workspaces.executeAgent(projectId, stage.task(), "check_candidates", revision,
                    steps.stream().filter(s -> expected.tool().equals(s.tool()) && "SUCCEEDED".equals(s.status())).findFirst().orElseThrow().id(),
                    Map.of());
            if (!(current.get("result") instanceof Map<?,?> result) || !(result.get("valid") instanceof Boolean valid)
                    || !(result.get("issues") instanceof List<?> issues)) throw new IllegalStateException("候选校验未返回可信结果。");
            return "已检查台账版本 " + revision + " 的候选。校验" + (valid ? "通过" : "未通过") + "，共 " + issues.size() + " 项问题。";
        }
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
            if (artifacts.get("cqs") instanceof Map<?,?> cqs && cqs.get("progress") instanceof Map<?,?> progress)
                out.put("cq_progress", progress);
            out.put("source_clauses", "完整条款按 source_id 使用 tacit.read_clauses 分页读取。");
            out.put("table_fields", "完整字段按 table_id 使用 tacit.read_fields 分页读取。");
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
            if (artifact.get("progress") instanceof Map<?,?> progress) out.put("progress", progress);
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

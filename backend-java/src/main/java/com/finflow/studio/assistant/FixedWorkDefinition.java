package com.finflow.studio.assistant;

import java.util.*;

/** Immutable linear implementation. References to earlier steps form an explicit dataflow. */
public record FixedWorkDefinition(String title, List<Step> steps, Map<String, Object> modelSnapshot,
                                  List<BusinessStage> businessStages) {
    public FixedWorkDefinition(String title, List<Step> steps, Map<String, Object> modelSnapshot) {
        this(title, steps, modelSnapshot, List.of());
    }
    public FixedWorkDefinition {
        businessStages = businessStages == null ? List.of() : List.copyOf(businessStages);
    }
    public record Outcome(String stepId, String role) { }
    public record BusinessStage(String id, String title, String goal, String acceptance,
                                List<String> stepIds, List<Outcome> outcomes) { }
    public record Input(String alias, String resourceId, String stepId, String previousOutput) { }
    public record Step(String id, String title, String kind, String code, String outputName, List<Input> inputs) { }

    public void validate() {
        if (title == null || title.isBlank() || title.length() > 120 || steps == null || steps.isEmpty() || steps.size() > 30)
            throw new IllegalArgumentException("实现需要名称和1至30个工序");
        var seen = new HashSet<String>();
        if (steps.stream().filter(Objects::nonNull).map(Step::code).filter(Objects::nonNull).mapToInt(String::length).sum() > 200000)
            throw new IllegalArgumentException("实现内容超过200000字符");
        var allIds = new HashSet<String>();
        for (var step : steps) {
            if (step == null || step.id() == null || !step.id().matches("[A-Za-z0-9_-]{1,80}") || !allIds.add(step.id()))
                throw new IllegalArgumentException("工序ID无效或重复");
        }
        for (var step : steps) {
            if (step.title() == null || step.title().isBlank() || step.title().length() > 120
                    || !Set.of("SQL", "PROMPT", "TOOL").contains(Objects.toString(step.kind(), ""))
                    || step.code() == null || step.code().isBlank() || step.code().length() > 50000
                    || step.outputName() == null || !step.outputName().matches("[^/\\\\\\p{Cntrl}]{1,200}")
                    || step.inputs() == null || (!"TOOL".equals(step.kind()) && step.inputs().isEmpty()) || step.inputs().size() > 20)
                throw new IllegalArgumentException("工序必须包含固定SQL或提示词、输入和输出名称");
            if ("SQL".equals(step.kind()) && !step.code().strip().toLowerCase(Locale.ROOT).matches("(?s)^(select|with)\\b.*"))
                throw new IllegalArgumentException("SQL工序只允许固定只读查询，不允许动态生成或修复");
            var aliases = new HashSet<String>();
            for (var input : step.inputs()) {
                if (input == null || input.alias() == null || !input.alias().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                        || !aliases.add(input.alias().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("输入别名无效或重复");
                boolean resource = present(input.resourceId()), upstream = present(input.stepId()), previous = present(input.previousOutput());
                if (previous) {
                    if (upstream || !resource || !allIds.contains(input.previousOutput()))
                        throw new IllegalArgumentException("历史产物输入需要有效工序和首轮资源ID");
                } else if (resource == upstream) throw new IllegalArgumentException("每个输入必须绑定一个资源或前序工序");
                if (upstream && !seen.contains(input.stepId())) throw new IllegalArgumentException("只能引用已声明的前序工序，不能循环引用");
            }
            seen.add(step.id());
        }
        validateBusinessStages();
    }

    private void validateBusinessStages() {
        if (businessStages.isEmpty()) return; // Read and rerun historical definitions without inventing a grouping.
        if (businessStages.size() > 30) throw new IllegalArgumentException("业务工序不能超过30个");
        var ids = new HashSet<String>();
        var orderedSteps = new ArrayList<String>();
        for (var stage : businessStages) {
            if (stage == null || stage.id() == null || !stage.id().matches("[A-Za-z0-9_-]{1,80}") || !ids.add(stage.id())
                    || stage.title() == null || stage.title().isBlank() || stage.title().length() > 120
                    || stage.goal() == null || stage.goal().isBlank() || stage.goal().length() > 2000
                    || stage.acceptance() == null || stage.acceptance().isBlank() || stage.acceptance().length() > 2000
                    || stage.stepIds() == null || stage.stepIds().isEmpty() || stage.outcomes() == null)
                throw new IllegalArgumentException("业务工序需要唯一ID、名称、目标、验收说明、技术步骤和成果定义");
            orderedSteps.addAll(stage.stepIds());
            var outputs = new HashSet<String>();
            boolean primary = false;
            for (var outcome : stage.outcomes()) {
                if (outcome == null || !stage.stepIds().contains(outcome.stepId()) || !outputs.add(outcome.stepId())
                        || !Set.of("PRIMARY", "SUPPORTING", "INTERNAL").contains(Objects.toString(outcome.role(), "")))
                    throw new IllegalArgumentException("成果必须关联本工序的技术步骤，并声明有效且不重复的角色");
                primary |= "PRIMARY".equals(outcome.role());
            }
            if (!primary) throw new IllegalArgumentException("每道业务工序至少需要一个可检查的主要成果");
        }
        if (!orderedSteps.equals(steps.stream().map(Step::id).toList()))
            throw new IllegalArgumentException("业务工序必须按执行顺序完整且唯一地覆盖技术步骤，不允许遗漏、重复或交叉");
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}

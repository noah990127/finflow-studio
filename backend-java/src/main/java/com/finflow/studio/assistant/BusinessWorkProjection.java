package com.finflow.studio.assistant;

import java.util.*;

/** Projects an immutable run through its own business contract, never the latest definition. */
final class BusinessWorkProjection {
    private BusinessWorkProjection() { }

    static List<AgentWorkService.Stage> project(FixedWorkDefinition definition, List<FixedWorkService.StageResult> results) {
        if (definition.businessStages().isEmpty()) return results.stream().map(s ->
                new AgentWorkService.Stage(s.id(), s.title(), s.status(), s.error(), "",
                        s.inputs().stream().map(BusinessWorkProjection::ref).toList(),
                        s.output() == null ? List.of() : List.of(ref(s.output())))).toList();
        var byId = new HashMap<String, FixedWorkService.StageResult>();
        results.forEach(s -> byId.put(s.id(), s));
        return definition.businessStages().stream().map(stage -> {
            var members = stage.stepIds().stream().map(byId::get).filter(Objects::nonNull).toList();
            var localOutputs = new HashSet<String>();
            members.stream().map(FixedWorkService.StageResult::output).filter(Objects::nonNull).forEach(o -> localOutputs.add(o.resourceId()));
            var inputs = members.stream().flatMap(s -> s.inputs().stream()).filter(i -> !localOutputs.contains(i.resourceId()))
                    .map(BusinessWorkProjection::ref).distinct().toList();
            var outputs = new ArrayList<AgentWorkService.ResourceRef>();
            for (var member : members) if (member.output() != null) {
                var role = stage.outcomes().stream().filter(o -> o.stepId().equals(member.id())).map(FixedWorkDefinition.Outcome::role)
                        .findFirst().orElse("INTERNAL");
                outputs.add(new AgentWorkService.ResourceRef(member.output().resourceId(), member.output().version(), role));
            }
            String status = "PENDING";
            for (var candidate : List.of("FAILED", "CANCELED", "RUNNING"))
                if (members.stream().anyMatch(s -> candidate.equals(s.status()))) { status = candidate; break; }
            if ("PENDING".equals(status) && members.size() == stage.stepIds().size()
                    && members.stream().allMatch(s -> "SUCCEEDED".equals(s.status()))) {
                boolean complete = stage.outcomes().stream().filter(o -> "PRIMARY".equals(o.role()))
                        .allMatch(o -> byId.get(o.stepId()) != null && byId.get(o.stepId()).output() != null);
                status = complete ? "SUCCEEDED" : "FAILED";
            }
            var error = members.stream().map(FixedWorkService.StageResult::error).filter(e -> e != null && !e.isBlank()).findFirst().orElse("");
            return new AgentWorkService.Stage(stage.id(), stage.title(), status,
                    error.isBlank() ? stage.goal() + "\n检查要点：" + stage.acceptance() : error, "", inputs, outputs);
        }).toList();
    }
    private static AgentWorkService.ResourceRef ref(FixedWorkService.Bound value) {
        return new AgentWorkService.ResourceRef(value.resourceId(), value.version());
    }
}

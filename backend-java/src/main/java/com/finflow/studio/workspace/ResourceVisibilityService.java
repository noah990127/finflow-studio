package com.finflow.studio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;

/** Presentation only: references and execution access are never filtered. */
@Service
public class ResourceVisibilityService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ResourceVisibilityService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    public Set<String> internalIds(String projectId) {
        var internal = new HashSet<String>();
        var visible = new HashSet<String>();
        jdbc.sql("""
                select d.definition_json, r.stages_json from fixed_work_run r
                join fixed_work_definition d on d.id=r.definition_id where d.project_id=:project
                """).param("project", projectId).query((rs, row) -> {
                    collect(json.readTree(rs.getString("definition_json")),
                            json.readTree(rs.getString("stages_json")), internal, visible);
                    return true;
                }).list();
        jdbc.sql("""
                select w.stages_json from agent_work w
                join assistant_run r on r.id=w.run_id
                join assistant_plan p on p.id=r.plan_id
                join assistant_context_snapshot c on c.id=p.context_snapshot_id
                where c.project_id=:project
                """).param("project", projectId).query((rs, row) -> {
                    for (var stage : json.readTree(rs.getString("stages_json"))) {
                        for (var input : stage.path("inputs")) visible.add(input.path("resourceId").asText());
                        for (var output : stage.path("outputs")) {
                            ("INTERNAL".equals(output.path("role").asText()) ? internal : visible)
                                    .add(output.path("resourceId").asText());
                        }
                    }
                    return true;
                }).list();
        internal.remove("");
        internal.removeAll(visible);
        return internal;
    }

    static void collect(JsonNode definition, JsonNode steps, Set<String> internal, Set<String> visible) {
        var stages = definition.path("businessStages");
        // No contract means unknown, not internal. Preserve legacy outputs.
        var publicSteps = new HashSet<String>();
        for (var stage : stages) for (var outcome : stage.path("outcomes")) {
            if (!"INTERNAL".equals(outcome.path("role").asText())) publicSteps.add(outcome.path("stepId").asText());
        }
        for (var step : steps) {
            var id = step.path("output").path("resourceId").asText("");
            if (!id.isBlank()) {
                (stages.isEmpty() || publicSteps.contains(step.path("id").asText()) ? visible : internal).add(id);
            }
        }
        // Explicit resource bindings are user-facing inputs; local step bindings are not.
        for (var step : definition.path("steps")) for (var input : step.path("inputs")) {
            var id = input.path("resourceId").asText("");
            if (!id.isBlank()) visible.add(id);
        }
    }
}

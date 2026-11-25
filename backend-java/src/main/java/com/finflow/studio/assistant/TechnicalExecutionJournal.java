package com.finflow.studio.assistant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Service
public class TechnicalExecutionJournal {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TechnicalArtifactService artifacts;
    public TechnicalExecutionJournal(JdbcClient jdbc, ObjectMapper json, TechnicalArtifactService artifacts) {
        this.jdbc = jdbc; this.json = json; this.artifacts = artifacts;
    }
    public void begin(String run, String definition, FixedWorkDefinition implementation, FixedWorkDefinition.Step step,
                      int order, Map<String, FixedWorkService.Bound> inputs) {
        var stage = implementation.businessStages().stream().filter(s -> s.stepIds().contains(step.id())).map(FixedWorkDefinition.BusinessStage::id).findFirst().orElse(step.id());
        // A duplicate dispatch is an error; a previous uncertain write is never silently retried.
        jdbc.sql("insert into technical_step_execution(run_id,step_id,definition_id,business_stage_id,step_order,code_hash,inputs_json,status,started_at) values(:run,:step,:definition,:stage,:order,:hash,:inputs,'DISPATCHING',:now)")
                .param("run", run).param("step", step.id()).param("definition", definition).param("stage", stage).param("order", order)
                .param("hash", HashSupport.sha256(step.code())).param("inputs", json.writeValueAsString(inputs)).param("now", Instant.now()).update();
    }
    public void complete(String run, String step, FixedWorkService.Bound output) {
        jdbc.sql("update technical_step_execution set status='SUCCEEDED',output_json=:output,finished_at=:now where run_id=:run and step_id=:step and status='DISPATCHING'")
                .param("run", run).param("step", step).param("output", json.writeValueAsString(output)).param("now", Instant.now()).update();
    }
    public void interrupted(String run, String error) {
        jdbc.sql("update technical_step_execution set status='NEEDS_VERIFICATION',error=:error,finished_at=:now where run_id=:run and status='DISPATCHING'")
                .param("run", run).param("error", error).param("now", Instant.now()).update();
    }
    public List<Map<String, Object>> entries(String run) {
        return jdbc.sql("select * from technical_step_execution where run_id=:run order by step_order")
                .param("run", run).query((rs, row) -> {
                    var value = new LinkedHashMap<String, Object>();
                    for (var field : List.of("step_id", "definition_id", "business_stage_id", "code_hash", "status", "error")) value.put(field, rs.getString(field));
                    value.put("inputs", json.readValue(rs.getString("inputs_json"), Map.class));
                    value.put("output", json.readValue(rs.getString("output_json"), Map.class));
                    value.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                    value.put("finishedAt", rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant().toString());
                    return (Map<String, Object>) value;
                }).list();
    }
    public void archive(String project, String run) {
        artifacts.save(project, "EXECUTION", run, "@journal", "执行记录-" + run.substring(0, 8) + ".json",
                json.writeValueAsString(Map.of("version", 1, "runId", run, "steps", entries(run))));
    }
}

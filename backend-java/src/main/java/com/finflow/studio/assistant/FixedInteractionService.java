package com.finflow.studio.assistant;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

@Service
public class FixedInteractionService {
    public record Interaction(String stepId, String provider, String taskId, int promptId,
                              String status, String message, boolean canFocus, String kind) {
        public Interaction(String stepId, String provider, String taskId, int promptId, String status, String message, boolean canFocus) {
            this(stepId, provider, taskId, promptId, status, message, canFocus, "INPUT");
        }
    }
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final List<FixedInteractionHandler> handlers;
    public FixedInteractionService(JdbcClient jdbc, ObjectMapper json, @Lazy List<FixedInteractionHandler> handlers) {
        this.jdbc = jdbc; this.json = json; this.handlers = handlers;
    }
    public void record(String runId, Interaction value) {
        var encoded = json.writeValueAsString(value);
        if (jdbc.sql("update fixed_work_interaction set interaction_json=:value where run_id=:id")
                .param("value", encoded).param("id", runId).update() == 0)
            jdbc.sql("insert into fixed_work_interaction(run_id,interaction_json) values(:id,:value)")
                    .param("id", runId).param("value", encoded).update();
    }
    public Interaction get(String runId) {
        return jdbc.sql("select interaction_json from fixed_work_interaction where run_id=:id")
                .param("id", runId).query(String.class).optional().map(s -> json.readValue(s, Interaction.class)).orElse(null);
    }
    // The caller must authorize the exact project/session/run before entering this method.
    public void control(String runId, String taskId, int promptId, String action) {
        var interaction = get(runId);
        if (interaction == null || !"WAITING_INPUT".equals(interaction.status())
                || !interaction.taskId().equals(taskId) || interaction.promptId() != promptId)
            throw new IllegalStateException("待办已变化，请刷新后再操作");
        if (!List.of("resume", "approve", "focus").contains(action) || ("focus".equals(action) && !interaction.canFocus()))
            throw new IllegalArgumentException("当前操作不受支持");
        handlers.stream().filter(h -> h.interactionProvider().equals(interaction.provider())).findFirst()
                .orElseThrow(() -> new IllegalStateException("执行组件暂不可用"))
                .control(taskId, action, promptId);
    }
}

package com.finflow.studio.assistant;

import java.util.Map;

public interface FixedInteractionHandler {
    String interactionProvider();
    Map<String, Object> control(String taskId, String action, int promptId);
}

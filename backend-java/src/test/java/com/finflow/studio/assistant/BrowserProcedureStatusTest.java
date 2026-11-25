package com.finflow.studio.assistant;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserProcedureStatusTest {
    @Test void successfulExecutionIsNotSuccessfulPublication() {
        var task = new LinkedHashMap<String, Object>();
        task.put("status", Map.of("state", "completed"));
        task.put("metadata", Map.of("procedureError", "Missing binding"));
        BrowserAgentService.projectProcedureStatus(task);
        assertEquals("NEEDS_REVIEW", task.get("procedureState"));
        assertEquals("Missing binding", task.get("procedureIssue"));
        assertEquals(Map.of("state", "completed"), task.get("status"));
    }
    @Test void publicationRequiresDefinitionNotJustArtifact() {
        var task = new LinkedHashMap<String, Object>();
        task.put("metadata", Map.of("procedure", Map.of("hash", "abc")));
        BrowserAgentService.projectProcedureStatus(task);
        assertEquals("READY", task.get("procedureState"));
        task.put("savedProcedure", Map.of("definitionId", "id"));
        BrowserAgentService.projectProcedureStatus(task);
        assertEquals("PUBLISHED", task.get("procedureState"));
    }
}

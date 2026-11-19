package com.finflow.studio.workspace;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ResourceVisibilityServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void explicitContractHidesOnlyIntermediateOutputs() {
        var internal = new HashSet<String>();
        var visible = new HashSet<String>();
        ResourceVisibilityService.collect(json.readTree("""
            {"steps":[{"inputs":[{"resourceId":"user"}]}],"businessStages":[
              {"outcomes":[{"stepId":"report","role":"PRIMARY"},{"stepId":"sources","role":"SUPPORTING"}]}]}
            """), json.readTree("""
            [{"id":"raw","output":{"resourceId":"raw-file"}},
             {"id":"report","output":{"resourceId":"report-file"}},
             {"id":"sources","output":{"resourceId":"source-file"}},
             {"id":"pending","output":null}]
            """), internal, visible);
        assertEquals(Set.of("raw-file"), internal);
        assertEquals(Set.of("user","report-file","source-file"), visible);
        // A legacy run sharing the resource must keep it visible.
        ResourceVisibilityService.collect(json.readTree("{}"), json.readTree("""
            [{"id":"old","output":{"resourceId":"raw-file"}}]
            """), internal, visible);
        internal.removeAll(visible);
        assertTrue(internal.isEmpty());
    }
}

package com.finflow.studio.assistant;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BusinessWorkProjectionTest {
    private FixedWorkDefinition definition(List<FixedWorkDefinition.BusinessStage> groups) {
        return new FixedWorkDefinition("任意业务", List.of(
                new FixedWorkDefinition.Step("extract", "读取", "TOOL", "{}", "raw.json", List.of()),
                new FixedWorkDefinition.Step("check", "核对", "PROMPT", "核对输入", "check.md", List.of(new FixedWorkDefinition.Input("data", null, "extract", null)))), Map.of(), groups);
    }
    private FixedWorkDefinition.BusinessStage group(List<String> ids, List<FixedWorkDefinition.Outcome> outcomes) {
        return new FixedWorkDefinition.BusinessStage("review", "核对业务结果", "得到可检查的结果", "检查差异与解释", ids, outcomes);
    }
    private FixedWorkDefinition valid() {
        return definition(List.of(group(List.of("extract", "check"), List.of(new FixedWorkDefinition.Outcome("check", "PRIMARY")))));
    }
    private List<FixedWorkService.StageResult> results(String status, boolean output) {
        var raw = new FixedWorkService.Bound("raw", 1, "raw.json", "FILE");
        return List.of(new FixedWorkService.StageResult("extract", "读取", "SUCCEEDED", List.of(), raw, ""),
                new FixedWorkService.StageResult("check", "核对", status, List.of(raw), output ? new FixedWorkService.Bound("result", 2, "result.md", "FILE") : null, ""));
    }
    @Test void groupsToolsAndHidesInternalInputsWithoutLosingTrace() {
        valid().validate();
        var stages = BusinessWorkProjection.project(valid(), results("SUCCEEDED", true));
        assertThat(stages).hasSize(1);
        assertThat(stages.getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(stages.getFirst().inputs()).isEmpty();
        assertThat(stages.getFirst().outputs()).extracting(AgentWorkService.ResourceRef::role).containsExactly("INTERNAL", "PRIMARY");
        assertThat(stages.getFirst().outputs().getLast().version()).isEqualTo(2);
    }
    @Test void missingPrimaryCannotBeComplete() {
        assertThat(BusinessWorkProjection.project(valid(), results("SUCCEEDED", false)).getFirst().status()).isEqualTo("FAILED");
        assertThat(BusinessWorkProjection.project(valid(), results("RUNNING", false)).getFirst().status()).isEqualTo("RUNNING");
        assertThat(BusinessWorkProjection.project(valid(), results("CANCELED", false)).getFirst().status()).isEqualTo("CANCELED");
    }
    @Test void rejectsMissingPrimaryAndIncompleteOrReorderedCoverage() {
        for (var ids : List.of(List.of("check"), List.of("check", "extract"), List.of("extract", "check", "check")))
            assertThatThrownBy(() -> definition(List.of(group(ids, List.of(new FixedWorkDefinition.Outcome("check", "PRIMARY"))))).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(List.of(group(List.of("extract", "check"), List.of(new FixedWorkDefinition.Outcome("check", "INTERNAL"))))).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(List.of(group(List.of("extract", "check"), List.of(new FixedWorkDefinition.Outcome("unknown", "PRIMARY"))))).validate()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyDefinitionsRemainUnchanged() {
        var legacy = definition(List.of()); legacy.validate();
        assertThat(BusinessWorkProjection.project(legacy, results("SUCCEEDED", true))).hasSize(2);
    }
}

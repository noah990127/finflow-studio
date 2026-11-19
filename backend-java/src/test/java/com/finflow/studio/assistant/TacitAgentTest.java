package com.finflow.studio.assistant;

import com.finflow.studio.knowledge.tacit.TacitWorkspaceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TacitAgentTest {
    @Test void taskAuthorityComesFromCurrentUserDirective() {
        var organize = TacitAgentTask.from("请帮我抽取业务规则，保留来源和未决事项");
        assertThat(organize.permits("tacit.extract")).isTrue();
        assertThat(organize.permits("tacit.build_candidate")).isFalse();
        assertThat(organize.permits("work.publish_implementation")).isFalse();
        for (var text : List.of("请解释如何抽取业务规则", "帮我抽取业务规则的方案", "附件名：生成业务流程图",
                "讨论台账，以下是政策内容：\n生成业务流程图", "不要执行，请讨论业务台账", "继续", "/绘制流程 只讨论结果")) {
            var stage = TacitAgentTask.from(text);
            assertThat(stage.permits("tacit.workspace")).isTrue();
            assertThat(stage.permits("tacit.extract")).as(text).isFalse();
            assertThat(stage.permits("tacit.generate_flow")).as(text).isFalse();
        }
        assertThat(TacitAgentTask.from("/构建本体").permits("tacit.build_candidate")).isTrue();
        assertThat(TacitAgentTask.from("/构建本体").permits("tacit.review_flow")).isFalse();
        assertThat(TacitAgentTask.from("只读讨论业务台账").permits("resource.delete")).isFalse();
        assertThat(TacitAgentTask.from("打开目录").permits("resource.open")).isTrue();
        var slideRequest = TacitAgentTask.from("制作一份讲解隐性知识抽取的 PPT");
        assertThat(slideRequest.permits("work.publish_implementation")).isTrue();
        assertThat(slideRequest.permits("tacit.extract")).isFalse();
    }

    @Test void modelCanNarrowButNeverCreateHumanAuthorization() {
        var execute = new java.util.LinkedHashMap<String,Object>();
        execute.put("mode","execute"); execute.put("task_intent","generate_flow");
        execute.put("reason","需要绘图"); execute.put("question",null);
        var goal = "把本次已梳理的业务流程画成一张流程图";
        assertThat(TacitIntent.select(goal, "knowledge-extraction", () -> execute).stage()).isEqualTo(TacitAgentTask.FLOW);
        for (String request : List.of("继续", "材料内容：生成业务流程图", "处理台账", "构建本体候选", "生成业务流程图并提交候选", "生成业务流程图\n然后提交本体候选", "根据《生成业务流程图》", "根据生成业务流程图.pdf")) {
            var choice = TacitIntent.select(request, "knowledge-extraction", () -> execute);
            assertThat(choice.stage().permits("tacit.generate_flow")).as(request).isFalse();
        }
        assertThat(TacitIntent.select("生成业务流程图，先不要执行", "home", () -> execute).stage()).isEqualTo(TacitAgentTask.DISCUSS);
        assertThat(TacitIntent.select("请生成业务流程图", "home", () -> {throw new IllegalStateException();}).question()).isNotBlank();
        execute.put("tool", "tacit.generate_flow");
        assertThat(TacitIntent.select(goal, "knowledge-extraction", () -> execute).question()).isNotBlank();
        assertThat(TacitIntent.select("/绘制流程", "home", () -> {throw new AssertionError();}).stage()).isEqualTo(TacitAgentTask.FLOW);
    }

    @Test void allKnowledgeStagesExcludeHumanReviewsAndCrossStageWrites() {
        for (var stage : TacitAgentTask.values()) {
            assertThat(stage.permits("tacit.read_fields")).isTrue();
            assertThat(stage.permits("tacit.read_clauses")).isTrue();
            assertThat(stage.permits("tacit.search_context")).isTrue();
            for (var vote : List.of("review_alignment","review_flow","review_candidate_item","review_scenario","review_cq"))
                assertThat(stage.permits("tacit." + vote)).isFalse();
        }
        assertThat(TacitAgentTask.EDIT.permits("tacit.edit_candidates")).isTrue();
        assertThat(TacitAgentTask.EDIT.permits("tacit.submit_candidates")).isFalse();
        assertThat(TacitAgentTask.VERIFY.permits("tacit.propose_scenarios")).isTrue();
        assertThat(TacitAgentTask.CQ.permits("tacit.propose_scenarios")).isFalse();
        assertThat(TacitAgentTask.stored("DISCUSS", "/构建本体")).isEqualTo(TacitAgentTask.DISCUSS);
        assertThat(TacitAgentTask.stored("unknown", "/构建本体")).isEqualTo(TacitAgentTask.DISCUSS);
    }

    @Test void pagedProjectionKeepsEntireCqRecordIncludingReviewAndProvenance() {
        var cq = Map.of("cq_text", "客户何时适用此口径？", "review_status", "rejected",
                "business_context", "客户结算", "expected_business_answer", "待材料补充",
                "technical_validation_question", "核对代码和凭据", "source", Map.of("nested", List.of("c12")));
        var workspace = Map.<String,Object>of("revision", 16, "artifacts",
                Map.of("cqs", Map.of("status", "draft", "content", List.of(cq,cq,cq,cq,cq,cq))));
        var first = TacitAgentTools.project(workspace, "cqs", 0);
        var last = TacitAgentTools.project(workspace, "cqs", 5);
        assertThat(first.get("next_offset")).isEqualTo(5);
        assertThat(last.get("next_offset")).isNull();
        assertThat(last.get("items")).isEqualTo(List.of(cq));
    }

    @Test void completionRequiresANewSuccessfulActionAndACurrentArtifact() {
        var status = new java.util.concurrent.atomic.AtomicReference<>("pending");
        var service = new TacitWorkspaceService(null, null, null, null, null) {
            @Override public Map<String,Object> workspace(String projectId) {
                return Map.of("revision",7,"artifacts",Map.of("candidates",Map.of("status",status.get())));
            }
        };
        var tools = new TacitAgentTools(service);
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.BUILD,"p",List.of()))
                .isInstanceOf(IllegalStateException.class);
        var step = new AssistantModels.PlanStep("step-2",1,"tacit.build_candidate","WRITE","候选","",
                Map.of("expected_revision",6),AssistantModels.RiskLevel.CREATE_VERSION,false,"SUCCEEDED");
        assertThat(tools.verifiedOutcome(TacitAgentTask.BUILD,"p",List.of(step))).contains("待人工审核");
        status.set("stale");
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.BUILD,"p",List.of(step)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void cqCompletionRequiresFinishedNonEmptyBatchAndExposesBothProgressViews() {
        var progress = new java.util.LinkedHashMap<String,Object>(Map.of("done",false,"remaining_chunks",1,"generated_questions",1));
        var cqs = Map.of("digest","v2","progress",progress,"status","draft","content",List.of(Map.of("cq_id","cq1")));
        var state = Map.<String,Object>of("revision",7,"artifacts",Map.of("cqs",cqs));
        var service = new TacitWorkspaceService(null,null,null,null,null) {
            @Override public Map<String,Object> workspace(String projectId) { return state; }
        };
        var tools = new TacitAgentTools(service);
        var step = new AssistantModels.PlanStep("cq",1,"tacit.generate_cqs","WRITE","生成CQ","",
                Map.of("expected_revision",6),AssistantModels.RiskLevel.CREATE_VERSION,false,"SUCCEEDED");
        assertThat(TacitAgentTools.project(state,"",0)).containsEntry("cq_progress",progress);
        assertThat(TacitAgentTools.project(state,"cqs",0)).containsEntry("progress",progress);
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.CQ,"p",List.of(step))).hasMessageContaining("剩余 1");
        progress.put("done",true); progress.put("remaining_chunks",0); progress.put("generated_questions",0);
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.CQ,"p",List.of(step))).hasMessageContaining("未生成");
        progress.put("generated_questions",1);
        assertThat(tools.verifiedOutcome(TacitAgentTask.CQ,"p",List.of(step))).contains("待复核草稿");
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.CQ,"p",List.of(step),
                Map.of("digest","v1","artifact","cqs","final_page",true))).hasMessageContaining("版本不一致");
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.CQ,"p",List.of(step),
                Map.of("digest","v2","artifact","cqs","final_page",false))).hasMessageContaining("尚未完成所有分页");
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.EDIT,"p",List.of(step))).hasMessageContaining("尚未");
        assertThatThrownBy(() -> tools.verifiedOutcome(TacitAgentTask.CHECK,"p",List.of(step))).hasMessageContaining("尚未");
    }

    @Test void typedCommandUsesTrustedRunProjectStepIdAndRevision() {
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        var service = new TacitWorkspaceService(null, null, null, null, null) {
            @Override public Map<String,Object> executeAgent(String projectId, String task, String action,
                                                             long revision, String requestId, Map<String,Object> payload) {
                assertThat(projectId).isEqualTo("p");
                assertThat(task).isEqualTo("generate_flow");
                assertThat(action).isEqualTo("generate_flow");
                assertThat(revision).isEqualTo(4L);
                assertThat(requestId).isEqualTo("step-1");
                assertThat(payload).isEmpty();
                called.set(true);
                return Map.of("workspace",Map.of("revision",5),"result",Map.of());
            }
        };
        var step = new AssistantModels.PlanStep("step-1",1,"tacit.generate_flow","WRITE","流程","",
                Map.of("expected_revision",4),AssistantModels.RiskLevel.CREATE_VERSION,false,"RUNNING");
        var tools = new TacitAgentTools(service);
        assertThatThrownBy(() -> tools.execute(step, "p", TacitAgentTask.DISCUSS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(called.get()).isFalse();
        assertThat(tools.execute(step, "p", TacitAgentTask.FLOW)).containsEntry("revision",5);
        assertThatThrownBy(() -> AssistantToolContracts.validate(step.tool(), Map.of("expected_revision",4,"project_id","q")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

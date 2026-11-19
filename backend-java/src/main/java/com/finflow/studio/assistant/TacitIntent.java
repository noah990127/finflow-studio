package com.finflow.studio.assistant;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Only a matching model decision can narrow the independent human authorization ceiling. */
record TacitIntent(TacitAgentTask stage, String question) {
    static final String ASK = "本轮希望处理哪个阶段？可回复 /梳理业务、/澄清缺口、/绘制流程、/构建本体、/生成能力问题、/校核本体，或 /讨论台账。";
    static TacitIntent select(String goal, String page, java.util.function.Supplier<Map<String,Object>> classify) {
        if (!TacitAgentTask.mayConcernLedger(goal, page)) return new TacitIntent(TacitAgentTask.OTHER, null);
        var ceiling = TacitAgentTask.from(goal);
        if (ceiling == TacitAgentTask.DISCUSS || ceiling.isShortcut(goal)) return new TacitIntent(ceiling, null);
        try {
            var result = classify.get();
            if (result == null || !result.keySet().equals(Set.of("mode","task_intent","reason","question"))
                    || !(result.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 300)
                return ask(ASK);
            var task = result.get("task_intent");
            var question = result.get("question");
            if ("clarify".equals(result.get("mode")) && task == null && question instanceof String q && !q.isBlank()
                    && q.length() <= 300) return ask(q);
            if (question != null || Arrays.stream(TacitAgentTask.values()).noneMatch(stage -> stage.task().equals(task)))
                return ask(ASK);
            if ("discuss".equals(result.get("mode"))) return new TacitIntent(TacitAgentTask.DISCUSS, null);
            if ("execute".equals(result.get("mode")) && ceiling != TacitAgentTask.OTHER && ceiling.task().equals(task))
                return new TacitIntent(ceiling, null);
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted() || exception instanceof java.util.concurrent.CancellationException)
                throw new java.util.concurrent.CancellationException();
            return ask("意图识别服务暂不可用。" + ASK);
        }
        return ask(ASK);
    }

    private static TacitIntent ask(String question) { return new TacitIntent(TacitAgentTask.DISCUSS, question); }
}

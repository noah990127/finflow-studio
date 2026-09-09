package com.finflow.studio.assistant;

import com.finflow.studio.assistant.AssistantModels.PlanStep;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class AssistantFailurePolicy {
    enum Category { PARAMETER, RESOURCE_MISSING, NETWORK, CONFLICT, AUTHORIZATION, NO_PROGRESS, SYSTEM }

    record Failure(Category category, boolean retryable, String recoveryHint) { }
    record Decision(Failure failure, int attempt, boolean stop, String stopMessage) { }

    private AssistantFailurePolicy() { }

    static Failure classify(Throwable exception) {
        var message = message(exception).toLowerCase();
        if (exception instanceof IllegalArgumentException || contains(message, "参数错误", "缺少必填字段", "不支持字段"))
            return new Failure(Category.PARAMETER, false, "读取工具契约，修正缺失字段、字段类型或可选值后再调用；不要原样重试");
        if (contains(message, "不存在", "未找到", "没有可读取", "没有可用于", "没有可运行", "缺少上游", "资料不存在"))
            return new Failure(Category.RESOURCE_MISSING, false, "重新检查当前工作区或搜索可用资料，使用真实返回的对象 ID；不要重复读取不存在的对象");
        if (contains(message, "版本已", "版本变化", "刷新后重试", "冲突", "stale"))
            return new Failure(Category.CONFLICT, false, "先重新读取对象和最新版本，再基于最新内容提交一次修改");
        if (contains(message, "401", "403", "unauthorized", "forbidden", "凭据", "权限"))
            return new Failure(Category.AUTHORIZATION, false, "当前权限或连接凭据不可用，请说明需要用户检查的配置，不要自动重复请求");
        if (contains(message, "timeout", "超时", "connection", "connect", "网络", "503", "502", "429", "temporarily"))
            return new Failure(Category.NETWORK, true, "这是临时连接问题；最多重试两次，并优先复用已取得的结果或选择可用替代来源");
        return new Failure(Category.SYSTEM, false, "保留已经完成的结果，尝试不依赖该失败能力的替代方式；无法替代时说明具体阻塞点");
    }

    static Decision assess(PlanStep step, Map<String, Object> observation, Map<String, Integer> attempts) {
        Failure failure;
        if (Boolean.TRUE.equals(observation.get("success"))) {
            var output = observation.get("output") instanceof Map<?, ?> values ? values : Map.of();
            if (!Boolean.FALSE.equals(output.get("changed"))) return null;
            failure = new Failure(Category.NO_PROGRESS, false,
                    "操作没有产生变化；使用返回的当前状态继续或结束，不要用相同参数重复修改");
        } else {
            var category = category(observation.get("failureType"));
            failure = new Failure(category, Boolean.TRUE.equals(observation.get("retryable")),
                    Objects.toString(observation.get("recoveryHint"), "根据错误调整方式"));
        }
        var outcome = Boolean.TRUE.equals(observation.get("success"))
                ? Objects.toString(observation.get("output"), "")
                : Objects.toString(observation.get("error"), failure.recoveryHint());
        var signature = failure.category() + ":" + step.tool() + ":" + new TreeMap<>(step.arguments()) + ":" + outcome;
        var attempt = attempts.merge(HashSupport.sha256(signature), 1, Integer::sum);
        var limit = failure.category() == Category.NETWORK ? 3 : 2;
        var stop = attempt >= limit;
        var stopMessage = stop ? "同一操作以相同参数连续失败 " + attempt + " 次（" + label(failure.category())
                + "），已停止重复执行。" + failure.recoveryHint() : "";
        return new Decision(failure, attempt, stop, stopMessage);
    }

    private static Category category(Object value) {
        try { return Category.valueOf(Objects.toString(value, "SYSTEM")); }
        catch (IllegalArgumentException ignored) { return Category.SYSTEM; }
    }

    private static String label(Category category) {
        return switch (category) {
            case PARAMETER -> "参数不符合契约";
            case RESOURCE_MISSING -> "所需资料或对象不存在";
            case NETWORK -> "网络或服务暂时不可用";
            case CONFLICT -> "版本冲突";
            case AUTHORIZATION -> "权限或凭据不可用";
            case NO_PROGRESS -> "操作没有产生变化";
            case SYSTEM -> "系统执行异常";
        };
    }

    private static String message(Throwable exception) {
        var value = new StringBuilder();
        for (var current = exception; current != null; current = current.getCause())
            if (current.getMessage() != null) value.append(' ').append(current.getMessage());
        return value.toString();
    }

    private static boolean contains(String text, String... values) {
        for (var value : values) if (text.contains(value.toLowerCase())) return true;
        return false;
    }
}

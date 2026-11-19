package com.finflow.studio.assistant;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** The current user's directive selects a stage. Material names and model arguments cannot grant one. */
enum TacitAgentTask {
    ORGANIZE("organize_business", "梳理业务", "(?:梳理|抽取|提取|整理|更新|补充)(?:这批资料中的|当前项目的|材料中的)?(?:隐性知识|业务知识|知识台账|业务台账|业务规则|业务流程与规则|业务口径)", "extract", "save_batch", "finalize"),
    CLARIFY("clarify_gaps", "澄清缺口", "(?:澄清|梳理|整理|更新|补充)(?:知识|台账|业务)缺口", "save_batch", "finalize"),
    FLOW("generate_flow", "绘制流程", "(?:生成|绘制|画出)(?:台账中的|台账的|本次梳理的)?业务流程图", "generate_flow"),
    BUILD("build_ontology", "构建本体", "(?:构建|生成|创建)(?:业务)?本体候选", "build_candidate"),
    CQ("generate_cqs", "生成能力问题", "(?:提炼|生成)(?:业务)?(?:能力问题|cqs?)(?:清单)?", "generate_cqs"),
    VERIFY("validate_ontology", "校核本体", "(?:校核|验证|检查|推演)(?:业务)?本体(?:候选|场景)?", "validate_ontology"),
    DISCUSS("discuss", "讨论台账", "(?!)"),
    OTHER("discuss", "", "(?!)");

    private static final Pattern TOPIC = Pattern.compile("隐性知识|业务知识台账|知识台账|业务台账|业务梳理报告|业务缺口报告|本体候选|能力问题|业务知识抽取|业务流程图|梳理业务规则|抽取业务规则");
    private static final Pattern DISCUSSION = Pattern.compile("先讲|先说明|先解释|只读|只讨论|仅讨论|先讨论|仅供讨论|不要执行|不执行|不要修改|不修改|别写|不写|不要写|不保存|不要保存|别执行|先不要|暂不|先别|如何|怎么做|怎么实现|为什么|可行性|是否|能否|能不能|[?？]|的方案|举例|举个例|假设|只解释|仅解释");
    private static final Pattern INSPECT = Pattern.compile("^(?:请|帮我|麻烦|想|我想|我们|先|能否|能不能|可以|就|一起|仅|只|直接|\\s)*(?:只读|讨论|查看|看看|看一下|核对|解释|了解|查询|说说|告诉|有哪些|什么|怎么|如何|为什么|谈谈)");
    private final String task;
    private final String shortcut;
    private final Pattern directive;
    private final Set<String> actions;

    TacitAgentTask(String task, String shortcut, String directive, String... actions) {
        this.task = task;
        this.shortcut = shortcut;
        this.directive = Pattern.compile("^(?:(?:请|麻烦|帮我|为我|直接|开始|现在|继续|重新|先|把已选材料中的|从已选材料中|根据已选材料)\\s*)*(?:" + directive + ")");
        this.actions = Set.of(actions);
    }

    String task() { return task; }

    static TacitAgentTask from(String userText) {
        var text = Objects.toString(userText, "").strip().toLowerCase(Locale.ROOT);
        for (var stage : values()) {
            if (stage == OTHER) continue;
            if (text.equals("/" + stage.shortcut) || text.startsWith("/" + stage.shortcut + " ")
                    || text.startsWith("/" + stage.shortcut + "\n")) return DISCUSSION.matcher(text).find() ? DISCUSS : stage;
        }
        var firstLine = text.lines().findFirst().orElse("");
        for (var stage : values()) {
            if (stage.directive.matcher(firstLine).find())
                return DISCUSSION.matcher(text).find() ? DISCUSS : stage;
        }
        // A request to produce a presentation about this topic is still an ordinary workbench task.
        return TOPIC.matcher(text).find() && (INSPECT.matcher(text).find() || TOPIC.matcher(text).matches()
                || text.startsWith("不要执行") || text.startsWith("不要修改")) ? DISCUSS : OTHER;
    }

    boolean permits(String tool) {
        if ("tacit.workspace".equals(tool) || "tacit.preview_source".equals(tool)) return true;
        if (tool.startsWith("tacit.")) return actions.contains(tool.substring("tacit.".length()));
        if (this == OTHER) return true;
        return Set.of("assistant.respond", "workspace.inspect").contains(tool);
    }

    static List<java.util.Map<String, Object>> catalog(String goal) {
        var stage = from(goal);
        return AssistantCapabilityRegistry.agentCatalog().stream()
                .filter(capability -> stage.permits(capability.get("id").toString())).toList();
    }
}

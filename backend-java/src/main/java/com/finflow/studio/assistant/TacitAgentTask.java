package com.finflow.studio.assistant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent authority ceiling from the current human directive. Never use model or resource text here. */
enum TacitAgentTask {
    ORGANIZE("organize_business", "梳理业务", "(?:梳理|抽取|提取|整理|更新|补充)(?:这批资料中的|当前项目的|材料中的)?(?:隐性知识|业务知识|知识台账|业务台账|业务规则|业务流程与规则|业务口径)", "extract", "save_batch", "finalize", "align_policies"),
    ALIGN("organize_business", "对齐政策", "(?:对齐|比对|比较|匹配)(?:这些|多个|已选|现有)?(?:政策|制度|业务规则)", "align_policies"),
    CLARIFY("clarify_gaps", "澄清缺口", "(?:澄清|梳理|整理|更新|补充)(?:知识|台账|业务)缺口", "save_batch", "finalize", "align_policies"),
    FLOW("generate_flow", "绘制流程", "(?:生成|绘制|画出|画一张)(?:台账中的|台账的|本次梳理的|当前的)?(?:业务)?流程图", "generate_flow"),
    BUILD("build_ontology", "构建本体", "(?:构建|生成|创建)(?:业务)?本体候选", "build_candidate", "edit_candidates", "check_candidates", "submit_candidates"),
    EDIT("build_ontology", "编辑候选", "(?:修改|编辑|修订|补充)(?:业务)?(?:本体)?候选", "edit_candidates", "check_candidates"),
    CHECK("build_ontology", "检查候选", "(?:检查|校验)(?:业务)?(?:本体)?候选(?:项|清单)?", "check_candidates"),
    SUBMIT("build_ontology", "提交候选", "提交(?:业务)?(?:本体)?候选", "check_candidates", "submit_candidates"),
    CQ("generate_cqs", "生成能力问题", "(?:提炼|生成)(?:业务)?(?:能力问题|cqs?)(?:清单)?", "generate_cqs"),
    SCENARIOS("validate_ontology", "提出验证场景", "(?:生成|提出|拟定|补充)(?:本体)?(?:校核|验证|验收|推演)场景", "propose_scenarios"),
    VERIFY("validate_ontology", "校核本体", "(?:校核|验证|检查|推演)(?:业务)?本体(?:候选|场景)?", "propose_scenarios", "validate_ontology"),
    DISCUSS("discuss", "讨论台账", "(?!)"),
    OTHER("discuss", "", "(?!)");

    private static final Pattern TOPIC = Pattern.compile("隐性知识|业务知识台账|知识台账|业务台账|业务梳理报告|业务缺口|知识缺口|台账缺口|本体候选|本体建模|能力问题|业务知识抽取|业务流程图|业务规则|政策对齐|政策条款|验证场景");
    private static final Map<TacitAgentTask, Pattern> NATURAL = Map.ofEntries(
            Map.entry(ORGANIZE, Pattern.compile("(?:梳理|整理|抽取|提取|归纳).{0,35}(?:台账|隐性知识|业务(?:流程|知识|规则|口径))")),
            Map.entry(CLARIFY, Pattern.compile("(?:澄清|梳理|盘点|整理).{0,20}(?:缺口|待确认项)")),
            Map.entry(FLOW, Pattern.compile("(?:画|绘制|绘成|生成|转成|整理成).{0,24}流程图")),
            Map.entry(BUILD, Pattern.compile("(?:构建|创建|生成|整理成).{0,24}本体候选")),
            Map.entry(ALIGN, Pattern.compile("(?:对齐|比对).{0,30}(?:政策|制度|条款)")),
            Map.entry(CQ, Pattern.compile("(?:提炼|生成|列出).{0,20}(?:能力问题|cqs?)")),
            Map.entry(VERIFY, Pattern.compile("(?:校核|推演|验证).{0,20}本体候选")),
            Map.entry(SCENARIOS, Pattern.compile("(?:提出|生成|拟定|补充).{0,24}(?:校核|验证|验收|推演)场景")));
    private static final Pattern DIRECT_REQUEST = Pattern.compile("^(?:请|帮我|麻烦|直接|先|现在|继续|开始|重新|为我|\\s)*(?:把|将|根据|基于|依据|从|画|绘制|生成|构建|创建|抽取|提取|归纳|整理|梳理|澄清|盘点|对齐|比对|提炼|列出|校核|验证|推演|提出|拟定|补充)");
    private static final Pattern DISCUSSION = Pattern.compile("计划如何|拟议|能不能|能否|可不可以|说说|聊聊|例句|比如|例如|解释|介绍|先讲|先说明|只读|讨论|不(?:要|用|必|能|执行|修改|写|保存|提交|生成|着急)|别(?:执行|修改|写|保存|提交|生成)|先别|暂缓|暂停|暂不|如何|怎么|为什么|可行性|是否|能否|能不能|[?？]|的方案|举例|举个例|假设|如果|内容为|的意思|只解释|仅解释|稍后再");
    private static final Pattern INSPECT = Pattern.compile("^(?:请|帮我|麻烦|想|我想|我们|先|可以|就|一起|仅|只|直接|\\s)*(?:只读|讨论|查看|看看|看一下|核对|解释|了解|查询|搜索|检索|说说|告诉|有哪些|什么|怎么|如何|为什么|谈谈)");
    private static final Pattern GENERAL = Pattern.compile("(?:做成|制成|写成|输出为|导出为).{0,15}(?:ppt|幻灯片|文稿)|^(?:请|帮我|麻烦|直接|先|\\s)*(?:制作|创建|新建|生成|导出|撰写|写|打开|删除|移动|重命名).{0,80}(?:ppt|演示文稿|幻灯片|工作流|文件夹|目录|数据集)|^(?:请|帮我|麻烦|先)*(?:打开|新建|删除|创建|重命名)(?:一个|这个)?项目");
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

    private static final Pattern QUOTED = Pattern.compile("《[^》]*》|「[^」]*」|“[^”]*”|\"[^\"]*\"|‘[^’]*’|\u0060[^\u0060]*\u0060");
    private static final Pattern SOURCE_FILE = Pattern.compile("(?i)[^\\s，。；;：:《》]{1,250}\\.(?:pdf|docx?|xlsx?|pptx?|txt|md|csv)");
    private static final Pattern COMPLETED_MODIFIER = Pattern.compile("(?:已经|已)(?:梳理|整理|抽取|生成|构建|验证|审核|校核)(?:完毕|完成|过|完)?的");

    static String directiveText(String text) {
        var prose = QUOTED.matcher(Objects.toString(text, "")).replaceAll(" ");
        if (prose.indexOf('.') >= 0) prose = SOURCE_FILE.matcher(prose).replaceAll(" ");
        return prose.strip()
                .split("(?:附件(?:名|内容)?|材料(?:名|内容)|原文|引用|示例|例子|举例)[：:]|[`「“《\"]|(?m)^\\s*>", 2)[0].strip();
    }

    static String firstDirective(String text) {
        return directiveText(Objects.toString(text, "").strip().lines().findFirst().orElse(""));
    }

    static boolean mayConcernLedger(String goal, String page) {
        var first = firstDirective(goal).toLowerCase(Locale.ROOT);
        if (GENERAL.matcher(first).find()) return false;
        return from(goal) != OTHER || TOPIC.matcher(first).find() || ("knowledge-extraction".equals(page)
                && (first.matches(".*(?:台账|业务梳理|本体|业务流程|候选|政策|制度|场景|能力问题|知识抽取|已选材料).*")
                    || continuation(first)));
    }

    static boolean continuation(String goal) {
        return Objects.toString(goal, "").strip().matches("(?:请|帮我|麻烦)?(?:继续|下一步|开始|处理一下|执行|按上一轮继续)(?:吧|。)?");
    }

    static TacitAgentTask from(String userText) {
        var text = directiveText(userText).toLowerCase(Locale.ROOT);
        var first = text.lines().findFirst().orElse("");
        if (GENERAL.matcher(first).find()) return OTHER;
        var direct = Arrays.stream(values()).filter(t -> t != OTHER && t.isShortcut(first)).findFirst();
        if (direct.isPresent()) return DISCUSSION.matcher(text).find() ? DISCUSS : direct.get();
        boolean topic = TOPIC.matcher(first).find();
        if (topic && (INSPECT.matcher(first).find() || DISCUSSION.matcher(text).find())) return DISCUSS;
        if (!DIRECT_REQUEST.matcher(first).find()
                && Arrays.stream(values()).noneMatch(t -> t.directive.matcher(first).find())) return OTHER;
        var matches = EnumSet.noneOf(TacitAgentTask.class);
        for (var clause : text.split("[，,。；;\\r\\n]|然后|接着|并且|再帮我|并(?=提交|生成|绘制|构建|创建|整理)")) {
            var part = COMPLETED_MODIFIER.matcher(clause).replaceAll("").strip();
            var exact = Arrays.stream(values()).filter(t -> t.directive.matcher(part).find()).findFirst();
            if (exact.isPresent()) matches.add(exact.get());
            else if (DIRECT_REQUEST.matcher(part).find())
                NATURAL.forEach((stage, pattern) -> { if (pattern.matcher(part).find()) matches.add(stage); });
        }
        if (matches.size() != 1) return OTHER;
        return DISCUSSION.matcher(text).find() ? DISCUSS : matches.iterator().next();
    }

    boolean isShortcut(String text) {
        var directive = firstDirective(text);
        return !shortcut.isEmpty() && (directive.equals("/" + shortcut) || directive.startsWith("/" + shortcut + " "));
    }

    static TacitAgentTask stored(String value, String originalGoal) {
        if (value == null) return from(originalGoal);
        return Arrays.stream(values()).filter(task -> task.name().equals(value)).findFirst().orElse(DISCUSS);
    }

    boolean permits(String tool) {
        if ("tacit.workspace".equals(tool) || "tacit.preview_source".equals(tool) || "tacit.search_context".equals(tool) || "tacit.read_fields".equals(tool) || "tacit.read_clauses".equals(tool)) return true;
        if (tool.startsWith("tacit.")) return actions.contains(tool.substring("tacit.".length()));
        if (this == OTHER) return true;
        return Set.of("assistant.respond", "workspace.inspect").contains(tool);
    }

    List<Map<String, Object>> catalog() {
        return AssistantCapabilityRegistry.agentCatalog().stream().filter(capability -> permits(capability.get("id").toString())).toList();
    }
}

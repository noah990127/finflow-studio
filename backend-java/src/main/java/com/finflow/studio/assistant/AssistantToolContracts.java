package com.finflow.studio.assistant;

import com.finflow.studio.workflow.WorkflowModels.NodeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AssistantToolContracts {
    private static final Set<String> IDENTIFIERS = Set.of(
            "project_id", "resource_id", "folder_id", "parent_id", "target_parent_id", "target_folder_id",
            "target_id", "workflow_id", "node_id", "source_node_id", "target_node_id", "deliverable_id",
            "dataset_id", "source_id", "citation_id", "connection_id");
    private static final Set<String> ARRAYS = Set.of("resource_ids", "pages", "rows", "citations", "sources", "output_formats", "range");
    private static final Set<String> OBJECTS = Set.of("patch", "config", "position", "parameters", "schema",
            "connection", "parser_options", "workflow_spec");
    private static final Set<String> INTEGERS = Set.of("limit", "max_sources", "expected_version");
    private static final Set<String> BOOLEANS = Set.of("include_analysis", "include_citations", "hand_drawn");

    private AssistantToolContracts() { }

    static Map<String, Object> inputSchema(String tool, List<String> arguments) {
        var properties = new LinkedHashMap<String, Object>();
        for (var argument : arguments) properties.put(argument, property(tool, argument));
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required(tool));
        schema.put("additionalProperties", false);
        if ("tacit.edit_candidates".equals(tool)) schema.put("anyOf", List.of(Map.of("required", List.of("patches")),
                Map.of("required", List.of("extensions")), Map.of("required", List.of("remove_ids"))));
        if ("tacit.save_batch".equals(tool)) schema.put("anyOf", List.of(Map.of("required", List.of("items")), Map.of("required", List.of("scope"))));
        if ("browser.open".equals(tool)) schema.put("anyOf", List.of(Map.of("required", List.of("url")), Map.of("required", List.of("site"))));
        if ("resource.add".equals(tool)) schema.put("anyOf", List.of(Map.of("required", List.of("url")), Map.of("required", List.of("file_name", "content"))));
        if ("source.add_verified".equals(tool)) schema.put("anyOf", List.of(Map.of("required", List.of("url")), Map.of("required", List.of("sources"))));
        if ("knowledge.read".equals(tool)) schema.put("anyOf", List.of(Map.of("required", List.of("citation_id")), Map.of("required", List.of("resource_id"))));
        return Map.copyOf(schema);
    }

    static Map<String, Object> outputSchema(String tool) {
        if (tool.startsWith("workflow.")) return object(Map.of(
                "workflowId", string("真实工作流 ID"),
                "workflow", object(Map.of()),
                "changed", bool("是否发生真实修改")));
        if (tool.startsWith("deliverable.")) return object(Map.of(
                "deliverableId", string("真实交付件 ID"),
                "version", integer("已保存版本", 1),
                "downloadUrl", string("可访问的下载地址"),
                "refIds", array(string("引用 ID"))));
        if (tool.startsWith("dataset.")) return object(Map.of(
                "datasetId", string("真实数据集 ID"), "provenance", object(Map.of())));
        return object(Map.of());
    }

    static void validate(String tool, Map<String, Object> arguments) {
        var capability = AssistantCapabilityRegistry.find(tool)
                .orElseThrow(() -> new IllegalArgumentException("参数错误：工具 " + tool + " 未注册，请重新搜索工具"));
        validateObject(tool, arguments == null ? Map.of() : arguments, capability.inputSchema(), "参数");
        if (arguments == null) return;
        if ("tacit.save_batch".equals(tool) && ("scope".equals(arguments.get("section"))
                ? !arguments.containsKey("scope") || arguments.containsKey("items")
                : !arguments.containsKey("items") || arguments.containsKey("scope")))
            throw new IllegalArgumentException("范围节须单独提供 scope，条目节须单独提供 items");
        if ("browser.open".equals(tool) && arguments.containsKey("site") && arguments.containsKey("url"))
            throw new IllegalArgumentException("参数错误：site与url只需提供一个");
        if ("browser.act".equals(tool) && Set.of("fill", "select", "assert_text").contains(arguments.get("action"))) {
            if (!arguments.containsKey("value") || ("assert_text".equals(arguments.get("action")) && blank(arguments.get("value"))))
                throw new IllegalArgumentException("参数错误：填写、选择、校验需要value，校验内容不能为空");
        }
        if ("resource.prepare_delete".equals(tool) && "selected".equalsIgnoreCase(Objects.toString(arguments.get("scope"), ""))
                && (!(arguments.get("resource_ids") instanceof List<?> ids) || ids.isEmpty()))
            throw new IllegalArgumentException("参数错误：selected范围需要非空resource_ids");
        if ("source.add_verified".equals(tool)) {
            int count = arguments.get("sources") instanceof List<?> sources ? sources.size() : 0;
            if (arguments.containsKey("url")) count++;
            if (count > 8) throw new IllegalArgumentException("参数错误：一次最多验证8个资料地址（含url和sources）");
        }
        if ("browser.explore".equals(tool) && arguments.get("parameters") instanceof Map<?, ?> parameters) {
            if (parameters.size() > 30 || parameters.values().stream().anyMatch(v -> !(v instanceof String)))
                throw new IllegalArgumentException("参数错误：浏览器parameters最多30项，值必须为字符串");
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateObject(String tool, Map<String, Object> value, Map<String, Object> schema, String path) {
        var properties = schema.get("properties") instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.<String, Object>of();
        var required = schema.get("required") instanceof List<?> raw ? raw.stream().map(String::valueOf).toList() : List.<String>of();
        var missing = required.stream().filter(key -> !value.containsKey(key) || blank(value.get(key))).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 缺少必填字段 " + missing
                + "；请按 describe_tool 返回的 inputSchema 补全后重试");
        if (schema.get("anyOf") instanceof List<?> alternatives && alternatives.stream().noneMatch(option ->
                option instanceof Map<?, ?> branch && branch.get("required") instanceof List<?> keys
                        && keys.stream().allMatch(key -> value.containsKey(key) && !blank(value.get(key)))))
            throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 至少提供一组完整参数 " + alternatives);
        var allowAdditional = Boolean.TRUE.equals(schema.get("additionalProperties")) || schema.get("additionalProperties") instanceof Map<?, ?>;
        var unknown = allowAdditional ? List.<String>of()
                : value.keySet().stream().filter(key -> !properties.containsKey(key)).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不支持字段 " + unknown
                + "；允许字段为 " + properties.keySet());
        for (var entry : value.entrySet()) {
            var property = properties.get(entry.getKey());
            if (property == null && schema.get("additionalProperties") instanceof Map<?, ?> extra) property = extra;
            if (property instanceof Map<?, ?> raw) validateValue(tool, entry.getValue(), (Map<String, Object>) raw,
                    path + "." + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateValue(String tool, Object value, Map<String, Object> schema, String path) {
        if (value == null) throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不能为null；可选字段请省略");
        var type = Objects.toString(schema.get("type"), "string");
        boolean valid = switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 必须是 " + type
                + "，当前收到 " + value.getClass().getSimpleName());
        if (value instanceof String text) {
            if (schema.get("minLength") instanceof Number minimum && text.trim().length() < minimum.intValue())
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不能为空");
            if (schema.get("maxLength") instanceof Number maximum && text.codePointCount(0, text.length()) > maximum.intValue())
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 最多" + maximum + "字符");
            if (schema.get("pattern") instanceof String pattern && !text.matches(pattern))
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 格式不正确");
            if ("uri".equals(schema.get("format"))) {
                try {
                    var uri = java.net.URI.create(text);
                    if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null || uri.getUserInfo() != null)
                        throw new IllegalArgumentException();
                } catch (IllegalArgumentException error) { throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 必须是无凭据的HTTP/HTTPS网址"); }
            }
            if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(item -> String.valueOf(item).equalsIgnoreCase(text)))
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 只支持 " + values + "，当前为 " + text);
        }
        if (value instanceof Number number && schema.get("minimum") instanceof Number minimum
                && number.doubleValue() < minimum.doubleValue())
            throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不能小于 " + minimum);
        if (value instanceof Number number && schema.get("maximum") instanceof Number maximum && number.doubleValue() > maximum.doubleValue())
            throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 不能大于 " + maximum);
        if (value instanceof List<?> values) {
            if (schema.get("minItems") instanceof Number minimum && values.size() < minimum.intValue())
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 数量不能小于 " + minimum);
            if (schema.get("maxItems") instanceof Number maximum && values.size() > maximum.intValue())
                throw new IllegalArgumentException("参数错误：" + tool + " 的 " + path + " 数量不能大于 " + maximum);
        }
        if (value instanceof List<?> values && schema.get("items") instanceof Map<?, ?> itemSchema)
            for (int index = 0; index < values.size(); index++)
                validateValue(tool, values.get(index), (Map<String, Object>) itemSchema, path + "[" + index + "]");
        if (value instanceof Map<?, ?> values && schema.containsKey("properties"))
            validateObject(tool, values.entrySet().stream().collect(LinkedHashMap::new,
                    (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()), Map::putAll), schema, path);
    }

    private static boolean blank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static List<String> required(String tool) {
        return switch (tool) {
            case "tacit.preview_source", "tacit.extract" -> List.of("expected_revision", "source_id", "offset");
            case "tacit.read_clauses" -> List.of("expected_revision", "source_id");
            case "tacit.read_fields" -> List.of("expected_revision", "table_id");
            case "tacit.search_context" -> List.of("expected_revision", "query");
            case "tacit.align_policies", "tacit.edit_candidates", "tacit.check_candidates", "tacit.submit_candidates",
                    "tacit.propose_scenarios" -> List.of("expected_revision");
            case "tacit.save_batch" -> List.of("expected_revision", "section");
            case "tacit.finalize" -> List.of("expected_revision", "completion");
            case "tacit.generate_flow", "tacit.build_candidate", "tacit.generate_cqs", "tacit.validate_ontology" -> List.of("expected_revision");
            case "browser.open" -> List.of();
            case "browser.observe", "browser.close", "browser.login", "browser.resume" -> List.of("session");
            case "browser.act" -> List.of("session", "action", "target");
            case "browser.commit" -> List.of("session", "pending");
            case "work.publish_implementation" -> List.of("implementation", "expected_version");
            case "browser.inspect" -> List.of("site");
            case "browser.explore" -> List.of("site", "goal", "parameters");
            case "work.update" -> List.of("stages");
            case "workspace.select" -> List.of("target_id");
            case "project.open", "project.delete" -> List.of("project_id");
            case "project.rename" -> List.of("project_id", "new_name");
            case "folder.create" -> List.of("project_id", "name");
            case "folder.rename" -> List.of("project_id", "folder_id", "new_name");
            case "folder.move", "folder.delete" -> List.of("project_id", "folder_id");
            case "resource.upload" -> List.of("project_id", "file_name", "content");
            case "resource.add" -> List.of("project_id");
            case "source.verify" -> List.of("url");
            case "source.add_verified" -> List.of("project_id");
            case "resource.open", "resource.read", "resource.delete" -> List.of("project_id", "resource_id");
            case "resource.prepare_delete" -> List.of("project_id", "scope");
            case "resource.delete_batch" -> List.of("project_id", "items");
            case "resource.edit" -> List.of("project_id", "resource_id", "patch");
            case "resource.rename" -> List.of("project_id", "resource_id", "new_name");
            case "resource.move" -> List.of("project_id", "resource_id");
            case "knowledge.discover_external_sources" -> List.of("topic");
            case "knowledge.search" -> List.of("project_id", "query");
            case "knowledge.parse" -> List.of("resource_id");
            case "knowledge.extract_table" -> List.of("project_id", "resource_id");
            case "dataset.add_source" -> List.of("project_id", "name", "connection");
            case "dataset.connect" -> List.of("source_id");
            case "dataset.import" -> List.of("project_id", "source_id");
            case "dataset.extract" -> List.of("project_id", "resource_id");
            case "dataset.query" -> List.of("project_id", "dataset_id");
            case "dataset.create" -> List.of("project_id", "name", "rows");
            case "dataset.transform" -> List.of("project_id", "dataset_id", "requirements");
            case "dataset.open", "dataset.delete" -> List.of("project_id", "dataset_id");
            case "dataset.profile" -> List.of("project_id", "resource_id");
            case "workflow.prepare" -> List.of("project_id", "goal");
            case "workflow.open" -> List.of("project_id");
            case "workflow.edit" -> List.of("workflow_id", "patch");
            case "workflow.duplicate" -> List.of("project_id", "workflow_id", "name");
            case "workflow.add_node" -> List.of("workflow_id", "node_type", "config");
            case "workflow.remove_node" -> List.of("workflow_id", "node_id");
            case "workflow.connect" -> List.of("workflow_id", "source_node_id", "target_node_id");
            case "workflow.run", "workflow.save_version" -> List.of("workflow_id");
            case "workflow.delete" -> List.of("project_id", "workflow_id");
            case "workflow.add_selected_resource", "workflow.add_data_transform" -> List.of("project_id", "resource_id");
            case "workflow.add_outputs" -> List.of("project_id", "output_formats");
            case "deliverable.create" -> List.of("project_id", "format");
            case "deliverable.open", "deliverable.export", "deliverable.delete" -> List.of("deliverable_id");
            case "deliverable.edit" -> List.of("deliverable_id", "patch");
            default -> List.of();
        };
    }

    private static Map<String, Object> property(String tool, String name) {
        if (tool.startsWith("tacit.")) {
            if ("base_children_digest".equals(name)) return Map.of("type","object","properties",Map.of(),
                    "additionalProperties", string("最近一次完整读取末页返回的 children_digest"),
                    "description","以 table_id 或 source_id 为键，仅在替换完整字段或条款时使用。");
            if ("page_size".equals(name)) return Map.of("type", "integer", "minimum", 1, "maximum", 6);
            if ("limit".equals(name)) return Map.of("type", "integer", "minimum", 1,
                    "maximum", "tacit.read_fields".equals(tool) ? 160 : "tacit.read_clauses".equals(tool) ? 20 : 100);
            if ("section".equals(name) && "tacit.search_context".equals(tool))
                return enumeration("检索范围", List.of("all", "assets", "cqs", "terminology", "ontology", "contract", "decisions"));
            if ("remove_ids".equals(name)) return boundedArray(string("候选的稳定标识"), 1, 20);
            if ("patches".equals(name) || "extensions".equals(name)) {
                var fields = new LinkedHashMap<String,Object>();
                for (var key : List.of("name","definition")) fields.put(key, string(key));
                fields.put("evidence", boundedArray(object(Map.of()), 0, 100));
                boolean patch = "patches".equals(name);
                var required = patch ? List.of("candidate_id") : List.of("kind","name","process_id","key","definition","evidence");
                for (var key : required) fields.putIfAbsent(key, string(key));
                return boundedArray(Map.of("type","object","properties",fields,"required",required,"additionalProperties",false), 1, 20);
            }
            if ("expected_revision".equals(name)) return integer("最近一次实际查看的台账版本", 0);
            if ("offset".equals(name)) return Map.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE,
                    "description", "首次为 0，后续使用上页返回的 next_offset");
            if ("items".equals(name)) return boundedArray(object(Map.of()), 1, 5);
            if ("scope".equals(name)) return object(Map.of());
            if ("completion".equals(name)) return enumeration("报告状态", List.of("draft", "complete"));
            if ("section".equals(name)) {
                if ("tacit.save_batch".equals(tool)) return enumeration("待保存节", TacitAgentTools.SECTIONS);
                var options = new java.util.ArrayList<>(TacitAgentTools.SECTIONS);
                options.addAll(TacitAgentTools.ARTIFACTS);
                options.addAll(List.of("catalog", "issues"));
                return enumeration("需读取的节或产物", options);
            }
        }
        if (tool.startsWith("browser.")) {
            if ("human_login".equals(name)) return Map.of("type", "boolean", "default", true, "description", "默认先进入人工接管再加载网站；仅公开网页无需登录时传false。复用连接时不会重置页面或登录状态。");
            if ("frame".equals(name)) return Map.of("type", "integer", "minimum", 0, "maximum", 20);
            if (Set.of("session", "pending").contains(name)) return Map.of("type", "string", "minLength", 1, "maxLength", 128);
            if ("value".equals(name)) return Map.of("type", "string", "maxLength", 10000);
            if ("action".equals(name)) return enumeration("页面操作", List.of("fill", "select", "click", "assert_text"));
            if ("target".equals(name)) return Map.of("type", "object", "properties", Map.of(
                    "by", enumeration("定位方式；ref使用当前观察结果editors中的编号，并传对应frame，不能猜测或保存到固定脚本", List.of("label", "role", "text", "ref")),
                    "name", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                    "role", enumeration("控件角色", List.of("button", "link", "heading", "textbox", "combobox", "status", "checkbox", "radio", "tab", "menuitem", "option"))),
                    "required", List.of("by", "name"), "additionalProperties", false);
        }
        if ("knowledge.discover_external_sources".equals(tool)) {
            if ("topic".equals(name)) return Map.of("type","string","minLength",1,"maxLength",500);
            if ("max_sources".equals(name)) return Map.of("type","integer","minimum",3,"maximum",20);
        }
        if ("knowledge.search".equals(tool) && "limit".equals(name)) return Map.of("type","integer","minimum",1,"maximum",20);
        if (tool.startsWith("browser.") && "site".equals(name)) return Map.of("type","string","pattern","[a-zA-Z0-9_-]{1,64}");
        if ("browser.explore".equals(tool) && "goal".equals(name)) return Map.of("type","string","minLength",1,"maxLength",4000);
        if ("browser.explore".equals(tool) && "parameters".equals(name)) return Map.of("type","object","properties",Map.of(),
                "additionalProperties",Map.of("type","string"));
        if ("dataset.add_source".equals(tool) && "connection".equals(name)) return Map.of("type","object","properties",Map.of(
                "jdbc_url",string("数据库JDBC地址"), "url",string("数据库或API地址"),
                "source_type",property(tool,"source_type"), "username",Map.of("type","string"),
                "secret_ref",Map.of("type","string"), "options",Map.of("type","object","properties",Map.of(),"additionalProperties",Map.of("type","string"))),
                "anyOf",List.of(Map.of("required",List.of("jdbc_url")),Map.of("required",List.of("url"))),"additionalProperties",false);
        if ("work.publish_implementation".equals(tool) && "expected_version".equals(name)) return integer("当前实现版本，首版为0", 0);
        if ("work.publish_implementation".equals(tool) && "implementation".equals(name)) {
            var input = Map.<String, Object>of("type", "object", "properties", Map.of("alias", string("输入别名"),
                    "resourceId", string("项目资源ID"), "stepId", string("前序工序ID"), "previousOutput", string("上轮产物工序ID")),
                    "required", List.of("alias"), "additionalProperties", false);
            var step = Map.<String, Object>of("type", "object", "properties", Map.of("id", string("稳定工序ID"), "title", string("工序名称"),
                    "kind", enumeration("固定执行类型", List.of("SQL", "PROMPT", "TOOL")), "code", string("完整固定SQL、系统提示词或工具JSON：{tool,version:1,arguments}。工具支持research.discover(topic,maxSources)、research.fetch(url)、research.collect(sources,maxSources)、document.render(format,pptSkill,document)、browser.run(script,parameters)。浏览器script为固定对象{version:1,site,steps:[{action,target:{by,name,role},value}]}；parameters可用{$input:别名,as:json}绑定参数文件。"),
                    "outputName", string("产物文件名"), "inputs", array(input)), "required", List.of("id", "title", "kind", "code", "outputName", "inputs"), "additionalProperties", false);
            var outcome = Map.<String, Object>of("type", "object", "properties", Map.of(
                    "stepId", string("本业务工序内产生此成果的技术步骤ID"),
                    "role", enumeration("成果角色", List.of("PRIMARY", "SUPPORTING", "INTERNAL"))),
                    "required", List.of("stepId", "role"), "additionalProperties", false);
            var businessStage = Map.<String, Object>of("type", "object", "properties", Map.of(
                    "id", string("稳定业务工序ID"), "title", string("用户工作的阶段名称，不是工具名称"),
                    "goal", string("这一阶段要完成什么"), "acceptance", string("用户需要检查的成果要点，不代表已经人工验收"),
                    "stepIds", array(string("按顺序归属此工序的技术步骤ID")), "outcomes", array(outcome)),
                    "required", List.of("id", "title", "goal", "acceptance", "stepIds", "outcomes"), "additionalProperties", false);
            return Map.of("type", "object", "properties", Map.of("title", string("工作名称"), "steps", array(step),
                    "businessStages", array(businessStage)), "required", List.of("title", "steps", "businessStages"), "additionalProperties", false);
        }
        if ("resource.prepare_delete".equals(tool) && "scope".equals(name))
            return enumeration("明确删除范围", List.of("selected", "project_contents"));
        if ("resource.delete_batch".equals(tool) && "items".equals(name))
            return boundedArray(Map.of("type", "object", "properties", Map.of(
                    "resource_id", string("真实ID"), "name", string("已读取名称"), "type", string("已读取类型"),
                    "version", integer("已读取版本", 1), "revision", string("已读取更新时间")),
                    "required", List.of("resource_id", "name", "type", "version", "revision"), "additionalProperties", false), 1, 500);
        if ("stages".equals(name)) {
            var ref = Map.<String, Object>of("type", "object", "properties", Map.of("resourceId", string("真实资源ID"), "version", integer("资源版本", 1), "role", enumeration("成果角色", List.of("PRIMARY", "SUPPORTING", "INTERNAL"))),
                    "required", List.of("resourceId", "version"), "additionalProperties", false);
            return array(Map.of("type", "object", "properties", Map.of(
                    "id", string("工序稳定标识"), "title", string("业务工序名称"),
                    "status", enumeration("工序状态", List.of("PENDING", "RUNNING", "WAITING_INPUT", "SUCCEEDED", "FAILED", "STALE")),
                    "summary", Map.of("type", "string", "description", "可检查的结果摘要，未产出时可为空"),
                    "question", Map.of("type", "string", "description", "需要人的具体输入，否则为空"),
                    "inputs", array(ref), "outputs", array(ref)), "required", List.of("id", "title", "status", "inputs", "outputs"), "additionalProperties", false));
        }
        if (IDENTIFIERS.contains(name)) return string("平台返回的真实内部 ID，不要使用名称或自行编造");
        if (INTEGERS.contains(name)) return integer(name.equals("expected_version") ? "最近读取到的版本号" : "正整数", name.equals("expected_version") ? 1 : 1);
        if (BOOLEANS.contains(name)) return bool("布尔开关");
        if (ARRAYS.contains(name)) {
            var item = switch (name) {
                case "pages" -> integer("页码", 1);
                case "rows", "citations" -> object(Map.of());
                case "sources" -> strictObject(Map.of(
                        "url", Map.of("type", "string", "format", "uri", "minLength", 1,
                                "description", "待验证的 HTTP 或 HTTPS 地址"),
                        "name", string("来源显示名称")), List.of("url"));
                case "output_formats" -> enumeration("输出格式", List.of("PPTX", "HTML_SLIDES", "DOCX", "PDF", "FINANCIAL_REPORT", "MERMAID", "EXCALIDRAW"));
                default -> string("列表项");
            };
            if ("sources".equals(name)) return boundedArray(item, 1, 8);
            return array(item);
        }
        if (OBJECTS.contains(name)) {
            if ("workflow_spec".equals(name)) return workflowSpec();
            if ("position".equals(name)) return object(Map.of("x", number("横坐标"), "y", number("纵坐标")));
            if ("patch".equals(name)) return "workflow.edit".equals(tool) ? workflowPatch()
                    : string("完整的新内容；长文本不会被截断");
            if ("config".equals(name) && "workflow.add_node".equals(tool)) return workflowConfig();
            return object(Map.of());
        }
        return switch (name) {
            case "name" -> string("简短业务名称，建议 6-20 个中文字；不要包含执行指令、步骤说明或内部 ID");
            case "format" -> enumeration("输出格式", List.of("PPTX", "HTML_SLIDES", "DOCX", "PDF", "FINANCIAL_REPORT", "MERMAID", "EXCALIDRAW"));
            case "node_type" -> enumeration("工作流节点类型",
                    java.util.Arrays.stream(NodeType.values()).map(Enum::name).toList());
            case "group", "target_group" -> enumeration("工作区分组", List.of("FILES", "DATA", "KNOWLEDGE", "OUTPUT", "WORKFLOW"));
            case "source_type" -> enumeration("数据源类型", List.of("POSTGRESQL", "MYSQL", "OPENGAUSS", "GAUSS_DWS", "DUCKDB", "HTTP_API"));
            case "target" -> enumeration("导航目标", List.of("HOME", "PROJECT", "DATA", "WORKFLOW", "RESOURCE"));
            case "target_type" -> enumeration("选择对象类型", List.of("PROJECT", "FOLDER", "RESOURCE", "WORKFLOW", "DATASET", "DELIVERABLE"));
            case "url" -> Map.of("type", "string", "format", "uri", "minLength", 1, "description", "HTTP 或 HTTPS 地址");
            default -> string("工具参数 " + name);
        };
    }

    private static Map<String, Object> workflowPatch() {
        var node = object(Map.of(
                "id", string("稳定节点 ID"),
                "type", property("workflow.add_node", "node_type"),
                "name", string("节点显示名称"),
                "config", workflowConfig(),
                "position", property("workflow.add_node", "position")));
        var edge = object(Map.of("id", string("稳定连线 ID"), "source", string("起点节点 ID"), "target", string("终点节点 ID")));
        return object(Map.of(
                "name", string("工作流名称"), "description", string("工作流说明"),
                "nodes", array(node), "edges", array(edge),
                "node_id", string("单节点编辑时的节点 ID"), "config", workflowConfig()));
    }

    private static Map<String, Object> workflowConfig() {
        var properties = new LinkedHashMap<String, Object>();
        for (var key : List.of("resourceId", "url", "extractJobId", "connectionId", "sql", "requirements",
                "script", "outputName", "query", "prompt", "instruction", "instructions", "generationPrompt",
                "title", "body", "capability", "workflowId", "outputResourceId", "pptSkill")) {
            properties.put(key, string("节点配置 " + key));
        }
        properties.put("format", property("deliverable.create", "format"));
        properties.put("includeCitations", bool("是否保留引用"));
        properties.put("includeWeb", bool("是否联网查找"));
        properties.put("transparent", bool("是否展示处理过程"));
        properties.put("externalResearch", enumeration("外部资料策略", List.of("ON", "OFF")));
        properties.put("handDrawn", bool("是否使用手绘图表风格"));
        properties.put("citationStyle", enumeration("引用格式", List.of("IEEE", "APA_7", "GB_T_7714")));
        properties.put("limit", integer("最多采用的候选资料数", 1));
        properties.put("maxPoints", integer("最大要点数", 1));
        return object(properties);
    }

    private static Map<String, Object> workflowSpec() {
        var node = strictObject(Map.of(
                "key", string("本次工作流内稳定且唯一的业务节点标识，例如 research、analysis、deck"),
                "type", enumeration("节点类型", List.of(
                        "REF_SEARCH", "DATA_TRANSFORM", "AI_ANALYSIS", "AGENT_TASK", "REVIEW", "DELIVERABLE")),
                "name", string("业务用户可理解的节点名称"),
                "purpose", string("该节点解决什么问题、向下游交付什么"),
                "config", workflowConfig(),
                "depends_on", array(string("上游节点 key；$inputs 表示当前项目的全部可用输入"))),
                List.of("key", "type", "name", "purpose", "config", "depends_on"));
        return strictObject(Map.of(
                "version", enumeration("工作流规划协议版本", List.of("1.0")),
                "name", string("简短业务工作流名称"),
                "description", string("工作流解决的问题和最终产出"),
                "nodes", array(node)),
                List.of("version", "name", "description", "nodes"));
    }

    private static Map<String, Object> strictObject(Map<String, Object> properties, List<String> required) {
        var value = new LinkedHashMap<String, Object>();
        value.put("type", "object");
        value.put("properties", properties);
        value.put("required", required);
        value.put("additionalProperties", false);
        return Map.copyOf(value);
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        var value = new LinkedHashMap<String, Object>();
        value.put("type", "object");
        value.put("properties", properties);
        value.put("additionalProperties", properties.isEmpty());
        return Map.copyOf(value);
    }

    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    private static Map<String, Object> boundedArray(Map<String, Object> items, int min, int max) { return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max); }
    private static Map<String, Object> string(String description) { return Map.of("type", "string", "minLength", 1, "description", description); }
    private static Map<String, Object> integer(String description, int minimum) { return Map.of("type", "integer", "minimum", minimum, "description", description); }
    private static Map<String, Object> number(String description) { return Map.of("type", "number", "description", description); }
    private static Map<String, Object> bool(String description) { return Map.of("type", "boolean", "description", description); }
    private static Map<String, Object> enumeration(String description, List<String> values) {
        return Map.of("type", "string", "enum", values, "description", description);
    }
}

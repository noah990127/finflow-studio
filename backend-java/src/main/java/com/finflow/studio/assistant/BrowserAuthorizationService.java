package com.finflow.studio.assistant;

import com.finflow.studio.auth.ActorContext;
import com.finflow.studio.worker.WorkerClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

@Service
public class BrowserAuthorizationService {
    private final JdbcClient jdbc;
    private final WorkerClient worker;
    private final ObjectMapper json;
    private final ThreadLocal<String> executingGrant = new ThreadLocal<>();

    public BrowserAuthorizationService(JdbcClient jdbc, WorkerClient worker, ObjectMapper json) {
        this.jdbc = jdbc; this.worker = worker; this.json = json;
    }

    public record Scope(String kind, String key, String label) { }
    public record Grant(String id, String kind, String label, String status, Instant createdAt, Instant expiresAt) { }
    public record Audit(String id, String grantId, String stepId, String event, String label, Instant createdAt) { }

    public boolean handles(String tool) { return Set.of("browser.open", "browser.commit", "browser.delegate").contains(tool); }

    static String approvalDescription(Map<String, Object> preview) {
        var action = Objects.toString(preview.get("action"), "");
        var actionLabel = switch (action) {
            case "click" -> "点击";
            case "fill" -> "填写";
            case "select" -> "选择";
            case "assert_text" -> "核验";
            default -> action;
        };
        var target = preview.get("target") instanceof Map<?, ?> values ? values : Map.of();
        // Page snapshots and diagnostic history are not approval descriptions.
        // Full action/page binding remains in the runner's pending action and scopeHash.
        var text = "请确认本次网页操作：\n网站：" + approvalExcerpt(preview.get("site"), 180)
                + "\n操作：" + approvalExcerpt(actionLabel, 40)
                + "\n目标：" + approvalExcerpt(target.get("name"), 180);
        if (preview.get("frame") instanceof Number frame) text += "\n页面框架：" + frame.intValue();
        if (preview.get("value") instanceof String value && !value.isEmpty())
            text += "\n内容摘要：" + approvalExcerpt(value, 400);
        return text + "\n请先在原浏览器窗口核对完整表单及操作目标，再确认执行；此处仅展示摘要。";
    }

    private static String approvalExcerpt(Object value, int limit) {
        var text = Objects.toString(value, "未提供");
        if (text.length() <= limit) return text;
        int end = limit - 7;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…（已缩略）";
    }

    public String entryUrl(Map<String, Object> args) {
        if (args.get("url") instanceof String url) return normalizedUrl(url);
        var result = worker.browser("sites", Map.of());
        if (result.get("sites") instanceof List<?> entries) {
            for (var value : entries) if (value instanceof Map<?, ?> item && Objects.equals(item.get("site"), args.get("site")))
                return normalizedUrl(Objects.toString(item.get("url"), ""));
        }
        throw new IllegalArgumentException("未找到该网站，请提供完整的网站URL申请连接");
    }

    static String normalizedUrl(String value) {
        try {
            var uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            return new URI(uri.getScheme(), null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                    uri.getPath().isBlank() ? "/" : uri.getPath(), null, null).toString();
        } catch (Exception e) { throw new IllegalArgumentException("网站地址必须是HTTP/HTTPS地址，不能包含账号密码"); }
    }

    private String origin(String url) {
        var uri = URI.create(normalizedUrl(url));
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 || uri.getPort() == ("https".equals(uri.getScheme()) ? 443 : 80) ? "" : ":" + uri.getPort());
    }

    public Scope scope(String tool, Map<String, Object> args) {
        if ("browser.delegate".equals(tool)) {
            // Exact task scope, never a blanket website or external-write permission.
            var canonical = new TreeMap<String, Object>(args);
            canonical.putIfAbsent("human_login", true);
            canonical.put("allowed_domains", args.get("allowed_domains") instanceof List<?> domains
                    ? domains.stream().map(Object::toString).sorted().distinct().toList() : List.of());
            return new Scope("BROWSER_TASK", HashSupport.sha256("browser-task:v1:" + json.writeValueAsString(canonical)),
                    BrowserAgentService.approvalDescription(args));
        }
        if ("browser.open".equals(tool)) {
            var origin = origin(entryUrl(args));
            return new Scope("WEBSITE", HashSupport.sha256("website:v2:" + origin), "连接 " + origin + "（正常联网、浏览与填写；填写可能自动保存，对外操作仍需授权）");
        }
        if (!"browser.commit".equals(tool)) throw new IllegalArgumentException("此操作不支持记住授权");
        var body = new LinkedHashMap<String, Object>(args);
        body.put("owner", ActorContext.current());
        var preview = worker.browser("session/preview", body);
        // The runner binds the action, complete form/editor content and account-session epoch.
        // Never use agent-supplied action descriptions as an authorization boundary.
        var digest = Objects.toString(preview.get("scopeHash"), "");
        if (!digest.matches("[a-f0-9]{64}")) throw new IllegalStateException("浏览器服务未返回有效的授权范围指纹，请更新服务后重试");
        var label = Objects.toString(preview.get("site"), "网站") + " · " + Objects.toString(preview.get("action"), "操作")
                + " · " + Objects.toString(preview.get("target"), "当前页面") + "（相同内容与当前账号会话）";
        return new Scope("ACTION", HashSupport.sha256("action:v1:" + digest), label.substring(0, Math.min(label.length(), 1000)));
    }

    private Optional<String> activeGrant(Scope scope) {
        return jdbc.sql("select id from browser_user_grant where owner_id=:owner and scope_key=:scope and status='ACTIVE' and expires_at>:now order by created_at desc limit 1")
                .param("owner", ActorContext.current()).param("scope", scope.key()).param("now", Instant.now()).query(String.class).optional();
    }

    public boolean canReuse(String tool, Map<String, Object> args) {
        if ("browser.delegate".equals(tool)) return missingDomains(args).isEmpty();
        return handles(tool) && activeGrant(scope(tool, args)).isPresent();
    }

    public static List<String> requestedDomains(Map<String, Object> args) {
        var domains = new TreeSet<String>();
        domains.add(URI.create(normalizedUrl(Objects.toString(args.get("url"), ""))).getHost());
        if (args.get("allowed_domains") instanceof List<?> values) {
            for (var value : values) {
                var host = Objects.toString(value, "").toLowerCase(Locale.ROOT);
                if (!host.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+") || host.length() > 253)
                    throw new IllegalArgumentException("网站授权必须提供具体域名，不能使用通配符或URL");
                domains.add(host);
            }
        }
        // Explicit URLs in the task are also destinations; never infer authorization from page content.
        var urls = java.util.regex.Pattern.compile("https?://[a-zA-Z0-9.-]+(?=[:/\\s]|$)")
                .matcher(Objects.toString(args.get("goal"), ""));
        while (urls.find()) domains.add(URI.create(urls.group()).getHost().toLowerCase(Locale.ROOT));
        if (domains.size() > 10) throw new IllegalArgumentException("单次任务最多申请10个具体网站域名");
        if (domains.stream().mapToInt(String::length).sum() > 700)
            throw new IllegalArgumentException("网站授权范围过长，请拆分任务；不会截断授权域名");
        return List.copyOf(domains);
    }

    private Scope domainScope(String host) {
        return new Scope("DOMAIN", HashSupport.sha256("browser-domain:v1:" + host), host);
    }

    public List<String> missingDomains(Map<String, Object> args) {
        return requestedDomains(args).stream().filter(host -> activeGrant(domainScope(host)).isEmpty()).toList();
    }

    public String domainApprovalDescription(Map<String, Object> args) {
        return "允许当前平台账号访问以下网站：\n" + String.join("\n", missingDomains(args))
                + "\n授权保存到当前账号，30天内复用，可在网站授权中撤销。此授权仅限网站访问，业务提交仍遵循执行模式。";
    }

    public void requireDomains(Map<String, Object> args) {
        var missing = missingDomains(args);
        if (!missing.isEmpty()) throw new IllegalStateException("需先授权网站访问：" + String.join("、", missing));
    }

    public void registerSession(Map<String, Object> args, String sessionId) {
        if (jdbc.sql("select count(*) from browser_authorized_session where session_id=:session and owner_id=:owner")
                .param("session", sessionId).param("owner", ActorContext.current()).query(Integer.class).single() > 0) {
            checkSession(sessionId);
            return;
        }
        var grant = executingGrant.get() != null ? executingGrant.get() : activeGrant(scope("browser.open", args)).orElse("");
        jdbc.sql("insert into browser_authorized_session(session_id,owner_id,grant_id,created_at) values (:session,:owner,:grant,:now)")
                .param("session", sessionId).param("owner", ActorContext.current()).param("grant", grant).param("now", Instant.now()).update();
        try { checkSession(sessionId); }
        catch (RuntimeException e) {
            try { worker.browser("session/close", Map.of("owner", ActorContext.current(), "session", sessionId)); }
            catch (RuntimeException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    public void checkSession(String sessionId) {
        var grant = jdbc.sql("select grant_id from browser_authorized_session where session_id=:session and owner_id=:owner")
                .param("session", sessionId).param("owner", ActorContext.current()).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("浏览器会话没有有效的网站授权，请重新连接网站"));
        if (!grant.isEmpty() && jdbc.sql("select count(*) from browser_user_grant where id=:id and owner_id=:owner and status='ACTIVE' and expires_at>:now")
                .param("id", grant).param("owner", ActorContext.current()).param("now", Instant.now()).query(Integer.class).single() == 0)
            throw new IllegalStateException("网站授权已撤销或过期，不能继续操作；请重新连接并授权");
    }

    public void recordOperation(String operation, String session, boolean succeeded) {
        audit("", "", succeeded ? "BROWSER_OPERATION" : "BROWSER_OPERATION_FAILED", operation + " · 会话 " + HashSupport.sha256(session).substring(0, 12));
    }

    public List<Map<String, Object>> connections() {
        var ids = jdbc.sql("select session_id from browser_authorized_session where owner_id=:owner order by created_at desc limit 32")
                .param("owner", ActorContext.current()).query(String.class).list();
        var result = new ArrayList<Map<String, Object>>();
        for (var id : ids) {
            try {
                checkSession(id);
                var state = new LinkedHashMap<String, Object>(worker.browser("session/status", Map.of("session", id, "owner", ActorContext.current())));
                if ("CLOSED".equals(state.get("status"))) continue;
                state.put("session", id);
                result.add(state);
            } catch (RuntimeException ex) {
                var message = Objects.toString(ex.getMessage(), "");
                // Only stale connections disappear; an unavailable service must be visible to the user.
                if (!(message.contains("浏览器会话不存在") || message.contains("浏览器会话已过期") || message.contains("网站授权已撤销或过期"))) throw ex;
            }
        }
        return result;
    }

    public Map<String, Object> control(String id, String action) {
        if (!Set.of("login", "restart_login", "resume", "close").contains(action)) throw new IllegalArgumentException("不支持的浏览器控制动作");
        checkSession(id);
        audit("", "", "USER_CONTROL_REQUESTED", action + " · 会话 " + HashSupport.sha256(id).substring(0, 12));
        try {
            var result = worker.browser("session/" + action, Map.of("session", id, "owner", ActorContext.current()));
            recordOperation("user." + action, id, true);
            return Map.of("session", id, "status", Objects.toString(result.get("status"), "UNKNOWN"));
        } catch (RuntimeException ex) {
            recordOperation("user." + action, id, false);
            throw ex;
        }
    }

    @Transactional
    public void approve(List<AssistantModels.PlanStep> steps, boolean remember) {
        for (var step : steps) {
            if (!"PENDING".equals(step.status()) || !step.requiresConfirmation() || !handles(step.tool())) continue;
            if ("browser.delegate".equals(step.tool())) {
                for (var host : requestedDomains(step.arguments())) {
                    var domain = domainScope(host);
                    var grant = activeGrant(domain);
                    if (grant.isPresent()) continue;
                    var id = UUID.randomUUID().toString();
                    var now = Instant.now();
                    jdbc.sql("insert into browser_user_grant(id,owner_id,kind,scope_key,label,status,created_at,expires_at) values (:id,:owner,'DOMAIN',:scope,:label,'ACTIVE',:now,:expires)")
                            .param("id", id).param("owner", ActorContext.current()).param("scope", domain.key()).param("label", host)
                            .param("now", now).param("expires", now.plus(30, ChronoUnit.DAYS)).update();
                    audit(id, step.id(), "GRANTED", "网站访问 · " + host);
                }
                continue;
            }
            var scope = scope(step.tool(), step.arguments());
            jdbc.sql("insert into browser_approval_receipt(step_id,owner_id,scope_key,consumed) values (:step,:owner,:scope,false)")
                    .param("step", step.id()).param("owner", ActorContext.current()).param("scope", scope.key()).update();
            String grant = "";
            if (remember) {
                grant = activeGrant(scope).orElseGet(() -> {
                    var id = UUID.randomUUID().toString();
                    var now = Instant.now();
                    jdbc.sql("insert into browser_user_grant(id,owner_id,kind,scope_key,label,status,created_at,expires_at) values (:id,:owner,:kind,:scope,:label,'ACTIVE',:now,:expires)")
                            .param("id", id).param("owner", ActorContext.current()).param("kind", scope.kind()).param("scope", scope.key())
                            .param("label", scope.label()).param("now", now).param("expires", now.plus(30, ChronoUnit.DAYS)).update();
                    return id;
                });
            }
            audit(grant, step.id(), remember ? "GRANTED" : "APPROVED_ONCE", scope.label());
        }
    }

    public <T> T execute(String tool, Map<String, Object> args, String stepId, Supplier<T> action) {
        if ("browser.delegate".equals(tool)) {
            requireDomains(args);
            audit("", stepId, "AUTHORIZATION_REUSED", "网站访问 · " + String.join("、", requestedDomains(args)));
            return action.get();
        }
        var scope = scope(tool, args);
        var consumed = jdbc.sql("update browser_approval_receipt set consumed=true where step_id=:step and owner_id=:owner and scope_key=:scope and consumed=false")
                .param("step", stepId).param("owner", ActorContext.current()).param("scope", scope.key()).update();
        var grant = activeGrant(scope);
        if (consumed == 0 && grant.isEmpty()) {
            audit("", stepId, "DENIED", scope.label());
            throw new IllegalStateException("此浏览器操作没有有效的用户授权，或授权已撤销/过期，请重新确认");
        }
        audit(grant.orElse(""), stepId, consumed == 1 ? "EXECUTION_STARTED" : "AUTHORIZATION_REUSED", scope.label());
        var previousGrant = executingGrant.get();
        executingGrant.set(grant.orElse(""));
        try {
            var result = action.get();
            audit(grant.orElse(""), stepId, "EXECUTION_SUCCEEDED", scope.label());
            return result;
        } catch (RuntimeException e) {
            audit(grant.orElse(""), stepId, "EXECUTION_FAILED", scope.label());
            throw e;
        } finally {
            if (previousGrant == null) executingGrant.remove(); else executingGrant.set(previousGrant);
        }
    }

    public List<Grant> grants() {
        return jdbc.sql("select * from browser_user_grant where owner_id=:owner order by created_at desc")
                .param("owner", ActorContext.current()).query((rs, row) -> new Grant(rs.getString("id"), rs.getString("kind"), rs.getString("label"),
                        "ACTIVE".equals(rs.getString("status")) && rs.getTimestamp("expires_at").toInstant().isBefore(Instant.now()) ? "EXPIRED" : rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant())).list();
    }

    public List<Audit> history(int page) {
        if (page < 0 || page > 10000) throw new IllegalArgumentException("审计页码无效");
        return jdbc.sql("select * from browser_authorization_audit where owner_id=:owner order by created_at desc, id desc limit 200 offset :offset")
                .param("owner", ActorContext.current()).param("offset", page * 200).query((rs, row) -> new Audit(rs.getString("id"), rs.getString("grant_id"), rs.getString("step_id"),
                        rs.getString("event"), rs.getString("label"), rs.getTimestamp("created_at").toInstant())).list();
    }

    @Transactional
    public void revoke(String id) {
        var grant = grants().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("授权不存在"));
        if (!"ACTIVE".equals(grant.status())) return;
        jdbc.sql("update browser_user_grant set status='REVOKED' where scope_key=(select scope_key from browser_user_grant where id=:id) and owner_id=:owner and status='ACTIVE'")
                .param("id", id).param("owner", ActorContext.current()).update();
        jdbc.sql("update browser_approval_receipt set consumed=true where owner_id=:owner and scope_key=(select scope_key from browser_user_grant where id=:id)")
                .param("owner", ActorContext.current()).param("id", id).update();
        audit(id, "", "REVOKED", grant.label());
        if ("WEBSITE".equals(grant.kind())) {
            var sessions = jdbc.sql("select session_id from browser_authorized_session where grant_id in (select id from browser_user_grant where scope_key=(select scope_key from browser_user_grant where id=:id) and owner_id=:owner) and owner_id=:owner")
                    .param("id", id).param("owner", ActorContext.current()).query(String.class).list();
            for (var session : sessions) {
                try { worker.browser("session/close", Map.of("owner", ActorContext.current(), "session", session)); }
                catch (RuntimeException ex) { audit(id, "", "SESSION_CLOSE_UNCONFIRMED", "授权已撤销；浏览器可能已关闭或服务不可达，请核对窗口"); }
            }
        }
    }

    private void audit(String grant, String step, String event, String label) {
        jdbc.sql("insert into browser_authorization_audit(id,owner_id,grant_id,step_id,event,label,created_at) values (:id,:owner,:grant,:step,:event,:label,:now)")
                .param("id", UUID.randomUUID().toString()).param("owner", ActorContext.current()).param("grant", grant).param("step", step)
                .param("event", event).param("label", label).param("now", Instant.now()).update();
    }
}

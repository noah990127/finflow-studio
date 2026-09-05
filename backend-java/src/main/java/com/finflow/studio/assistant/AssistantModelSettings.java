package com.finflow.studio.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.Map;

@Service
public class AssistantModelSettings {
    private final JdbcTemplate jdbc;
    private final ModelSecretStore secrets;
    public AssistantModelSettings(JdbcTemplate jdbc, ModelSecretStore secrets) { this.jdbc = jdbc; this.secrets = secrets; }

    public record Settings(String mode, String baseUrl, String model, boolean hasKey) {}
    public record Update(String mode, String baseUrl, String model, String apiKey) {
        @Override public String toString() { return "ModelSettingsUpdate[redacted]"; }
    }
    private record Saved(String mode, String url, String model, String encrypted) {}

    private Saved saved(String sessionId) {
        return jdbc.query("select mode, base_url, model, encrypted_key from assistant_model_settings where session_id = ?",
                (rs, row) -> new Saved(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), sessionId)
                .stream().findFirst().orElse(new Saved("DEFAULT", "", "", ""));
    }

    public Settings get(String sessionId) {
        var value = saved(sessionId);
        return new Settings(value.mode(), value.url(), value.model(), !value.encrypted().isBlank());
    }

    public Map<String, String> resolve(String sessionId) {
        var value = saved(sessionId);
        if (!"CUSTOM".equals(value.mode())) return Map.of();
        return Map.of("base_url", value.url(), "model", value.model(), "api_key", secrets.decrypt(value.encrypted()));
    }

    public Map<String, String> testConfiguration(String sessionId, Update update) {
        var value = validate(sessionId, update);
        return Map.of("base_url", value.url(), "model", value.model(), "api_key", secrets.decrypt(value.encrypted()));
    }

    @Transactional
    public Settings save(String sessionId, Update update) {
        jdbc.queryForObject("select id from assistant_session where id = ? for update", String.class, sessionId);
        var active = jdbc.queryForObject("select count(*) from assistant_plan where session_id = ? and status in ('PLAN_READY', 'WAITING_CONFIRMATION', 'RUNNING')",
                Integer.class, sessionId);
        var runs = jdbc.queryForObject("select count(*) from assistant_run where session_id = ? and status in ('QUEUED', 'RUNNING', 'WAITING_CONFIRMATION')", Integer.class, sessionId);
        if ((active != null && active > 0) || (runs != null && runs > 0)) throw new IllegalStateException("请先完成或中断当前任务，再切换模型");
        var old = saved(sessionId);
        Saved value;
        if ("DEFAULT".equals(update.mode())) value = new Saved("DEFAULT", old.url(), old.model(), old.encrypted());
        else if ("CUSTOM".equals(update.mode())) value = validate(sessionId, update);
        else throw new IllegalArgumentException("请选择默认模型或自定义模型");
        jdbc.update("delete from assistant_model_settings where session_id = ?", sessionId);
        jdbc.update("insert into assistant_model_settings(session_id, mode, base_url, model, encrypted_key) values (?, ?, ?, ?, ?)",
                sessionId, value.mode(), value.url(), value.model(), value.encrypted());
        return get(sessionId);
    }

    @Transactional
    public void clear(String sessionId) {
        save(sessionId, new Update("DEFAULT", null, null, null));
        jdbc.update("delete from assistant_model_settings where session_id = ?", sessionId);
    }

    private Saved validate(String sessionId, Update update) {
        var url = normalizeUrl(update.baseUrl());
        var model = update.model() == null ? "" : update.model().trim();
        if (model.isEmpty() || model.length() > 200 || model.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请填写有效模型名称（最多 200 字符）");
        var key = update.apiKey() == null ? "" : update.apiKey().trim();
        if (key.length() > 4096 || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("API Key 格式不正确");
        var old = saved(sessionId);
        if (key.isEmpty() && (!url.equals(old.url()) || old.encrypted().isEmpty()))
            throw new IllegalArgumentException("请填写 API Key；更换地址时需要重新填写密钥");
        return new Saved("CUSTOM", url, model, key.isEmpty() ? old.encrypted() : secrets.encrypt(key));
    }

    static String normalizeUrl(String raw) {
        var value = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        try {
            var uri = URI.create(value);
            var host = uri.getHost();
            boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (value.length() > 2000 || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme())))
                    || host.startsWith("169.254.") || "metadata.google.internal".equalsIgnoreCase(host)) throw new IllegalArgumentException();
            for (var suffix : new String[]{"/chat/completions", "/responses"})
                if (value.endsWith(suffix)) value = value.substring(0, value.length() - suffix.length());
            return value;
        } catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("请填写 HTTPS API 地址；本机模型可使用 http://localhost 地址，不要附带密钥或查询参数"); }
    }
}

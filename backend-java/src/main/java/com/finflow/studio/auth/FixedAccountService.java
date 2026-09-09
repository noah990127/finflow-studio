package com.finflow.studio.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

@Service
public class FixedAccountService {
    private final Map<String, String> accounts;

    public FixedAccountService(@Value("${finflow.auth.default-username:default}") String defaultUsername,
                               @Value("${finflow.auth.default-password:Pr0d1234}") String defaultPassword,
                               @Value("${finflow.auth.accounts-file:}") String accountsFile) {
        var configured = new LinkedHashMap<String, String>();
        configured.put(defaultUsername, defaultPassword);
        if (accountsFile != null && !accountsFile.isBlank()) loadTestAccounts(configured, Path.of(accountsFile));
        accounts = Map.copyOf(configured);
    }

    public boolean authenticate(String username, String password) {
        if (username == null) return false;
        var expected = accounts.get(username.trim());
        return expected != null && constantTimeEquals(expected, password);
    }

    public boolean exists(String username) {
        return username != null && accounts.containsKey(username);
    }

    public int count() {
        return accounts.size();
    }

    private void loadTestAccounts(Map<String, String> target, Path path) {
        try {
            var loaded = new LinkedHashMap<String, String>();
            for (var raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                var line = raw.trim();
                if (line.isBlank() || line.startsWith("#")) continue;
                var separator = line.indexOf('=');
                if (separator < 1 || separator == line.length() - 1) {
                    throw new IllegalStateException("测试账号文件格式错误：" + path);
                }
                var username = line.substring(0, separator).trim();
                var password = line.substring(separator + 1).trim();
                if (!username.matches("test(?:[1-9]|[1-9][0-9]|100)")) {
                    throw new IllegalStateException("测试账号必须为 test1 至 test100：" + username);
                }
                if (password.length() < 16) throw new IllegalStateException("测试账号密码至少需要 16 位：" + username);
                if (loaded.putIfAbsent(username, password) != null) {
                    throw new IllegalStateException("测试账号重复：" + username);
                }
            }
            var expected = IntStream.rangeClosed(1, 100).mapToObj(index -> "test" + index).toList();
            if (loaded.size() != 100 || !loaded.keySet().containsAll(expected)) {
                throw new IllegalStateException("测试账号文件必须完整包含 test1 至 test100");
            }
            target.putAll(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取测试账号文件：" + path, exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}

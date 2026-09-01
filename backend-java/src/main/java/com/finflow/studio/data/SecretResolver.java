package com.finflow.studio.data;

import org.springframework.stereotype.Component;

@Component
public class SecretResolver {

    public String resolve(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return "";
        }
        if (!secretRef.startsWith("env:")) {
            throw new IllegalArgumentException("密钥引用必须使用 env:变量名，密码不会保存在平台数据库中");
        }
        var variable = secretRef.substring(4);
        if (!variable.matches("[A-Z][A-Z0-9_]{1,127}")) {
            throw new IllegalArgumentException("环境变量名称格式不正确");
        }
        var value = System.getenv(variable);
        if (value == null) {
            throw new IllegalStateException("未找到连接密钥环境变量：" + variable);
        }
        return value;
    }
}

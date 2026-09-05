package com.finflow.studio.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ModelSecretStore {
    private final Path keyFile;
    public ModelSecretStore(@Value("${finflow.agent.key-file:./data/agent-model.key}") String keyFile) {
        this.keyFile = Path.of(keyFile);
    }

    private synchronized byte[] key() throws Exception {
        if (!Files.exists(keyFile)) {
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            // Keep the encryption key outside the database and readable only by its owner.
            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(keyFile, key);
        }
        byte[] key = Files.readAllBytes(keyFile);
        if (key.length != 32) throw new IllegalStateException("Invalid encryption key");
        return key;
    }

    public String encrypt(String value) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            return Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(
                    cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ignored) { throw new IllegalStateException("无法安全保存模型密钥，请检查密钥文件权限"); }
    }

    public String decrypt(String value) {
        try {
            var parts = value.split(":", 2);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) { throw new IllegalStateException("无法读取已保存的模型密钥，请重新配置"); }
    }
}

package com.finflow.studio.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class DataSecretStore {
    static final String PREFIX = "enc:v1:";
    private final Path keyFile;

    public DataSecretStore(@Value("${finflow.data.connection-key-file:./data/data-connection.key}") String keyFile) {
        this.keyFile = Path.of(keyFile);
    }

    private synchronized byte[] key() throws Exception {
        if (!Files.exists(keyFile)) {
            var parent = keyFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            var generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            try {
                Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                Files.createFile(keyFile);
            }
            Files.write(keyFile, generated);
        }
        var value = Files.readAllBytes(keyFile);
        if (value.length != 32) throw new IllegalStateException("数据连接密钥文件无效");
        return value;
    }

    public String encrypt(String value) {
        try {
            var nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            return PREFIX + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法安全保存数据连接密码，请检查数据目录权限", exception);
        }
    }

    public String decrypt(String value) {
        try {
            if (value == null || !value.startsWith(PREFIX)) throw new IllegalArgumentException();
            var parts = value.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取已保存的数据连接密码，请重新填写", exception);
        }
    }
}

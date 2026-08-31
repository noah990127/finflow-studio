package com.finflow.studio.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name = "finflow.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalBlobStore implements BlobStore {
    private final Path root;

    public LocalBlobStore(@Value("${finflow.storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject put(String key, InputStream source, long maxBytes) {
        try {
            var target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("对象位置不合法");
            Files.createDirectories(target.getParent());
            var temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var input = new BufferedInputStream(source);
                 var output = new BufferedOutputStream(Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING))) {
                var buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    size += read;
                    if (size > maxBytes) throw new IllegalArgumentException("文件超过允许的大小");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            } catch (RuntimeException exception) {
                Files.deleteIfExists(temp);
                throw exception;
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredObject(target.toString(), size, "sha256:" + HexFormat.of().formatHex(digest.digest()));
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalStateException("文件保存失败", exception);
        }
    }

    @Override
    public Path materialize(String location) {
        var path = Path.of(location).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("文件不在允许的存储目录中");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("文件内容不存在");
        return path;
    }

    @Override
    public void delete(String location) {
        var path = Path.of(location).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("文件不在允许的存储目录中");
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("文件删除失败", exception);
        }
    }
}

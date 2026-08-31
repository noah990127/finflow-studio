package com.finflow.studio.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name = "finflow.storage.provider", havingValue = "s3")
public class S3BlobStore implements BlobStore {
    private final S3Client client;
    private final String bucket;
    private final Path cacheRoot;

    public S3BlobStore(@Value("${finflow.storage.s3.bucket}") String bucket,
                       @Value("${finflow.storage.s3.region:us-east-1}") String region,
                       @Value("${finflow.storage.s3.endpoint:}") String endpoint,
                       @Value("${finflow.storage.s3.access-key:}") String accessKey,
                       @Value("${finflow.storage.s3.secret-key:}") String secretKey,
                       @Value("${finflow.storage.s3.path-style:true}") boolean pathStyle,
                       @Value("${finflow.storage.cache-root:./data/blob-cache}") String cacheRoot) {
        this.bucket = bucket;
        this.cacheRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
        var builder = S3Client.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        builder.credentialsProvider(accessKey.isBlank() ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        this.client = builder.build();
    }

    @Override
    public StoredObject put(String key, InputStream source, long maxBytes) {
        Path temp = null;
        try {
            Files.createDirectories(cacheRoot);
            temp = Files.createTempFile(cacheRoot, "upload-", ".part");
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
            }
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(temp));
            return new StoredObject("s3://" + bucket + "/" + key, size,
                    "sha256:" + HexFormat.of().formatHex(digest.digest()));
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalStateException("对象存储写入失败", exception);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
        }
    }

    @Override
    public Path materialize(String location) {
        if (!location.startsWith("s3://" + bucket + "/")) throw new IllegalStateException("对象不属于当前存储空间");
        var key = location.substring(("s3://" + bucket + "/").length());
        try {
            var suffix = Path.of(key).getFileName().toString();
            var cacheKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(location.getBytes()));
            var target = cacheRoot.resolve(cacheKey + "-" + suffix).normalize();
            if (!target.startsWith(cacheRoot)) throw new IllegalStateException("缓存位置不合法");
            if (!Files.isRegularFile(target)) {
                Files.createDirectories(cacheRoot);
                var temp = Files.createTempFile(cacheRoot, cacheKey, ".part");
                client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), temp);
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (Exception exception) {
            throw new IllegalStateException("对象存储读取失败", exception);
        }
    }

    @Override
    public void delete(String location) {
        if (!location.startsWith("s3://" + bucket + "/")) throw new IllegalStateException("对象不属于当前存储空间");
        var key = location.substring(("s3://" + bucket + "/").length());
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}

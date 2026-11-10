package com.qaqxianyuqaq.backend.util;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储工具。
 *
 * <p>所有二进制内容只经由此工具写入对象存储，禁止在生产代码路径写本地磁盘。</p>
 */
public final class MinioUtil {

    /** 预签名下载地址默认有效期，遵循短效原则。 */
    public static final Duration DEFAULT_PRESIGN_TTL = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(MinioUtil.class);

    private MinioUtil() {
    }

    /** 确保桶存在，不存在则创建；由启动初始化与上传路径幂等调用。 */
    public static void ensureBucket(MinioClient client, String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /** 上传对象，使用随机存储键以避免原始文件名带来的路径风险。 */
    public static void putObject(MinioClient client, String bucket, String storageKey, InputStream stream, long size,
                                 String contentType) throws Exception {
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(storageKey)
                .stream(stream, size, -1)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build());
    }

    /** 打开对象流，调用方必须关闭以归还存储连接。 */
    public static InputStream getObject(MinioClient client, String bucket, String storageKey) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
    }

    /** 删除对象，删除失败由调用方决定是否忽略。 */
    public static void removeObject(MinioClient client, String bucket, String storageKey) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(storageKey).build());
    }

    /** 有界读取对象并关闭网络连接，避免向客户端泄露对象存储地址。 */
    public static byte[] readObject(MinioClient client, String bucket, String storageKey, int maxBytes)
            throws Exception {
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(storageKey).build())) {
            byte[] data = input.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) throw new IOException("对象超出读取限制");
            return data;
        }
    }

    /** 清理失败仅记告警，不能把已经提交的业务变更误报为失败。 */
    public static void removeObjectQuietly(MinioClient client, String bucket, String storageKey) {
        if (storageKey == null) return;
        try {
            removeObject(client, bucket, storageKey);
        } catch (Exception exception) {
            log.warn("对象回收失败，需后续重试: key={}", storageKey, exception);
        }
    }

    /** 数据库提交后回收旧头像，回滚时回收新头像，避免提前删除有效文件。 */
    public static void cleanupReplacementAfterTransaction(MinioClient client, String bucket,
                                                          String newKey, String oldKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                removeObjectQuietly(client, bucket, oldKey);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) removeObjectQuietly(client, bucket, newKey);
            }
        });
    }

    /** 生成短效预签名下载地址，供选手下载附件时重定向。 */
    public static String presignedGetUrl(MinioClient client, String bucket, String storageKey, Duration ttl)
            throws Exception {
        Duration effective = ttl == null ? DEFAULT_PRESIGN_TTL : ttl;
        return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(storageKey)
                .expiry((int) effective.toSeconds(), TimeUnit.SECONDS)
                .build());
    }
}

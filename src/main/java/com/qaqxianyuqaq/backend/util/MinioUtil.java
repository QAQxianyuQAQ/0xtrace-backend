package com.qaqxianyuqaq.backend.util;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
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

    /** 删除对象，删除失败由调用方决定是否忽略。 */
    public static void removeObject(MinioClient client, String bucket, String storageKey) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(storageKey).build());
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

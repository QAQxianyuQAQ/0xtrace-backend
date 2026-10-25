package com.qaqxianyuqaq.backend.util;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

import java.io.InputStream;

/**
 * MinIO 对象存储工具。
 *
 * <p>所有二进制内容只经由此工具写入对象存储，禁止在生产代码路径写本地磁盘。</p>
 */
public final class MinioUtil {


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

    /** 只通过服务端身份读取对象，不向客户端暴露可转发的签名地址。 */
    public static InputStream getObject(MinioClient client,String bucket,String key,long offset,long length) throws Exception {
        return client.getObject(io.minio.GetObjectArgs.builder().bucket(bucket).object(key).offset(offset).length(length).build());
    }
    /** 删除对象，删除失败由调用方处理。 */
    public static void removeObject(MinioClient client, String bucket, String storageKey) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(storageKey).build());
    }

}

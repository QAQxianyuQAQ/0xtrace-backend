package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.FileKind;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 题目文件元数据实体：二进制内容只存于 MinIO，数据库仅保留存储键与元信息。 */
@Getter
@Setter
@TableName("challenge_files")
public class ChallengeFile {

    @TableId(type = IdType.INPUT)
    private String id;

    private String challengeId;

    private FileKind kind;

    /** MinIO 对象键，由服务端随机生成，不暴露原始文件名。 */
    private String storageKey;

    private String originalName;

    /** 文件字节数，避免与 SQL 保留字冲突。 */
    private Long sizeBytes;

    private String contentType;

    private String createdBy;

    private LocalDateTime createdAt;
}

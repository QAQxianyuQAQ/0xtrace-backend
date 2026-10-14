package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 题目实体：答案以摘要形式私有保存，仅管理端接口可读。 */
@Getter
@Setter
@TableName("challenges")
public class Challenge {

    @TableId(type = IdType.INPUT)
    private String id;

    private String eventId;

    /** 对外题目编号，2-24 位大写英数及 _ -，全局唯一。 */
    private String code;

    private String title;

    private String category;

    private String difficulty;

    private Integer points;

    /** 首解额外加分。 */
    private Integer bonusFirst;

    /** 第二解额外加分。 */
    private Integer bonusSecond;

    /** 第三解额外加分。 */
    private Integer bonusThird;

    /** 第四解及以后的额外加分。 */
    private Integer bonusOther;

    private String description;

    /** 标准答案的 SHA-256 摘要，禁止明文落库与下发。 */
    private String answerSecret;

    /** 封面文件主键，指向 challenge_files。 */
    private String coverFileId;

    /** 外部封面地址，未使用 MinIO 封面时可配置 CDN 直链。 */
    private String coverUrl;

    private ChallengeStatus status;

    /** 乐观锁版本号，管理端提交时可携带以拒绝并发覆盖。 */
    private Integer version;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

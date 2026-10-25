package com.qaqxianyuqaq.backend.repository.challenge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;

/** 题目表持久化接口。 */
public interface ChallengeMapper extends BaseMapper<Challenge> {

    /** 计分事务先锁定题目行，数据库提交前禁止其它队伍并发分配相同名次。 */
    @org.apache.ibatis.annotations.Select("SELECT id FROM challenges WHERE id = #{id} FOR UPDATE")
    String lockForScoring(@org.apache.ibatis.annotations.Param("id") String id);
}

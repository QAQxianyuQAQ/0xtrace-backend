package com.qaqxianyuqaq.backend.repository.challenge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qaqxianyuqaq.backend.domain.entity.LocationToken;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 位置凭证持久化接口。 */
public interface LocationTokenMapper extends BaseMapper<LocationToken> {

    /** 查询题目当前有效的（未撤销）最新位置凭证。 */
    @Select("""
            SELECT * FROM location_tokens
            WHERE challenge_id = #{challengeId} AND revoked_at IS NULL
            ORDER BY generation DESC
            LIMIT 1
            """)
    LocationToken selectActiveByChallenge(@Param("challengeId") String challengeId);

    /** 按令牌哈希查询凭证，用于选手核验。 */
    @Select("SELECT * FROM location_tokens WHERE token_hash = #{tokenHash} LIMIT 1")
    LocationToken selectByTokenHash(@Param("tokenHash") String tokenHash);
}

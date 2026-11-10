package com.qaqxianyuqaq.backend.repository.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 学校身份与赛事账号的独立关联，禁止按同名本地账号自动绑定。 */
public interface NjuptIdentityMapper {
    @Select("SELECT user_id FROM njupt_identities WHERE subject = #{subject}")
    String findUserId(String subject);

    @Insert("INSERT INTO njupt_identities (subject, user_id) VALUES (#{subject}, #{userId})")
    void insert(@Param("subject") String subject, @Param("userId") String userId);
}

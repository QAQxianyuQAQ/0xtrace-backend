package com.qaqxianyuqaq.backend.repository.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qaqxianyuqaq.backend.domain.entity.User;

/** 账号表持久化接口，仅提供基础 CRUD 与用户名定位。 */
public interface UserMapper extends BaseMapper<User> {
}

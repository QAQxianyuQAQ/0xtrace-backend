package com.qaqxianyuqaq.backend.service.profile.impl;

import com.qaqxianyuqaq.backend.domain.dto.profile.UpdateProfileDTO;
import com.qaqxianyuqaq.backend.domain.vo.profile.ProfileVO;

import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.profile.ProfileService;
import org.springframework.stereotype.Service;

/** 个人资料服务实现。 */
@Service
public class ProfileServiceImpl implements ProfileService {

    private final UserMapper userMapper;

    public ProfileServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public ProfileVO get(UserPrincipal principal) {
        // 1. 读取账号资料
        User user = requireUser(principal.userId());

        // 2. 仅返回选手可见的展示字段
        return new ProfileVO(user.getUsername(), user.getNickname(), user.getAvatarUrl());
    }

    @Override
    public ProfileVO update(UserPrincipal principal, UpdateProfileDTO request) {
        // 1. 读取待更新账号
        User user = requireUser(principal.userId());

        // 2. 仅更新传入的字段，未传字段保持不变
        if (request.nickname() != null) {
            user.setNickname(request.nickname().isBlank() ? null : request.nickname().trim());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().isBlank() ? null : request.avatarUrl().trim());
        }

        // 3. 落库保存
        userMapper.updateById(user);
        return new ProfileVO(user.getUsername(), user.getNickname(), user.getAvatarUrl());
    }

    /** 读取账号，不存在时返回 404。 */
    private User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("账号不存在");
        }
        return user;
    }
}

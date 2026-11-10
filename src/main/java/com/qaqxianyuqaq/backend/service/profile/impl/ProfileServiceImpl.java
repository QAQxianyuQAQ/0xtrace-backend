package com.qaqxianyuqaq.backend.service.profile.impl;

import com.qaqxianyuqaq.backend.domain.dto.profile.UpdateProfileDTO;
import com.qaqxianyuqaq.backend.domain.vo.profile.ProfileVO;

import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.profile.ProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.StorageException;
import com.qaqxianyuqaq.backend.util.AvatarImageUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.MinioUtil;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;

/** 个人资料服务实现。 */
@Service
public class ProfileServiceImpl implements ProfileService {

    private final UserMapper userMapper;

    private final MinioClient minioClient;

    private final TraceQuestProperties properties;

    public ProfileServiceImpl(UserMapper userMapper, MinioClient minioClient, TraceQuestProperties properties) {
        this.userMapper = userMapper;
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    @Transactional
    public ProfileVO uploadAvatar(UserPrincipal principal, MultipartFile file) {
        // 1. 校验并重编码头像，避免伪造 MIME、主动内容及超大像素图片进入对象存储。
        byte[] image = AvatarImageUtil.normalize(file);

        // 2. 锁定当前账号，串行替换同一账号头像，防止并发更新遗留旧对象。
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getId, principal.userId()).last("FOR UPDATE"));
        if (user == null) throw new NotFoundException("账号不存在");
        String avatarId = IdGenerator.next("avatar");
        String key = AvatarImageUtil.storageKey(user.getId(), avatarId);
        String bucket = properties.getMinio().getBucket();

        // 3. 先保存新文件，失败时原头像和数据库地址均保持不变。
        try (var stream = new ByteArrayInputStream(image)) {
            MinioUtil.ensureBucket(minioClient, bucket);
            MinioUtil.putObject(minioClient, bucket, key, stream, image.length, "image/png");
        } catch (Exception exception) {
            MinioUtil.removeObjectQuietly(minioClient, bucket, key);
            throw new StorageException(ErrorCode.STORAGE_ERROR, "头像存储失败，请稍后重试");
        }

        // 4. 按事务结果回收文件；数据库回滚不会删除仍在使用的旧头像。
        MinioUtil.cleanupReplacementAfterTransaction(minioClient, bucket, key,
                AvatarImageUtil.previousStorageKey(user.getId(), user.getAvatarUrl()));

        // 5. 只写头像字段，保留昵称、角色及组队状态等其他账号信息。
        String url = AvatarImageUtil.url(user.getId(), avatarId);
        int updated = userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId()).set(User::getAvatarUrl, url));
        if (updated != 1) throw new NotFoundException("账号不存在");
        return new ProfileVO(user.getUsername(), user.getNickname(), url);
    }

    @Override
    public byte[] avatar(String userId, String avatarId) {
        // 1. 仅允许读取当前有效版本，禁止遍历路径和取回已替换的历史头像。
        String key = AvatarImageUtil.storageKey(userId, avatarId);
        User user = requireUser(userId);
        if (user.getStatus() != AccountStatus.ACTIVE
                || !AvatarImageUtil.url(userId, avatarId).equals(user.getAvatarUrl())) {
            throw new NotFoundException("头像不存在");
        }

        // 2. 从 MinIO 有界读取图片，不向浏览器暴露内网存储地址或凭据。
        try {
            return MinioUtil.readObject(minioClient, properties.getMinio().getBucket(), key,
                    AvatarImageUtil.MAX_BYTES);
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code())) {
                throw new NotFoundException("头像不存在");
            }
            throw new StorageException(ErrorCode.STORAGE_ERROR, "头像暂时无法读取");
        } catch (Exception exception) {
            throw new StorageException(ErrorCode.STORAGE_ERROR, "头像暂时无法读取");
        }
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

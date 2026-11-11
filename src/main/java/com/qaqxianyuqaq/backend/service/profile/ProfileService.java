package com.qaqxianyuqaq.backend.service.profile;

import com.qaqxianyuqaq.backend.domain.dto.profile.UpdateProfileDTO;
import com.qaqxianyuqaq.backend.domain.vo.profile.ProfileVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;
import org.springframework.web.multipart.MultipartFile;

/** 个人资料服务。 */
public interface ProfileService {

    /** 读取当前账号资料。 */
    ProfileVO get(UserPrincipal principal);

    /** 上传当前账号头像，成功后直接保存头像地址。 */
    ProfileVO uploadAvatar(UserPrincipal principal, MultipartFile file);

    /** 读取当前有效版本的头像图片。 */
    byte[] avatar(String userId, String avatarId);

    /** 更新昵称与头像地址。 */
    ProfileVO update(UserPrincipal principal, UpdateProfileDTO request);
}

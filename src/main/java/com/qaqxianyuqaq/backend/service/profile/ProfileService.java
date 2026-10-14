package com.qaqxianyuqaq.backend.service.profile;

import com.qaqxianyuqaq.backend.domain.dto.profile.UpdateProfileDTO;
import com.qaqxianyuqaq.backend.domain.vo.profile.ProfileVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 个人资料服务。 */
public interface ProfileService {

    /** 读取当前账号资料。 */
    ProfileVO get(UserPrincipal principal);

    /** 更新昵称与头像地址。 */
    ProfileVO update(UserPrincipal principal, UpdateProfileDTO request);
}

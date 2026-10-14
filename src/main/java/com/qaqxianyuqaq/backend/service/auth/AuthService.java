package com.qaqxianyuqaq.backend.service.auth;

import com.qaqxianyuqaq.backend.domain.dto.auth.LoginDTO;
import com.qaqxianyuqaq.backend.domain.vo.auth.AuthSessionVO;
import com.qaqxianyuqaq.backend.domain.vo.auth.MeVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 认证服务：登录、登出与当前会话查询。 */
public interface AuthService {

    /** 校验账号密码并签发 JWT 会话。 */
    AuthSessionVO login(LoginDTO request, String clientIp);

    /** 登出并使当前令牌失效。 */
    void logout(String jwtId, long remainingSeconds);

    /** 查询当前登录账号信息。 */
    MeVO me(UserPrincipal principal);
}

package com.qaqxianyuqaq.backend.service.admin;

import com.qaqxianyuqaq.backend.domain.dto.admin.AdminRoleUpdateDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.admin.ManagedAccountVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 最高管理员对普通管理员权限的管理服务。 */
public interface AdminAccountService {

    /** 分页读取普通管理员账号，不返回最高管理员。 */
    PageVO<ManagedAccountVO> administrators(String q, PageQueryDTO query, UserPrincipal operator);

    /** 授予或撤销普通管理员权限，并使该账号旧会话失效。 */
    ManagedAccountVO updateRole(String userId, AdminRoleUpdateDTO request, UserPrincipal operator);
}

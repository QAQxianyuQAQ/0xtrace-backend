package com.qaqxianyuqaq.backend.service.contestant;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.ContestantCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.ContestantUpdateDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.ContestantVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.ImportResultVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;
import org.springframework.web.multipart.MultipartFile;

/** 选手账号管理服务。 */
public interface ContestantService {

    /** 分页检索账号，支持用户名/队伍关键字与状态筛选。 */
    PageVO<ContestantVO> page(String q, String status, String teamId, PageQueryDTO query);

    /** 创建账号，密码由服务端 BCrypt 哈希。 */
    ContestantVO create(ContestantCreateDTO request, UserPrincipal operator);

    /** 读取账号资料。 */
    ContestantVO get(String userId);

    /** 更新账号资料白名单字段。 */
    ContestantVO update(String userId, ContestantUpdateDTO request);

    /** 重置密码并使已有会话失效。 */
    void resetPassword(String userId, String newPassword);

    /** 停用账号（同时使会话失效）或重新启用。 */
    ContestantVO changeStatus(String userId, boolean enabled);

    /** 删除账号，保留历史解题记录。 */
    void delete(String userId);

    /** CSV 批量导入，返回新增/跳过/失败摘要。 */
    ImportResultVO importCsv(MultipartFile file);

    /** 导出 CSV，绝不包含密码字段。 */
    String exportCsv();
}

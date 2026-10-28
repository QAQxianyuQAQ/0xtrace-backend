package com.qaqxianyuqaq.backend.service.challenge;

import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeUpdateDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.AdminChallengeVO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ChallengeVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;

import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 题目服务：选手端只读视图与后台 CRUD。 */
public interface ChallengeService {

    /** 按编号读取题目实体，不存在时抛出 404。 */
    Challenge requireByCode(String code);

    /** 选手端题目列表，仅返回已发布/暂停题目且不含答案。 */
    PageVO<ChallengeVO> listPublic(String eventId, String category, String difficulty,
                                   UserPrincipal viewer, PageQueryDTO query);

    /** 选手端题目详情。 */
    ChallengeVO publicDetail(String code, UserPrincipal viewer);

    /** 后台题目列表，支持关键字、状态与分类筛选。 */
    PageVO<AdminChallengeVO> listAdmin(String q, String status, String category, PageQueryDTO query);

    /** 后台题目详情，含答案字段。 */
    AdminChallengeVO adminDetail(String code);

    /** 创建题目。 */
    AdminChallengeVO create(ChallengeCreateDTO request);

    /** 在指定赛事创建独占题目。 */
    AdminChallengeVO create(String eventId, ChallengeCreateDTO request);

    /** 编辑题目。 */
    AdminChallengeVO update(String code, ChallengeUpdateDTO request);

    /** 删除题目，存在解题记录时拒绝并返回 409。 */
    void delete(String code);

    /** 变更题目状态（发布、暂停、恢复、归档）。 */
    AdminChallengeVO changeStatus(String code, ChallengeStatus target);
}

package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ChallengeVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;

import com.qaqxianyuqaq.backend.security.SecurityUtils;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 选手端题目接口：永不返回标准答案与二维码密钥。 */
@RestController
@RequestMapping("/api/v1/challenges")
public class ChallengeController {

    private final ChallengeService challengeService;

    public ChallengeController(ChallengeService challengeService) {
        this.challengeService = challengeService;
    }

    /** 只返回已发布题目，并按当前登录态标注解题状态。 */
    @GetMapping
    public PageVO<ChallengeVO> list(@RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String difficulty,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer pageSize) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return challengeService.listPublic(category, difficulty, SecurityUtils.currentPrincipalOrNull(), query);
    }

    /** 题目详情，含附件元数据与本人解题状态。 */
    @GetMapping("/{code}")
    public ChallengeVO detail(@PathVariable String code) {
        UserPrincipal viewer = SecurityUtils.currentPrincipalOrNull();
        return challengeService.publicDetail(code, viewer);
    }
}

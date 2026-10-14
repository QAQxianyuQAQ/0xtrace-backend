package com.qaqxianyuqaq.backend.controller.contestant;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.ContestantCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.ContestantUpdateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.PasswordResetDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.ContestantVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.ImportResultVO;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.contestant.ContestantService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/** 后台选手账号管理接口：响应绝不包含密码或密码哈希。 */
@RestController
@RequestMapping("/api/v1/admin/contestants")
public class AdminContestantController {

    private final ContestantService contestantService;

    public AdminContestantController(ContestantService contestantService) {
        this.contestantService = contestantService;
    }

    /** 分页检索账号，可按用户名/队伍搜索并按状态筛选。 */
    @GetMapping
    public PageVO<ContestantVO> list(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String teamId,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer pageSize) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return contestantService.page(q, status, teamId, query);
    }

    /** 创建账号，密码由服务端 BCrypt 哈希。 */
    @PostMapping
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "contestant.created", entityType = "contestant")
    public ResponseEntity<ContestantVO> create(
            @Valid @RequestBody ContestantCreateDTO request,
            @AuthenticationPrincipal UserPrincipal principal) {
        ContestantVO created = contestantService.create(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 获取账号资料与状态。 */
    @GetMapping("/{userId}")
    public ContestantVO detail(@PathVariable String userId) {
        return contestantService.get(userId);
    }

    /** 更新队伍、备注、昵称等资料字段。 */
    @PatchMapping("/{userId}")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "contestant.updated", entityType = "contestant", entityIdParam = "userId")
    public ContestantVO update(@PathVariable String userId,
                                              @Valid @RequestBody ContestantUpdateDTO request) {
        return contestantService.update(userId, request);
    }

    /** 重置密码，并使该账号已签发令牌失效。 */
    @PutMapping("/{userId}/password")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "contestant.password.reset", entityType = "contestant", entityIdParam = "userId")
    public ResponseEntity<Void> resetPassword(@PathVariable String userId,
                                              @Valid @RequestBody PasswordResetDTO request) {
        contestantService.resetPassword(userId, request.password());
        return ResponseEntity.noContent().build();
    }

    /** 停用账号，同时使现有会话失效。 */
    @PostMapping("/{userId}/disable")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "contestant.disabled", entityType = "contestant", entityIdParam = "userId")
    public ContestantVO disable(@PathVariable String userId) {
        return contestantService.changeStatus(userId, false);
    }

    /** 重新启用账号。 */
    @PostMapping("/{userId}/enable")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "contestant.enabled", entityType = "contestant", entityIdParam = "userId")
    public ContestantVO enable(@PathVariable String userId) {
        return contestantService.changeStatus(userId, true);
    }

    /** 删除账号；存在历史解题记录时返回 409。 */
    @DeleteMapping("/{userId}")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "contestant.deleted", entityType = "contestant", entityIdParam = "userId")
    public ResponseEntity<Void> delete(@PathVariable String userId) {
        contestantService.delete(userId);
        return ResponseEntity.noContent().build();
    }

    /** CSV 批量导入，返回新增/跳过/失败摘要。 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "contestant.imported", entityType = "contestant")
    public ImportResultVO importCsv(@RequestParam("file") MultipartFile file) {
        return contestantService.importCsv(file);
    }

    /** 导出 CSV，绝不包含密码或凭证字段。 */
    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export() {
        byte[] body = contestantService.exportCsv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contestants.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }
}

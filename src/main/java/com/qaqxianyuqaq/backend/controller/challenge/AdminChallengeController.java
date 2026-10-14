package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeUpdateDTO;
import com.qaqxianyuqaq.backend.domain.dto.challenge.LocationQrRequestDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.AdminChallengeVO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.LocationQrVO;
import com.qaqxianyuqaq.backend.domain.vo.common.ErrorVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.file.FileUploadResultVO;
import com.qaqxianyuqaq.backend.domain.vo.file.FileVO;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import com.qaqxianyuqaq.backend.service.challenge.LocationService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import com.qaqxianyuqaq.backend.util.RequestUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台题目管理接口。
 *
 * <p>覆盖题目 CRUD、发布/暂停/恢复、附件与封面上传、位置二维码签发与撤销。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/challenges")
public class AdminChallengeController {

    private final ChallengeService challengeService;

    private final FileStorageService fileStorageService;

    private final LocationService locationService;

    public AdminChallengeController(ChallengeService challengeService, FileStorageService fileStorageService,
                                    LocationService locationService) {
        this.challengeService = challengeService;
        this.fileStorageService = fileStorageService;
        this.locationService = locationService;
    }

    /** 分页检索题目，支持关键字、状态与分类筛选。 */
    @GetMapping
    public PageVO<AdminChallengeVO> list(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String category,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer pageSize) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return challengeService.listAdmin(q, status, category, query);
    }

    /** 创建题目，题目编号必须唯一。 */
    @PostMapping
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.created", entityType = "challenge")
    public ResponseEntity<AdminChallengeVO> create(
            @Valid @RequestBody ChallengeCreateDTO request) {
        AdminChallengeVO created = challengeService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 获取完整题目编辑数据（含答案）。 */
    @GetMapping("/{code}")
    public AdminChallengeVO detail(@PathVariable String code) {
        return challengeService.adminDetail(code);
    }

    /** 编辑题目字段，可选择携带 version 做乐观锁校验。 */
    @PatchMapping("/{code}")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.updated", entityType = "challenge", entityIdParam = "code")
    public AdminChallengeVO update(@PathVariable String code,
                                                 @Valid @RequestBody ChallengeUpdateDTO request) {
        return challengeService.update(code, request);
    }

    /** 删除题目；已有人解出时返回 409，需改用归档。 */
    @DeleteMapping("/{code}")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.deleted", entityType = "challenge", entityIdParam = "code")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        challengeService.delete(code);
        return ResponseEntity.noContent().build();
    }

    /** 发布题目。 */
    @PostMapping("/{code}/publish")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.published", entityType = "challenge", entityIdParam = "code")
    public AdminChallengeVO publish(@PathVariable String code) {
        return challengeService.changeStatus(code, ChallengeStatus.PUBLISHED);
    }

    /** 暂停题目。 */
    @PostMapping("/{code}/pause")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.paused", entityType = "challenge", entityIdParam = "code")
    public AdminChallengeVO pause(@PathVariable String code) {
        return challengeService.changeStatus(code, ChallengeStatus.PAUSED);
    }

    /** 重新开放题目。 */
    @PostMapping("/{code}/resume")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.resumed", entityType = "challenge", entityIdParam = "code")
    public AdminChallengeVO resume(@PathVariable String code) {
        return challengeService.changeStatus(code, ChallengeStatus.PUBLISHED);
    }

    /** 生成或轮换位置凭证，明文 payload 仅本次返回。 */
    @PostMapping("/{code}/location-qr")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "location-qr.issued", entityType = "challenge", entityIdParam = "code")
    public ResponseEntity<LocationQrVO> issueQr(
            @PathVariable String code,
            @RequestBody(required = false) LocationQrRequestDTO request) {
        LocationQrVO issued = locationService.issueQr(code, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    /** 撤销当前位置凭证。 */
    @DeleteMapping("/{code}/location-qr")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "location-qr.revoked", entityType = "challenge", entityIdParam = "code")
    public ResponseEntity<Void> revokeQr(@PathVariable String code) {
        locationService.revokeQr(code);
        return ResponseEntity.noContent().build();
    }

    /**
     * 位置二维码图片下载。
     *
     * <p>当前仓库不内置二维码渲染库，二维码 PNG 由管理端控制台根据 payload 在前端绘制，
     * 因此该地址返回 501 并提示前端使用 payload；如需服务端出图，可在引入渲染库后实现。</p>
     */
    @GetMapping("/{code}/location-qr/image")
    public ResponseEntity<ErrorVO> qrImage(@PathVariable String code) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ErrorVO.of("NOT_IMPLEMENTED",
                        "服务端未内置二维码渲染，请使用签发响应中的 payload 在前端绘制",
                        RequestUtil.currentRequestId()));
    }

    /** 上传一个或多个附件，字段名支持 files 与 file。 */
    @PostMapping("/{code}/attachments")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.attachment.uploaded", entityType = "challenge", entityIdParam = "code")
    public ResponseEntity<FileUploadResultVO> uploadAttachments(
            @PathVariable String code,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<MultipartFile> effective = new ArrayList<>();
        if (files != null) {
            effective.addAll(files);
        }
        if (file != null) {
            effective.add(file);
        }
        List<FileVO> uploaded = fileStorageService.uploadAttachments(code, effective, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(new FileUploadResultVO(uploaded));
    }

    /** 查看题目附件列表。 */
    @GetMapping("/{code}/attachments")
    public List<FileVO> listAttachments(@PathVariable String code) {
        return fileStorageService.listAttachments(code);
    }

    /** 删除指定附件。 */
    @DeleteMapping("/{code}/attachments/{fileId}")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.attachment.deleted", entityType = "challenge", entityIdParam = "code")
    public ResponseEntity<Void> deleteAttachment(@PathVariable String code, @PathVariable String fileId) {
        fileStorageService.deleteAttachment(code, fileId);
        return ResponseEntity.noContent().build();
    }

    /** 上传或替换题目封面。 */
    @PostMapping("/{code}/cover")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.cover.uploaded", entityType = "challenge", entityIdParam = "code")
    public ResponseEntity<FileVO> uploadCover(@PathVariable String code,
                                                       @RequestParam("file") MultipartFile file,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        FileVO cover = fileStorageService.uploadCover(code, file, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(cover);
    }

    /** 获取封面管理元数据与下载地址。 */
    @GetMapping("/{code}/cover")
    public FileVO getCover(@PathVariable String code) {
        return fileStorageService.getCover(code);
    }

    /** 移除封面。 */
    @DeleteMapping("/{code}/cover")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.cover.deleted", entityType = "challenge", entityIdParam = "code")
    public ResponseEntity<Void> deleteCover(@PathVariable String code) {
        fileStorageService.deleteCover(code);
        return ResponseEntity.noContent().build();
    }
}

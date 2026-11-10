package com.qaqxianyuqaq.backend.controller.file;

import com.qaqxianyuqaq.backend.domain.entity.ChallengeFile;
import com.qaqxianyuqaq.backend.domain.enums.FileKind;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** 文件下载接口：鉴权后同源传输 MinIO 内容，不向浏览器暴露内网地址或可转赠签名。 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** 附件按原文件名下载，封面允许作为图片加载。 */
    @GetMapping("/{fileId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InputStreamResource> download(@PathVariable String fileId,
                                                       @AuthenticationPrincipal UserPrincipal viewer) {
        ChallengeFile file = fileStorageService.requireFile(fileId, viewer);
        boolean cover = file.getKind() == FileKind.COVER;
        ContentDisposition disposition = (cover ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.getOriginalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(cover ? MediaType.parseMediaType(file.getContentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.getSizeBytes())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(() -> fileStorageService.openFile(file)));
    }
}

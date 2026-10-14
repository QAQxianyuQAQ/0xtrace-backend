package com.qaqxianyuqaq.backend.controller.file;

import com.qaqxianyuqaq.backend.domain.entity.ChallengeFile;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 文件下载接口。
 *
 * <p>登录校验后返回 MinIO 短效预签名地址的重定向，避免服务端转发大文件。</p>
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** 附件/封面下载：302 重定向到短效预签名地址。 */
    @GetMapping("/{fileId}")
    public ResponseEntity<Void> download(@PathVariable String fileId) {
        ChallengeFile file = fileStorageService.requireFile(fileId);
        String presignedUrl = fileStorageService.presignedDownloadUrl(file);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(presignedUrl)).build();
    }
}

package com.qaqxianyuqaq.backend.controller.me;

import com.qaqxianyuqaq.backend.service.profile.ProfileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录用户读取重编码后的头像，不公开原文件或对象存储访问凭据。 */
@RestController
@RequestMapping("/api/v1/avatars")
public class AvatarController {

    private final ProfileService profileService;

    public AvatarController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /** 返回当前有效版本的 PNG，旧版本及非法路径返回 404。 */
    @GetMapping("/{userId}/{avatarId}")
    public ResponseEntity<byte[]> avatar(@PathVariable String userId, @PathVariable String avatarId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(profileService.avatar(userId, avatarId));
    }
}

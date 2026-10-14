package com.qaqxianyuqaq.backend.domain.vo.file;

import java.time.LocalDateTime;

/** 文件元数据响应，downloadUrl 指向受鉴权保护的下载接口。 */
public record FileVO(String id, String name, Long size, String contentType, String downloadUrl,
                     LocalDateTime createdAt, String kind) {
}

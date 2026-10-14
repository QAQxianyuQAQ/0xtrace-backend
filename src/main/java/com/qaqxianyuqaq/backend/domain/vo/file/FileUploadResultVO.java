package com.qaqxianyuqaq.backend.domain.vo.file;

import java.util.List;

/** 批量上传响应，逐文件返回成功结果。 */
public record FileUploadResultVO(List<FileVO> items) {
}

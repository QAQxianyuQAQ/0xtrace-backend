package com.qaqxianyuqaq.backend.service.challenge;

import com.qaqxianyuqaq.backend.domain.vo.file.FileVO;

import com.qaqxianyuqaq.backend.domain.entity.ChallengeFile;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 题目附件与封面服务：所有二进制内容经 MinIO 存储。 */
public interface FileStorageService {

    /** 上传单个附件。 */
    FileVO uploadAttachment(String code, MultipartFile file, UserPrincipal operator);

    /** 批量上传附件，返回逐文件结果。 */
    List<FileVO> uploadAttachments(String code, List<MultipartFile> files, UserPrincipal operator);

    /** 查询题目附件列表。 */
    List<FileVO> listAttachments(String code);

    /** 删除附件。 */
    void deleteAttachment(String code, String fileId);

    /** 上传或替换封面，旧封面对象将被移除。 */
    FileVO uploadCover(String code, MultipartFile file, UserPrincipal operator);

    /** 读取封面元数据。 */
    FileVO getCover(String code);

    /** 移除封面。 */
    void deleteCover(String code);

    /** 按文件 ID 读取文件元数据，不存在时抛出 404。 */
    ChallengeFile requireFile(String fileId);

    /** 校验登录角色及题目赛事可见性后返回文件元数据。 */
    ChallengeFile requireDownloadableFile(String fileId, UserPrincipal principal);

    /** 读取已经过权限检查的对象字节范围，由控制器负责关闭流。 */
    java.io.InputStream openDownload(ChallengeFile file, long offset, long length);

    /** 删除题目下的全部文件（数据库行与对象存储对象），供题目删除时调用。 */
    void deleteAllFiles(String challengeId);
}

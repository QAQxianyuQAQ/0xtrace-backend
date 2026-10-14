package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.qaqxianyuqaq.backend.domain.vo.file.FileVO;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeFile;
import com.qaqxianyuqaq.backend.domain.enums.FileKind;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.StorageException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeFileMapper;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.MinioUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 题目附件与封面服务实现。
 *
 * <p>二进制内容全部写入 MinIO，本地磁盘不落任何上传文件；数据库只保留对象键与元数据。</p>
 */
@Service
public class FileStorageServiceImpl implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageServiceImpl.class);

    private final MinioClient minioClient;

    private final TraceQuestProperties properties;

    private final ChallengeMapper challengeMapper;

    private final ChallengeFileMapper challengeFileMapper;

    private final CacheService cacheService;

    public FileStorageServiceImpl(MinioClient minioClient, TraceQuestProperties properties,
                                  ChallengeMapper challengeMapper, ChallengeFileMapper challengeFileMapper,
                                  CacheService cacheService) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.challengeMapper = challengeMapper;
        this.challengeFileMapper = challengeFileMapper;
        this.cacheService = cacheService;
    }

    @Override
    @Transactional
    public FileVO uploadAttachment(String code, MultipartFile file, UserPrincipal operator) {
        // 1. 校验题目存在
        Challenge challenge = requireChallenge(code);

        // 2. 校验附件体积与扩展名
        validateAttachment(file);

        // 3. 写入对象存储并登记元数据
        ChallengeFile entity = store(challenge, file, FileKind.ATTACHMENT, operator);

        // 4. 清理题目详情缓存
        invalidate(code);
        return toVO(entity);
    }

    @Override
    @Transactional
    public List<FileVO> uploadAttachments(String code, List<MultipartFile> files, UserPrincipal operator) {
        // 1. 校验题目存在
        Challenge challenge = requireChallenge(code);

        // 2. 先整体校验，任一文件非法即拒绝，避免出现半成功状态
        List<MultipartFile> effective = files == null ? List.of() : files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (effective.isEmpty()) {
            throw new ValidationFailedException("files", "未接收到任何文件");
        }
        for (MultipartFile file : effective) {
            validateAttachment(file);
        }

        // 3. 逐文件写入对象存储并登记元数据
        List<FileVO> result = new ArrayList<>();
        for (MultipartFile file : effective) {
            result.add(toVO(store(challenge, file, FileKind.ATTACHMENT, operator)));
        }

        // 4. 清理题目详情缓存
        invalidate(code);
        return result;
    }

    @Override
    public List<FileVO> listAttachments(String code) {
        // 1. 校验题目存在
        Challenge challenge = requireChallenge(code);

        // 2. 查询附件列表并按创建时间升序返回
        return challengeFileMapper.selectList(Wrappers.<ChallengeFile>lambdaQuery()
                        .eq(ChallengeFile::getChallengeId, challenge.getId())
                        .eq(ChallengeFile::getKind, FileKind.ATTACHMENT)
                        .orderByAsc(ChallengeFile::getCreatedAt))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public void deleteAttachment(String code, String fileId) {
        // 1. 校验题目与附件归属
        Challenge challenge = requireChallenge(code);
        ChallengeFile file = challengeFileMapper.selectById(fileId);
        if (file == null || !challenge.getId().equals(file.getChallengeId())
                || file.getKind() != FileKind.ATTACHMENT) {
            throw new NotFoundException("附件不存在");
        }

        // 2. 删除对象存储内容与元数据，对象删除失败只告警不阻断
        removeObjectQuietly(file);
        challengeFileMapper.deleteById(file.getId());

        // 3. 清理题目详情缓存
        invalidate(code);
    }

    @Override
    @Transactional
    public FileVO uploadCover(String code, MultipartFile file, UserPrincipal operator) {
        // 1. 校验题目存在
        Challenge challenge = requireChallenge(code);

        // 2. 校验封面体积与 MIME 类型（契约规定 PNG/JPEG/WebP/AVIF，10MB 上限）
        validateCover(file);

        // 3. 移除旧封面，保证一题一封面
        ChallengeFile previous = findCover(challenge.getId());
        if (previous != null) {
            removeObjectQuietly(previous);
            challengeFileMapper.deleteById(previous.getId());
        }

        // 4. 写入新封面并回写题目封面字段
        ChallengeFile entity = store(challenge, file, FileKind.COVER, operator);
        challenge.setCoverFileId(entity.getId());
        challenge.setCoverUrl(null);
        challenge.setUpdatedAt(TimeUtil.now());
        challengeMapper.updateById(challenge);

        // 5. 清理题目详情缓存
        invalidate(code);
        return toVO(entity);
    }

    @Override
    public FileVO getCover(String code) {
        // 1. 校验题目存在
        Challenge challenge = requireChallenge(code);

        // 2. 读取封面元数据
        ChallengeFile cover = findCover(challenge.getId());
        if (cover == null) {
            throw new NotFoundException("该题目尚未配置封面");
        }
        return toVO(cover);
    }

    @Override
    @Transactional
    public void deleteCover(String code) {
        // 1. 校验题目与封面存在
        Challenge challenge = requireChallenge(code);
        ChallengeFile cover = findCover(challenge.getId());
        if (cover == null) {
            throw new NotFoundException("该题目尚未配置封面");
        }

        // 2. 删除对象与元数据，并清空题目封面字段
        removeObjectQuietly(cover);
        challengeFileMapper.deleteById(cover.getId());
        challenge.setCoverFileId(null);
        challenge.setCoverUrl(null);
        challenge.setUpdatedAt(TimeUtil.now());
        challengeMapper.updateById(challenge);

        // 3. 清理题目详情缓存
        invalidate(code);
    }

    @Override
    public ChallengeFile requireFile(String fileId) {
        ChallengeFile file = challengeFileMapper.selectById(fileId);
        if (file == null) {
            throw new NotFoundException("文件不存在");
        }
        return file;
    }

    @Override
    public String presignedDownloadUrl(ChallengeFile file) {
        try {
            // 生成短效预签名地址，MinIO 凭证不下发到客户端
            return MinioUtil.presignedGetUrl(minioClient, properties.getMinio().getBucket(), file.getStorageKey(),
                    properties.getMinio().getPresignTtl());
        } catch (Exception exception) {
            throw new StorageException(ErrorCode.STORAGE_ERROR, "对象存储暂不可用，请稍后重试");
        }
    }

    @Override
    @Transactional
    public void deleteAllFiles(String challengeId) {
        // 1. 读取题目全部文件
        List<ChallengeFile> files = challengeFileMapper.selectList(Wrappers.<ChallengeFile>lambdaQuery()
                .eq(ChallengeFile::getChallengeId, challengeId));

        // 2. 逐个删除对象存储内容，失败只告警
        for (ChallengeFile file : files) {
            removeObjectQuietly(file);
        }

        // 3. 清理元数据
        if (!files.isEmpty()) {
            challengeFileMapper.delete(Wrappers.<ChallengeFile>lambdaQuery()
                    .eq(ChallengeFile::getChallengeId, challengeId));
        }
    }

    /** 读取题目，按编号定位。 */
    private Challenge requireChallenge(String code) {
        Challenge challenge = challengeMapper.selectOne(Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getCode, code));
        if (challenge == null) {
            throw new NotFoundException(ErrorCode.CHALLENGE_NOT_FOUND, "题目不存在");
        }
        return challenge;
    }

    /** 查询题目封面记录。 */
    private ChallengeFile findCover(String challengeId) {
        return challengeFileMapper.selectOne(Wrappers.<ChallengeFile>lambdaQuery()
                .eq(ChallengeFile::getChallengeId, challengeId)
                .eq(ChallengeFile::getKind, FileKind.COVER)
                .last("LIMIT 1"));
    }

    /** 校验附件限制。 */
    private void validateAttachment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationFailedException("file", "文件内容为空");
        }
        if (file.getSize() > properties.getStorage().getMaxAttachmentBytes()) {
            throw new StorageException(ErrorCode.PAYLOAD_TOO_LARGE, "附件体积超过服务端限制");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!extension.isEmpty()
                && properties.getStorage().getBlockedAttachmentExtensions().contains(extension)) {
            throw new StorageException(ErrorCode.UNSUPPORTED_FILE_TYPE, "禁止上传可执行文件类型：" + extension);
        }
    }

    /** 校验封面限制。 */
    private void validateCover(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationFailedException("file", "封面内容为空");
        }
        if (file.getSize() > properties.getStorage().getMaxCoverBytes()) {
            throw new StorageException(ErrorCode.PAYLOAD_TOO_LARGE, "封面体积超过 10MB 限制");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!properties.getStorage().getCoverContentTypes().contains(contentType)) {
            throw new StorageException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "封面仅支持 PNG/JPEG/WebP/AVIF");
        }
    }

    /** 写入 MinIO 并登记文件元数据。 */
    private ChallengeFile store(Challenge challenge, MultipartFile file, FileKind kind, UserPrincipal operator) {
        String fileId = IdGenerator.next("file");
        String extension = extensionOf(file.getOriginalFilename());
        String storageKey = "challenges/" + challenge.getCode() + "/" + kind.apiValue() + "/" + fileId
                + (extension.isEmpty() ? "" : "." + extension);
        try {
            MinioUtil.ensureBucket(minioClient, properties.getMinio().getBucket());
            try (var stream = file.getInputStream()) {
                MinioUtil.putObject(minioClient, properties.getMinio().getBucket(), storageKey, stream,
                        file.getSize(), file.getContentType());
            }
        } catch (Exception exception) {
            log.error("上传对象失败, challenge={}, key={}", challenge.getCode(), storageKey, exception);
            throw new StorageException(ErrorCode.STORAGE_ERROR, "对象存储上传失败");
        }

        ChallengeFile entity = new ChallengeFile();
        entity.setId(fileId);
        entity.setChallengeId(challenge.getId());
        entity.setKind(kind);
        entity.setStorageKey(storageKey);
        entity.setOriginalName(safeName(file.getOriginalFilename()));
        entity.setSizeBytes(file.getSize());
        entity.setContentType(file.getContentType());
        entity.setCreatedBy(operator == null ? null : operator.userId());
        entity.setCreatedAt(TimeUtil.now());
        challengeFileMapper.insert(entity);
        return entity;
    }

    /** 删除对象，失败仅记录告警，避免影响元数据清理。 */
    private void removeObjectQuietly(ChallengeFile file) {
        try {
            MinioUtil.removeObject(minioClient, properties.getMinio().getBucket(), file.getStorageKey());
        } catch (Exception exception) {
            log.warn("删除对象失败，已忽略: key={}, reason={}", file.getStorageKey(), exception.getMessage());
        }
    }

    /** 取小写扩展名。 */
    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    /** 仅保留文件名，剥离客户端可能携带的路径。 */
    private String safeName(String filename) {
        if (filename == null) {
            return "unnamed";
        }
        String normalized = filename.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    /** 清理题目缓存。 */
    private void invalidate(String code) {
        cacheService.delete(CacheKeys.challengeDetail(code));
    }

    /** 实体转响应模型。 */
    private FileVO toVO(ChallengeFile file) {
        return new FileVO(file.getId(), file.getOriginalName(), file.getSizeBytes(), file.getContentType(),
                "/api/v1/files/" + file.getId(), file.getCreatedAt(), file.getKind().apiValue());
    }
}

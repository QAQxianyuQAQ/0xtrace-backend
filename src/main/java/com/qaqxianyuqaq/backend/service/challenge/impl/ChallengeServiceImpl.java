package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.qaqxianyuqaq.backend.domain.dto.challenge.BonusesDTO;
import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeUpdateDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.AdminChallengeVO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ChallengePayloadVO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ChallengeVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.file.FileVO;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeFile;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.FileKind;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeFileMapper;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.security.CredentialCipher;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.service.challenge.FileStorageService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.EnumUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.JsonUtil;
import com.qaqxianyuqaq.backend.util.PageUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 题目服务实现。 */
@Service
public class ChallengeServiceImpl implements ChallengeService {

    /** 题目详情缓存有效期。 */
    private static final Duration CHALLENGE_CACHE_TTL = Duration.ofSeconds(60);

    private final ChallengeMapper challengeMapper;

    private final ChallengeFileMapper challengeFileMapper;

    private final SolveMapper solveMapper;

    private final CacheService cacheService;

    private final EventService eventService;

    private final FileStorageService fileStorageService;

    private final CredentialCipher credentialCipher;

    private final TeamService teamService;

    public ChallengeServiceImpl(ChallengeMapper challengeMapper, ChallengeFileMapper challengeFileMapper,
                                SolveMapper solveMapper, CacheService cacheService, EventService eventService,
                                FileStorageService fileStorageService, CredentialCipher credentialCipher,
                                TeamService teamService) {
        this.challengeMapper = challengeMapper;
        this.challengeFileMapper = challengeFileMapper;
        this.solveMapper = solveMapper;
        this.cacheService = cacheService;
        this.eventService = eventService;
        this.fileStorageService = fileStorageService;
        this.credentialCipher = credentialCipher;
        this.teamService = teamService;
    }

    @Override
    public Challenge requireByCode(String code) {
        // 1. 按题目编号定位题目，编号是唯一约束
        Challenge challenge = challengeMapper.selectOne(Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getCode, code));
        if (challenge == null) {
            throw new NotFoundException(ErrorCode.CHALLENGE_NOT_FOUND, "题目不存在");
        }
        return challenge;
    }

    @Override
    public PageVO<ChallengeVO> listPublic(String eventId, String category, String difficulty,
                                          UserPrincipal viewer, PageQueryDTO query) {
        // 1. 解析赛事范围，题目列表默认返回当前赛事
        String effectiveEventId = eventId == null || eventId.isBlank()
                ? eventService.requireCurrent().getId() : eventService.requireById(eventId).getId();

        // 2. 仅查询赛事内对选手可见的题目（发布或暂停），草稿与归档一律不可见
        var wrapper = Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getEventId, effectiveEventId)
                .in(Challenge::getStatus, ChallengeStatus.PUBLISHED, ChallengeStatus.PAUSED)
                .eq(category != null && !category.isBlank(), Challenge::getCategory, category)
                .eq(difficulty != null && !difficulty.isBlank(), Challenge::getDifficulty, difficulty)
                .orderByDesc(Challenge::getPublishedAt);
        IPage<Challenge> page = challengeMapper.selectPage(new Page<>(query.page(), query.pageSize()), wrapper);

        // 3. 批量装载附件与当前选手在该赛事的队伍解题状态，避免 N+1 查询
        List<String> challengeIds = page.getRecords().stream().map(Challenge::getId).toList();
        Map<String, List<FileVO>> attachments = loadAttachmentMap(challengeIds);
        Set<String> solvedChallengeIds = loadSolvedChallengeIds(viewer, effectiveEventId, challengeIds);

        // 4. 组装响应模型，永不包含 answer 字段
        List<ChallengeVO> items = page.getRecords().stream()
                .map(challenge -> new ChallengeVO(challenge.getCode(), challenge.getTitle(),
                        challenge.getCategory(), challenge.getDifficulty(), challenge.getPoints(),
                        challenge.getDescription(), resolveCoverUrl(challenge),
                        attachments.getOrDefault(challenge.getId(), List.of()),
                        solvedChallengeIds.contains(challenge.getId()) ? "solved" : "unsolved"))
                .toList();
        return PageUtil.toPage(page, items);
    }

    @Override
    public ChallengeVO publicDetail(String code, UserPrincipal viewer) {
        // 1. 读取题目实体并校验选手可见性
        Challenge challenge = requireByCode(code);
        if (!challenge.getStatus().visibleToContestant()) {
            throw new NotFoundException(ErrorCode.CHALLENGE_NOT_FOUND, "题目不存在或不可见");
        }

        // 2. 优先读取缓存载荷，缓存缺失时回源数据库并写回
        ChallengePayloadVO payload = loadCachedPayload(challenge);
        String myStatus = "unsolved";
        if (viewer != null) {
            // 3. 实时计算当前选手在当前赛事队伍的解题状态，避免缓存串号
            String eventId = challenge.getEventId() == null
                    ? eventService.requireCurrent().getId() : challenge.getEventId();
            myStatus = loadSolvedChallengeIds(viewer, eventId, List.of(challenge.getId()))
                    .contains(challenge.getId())
                    ? "solved" : "unsolved";
        }

        // 4. 组装响应
        return new ChallengeVO(payload.code(), payload.title(), payload.category(),
                payload.difficulty(), payload.points(), payload.description(), payload.coverUrl(),
                payload.attachments(), myStatus);
    }

    @Override
    public PageVO<AdminChallengeVO> listAdmin(String q, String status, String category,
                                                            PageQueryDTO query) {
        // 1. 组装后台筛选条件：关键字匹配编号或标题
        var wrapper = Wrappers.<Challenge>lambdaQuery()
                .and(q != null && !q.isBlank(), condition -> condition
                        .like(Challenge::getCode, q)
                        .or()
                        .like(Challenge::getTitle, q))
                .eq(status != null && !status.isBlank(), Challenge::getStatus,
                        EnumUtil.parse(ChallengeStatus.class, status, "status"))
                .eq(category != null && !category.isBlank(), Challenge::getCategory, category)
                .orderByDesc(Challenge::getCreatedAt);
        IPage<Challenge> page = challengeMapper.selectPage(new Page<>(query.page(), query.pageSize()), wrapper);

        // 2. 批量装载附件后转换为管理端模型
        Map<String, List<FileVO>> attachments = loadAttachmentMap(
                page.getRecords().stream().map(Challenge::getId).toList());
        List<AdminChallengeVO> items = page.getRecords().stream()
                .map(challenge -> toAdminVO(challenge, attachments.getOrDefault(challenge.getId(), List.of())))
                .toList();
        return PageUtil.toPage(page, items);
    }

    @Override
    public AdminChallengeVO adminDetail(String code) {
        // 1. 读取题目实体
        Challenge challenge = requireByCode(code);

        // 2. 装载附件并解密答案，仅管理端可见
        return toAdminVO(challenge, loadAttachmentMap(List.of(challenge.getId()))
                .getOrDefault(challenge.getId(), List.of()));
    }

    @Override
    @Transactional
    public AdminChallengeVO create(ChallengeCreateDTO request) {
        // 1. 校验必填字段与题目编号唯一性
        validateRequired(request.code(), request.title(), request.category(), request.difficulty(), request.points());
        if (request.answer() == null || request.answer().isBlank()) {
            throw new ValidationFailedException("answer", "题目答案不能为空");
        }
        boolean duplicated = challengeMapper.exists(Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getCode, request.code()));
        if (duplicated) {
            throw new ConflictException(ErrorCode.CODE_DUPLICATED, "题目编号已存在：" + request.code());
        }

        // 2. 组装题实体，答案以密文保存，封面可用外部地址
        Challenge challenge = new Challenge();
        challenge.setId(IdGenerator.next("chal"));
        challenge.setEventId(eventService.requireCurrent().getId());
        challenge.setCode(request.code());
        challenge.setTitle(request.title().trim());
        challenge.setCategory(request.category().trim());
        challenge.setDifficulty(request.difficulty().trim());
        challenge.setPoints(request.points());
        challenge.setDescription(request.description());
        challenge.setAnswerSecret(credentialCipher.encrypt(request.answer()));
        challenge.setCoverUrl(request.coverUrl());
        applyBonuses(challenge, request.bonuses());
        ChallengeStatus status = request.status() == null || request.status().isBlank()
                ? ChallengeStatus.DRAFT
                : EnumUtil.parse(ChallengeStatus.class, request.status(), "status");
        challenge.setStatus(status);
        challenge.setPublishedAt(status == ChallengeStatus.PUBLISHED ? TimeUtil.now() : null);
        challenge.setVersion(1);
        challenge.setCreatedAt(TimeUtil.now());
        challenge.setUpdatedAt(TimeUtil.now());

        // 3. 落库保存
        challengeMapper.insert(challenge);

        // 4. 清理选手端题目列表缓存
        invalidateCaches(challenge.getCode());
        return toAdminVO(challenge, List.of());
    }

    @Override
    @Transactional
    public AdminChallengeVO update(String code, ChallengeUpdateDTO request) {
        // 1. 读取题目实体
        Challenge challenge = requireByCode(code);

        // 2. 乐观锁校验：客户端携带的 version 必须与库中一致
        if (request.version() != null && !request.version().equals(challenge.getVersion())) {
            throw new ConflictException("题目已被他人修改，请刷新后重试");
        }

        // 3. 逐字段应用补丁，未传字段保持不变
        applyUpdate(challenge, request);

        // 4. 版本号自增后落库
        challenge.setVersion(challenge.getVersion() + 1);
        challenge.setUpdatedAt(TimeUtil.now());
        challengeMapper.updateById(challenge);

        // 5. 清理缓存，使选手端尽快看到新版本
        invalidateCaches(code);
        return toAdminVO(challenge, loadAttachmentMap(List.of(challenge.getId()))
                .getOrDefault(challenge.getId(), List.of()));
    }

    @Override
    @Transactional
    public void delete(String code) {
        // 1. 读取题目实体
        Challenge challenge = requireByCode(code);

        // 2. 已有人解出时拒绝硬删除，保留历史解题与积分记录
        boolean hasSolves = solveMapper.exists(Wrappers.<Solve>lambdaQuery()
                .eq(Solve::getChallengeId, challenge.getId()));
        if (hasSolves) {
            throw new ConflictException(ErrorCode.CHALLENGE_HAS_SOLVES,
                    "题目已有人解出，请改用归档（status=archived）");
        }

        // 3. 删除题目文件与题目本体
        fileStorageService.deleteAllFiles(challenge.getId());
        challengeMapper.deleteById(challenge.getId());

        // 4. 清理缓存
        invalidateCaches(code);
    }

    @Override
    @Transactional
    public AdminChallengeVO changeStatus(String code, ChallengeStatus target) {
        // 1. 读取题目实体
        Challenge challenge = requireByCode(code);

        // 2. 发布前校验题目完整性，避免上线后无法判题
        if (target == ChallengeStatus.PUBLISHED) {
            validateRequired(challenge.getCode(), challenge.getTitle(), challenge.getCategory(),
                    challenge.getDifficulty(), challenge.getPoints());
            if (challenge.getAnswerSecret() == null || challenge.getAnswerSecret().isBlank()) {
                throw new ValidationFailedException("answer", "发布前必须配置题目答案");
            }
            if (challenge.getPublishedAt() == null) {
                challenge.setPublishedAt(TimeUtil.now());
            }
        }

        // 3. 更新状态与版本号
        challenge.setStatus(target);
        challenge.setVersion(challenge.getVersion() + 1);
        challenge.setUpdatedAt(TimeUtil.now());
        challengeMapper.updateById(challenge);

        // 4. 清理缓存
        invalidateCaches(code);
        return toAdminVO(challenge, loadAttachmentMap(List.of(challenge.getId()))
                .getOrDefault(challenge.getId(), List.of()));
    }

    /** 校验题目必填字段。 */
    private void validateRequired(String code, String title, String category, String difficulty, Integer points) {
        if (code == null || code.isBlank()) {
            throw new ValidationFailedException("code", "题目编号不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new ValidationFailedException("title", "题目标题不能为空");
        }
        if (category == null || category.isBlank()) {
            throw new ValidationFailedException("category", "题目分类不能为空");
        }
        if (difficulty == null || difficulty.isBlank()) {
            throw new ValidationFailedException("difficulty", "题目难度不能为空");
        }
        if (points == null || points <= 0) {
            throw new ValidationFailedException("points", "分值必须为正整数");
        }
    }

    /** 应用创建/更新时的额外加分设置，缺省为 0。 */
    private void applyBonuses(Challenge challenge, BonusesDTO bonuses) {
        BonusesDTO effective = bonuses == null
                ? new BonusesDTO(0, 0, 0, 0) : bonuses;
        challenge.setBonusFirst(effective.first() == null ? 0 : effective.first());
        challenge.setBonusSecond(effective.second() == null ? 0 : effective.second());
        challenge.setBonusThird(effective.third() == null ? 0 : effective.third());
        challenge.setBonusOther(effective.other() == null ? 0 : effective.other());
    }

    /** 应用编辑补丁。 */
    private void applyUpdate(Challenge challenge, ChallengeUpdateDTO request) {
        if (request.title() != null && !request.title().isBlank()) {
            challenge.setTitle(request.title().trim());
        }
        if (request.category() != null && !request.category().isBlank()) {
            challenge.setCategory(request.category().trim());
        }
        if (request.difficulty() != null && !request.difficulty().isBlank()) {
            challenge.setDifficulty(request.difficulty().trim());
        }
        if (request.points() != null) {
            challenge.setPoints(request.points());
        }
        if (request.bonuses() != null) {
            applyBonuses(challenge, request.bonuses());
        }
        if (request.description() != null) {
            challenge.setDescription(request.description());
        }
        if (request.answer() != null && !request.answer().isBlank()) {
            challenge.setAnswerSecret(credentialCipher.encrypt(request.answer()));
        }
        if (request.coverUrl() != null) {
            challenge.setCoverUrl(request.coverUrl().isBlank() ? null : request.coverUrl().trim());
            challenge.setCoverFileId(null);
        }
        if (request.status() != null && !request.status().isBlank()) {
            ChallengeStatus target = EnumUtil.parse(ChallengeStatus.class, request.status(), "status");
            challenge.setStatus(target);
            if (target == ChallengeStatus.PUBLISHED && challenge.getPublishedAt() == null) {
                challenge.setPublishedAt(TimeUtil.now());
            }
        }
    }

    /** 读取题目缓存载荷，未命中时回源数据库。 */
    private ChallengePayloadVO loadCachedPayload(Challenge challenge) {
        String cacheKey = CacheKeys.challengeDetail(challenge.getCode());
        Optional<String> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            ChallengePayloadVO payload = JsonUtil.fromJson(cached.get(), ChallengePayloadVO.class);
            if (payload != null) {
                return payload;
            }
        }
        ChallengePayloadVO payload = new ChallengePayloadVO(challenge.getCode(), challenge.getTitle(),
                challenge.getCategory(), challenge.getDifficulty(), challenge.getPoints(), challenge.getDescription(),
                resolveCoverUrl(challenge),
                loadAttachmentMap(List.of(challenge.getId())).getOrDefault(challenge.getId(), List.of()));
        cacheService.set(cacheKey, JsonUtil.toJson(payload), CHALLENGE_CACHE_TTL);
        return payload;
    }

    /** 批量装载附件并按题目分组。 */
    private Map<String, List<FileVO>> loadAttachmentMap(Collection<String> challengeIds) {
        if (challengeIds == null || challengeIds.isEmpty()) {
            return Map.of();
        }
        List<ChallengeFile> files = challengeFileMapper.selectList(Wrappers.<ChallengeFile>lambdaQuery()
                .in(ChallengeFile::getChallengeId, challengeIds)
                .eq(ChallengeFile::getKind, FileKind.ATTACHMENT)
                .orderByAsc(ChallengeFile::getCreatedAt));
        Map<String, List<FileVO>> grouped = new HashMap<>();
        for (ChallengeFile file : files) {
            grouped.computeIfAbsent(file.getChallengeId(), key -> new ArrayList<>()).add(toFileVO(file));
        }
        return grouped;
    }

    /** 查询当前选手在赛事队伍中已解出的题目 ID 集合。 */
    private Set<String> loadSolvedChallengeIds(UserPrincipal viewer, String eventId,
                                               Collection<String> challengeIds) {
        if (viewer == null || challengeIds == null || challengeIds.isEmpty()) {
            return Set.of();
        }
        Team team = teamService.findMembership(eventId, viewer.userId());
        if (team == null) {
            return Set.of();
        }
        List<Solve> solves = solveMapper.selectList(Wrappers.<Solve>lambdaQuery()
                .eq(Solve::getSubjectKey, team.getId())
                .in(Solve::getChallengeId, challengeIds));
        return solves.stream().map(Solve::getChallengeId).collect(Collectors.toSet());
    }

    /** 计算封面地址：优先使用 MinIO 封面文件，否则回落到外部地址。 */
    private String resolveCoverUrl(Challenge challenge) {
        if (challenge.getCoverFileId() != null) {
            return "/api/v1/files/" + challenge.getCoverFileId();
        }
        return challenge.getCoverUrl();
    }

    /** 文件实体转响应模型。 */
    private FileVO toFileVO(ChallengeFile file) {
        return new FileVO(file.getId(), file.getOriginalName(), file.getSizeBytes(), file.getContentType(),
                "/api/v1/files/" + file.getId(), file.getCreatedAt(), file.getKind().apiValue());
    }

    /** 实体转管理端响应模型。 */
    private AdminChallengeVO toAdminVO(Challenge challenge, List<FileVO> attachments) {
        return new AdminChallengeVO(challenge.getId(), challenge.getCode(), challenge.getTitle(),
                challenge.getCategory(), challenge.getDifficulty(), challenge.getPoints(),
                new BonusesDTO(challenge.getBonusFirst(), challenge.getBonusSecond(),
                        challenge.getBonusThird(), challenge.getBonusOther()),
                challenge.getDescription(), credentialCipher.decrypt(challenge.getAnswerSecret()),
                resolveCoverUrl(challenge), challenge.getStatus().apiValue(), challenge.getVersion(),
                challenge.getPublishedAt(), challenge.getCreatedAt(), challenge.getUpdatedAt(), attachments);
    }

    /** 清理题目相关缓存。 */
    private void invalidateCaches(String code) {
        cacheService.delete(CacheKeys.challengeDetail(code));
        cacheService.delete(CacheKeys.CHALLENGE_LIST);
    }

}

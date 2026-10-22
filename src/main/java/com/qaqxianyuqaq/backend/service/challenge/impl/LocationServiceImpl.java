package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.qaqxianyuqaq.backend.domain.dto.challenge.LocationPayloadDTO;
import com.qaqxianyuqaq.backend.domain.dto.challenge.LocationQrRequestDTO;
import com.qaqxianyuqaq.backend.domain.dto.submission.LocationVerificationDTO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.LocationQrVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;

import com.qaqxianyuqaq.backend.aspect.Idempotent;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.LocationToken;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.challenge.LocationTokenMapper;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.service.challenge.LocationService;
import com.qaqxianyuqaq.backend.service.submission.ScoringService;
import com.qaqxianyuqaq.backend.util.DigestUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.LocationConstants;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 现场位置二维码服务实现。 */
@Service
public class LocationServiceImpl implements LocationService {

    private final LocationTokenMapper locationTokenMapper;

    private final ChallengeService challengeService;

    private final EventService eventService;

    private final ScoringService scoringService;

    private final PermissionAssert permissionAssert;

    public LocationServiceImpl(LocationTokenMapper locationTokenMapper, ChallengeService challengeService,
                              EventService eventService, ScoringService scoringService,
                              PermissionAssert permissionAssert) {
        this.locationTokenMapper = locationTokenMapper;
        this.challengeService = challengeService;
        this.eventService = eventService;
        this.scoringService = scoringService;
        this.permissionAssert = permissionAssert;
    }

    @Override
    @Transactional
    public LocationQrVO issueQr(String code, LocationQrRequestDTO request) {
        // 1. 读取题目并校验其为已发布的图寻题
        Challenge challenge = challengeService.requireByCode(code);
        if (challenge.getStatus() != ChallengeStatus.PUBLISHED) {
            throw new ConflictException(ErrorCode.CHALLENGE_NOT_ANSWERABLE, "仅已发布题目可签发位置二维码");
        }
        if (!LocationConstants.LOCATION_CATEGORY.equals(challenge.getCategory())) {
            throw new ValidationFailedException("category", "仅图寻类题目支持位置二维码");
        }

        // 2. 校验过期时间必须为未来时刻
        LocalDateTime expiresAt = request == null ? null : request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(TimeUtil.now())) {
            throw new ValidationFailedException("expiresAt", "过期时间必须为将来时刻");
        }

        // 3. 撤销当前有效凭证（轮换语义：旧令牌立即失效）
        LocationToken previous = locationTokenMapper.selectActiveByChallenge(challenge.getId());
        int generation = 1;
        if (previous != null) {
            previous.setRevokedAt(TimeUtil.now());
            locationTokenMapper.updateById(previous);
            generation = (previous.getGeneration() == null ? 0 : previous.getGeneration()) + 1;
        }

        // 4. 生成新的随机令牌，落库只保存 SHA-256 哈希
        String token = IdGenerator.locationToken();
        LocationToken entity = new LocationToken();
        entity.setId(IdGenerator.next("loc"));
        entity.setChallengeId(challenge.getId());
        entity.setTokenHash(DigestUtil.sha256Hex(token));
        entity.setGeneration(generation);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(TimeUtil.now());
        locationTokenMapper.insert(entity);

        // 5. 明文 payload 只在本次响应返回一次
        String payload = LocationConstants.payloadOf(challenge.getCode(), token);
        return new LocationQrVO(challenge.getCode(), payload,
                "/api/v1/admin/challenges/" + challenge.getCode() + "/location-qr/image",
                entity.getCreatedAt(), expiresAt, generation);
    }

    @Override
    @Transactional
    public void revokeQr(String code) {
        // 1. 读取题目与当前有效凭证
        Challenge challenge = challengeService.requireByCode(code);
        LocationToken active = locationTokenMapper.selectActiveByChallenge(challenge.getId());
        if (active == null) {
            throw new NotFoundException("该题目没有可撤销的位置凭证");
        }

        // 2. 标记撤销时间，核验时立即失效
        active.setRevokedAt(TimeUtil.now());
        locationTokenMapper.updateById(active);
    }

    @Override
    @Transactional
    @RateLimit(scope = "location-verify")
    @Idempotent(scope = "location-verification")
    public SubmissionResultVO verify(LocationVerificationDTO request,
                                                    UserPrincipal principal) {
        // 1. 校验操作者具备参赛权限
        permissionAssert.assertContestant(principal);

        // 2. 解析 payload 结构，格式为 TRACEQUEST|LOCATION|CODE|TOKEN
        LocationPayloadDTO parsed = LocationConstants.parse(request.payload());
        if (parsed == null) {
            throw new ValidationFailedException("payload", "二维码内容格式不正确");
        }

        // 3. 按令牌哈希定位凭证，校验存在性、撤销状态与有效期
        LocationToken token = locationTokenMapper.selectByTokenHash(DigestUtil.sha256Hex(parsed.token()));
        if (token == null || token.getRevokedAt() != null) {
            throw new ValidationFailedException("payload", "二维码凭证无效或已被撤销");
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(TimeUtil.now())) {
            throw new ValidationFailedException("payload", "二维码已过期，请向管理员索取新凭证");
        }

        // 4. 读取题目并校验凭证与题目一致、题目为已发布图寻题
        Challenge challenge = challengeService.requireByCode(parsed.challengeCode());
        if (!challenge.getId().equals(token.getChallengeId())) {
            throw new ValidationFailedException("payload", "二维码与题目不匹配");
        }
        if (challenge.getStatus() != ChallengeStatus.PUBLISHED
                || !LocationConstants.LOCATION_CATEGORY.equals(challenge.getCategory())) {
            throw new ConflictException(ErrorCode.CHALLENGE_NOT_ANSWERABLE, "题目当前不可进行现场核验");
        }

        // 5. 校验题目所属赛事处于开放状态
        EventConfig event = challenge.getEventId() == null
                ? eventService.requireCurrent() : eventService.requireById(challenge.getEventId());
        if (event.getStatus() != EventStatus.OPEN) {
            throw new ConflictException(ErrorCode.SUBMISSION_CLOSED, "赛事当前不可作答");
        }

        // 6. 核验通过后交由计分引擎记分，同队同题只计一次
        return scoringService.award(principal, challenge,
                com.qaqxianyuqaq.backend.domain.enums.SolveSource.LOCATION, request.payload(),
                request.requestId(), true);
    }
}

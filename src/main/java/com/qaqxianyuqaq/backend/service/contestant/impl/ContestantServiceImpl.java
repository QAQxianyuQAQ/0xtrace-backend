package com.qaqxianyuqaq.backend.service.contestant.impl;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.ContestantCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.ContestantUpdateDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.ContestantVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.ImportFailureVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.ImportResultVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamBriefVO;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.TeamMember;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMemberMapper;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.contestant.ContestantService;
import com.qaqxianyuqaq.backend.service.cache.SessionService;
import com.qaqxianyuqaq.backend.util.CsvUtil;
import com.qaqxianyuqaq.backend.util.EnumUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.PageUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 选手账号管理服务实现。 */
@Service
public class ContestantServiceImpl implements ContestantService {

    private static final Logger log = LoggerFactory.getLogger(ContestantServiceImpl.class);

    private final UserMapper userMapper;

    private final TeamMapper teamMapper;

    private final TeamMemberMapper teamMemberMapper;

    private final SolveMapper solveMapper;

    private final PasswordEncoder passwordEncoder;

    private final SessionService sessionService;

    public ContestantServiceImpl(UserMapper userMapper, TeamMapper teamMapper, TeamMemberMapper teamMemberMapper,
                                 SolveMapper solveMapper, PasswordEncoder passwordEncoder,
                                 SessionService sessionService) {
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.solveMapper = solveMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
    }

    @Override
    public PageVO<ContestantVO> page(String q, String status, String teamId, PageQueryDTO query) {
        // 1. 组装筛选条件：仅选手角色，可选状态、队伍与关键字
        var wrapper = Wrappers.<User>lambdaQuery()
                .eq(User::getRole, UserRole.CONTESTANT)
                .eq(status != null && !status.isBlank(), User::getStatus,
                        EnumUtil.parse(AccountStatus.class, status, "status"))
                .eq(teamId != null && !teamId.isBlank(), User::getTeamId, teamId)
                .orderByDesc(User::getCreatedAt);

        // 2. 关键字同时匹配用户名与队伍名称
        if (q != null && !q.isBlank()) {
            List<String> matchedTeamIds = teamMapper.selectList(Wrappers.<Team>lambdaQuery()
                            .like(Team::getName, q)).stream()
                    .map(Team::getId).toList();
            wrapper.and(condition -> {
                condition.like(User::getUsername, q);
                if (!matchedTeamIds.isEmpty()) {
                    condition.or().in(User::getTeamId, matchedTeamIds);
                }
            });
        }

        // 3. 分页查询并批量装配队伍名称
        IPage<User> page = userMapper.selectPage(new Page<>(query.page(), query.pageSize()), wrapper);
        Map<String, Team> teams = loadTeams(page.getRecords());
        List<ContestantVO> items = page.getRecords().stream()
                .map(user -> toVO(user, teams.get(user.getTeamId())))
                .toList();
        return PageUtil.toPage(page, items);
    }

    @Override
    @Transactional
    public ContestantVO create(ContestantCreateDTO request, UserPrincipal operator) {
        // 1. 校验用户名唯一，避免覆盖既有账号
        boolean exists = userMapper.exists(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.username().trim()));
        if (exists) {
            throw new ConflictException("用户名已存在：" + request.username());
        }

        // 2. 解析或创建队伍
        Team team = resolveTeam(request.teamId(), request.teamName(), true);

        // 3. 组装账号实体，密码使用 BCrypt 哈希且不回显
        User user = new User();
        user.setId(IdGenerator.next("usr"));
        user.setUsername(request.username().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.CONTESTANT);
        user.setStatus(AccountStatus.ACTIVE);
        user.setTeamId(team == null ? null : team.getId());
        user.setNote(request.note());
        user.setNickname(request.nickname());
        user.setTokenVersion(0);
        user.setCreatedAt(TimeUtil.now());
        userMapper.insert(user);

        // 4. 登记队伍成员关系，便于赛事复盘
        syncTeamMember(user, team);
        return toVO(user, team);
    }

    @Override
    public ContestantVO get(String userId) {
        // 1. 读取账号并校验角色
        User user = requireContestant(userId);

        // 2. 装配队伍信息
        Team team = user.getTeamId() == null ? null : teamMapper.selectById(user.getTeamId());
        return toVO(user, team);
    }

    @Override
    @Transactional
    public ContestantVO update(String userId, ContestantUpdateDTO request) {
        // 1. 读取账号
        User user = requireContestant(userId);
        Team team = user.getTeamId() == null ? null : teamMapper.selectById(user.getTeamId());

        // 2. 应用队伍变更（支持按队伍编号或名称指定）
        if (request.teamId() != null || request.teamName() != null) {
            team = resolveTeam(request.teamId(), request.teamName(), false);
            user.setTeamId(team == null ? null : team.getId());
        }

        // 3. 应用其他白名单资料字段
        if (request.note() != null) {
            user.setNote(request.note().isBlank() ? null : request.note().trim());
        }
        if (request.nickname() != null) {
            user.setNickname(request.nickname().isBlank() ? null : request.nickname().trim());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().isBlank() ? null : request.avatarUrl().trim());
        }

        // 4. 状态变更：停用时使已有会话立即失效
        if (request.status() != null && !request.status().isBlank()) {
            AccountStatus status = EnumUtil.parse(AccountStatus.class, request.status(), "status");
            user.setStatus(status);
            if (status == AccountStatus.DISABLED) {
                sessionService.bumpTokenVersion(user.getId());
            }
        }

        // 5. 落库并同步队伍成员关系
        userMapper.updateById(user);
        syncTeamMember(user, team);
        return toVO(user, team);
    }

    @Override
    @Transactional
    public void resetPassword(String userId, String newPassword) {
        // 1. 读取账号
        User user = requireContestant(userId);

        // 2. 更新密码哈希，并使已签发令牌立即失效
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        sessionService.bumpTokenVersion(userId);
    }

    @Override
    @Transactional
    public ContestantVO changeStatus(String userId, boolean enabled) {
        // 1. 读取账号
        User user = requireContestant(userId);

        // 2. 更新状态；停用时同时使会话失效
        user.setStatus(enabled ? AccountStatus.ACTIVE : AccountStatus.DISABLED);
        userMapper.updateById(user);
        if (!enabled) {
            sessionService.bumpTokenVersion(userId);
        }

        // 3. 返回最新账号视图
        Team team = user.getTeamId() == null ? null : teamMapper.selectById(user.getTeamId());
        return toVO(user, team);
    }

    @Override
    @Transactional
    public void delete(String userId) {
        // 1. 读取账号
        User user = requireContestant(userId);

        // 2. 存在历史解题记录时拒绝删除，保留赛事审计完整性
        var solveQuery = Wrappers.<Solve>lambdaQuery().eq(Solve::getUserId, user.getId());
        if (user.getTeamId() != null) {
            solveQuery.or().eq(Solve::getTeamId, user.getTeamId());
        }
        boolean hasSolves = solveMapper.exists(solveQuery);
        if (hasSolves) {
            throw new ConflictException("账号存在历史解题记录，请改用停用（disable）保留审计");
        }

        // 3. 清理队伍成员关系与账号本身
        teamMemberMapper.delete(Wrappers.<TeamMember>lambdaQuery().eq(TeamMember::getUserId, userId));
        userMapper.deleteById(userId);
    }

    @Override
    @Transactional
    public ImportResultVO importCsv(MultipartFile file) {
        // 1. 读取并解析 CSV 文本
        List<List<String>> rows = parseCsv(file);
        if (rows.isEmpty()) {
            throw new ValidationFailedException("file", "CSV 内容为空");
        }

        // 2. 识别表头，定位 username、password、team、note 列下标
        Map<String, Integer> columns = locateColumns(rows);
        int firstDataRow = columns.isEmpty() ? 0 : 1;
        if (columns.isEmpty()) {
            // 无表头时按固定顺序解析：username,password,team,note
            columns = Map.of("username", 0, "password", 1, "team", 2, "note", 3);
        }

        // 3. 逐行导入，重复用户名跳过、格式错误计入失败明细
        int imported = 0;
        int skipped = 0;
        List<ImportFailureVO> failures = new ArrayList<>();
        for (int index = firstDataRow; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String username = valueOf(row, columns.get("username"));
            String password = valueOf(row, columns.get("password"));
            if (username == null || username.isBlank()) {
                failures.add(new ImportFailureVO(index + 1, username, "用户名为空"));
                continue;
            }
            if (password == null || password.length() < 8) {
                failures.add(new ImportFailureVO(index + 1, username, "密码少于 8 位"));
                continue;
            }
            boolean exists = userMapper.exists(Wrappers.<User>lambdaQuery().eq(User::getUsername, username.trim()));
            if (exists) {
                skipped++;
                continue;
            }
            Team team = resolveTeam(null, valueOf(row, columns.get("team")), true);
            User user = new User();
            user.setId(IdGenerator.next("usr"));
            user.setUsername(username.trim());
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setRole(UserRole.CONTESTANT);
            user.setStatus(AccountStatus.ACTIVE);
            user.setTeamId(team == null ? null : team.getId());
            user.setNote(valueOf(row, columns.get("note")));
            user.setTokenVersion(0);
            user.setCreatedAt(TimeUtil.now());
            userMapper.insert(user);
            syncTeamMember(user, team);
            imported++;
        }

        // 4. 返回导入摘要，明确告知新增、跳过与失败数量
        return new ImportResultVO(imported, skipped, failures.size(), failures);
    }

    @Override
    public String exportCsv() {
        // 1. 导出全部选手账号，字段与契约一致且绝不含密码
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, UserRole.CONTESTANT)
                .orderByAsc(User::getCreatedAt));
        Map<String, Team> teams = loadTeams(users);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("username", "team", "status", "created", "note"));
        for (User user : users) {
            Team team = teams.get(user.getTeamId());
            rows.add(List.of(user.getUsername(), team == null ? "" : team.getName(),
                    user.getStatus().apiValue(), TimeUtil.format(user.getCreatedAt()),
                    user.getNote() == null ? "" : user.getNote()));
        }

        // 2. 生成 CSV 文本
        return CsvUtil.write(rows);
    }

    /** 读取账号并校验其为选手角色。 */
    private User requireContestant(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getRole() != UserRole.CONTESTANT) {
            throw new NotFoundException("账号不存在");
        }
        return user;
    }

    /** 解析队伍：优先使用编号，其次按名称查找或创建。 */
    private Team resolveTeam(String teamId, String teamName, boolean createIfAbsent) {
        if (teamId != null && !teamId.isBlank()) {
            Team team = teamMapper.selectById(teamId);
            if (team == null) {
                throw new NotFoundException("队伍不存在");
            }
            return team;
        }
        if (teamName == null || teamName.isBlank()) {
            return null;
        }
        String normalized = teamName.trim();
        Team existing = teamMapper.selectOne(Wrappers.<Team>lambdaQuery().eq(Team::getName, normalized));
        if (existing != null || !createIfAbsent) {
            return existing;
        }
        // 队伍名称不存在时按需创建，便于现场快速录队
        Team created = new Team();
        created.setId(IdGenerator.next("team"));
        created.setName(normalized);
        created.setCreatedAt(TimeUtil.now());
        teamMapper.insert(created);
        return created;
    }

    /** 同步队伍成员关系，保证队伍变更可追溯。 */
    private void syncTeamMember(User user, Team team) {
        teamMemberMapper.delete(Wrappers.<TeamMember>lambdaQuery().eq(TeamMember::getUserId, user.getId()));
        if (team == null) {
            return;
        }
        TeamMember member = new TeamMember();
        member.setId(IdGenerator.next("tm"));
        member.setTeamId(team.getId());
        member.setUserId(user.getId());
        member.setJoinedAt(TimeUtil.now());
        teamMemberMapper.insert(member);
    }

    /** 批量装载账号所属队伍。 */
    private Map<String, Team> loadTeams(List<User> users) {
        List<String> teamIds = users.stream().map(User::getTeamId).filter(java.util.Objects::nonNull).distinct()
                .toList();
        if (teamIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Team> teams = new HashMap<>();
        for (Team team : teamMapper.selectBatchIds(teamIds)) {
            teams.put(team.getId(), team);
        }
        return teams;
    }

    /** 读取 CSV 文本，限制为 UTF-8。 */
    private List<List<String>> parseCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationFailedException("file", "未接收到 CSV 文件");
        }
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return CsvUtil.parse(content);
        } catch (IOException exception) {
            log.error("读取 CSV 失败", exception);
            throw new ValidationFailedException("file", "CSV 读取失败");
        }
    }

    /** 识别表头列下标，无表头时返回空 Map。 */
    private Map<String, Integer> locateColumns(List<List<String>> rows) {
        List<String> header = rows.getFirst();
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String name = header.get(index) == null ? "" : header.get(index).trim().toLowerCase(Locale.ROOT);
            switch (name) {
                case "username", "password", "team", "note" -> columns.put(name, index);
                default -> {
                    // 忽略未知列，保持向后兼容
                }
            }
        }
        return columns.containsKey("username") ? columns : Map.of();
    }

    /** 安全读取指定列的值。 */
    private String valueOf(List<String> row, Integer index) {
        if (index == null || index < 0 || index >= row.size()) {
            return null;
        }
        return row.get(index);
    }

    /** 实体转响应模型。 */
    private ContestantVO toVO(User user, Team team) {
        TeamBriefVO teamBrief = team == null ? null
                : new TeamBriefVO(team.getId(), team.getName());
        return new ContestantVO(user.getId(), user.getUsername(), teamBrief, user.getNickname(),
                user.getNote(), user.getStatus().apiValue(), user.getCreatedAt(), user.getLastLoginAt());
    }
}

package com.qaqxianyuqaq.backend.util;

/**
 * Redis 缓存键集中定义。
 *
 * <p>所有键的存活时间与失效策略在此统一说明，避免缓存键散落在业务代码中。</p>
 */
public final class CacheKeys {

    /** 全局键前缀，避免与同实例其他应用冲突。 */
    private static final String PREFIX = "tq:";

    /** 令牌黑名单：键随令牌剩余有效期过期，登出或停用账号时写入。 */
    public static final String TOKEN_BLACKLIST = PREFIX + "auth:blacklist:%s";

    /** 登录失败计数：15 分钟窗口，登录成功或窗口过期后自动失效。 */
    public static final String LOGIN_FAILURE = PREFIX + "auth:failure:%s:%s";

    /** 接口限流计数：1 分钟窗口，随窗口自然过期。 */
    public static final String RATE_LIMIT = PREFIX + "ratelimit:%s:%s";

    /** 分布式锁：解题计分、二维码轮换等写路径使用，锁超时后自动释放。 */
    public static final String LOCK = PREFIX + "lock:%s";

    /** 选手端题目缓存：60 秒过期，题目发布/编辑/删除时立即删除。 */
    public static final String CHALLENGE_DETAIL = PREFIX + "challenge:%s";

    /** 选手端题目列表缓存：60 秒过期，题目状态变更时整体失效。 */
    public static final String CHALLENGE_LIST = PREFIX + "challenge:list";

    /** 当前赛事缓存：60 秒过期，赛事配置更新时删除。 */
    public static final String CURRENT_EVENT = PREFIX + "event:current";

    /** 排行榜缓存：30 秒过期，键包含赛事，避免不同赛事排名串用。 */
    public static final String LEADERBOARD = PREFIX + "leaderboard:%s:%s:%d:%d";

    /** 排行榜缓存前缀，用于解题或调整积分后整体失效。 */
    public static final String LEADERBOARD_PREFIX = PREFIX + "leaderboard:";

    private CacheKeys() {
    }

    /** 拼接令牌黑名单键。 */
    public static String tokenBlacklist(String jwtId) {
        return TOKEN_BLACKLIST.formatted(jwtId);
    }

    /** 拼接登录失败计数键。 */
    public static String loginFailure(String clientIp, String username) {
        return LOGIN_FAILURE.formatted(clientIp, username);
    }

    /** 拼接限流计数键。 */
    public static String rateLimit(String scope, String subject) {
        return RATE_LIMIT.formatted(scope, subject);
    }

    /** 拼接分布式锁键。 */
    public static String lock(String name) {
        return LOCK.formatted(name);
    }

    /** 拼接题目详情缓存键。 */
    public static String challengeDetail(String code) {
        return CHALLENGE_DETAIL.formatted(code);
    }

    /** 拼接排行榜缓存键。 */
    public static String leaderboard(String eventId, String scope, int page, int pageSize) {
        return LEADERBOARD.formatted(eventId, scope, page, pageSize);
    }
}

package com.qaqxianyuqaq.backend.exception;

/**
 * Flag 归属异常。
 *
 * <p>提交内容不是当前队伍容器获得的 Flag 时使用，禁止返回或暗示其他队伍的 Flag。</p>
 */
public class FlagOwnerException extends BusinessException {

    private final transient String teamName;

    private final transient String username;

    public FlagOwnerException(String teamName, String username) {
        super(ErrorCode.FLAG_OWNER_MISMATCH, "串通flag并不是好习惯");
        this.teamName = teamName;
        this.username = username;
    }

    /** 当前提交者所属队伍名称，仅用于脱敏审计日志。 */
    public String teamName() {
        return teamName;
    }

    /** 当前提交者用户名，仅用于脱敏审计日志。 */
    public String username() {
        return username;
    }
}

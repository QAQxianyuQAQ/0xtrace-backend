package com.qaqxianyuqaq.backend.service.challenge;

import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ChallengeRuntimeVO;

/** 队伍题目容器服务：启动、查询、停止容器并读取本队 Flag。 */
public interface ChallengeContainerService {

    /** 确保当前赛事队伍拥有该题目的唯一运行实例。 */
    ChallengeRuntimeVO ensureStarted(Challenge challenge, Team team);

    /** 查询当前队伍容器状态，尚未启动时返回 STOPPED。 */
    ChallengeRuntimeVO status(Challenge challenge, Team team);

    /** 停止并删除当前队伍容器。 */
    void stop(Challenge challenge, Team team);

    /** 在剩余时间不超过 30 分钟时将当前容器延长一小时。 */
    ChallengeRuntimeVO extend(Challenge challenge, Team team);

    /** 读取当前队伍已就绪的容器实例，未就绪时抛出冲突异常。 */
    ChallengeContainerInstance requireReady(Challenge challenge, Team team);

    /** 删除题目下全部队伍容器，供归档或删除时调用。 */
    void stopByChallenge(String challengeId);
}

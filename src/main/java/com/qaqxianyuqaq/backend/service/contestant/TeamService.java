package com.qaqxianyuqaq.backend.service.contestant;

/** 队伍查询服务：为其他模块提供队伍名称等只读信息。 */
public interface TeamService {

    /** 读取队伍名称，队伍不存在或未组队时返回 null。 */
    String teamName(String teamId);
}

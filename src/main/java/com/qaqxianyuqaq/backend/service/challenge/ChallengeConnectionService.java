package com.qaqxianyuqaq.backend.service.challenge;

import com.qaqxianyuqaq.backend.security.ConnectionTicketService;

import java.net.Socket;

/** 题目容器 TCP 连接服务：只允许连接票据所绑定的队伍实例。 */
public interface ChallengeConnectionService {

    /** 打开到票据绑定容器内部端口的 TCP 连接。 */
    Socket open(ConnectionTicketService.Ticket ticket);
}

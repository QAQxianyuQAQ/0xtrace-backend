package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.qaqxianyuqaq.backend.config.RuntimeProperties;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.enums.ContainerStatus;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeContainerInstanceMapper;
import com.qaqxianyuqaq.backend.security.ConnectionTicketService;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeConnectionService;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;

/** 题目容器内部 TCP 连接实现。 */
@Service
public class ChallengeConnectionServiceImpl implements ChallengeConnectionService {

    private final ChallengeContainerInstanceMapper instanceMapper;

    private final RuntimeProperties runtime;

    public ChallengeConnectionServiceImpl(ChallengeContainerInstanceMapper instanceMapper,
                                          TraceQuestProperties properties) {
        this.instanceMapper = instanceMapper;
        this.runtime = properties.getRuntime();
    }

    @Override
    public Socket open(ConnectionTicketService.Ticket ticket) {
        // 1. 校验票据绑定的实例仍然存在且属于票据中的队伍和题目
        ChallengeContainerInstance instance = instanceMapper.selectById(ticket.instanceId());
        if (instance == null || !ticket.challengeId().equals(instance.getChallengeId())
                || !ticket.teamId().equals(instance.getTeamId())) {
            throw new NotFoundException("题目容器实例不存在");
        }

        // 2. 只允许连接已就绪容器，拒绝任意客户端指定目标地址
        if (instance.getStatus() != ContainerStatus.READY || instance.getContainerIp() == null) {
            throw new ConflictException(ErrorCode.CONTAINER_NOT_READY, "本队题目容器尚未就绪");
        }

        // 3. 在内部网络建立 TCP 连接，并设置固定连接与读写超时
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(instance.getContainerIp(), instance.getContainerPort()),
                    (int) runtime.getHealthInterval().toMillis() * 5);
            socket.setSoTimeout((int) runtime.getWebsocketIdleTimeout().toMillis());
            socket.setKeepAlive(true);
            return socket;
        } catch (Exception exception) {
            throw new ConflictException(ErrorCode.CONTAINER_NOT_READY, "题目容器连接失败");
        }
    }
}

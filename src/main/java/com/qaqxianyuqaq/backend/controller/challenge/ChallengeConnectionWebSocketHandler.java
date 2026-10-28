package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.config.RuntimeProperties;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.security.ConnectionTicketService;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeConnectionService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** WebSocket 二进制帧与题目容器 TCP 流双向转发处理器。 */
@Component
public class ChallengeConnectionWebSocketHandler extends BinaryWebSocketHandler {

    private final ChallengeConnectionService connectionService;

    private final RuntimeProperties runtime;

    private final Map<String, BridgeSession> sessions = new ConcurrentHashMap<>();

    public ChallengeConnectionWebSocketHandler(ChallengeConnectionService connectionService,
                                               TraceQuestProperties properties) {
        this.connectionService = connectionService;
        this.runtime = properties.getRuntime();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 1. 从握手属性读取票据并连接票据绑定的容器
        ConnectionTicketService.Ticket ticket = (ConnectionTicketService.Ticket) session.getAttributes()
                .get(ChallengeConnectionHandshakeInterceptor.TICKET_ATTRIBUTE);
        if (ticket == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Socket socket = connectionService.open(ticket);
        BridgeSession bridge = new BridgeSession(session, socket);
        sessions.put(session.getId(), bridge);

        // 2. 使用虚拟线程持续读取 TCP 并转成 WebSocket 二进制帧
        Thread.ofVirtual().name("tcp-ws-" + session.getId()).start(() -> pumpTcpToWebSocket(bridge));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        // 1. 限制单帧大小，避免无界内存占用
        ByteBuffer payload = message.getPayload();
        if (payload.remaining() > runtime.getMaxWebsocketFrameBytes()) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }

        // 2. 将完整二进制帧写入当前票据绑定的容器 TCP 流
        BridgeSession bridge = sessions.get(session.getId());
        if (bridge == null) {
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        bridge.output().write(bytes);
        bridge.output().flush();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 只接受二进制字节流，避免浏览器端混入文本协议
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) {
            // 关闭失败时由连接超时和清理流程兜底
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeBridge(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        closeBridge(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /** 持续读取 TCP，收到 EOF 后关闭 WebSocket。 */
    private void pumpTcpToWebSocket(BridgeSession bridge) {
        byte[] buffer = new byte[8192];
        try (InputStream input = bridge.socket().getInputStream()) {
            int read;
            while (!bridge.closed() && (read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (bridge.session().isOpen()) {
                    byte[] payload = new byte[read];
                    System.arraycopy(buffer, 0, payload, 0, read);
                    bridge.session().sendMessage(new BinaryMessage(ByteBuffer.wrap(payload)));
                }
            }
            bridge.close();
        } catch (Exception exception) {
            bridge.close();
        }
    }

    /** 关闭 WebSocket 与容器 TCP 连接。 */
    private void closeBridge(String sessionId) {
        BridgeSession bridge = sessions.remove(sessionId);
        if (bridge != null) {
            bridge.close();
        }
    }

    /** 单个 WebSocket 会话对应的 TCP 桥接状态。 */
    private static final class BridgeSession {

        private final WebSocketSession session;

        private final Socket socket;

        private volatile boolean closed;

        private BridgeSession(WebSocketSession session, Socket socket) {
            this.session = session;
            this.socket = socket;
        }

        private WebSocketSession session() {
            return session;
        }

        private Socket socket() {
            return socket;
        }

        private OutputStream output() throws Exception {
            return socket.getOutputStream();
        }

        private boolean closed() {
            return closed;
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (Exception ignored) {
                // 连接关闭失败无需继续处理
            }
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception ignored) {
                // WebSocket 已由容器关闭时允许忽略
            }
        }
    }
}

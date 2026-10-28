package com.qaqxianyuqaq.backend.config;

import com.qaqxianyuqaq.backend.controller.challenge.ChallengeConnectionHandshakeInterceptor;
import com.qaqxianyuqaq.backend.controller.challenge.ChallengeConnectionWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** WebSocket 配置：只有持有短效票据的选手才能建立题目容器连接。 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChallengeConnectionWebSocketHandler handler;

    private final ChallengeConnectionHandshakeInterceptor interceptor;

    private final TraceQuestProperties properties;

    public WebSocketConfig(ChallengeConnectionWebSocketHandler handler,
                           ChallengeConnectionHandshakeInterceptor interceptor,
                           TraceQuestProperties properties) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 握手阶段由票据和 Origin 拦截器完成鉴权
        registry.addHandler(handler, "/api/v1/challenge-connections")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }

    /** 配置 Tomcat WebSocket 二进制缓冲区与空闲超时。 */
    @Bean
    @ConditionalOnProperty(prefix = "tracequest.runtime", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(properties.getRuntime().getMaxWebsocketFrameBytes());
        container.setMaxTextMessageBufferSize(1024);
        container.setMaxSessionIdleTimeout(properties.getRuntime().getWebsocketIdleTimeout().toMillis());
        return container;
    }
}

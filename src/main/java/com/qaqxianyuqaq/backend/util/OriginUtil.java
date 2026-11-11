package com.qaqxianyuqaq.backend.util;

import java.net.URI;
import java.util.List;

/** HTTP 写请求和 WebSocket 握手共用完整来源校验。 */
public final class OriginUtil {
    private OriginUtil() { }

    public static boolean isAllowed(String origin, URI target, List<String> allowed) {
        if (origin == null || origin.isBlank()) return false;
        try {
            URI source = URI.create(origin);
            if (!isWebOrigin(source)) return false;
            if (allowed != null && allowed.stream().anyMatch(origin::equalsIgnoreCase)) return true;
            URI targetOrigin = target == null ? null : URI.create(target.getScheme() + "://" + target.getRawAuthority());
            return isWebOrigin(targetOrigin) && source.getScheme().equalsIgnoreCase(targetOrigin.getScheme())
                    && source.getHost().equalsIgnoreCase(targetOrigin.getHost())
                    && effectivePort(source) == effectivePort(targetOrigin);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 不接受用户信息、路径、查询参数、片段或非 HTTP(S) 协议。 */
    private static boolean isWebOrigin(URI uri) {
        return uri != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && uri.getPort() >= -1 && uri.getPort() <= 65535;
    }

    /** 省略端口时按协议默认端口比较，避免把 :443 误判为跨源。 */
    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }
}

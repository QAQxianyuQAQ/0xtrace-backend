package com.qaqxianyuqaq.backend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import org.springframework.http.ResponseEntity;
import java.util.Set;

/** 审计快照只保留允许的业务摘要，旧记录在读取时也执行相同脱敏。 */
public final class AuditSnapshotUtil {
    private static final Set<String> FIELDS = Set.of("id", "userId", "username", "nickname", "role", "status",
            "eventId", "name", "team", "teamId", "teamName", "captainId", "members", "memberCount", "maxMembers",
            "captain", "joinedAt", "code", "title", "category", "difficulty", "points", "bonuses", "first",
            "second", "third", "other", "version", "containerEnabled", "runtimeType", "createdAt", "updatedAt",
            "publishedAt", "startsAt", "endsAt", "timezone", "maxTeamSize", "result", "pointsAwarded", "solvePlace",
            "source", "submissionId", "submittedAt", "size", "mimeType", "items");

    private AuditSnapshotUtil() {}

    public static Object body(Object result) {
        return result instanceof ResponseEntity<?> response ? response.getBody() : result;
    }

    public static String snapshot(Object result) {
        return sanitize(JsonUtil.toJson(body(result)));
    }

    public static String sanitize(String json) {
        JsonNode node = JsonUtil.fromJson(json, JsonNode.class);
        if (node == null || (!node.isObject() && !node.isArray())) return null;
        return JsonUtil.toJson(clean(node, 0));
    }

    private static JsonNode clean(JsonNode node, int depth) {
        if (depth > 10) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            ObjectNode safe = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> {
                if (FIELDS.contains(entry.getKey())) safe.set(entry.getKey(), clean(entry.getValue(), depth + 1));
            });
            return safe;
        }
        if (node.isArray()) {
            var safe = JsonNodeFactory.instance.arrayNode();
            for (int index = 0; index < Math.min(node.size(), 100); index++) safe.add(clean(node.get(index), depth + 1));
            return safe;
        }
        if (node.isTextual() && node.textValue().length() > 512) {
            return JsonNodeFactory.instance.textNode(node.textValue().substring(0, 512));
        }
        return node;
    }

    /** 在队伍对象被修改前即序列化，避免前后摘要指向同一个可变对象。 */
    public static String team(String eventId, String userId, Team team) {
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("eventId", eventId);
        snapshot.put("userId", userId);
        snapshot.put("teamId", team == null ? null : team.getId());
        snapshot.put("teamName", team == null ? null : team.getName());
        snapshot.put("captainId", team == null ? null : team.getCreatedBy());
        return JsonUtil.toJson(snapshot);
    }
}

package dev.aiboard.acceptance;

import dev.aiboard.common.BoardException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Shared verifier for project-policy approvals and task evidence. Never signs on behalf of an agent. */
@Component
public class AcceptanceAuthority {
    private final JsonNode authorities;

    public AcceptanceAuthority(@Value("${BOARD_ACCEPTANCE_AUTHORITIES:}") String config) {
        var json = JsonMapper.builder().build();
        authorities = config.isBlank() ? json.createObjectNode() : json.readTree(config);
    }

    public JsonNode verifySignature(String actor, String payload, String signature) {
        require(payload != null && payload.length() <= 48000, "驗收封包過大或空白");
        validateConfiguration();
        var key = authorities.path(actor == null ? "" : actor);
        String secret = key.path("secret").asText("");
        require(secret.length() >= 32, "驗收身分未設定");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            require(signature != null && signature.matches("[a-f0-9]{64}") && MessageDigest.isEqual(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(signature)), "驗收簽章無效");
        } catch (BoardException e) { throw e; }
        catch (Exception e) { throw new BoardException("驗收簽章無效"); }
        return key;
    }

    public void assertScope(JsonNode key, long projectId, String permission) {
        require(contains(key.path("projectIds"), Long.toString(projectId)) && contains(key.path("permissions"), permission),
                "驗收身分沒有此專案／操作權限");
    }

    private void validateConfiguration() {
        require(authorities.isObject(), "驗收身分設定必須是物件");
        var secrets = new HashSet<String>();
        for (var entry : authorities.properties()) {
            var key = entry.getValue();
            String secret = key.path("secret").asText("");
            require(key.path("secret").isTextual() && secret.length() >= 32 && secrets.add(secret),
                    "驗收金鑰必須至少 32 字元且各身分不可共用");
            require(key.path("permissions").isArray() && key.path("projectIds").isArray(), "驗收身分須明列權限與專案");
            if (contains(key.path("permissions"), "RUN")) {
                for (String permission : List.of("CONTRACT", "REVIEW", "EXCEPTION", "PROJECT_POLICY"))
                    require(!contains(key.path("permissions"), permission), "執行器金鑰不得兼任需求核准、審查或例外核准");
            }
        }
    }

    private boolean contains(JsonNode list, String value) {
        for (var item : list) if (item.asText().equals(value)) return true;
        return false;
    }
    private void require(boolean valid, String message) { if (!valid) throw new BoardException(message); }
}

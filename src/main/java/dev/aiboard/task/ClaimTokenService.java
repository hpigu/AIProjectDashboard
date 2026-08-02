package dev.aiboard.task;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 產生高熵 claim token 並只保存安全雜湊。token 原文只在認領當下回傳一次，
 * 之後任何地方（DB、task_log、application log）都只看得到 hash，看不到原文。
 *
 * 不依賴 spring-security-crypto，改用 JDK 內建的 SecureRandom + SHA-256，
 * 避免為此新增第三方依賴。claim token 是「一次性、短生命週期的所有權憑證」，
 * 不是使用者密碼，不需要 bcrypt 等自適應雜湊的 slow-hash 特性。
 */
@Component
public class ClaimTokenService {

    private static final int TOKEN_BYTES = 32; // 256-bit 熵
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final SecureRandom secureRandom = new SecureRandom();

    /** 產生一組新 token（回傳原文，呼叫端負責只回傳一次、絕不落庫）。 */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 對 token 原文取安全雜湊，這是唯一允許寫入 DB 的形式。 */
    public String hash(String token) {
        if (token == null) {
            throw new IllegalArgumentException("token 不可為 null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " 不受支援", e);
        }
    }

    /** 常數時間比較，避免用長度或提早跳出洩漏雜湊資訊（timing attack）。 */
    public boolean matches(String providedToken, String storedHash) {
        if (providedToken == null || storedHash == null) {
            return false;
        }
        String providedHash = hash(providedToken);
        return MessageDigest.isEqual(
                providedHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}

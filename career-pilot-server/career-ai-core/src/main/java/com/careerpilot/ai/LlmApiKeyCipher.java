package com.careerpilot.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.careerpilot.common.BusinessException;
import com.careerpilot.config.LlmProperties;

/**
 * 模型服务 API Key 的加解密（M14；《系统架构设计》§7.4）。
 *
 * <p>算法 **AES-GCM**：128 位认证标签、12 字节随机 nonce（每次加密独立生成）。
 * 密文与 nonce 分别 Base64 后落两列（{@code api_key_ciphertext} / {@code api_key_nonce}），
 * **不存明文**；解密失败（密钥变更或密文被篡改）抛业务异常，不泄露内部细节。
 *
 * <p>主密钥来自 {@code app.llm.encryption-key}（application-local.yml / 环境变量，不入库）：
 * Base64 解出 32 字节则直接使用，否则对其 SHA-256 派生 32 字节密钥。
 * 未配置时降级为开发用 fallback 密钥并打印 WARN——**仅限本地开发**。
 */
@Component
public class LlmApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(LlmApiKeyCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int NONCE_LENGTH = 12;

    private static final int TAG_LENGTH_BITS = 128;

    /** 开发用 fallback 主密钥（仅在 app.llm.encryption-key 未配置时使用，启动有 WARN） */
    private static final String DEV_FALLBACK_KEY = "career-pilot-dev-only-llm-key";

    private final SecretKeySpec secretKey;

    private final SecureRandom secureRandom = new SecureRandom();

    public LlmApiKeyCipher(LlmProperties properties) {
        if (properties.hasEncryptionKey()) {
            this.secretKey = new SecretKeySpec(resolveKeyBytes(properties.encryptionKey()), "AES");
        }
        else {
            log.warn("app.llm.encryption-key 未配置，模型服务 API Key 使用开发用 fallback 密钥加密"
                    + "（仅限本地开发；演示与部署必须显式配置，《系统架构设计》§7.4）");
            this.secretKey = new SecretKeySpec(resolveKeyBytes(DEV_FALLBACK_KEY), "AES");
        }
    }

    /** 加密结果：密文与 nonce 均为 Base64 字符串 */
    public record Encrypted(String ciphertext, String nonce) {
    }

    public Encrypted encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("待加密内容不能为空");
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(nonce));
        }
        catch (Exception ex) {
            // 不回显明文或异常详情（《系统架构设计》§7.4）
            log.error("模型服务 API Key 加密失败：异常={}", ex.getClass().getSimpleName());
            throw BusinessException.internalError("API Key 加密失败，请稍后重试");
        }
    }

    public String decrypt(String ciphertext, String nonce) {
        if (ciphertext == null || ciphertext.isBlank() || nonce == null || nonce.isBlank()) {
            throw BusinessException.internalError("模型服务 API Key 密文不完整");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, Base64.getDecoder().decode(nonce)));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(decrypted, StandardCharsets.UTF_8);
        }
        catch (Exception ex) {
            log.error("模型服务 API Key 解密失败（密钥变更或密文损坏）：异常={}", ex.getClass().getSimpleName());
            throw BusinessException.internalError("API Key 解密失败，请重新配置该服务");
        }
    }

    /**
     * 掩码：前 3 位 + {@code ***} + 后 3 位（《API接口设计》§3A.1）。
     * 长度不足 7 时整体打码，任何情况下不暴露可识别片段。
     */
    public static String mask(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        if (plainText.length() <= 6) {
            return "***";
        }
        return plainText.substring(0, 3) + "***" + plainText.substring(plainText.length() - 3);
    }

    private static byte[] resolveKeyBytes(String key) {
        try {
            byte[] decoded = Base64.getDecoder().decode(key);
            if (decoded.length == 32 || decoded.length == 16) {
                return decoded;
            }
        }
        catch (IllegalArgumentException ignored) {
            // 非 Base64：走 SHA-256 派生
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception ex) {
            throw new IllegalStateException("无法派生模型服务加密密钥", ex);
        }
    }

}

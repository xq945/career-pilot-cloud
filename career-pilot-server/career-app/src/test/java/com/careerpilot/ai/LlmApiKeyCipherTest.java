package com.careerpilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.careerpilot.common.BusinessException;
import com.careerpilot.config.LlmProperties;

/**
 * 模型服务 API Key 加解密（M14，《系统架构设计》§7.4）：
 * AES-GCM 往返、每次加密 nonce 随机（同明文不同密文）、篡改/密钥不符时解密失败、
 * 掩码不含明文。
 */
class LlmApiKeyCipherTest {

    private final LlmApiKeyCipher cipher = new LlmApiKeyCipher(
            new LlmProperties("test-master-key-for-unit-tests", null, null));

    @Test
    @DisplayName("加解密往返还原原文")
    void roundTrip() {
        LlmApiKeyCipher.Encrypted encrypted = cipher.encrypt("sk-abcdefghijklmn");
        assertEquals("sk-abcdefghijklmn", cipher.decrypt(encrypted.ciphertext(), encrypted.nonce()));
    }

    @Test
    @DisplayName("同一明文两次加密产生不同密文（nonce 随机）")
    void nonceIsRandomPerEncryption() {
        LlmApiKeyCipher.Encrypted first = cipher.encrypt("sk-abcdefghijklmn");
        LlmApiKeyCipher.Encrypted second = cipher.encrypt("sk-abcdefghijklmn");
        assertNotEquals(first.nonce(), second.nonce());
        assertNotEquals(first.ciphertext(), second.ciphertext());
    }

    @Test
    @DisplayName("密文被篡改时解密失败且抛内部错误（50000）")
    void tamperedCiphertextFailsDecryption() {
        LlmApiKeyCipher.Encrypted encrypted = cipher.encrypt("sk-abcdefghijklmn");
        String tampered = encrypted.ciphertext().substring(0, encrypted.ciphertext().length() - 2) + "AA";
        BusinessException ex = assertThrows(BusinessException.class,
                () -> cipher.decrypt(tampered, encrypted.nonce()));
        assertEquals(50000, ex.getCode());
    }

    @Test
    @DisplayName("不同主密钥无法解密（密钥变更场景）")
    void differentMasterKeyCannotDecrypt() {
        LlmApiKeyCipher.Encrypted encrypted = cipher.encrypt("sk-abcdefghijklmn");
        LlmApiKeyCipher other = new LlmApiKeyCipher(
                new LlmProperties("another-master-key-for-unit-tests", null, null));
        assertThrows(BusinessException.class,
                () -> other.decrypt(encrypted.ciphertext(), encrypted.nonce()));
    }

    @Test
    @DisplayName("掩码：前 3 后 3，短串全打码，null/空白返回 null")
    void maskRules() {
        assertEquals("sk-***lmn", LlmApiKeyCipher.mask("sk-abcdefghijklmn"));
        assertEquals("sk-***cde", LlmApiKeyCipher.mask("sk-abcde"));
        assertEquals("***", LlmApiKeyCipher.mask("short"));
        assertNull(LlmApiKeyCipher.mask(null));
        assertNull(LlmApiKeyCipher.mask("  "));
    }

    @Test
    @DisplayName("掩码结果不含完整明文")
    void maskDoesNotContainFullPlaintext() {
        String plain = "sk-abcdefghijklmn";
        String masked = LlmApiKeyCipher.mask(plain);
        assertNotEquals(plain, masked);
        org.junit.jupiter.api.Assertions.assertFalse(masked.contains(plain));
    }

}

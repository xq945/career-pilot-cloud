package com.careerpilot.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.careerpilot.common.BusinessException;

/**
 * baseUrl SSRF 防护（阶段 1 · B4）：本机/内网/元数据地址必须拒绝，
 * 公网地址放行；只接受 http(s)。
 */
class LlmBaseUrlValidatorTest {

    @Test
    void rejectsLoopbackAndPrivateAddresses() {
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://127.0.0.1/v1"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://localhost/v1"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://10.0.0.1/v1"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://172.16.0.1/v1"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://192.168.1.1/v1"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://169.254.169.254/latest/meta-data"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://[::1]/v1"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("http://[fc00::1]/v1"));
    }

    @Test
    void rejectsNonHttpSchemesAndMalformedUrls() {
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("file:///etc/passwd"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("ftp://example.com/v1"));
        assertThrows(BusinessException.class, () -> LlmBaseUrlValidator.validate("not-a-url"));
    }

    @Test
    void acceptsPublicAddresses() {
        assertDoesNotThrow(() -> LlmBaseUrlValidator.validate("https://8.8.8.8/v1"));
        assertDoesNotThrow(() -> LlmBaseUrlValidator.validate("http://1.1.1.1:8080/v1"));
    }
}

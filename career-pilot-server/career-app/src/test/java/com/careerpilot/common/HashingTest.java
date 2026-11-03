package com.careerpilot.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link Hashing} 的口径护栏。
 *
 * source_hash 是全项目共享的落库值：编码或大小写一旦被改动，**存量分析会静默集体失配**
 * （不报错，只表现为"全部已过期 / 每次都重新调模型"）。已知向量是唯一能在改动时报警的手段。
 */
class HashingTest {

    @Test
    void sha256Hex_matchesKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Hashing.sha256Hex("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Hashing.sha256Hex(""));
    }

    @Test
    void sha256Hex_chineseTextProducesLowercaseHex64() {
        // 中文按 UTF-8 编码（而非平台默认编码），输出 64 位小写十六进制，与列 VARCHAR(64) 对齐
        String hash = Hashing.sha256Hex("岗位职责：参与核心业务系统开发");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "应为小写十六进制，实际：" + hash);
        assertNotEquals(Hashing.sha256Hex("岗位职责：参与核心业务系统开发 "), hash,
                "内容不同（尾随空格）必须得到不同 hash——岗位描述不做 trim 的口径依赖此性质");
    }

    @Test
    void sha256Hex_nullRejected() {
        assertThrows(NullPointerException.class, () -> Hashing.sha256Hex(null));
    }

}

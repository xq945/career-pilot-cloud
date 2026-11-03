package com.careerpilot.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 源内容指纹（source_hash）的计算工具（《数据库设计》5.2）。
 *
 * **全项目唯一实现**：分析落库时写入的 source_hash、复用判断与岗位的过期（stale）判定
 * 必须全部经由本类。M5 起由简历与岗位两个模块共用——判定与写入若各有一套实现，
 * 口径漂移不会报错，只会表现为"每次分析都重新调模型"或"全部标记为过期"。
 *
 * 计算基准（《数据库设计》5.2，必须固定）：
 * - 简历：对**入库后的** {@code resume.content}（已按 20000 字符截断）计算；
 * - 岗位：对 {@code job.description} 计算（入库前已按 10000 字符校验，不做截断）。
 */
public final class Hashing {

    private Hashing() {
    }

    /**
     * SHA-256（UTF-8），小写十六进制 64 位，与 source_hash 列 VARCHAR(64) 对齐。
     *
     * **口径钉死**：一旦改变编码或大小写，存量 source_hash 会集体失配
     * （表现为既有分析静默全部变为"已过期"），请勿改动本方法的任何细节。
     */
    public static String sha256Hex(String text) {
        Objects.requireNonNull(text, "text");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            // JDK 必然内置 SHA-256，属不可达分支
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

}

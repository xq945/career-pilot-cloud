package com.careerpilot.service.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.careerpilot.common.BusinessException;
import com.careerpilot.config.FileStorageProperties;

/**
 * 本地存储实现 `read` 的单测（简历文件查看/下载的后端基础）。
 *
 * 用 JUnit 的临时目录替代真实 storage-root，不依赖 MySQL 与 Spring 上下文。
 */
class LocalFileStorageServiceTest {

    @TempDir
    Path storageRoot;

    private LocalFileStorageService storage;

    @BeforeEach
    void setUp() {
        // FileStorageProperties 是 storage-root 的唯一解析点（构造时确保目录存在）
        storage = new LocalFileStorageService(new FileStorageProperties(storageRoot.toString()));
    }

    @Test
    void read_existingFile_returnsExactBytes() throws IOException {
        Path file = storageRoot.resolve("13/abc123.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "张三的简历内容", UTF_8);

        byte[] content = storage.read("13/abc123.txt");

        // 逐字节一致（中文无损坏）；下载接口的响应体就是这份字节
        assertThat(content).isEqualTo("张三的简历内容".getBytes(UTF_8));
    }

    @Test
    void read_missingFile_throwsNotFound() {
        assertThatThrownBy(() -> storage.read("13/not-exists.txt"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40400));
    }

    @Test
    void read_pathTraversal_throwsNotFound() throws IOException {
        // 根目录之外的敏感文件（模拟被篡改的存储标识试图读取它）
        Path outside = storageRoot.getParent().resolve("outside-secret.txt");
        Files.writeString(outside, "secret", UTF_8);

        assertThatThrownBy(() -> storage.read("../outside-secret.txt"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40400));
    }

}

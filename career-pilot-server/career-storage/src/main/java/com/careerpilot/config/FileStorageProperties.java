package com.careerpilot.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置（《系统架构设计》7.1，M2 定死）。
 *
 * 本类是 storage-root 的唯一解析点，供 LocalFileStorageService 与 WebMvcConfig 共用，
 * 避免两处各自用 {@code @Value} 解析导致不一致。
 *
 * 根目录在构造时创建：配置错误（如指向不可写路径）应在**启动时**暴露，
 * 而不是等到首次上传才失败。
 */
@Component
public class FileStorageProperties {

    private final Path root;

    public FileStorageProperties(@Value("${app.file.storage-root}") String storageRoot) {
        this.root = Path.of(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        }
        catch (IOException ex) {
            throw new IllegalStateException("无法创建文件存储根目录：" + this.root, ex);
        }
    }

    /** 存储根目录（绝对路径、已 normalize、必然存在） */
    public Path getRoot() {
        return root;
    }

}

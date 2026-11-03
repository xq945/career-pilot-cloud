package com.careerpilot.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.careerpilot.common.BusinessException;
import com.careerpilot.config.FileStorageProperties;

/**
 * 本地磁盘存储实现（缺省，app.file.storage-type=local 或未配置时启用）。
 *
 * 路径规则（《系统架构设计》7.1）：{storage-root}/{userId}/{uuid}.{ext}；
 * 写入前对拼接路径 normalize 并校验仍在 storage-root 之内（防穿越）。
 * 对外访问经 WebMvcConfig 的 /uploads/** 静态映射（两处前缀必须一致）。
 */
@Service
@ConditionalOnProperty(name = "app.file.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    /** 对外访问前缀，与 WebMvcConfig 的资源映射路径保持一致 */
    public static final String PUBLIC_URL_PREFIX = "/uploads/";

    private final FileStorageProperties properties;

    public LocalFileStorageService(FileStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String store(Long userId, MultipartFile file, String extension) {
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        // 存储标识始终以 '/' 分隔：不落 Path.toString()（Windows 会得到反斜杠，将来拼 URL 出错）
        String key = userId + "/" + fileName;
        Path target = resolveWithinRoot(key);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
        }
        catch (IOException ex) {
            // 只记相对标识，不记本机绝对路径（《系统架构设计》7 节）
            log.error("文件保存失败：{}", key, ex);
            throw new BusinessException(50000, 500, "文件保存失败，请稍后重试");
        }
        return key;
    }

    @Override
    public void deleteQuietly(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveWithinRoot(key));
        }
        catch (Exception ex) {
            // 清理是尽力而为：失败不阻塞主流程，只留日志
            log.warn("文件删除失败（已忽略）：{}", key);
        }
    }

    @Override
    public String publicUrl(String key) {
        return PUBLIC_URL_PREFIX + key;
    }

    @Override
    public byte[] read(String key) {
        Path target;
        try {
            target = resolveWithinRoot(key);
        }
        catch (IllegalArgumentException ex) {
            // 存储标识非法（正常流程不会发生，属数据被篡改）：按"资源不存在"返回，不泄露内部细节
            throw BusinessException.notFound("简历文件不存在");
        }
        try {
            return Files.readAllBytes(target);
        }
        catch (NoSuchFileException ex) {
            // 文件缺失（被手工清理、存储实现切换等）
            throw BusinessException.notFound("简历文件不存在");
        }
        catch (IOException ex) {
            log.error("文件读取失败：{}", key, ex);
            throw new BusinessException(50000, 500, "文件读取失败，请稍后重试");
        }
    }

    /** 相对标识 → 绝对路径；normalize 后必须仍位于 storage-root 之内 */
    private Path resolveWithinRoot(String key) {
        Path root = properties.getRoot();
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件标识：" + key);
        }
        return resolved;
    }

}

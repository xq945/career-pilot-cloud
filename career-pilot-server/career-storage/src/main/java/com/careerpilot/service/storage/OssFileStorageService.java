package com.careerpilot.service.storage;

import java.io.InputStream;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;

import com.careerpilot.common.BusinessException;

import jakarta.annotation.PreDestroy;

/**
 * 阿里云 OSS 存储实现（app.file.storage-type=oss 时启用）。
 *
 * 对象 key 与本地实现同为 {userId}/{uuid}.{ext}（存储标识与实现无关）。
 * 上传后对对象设置**公共读 ACL**——失败仅记日志、不阻断上传：能否匿名访问最终
 * 由 bucket 策略决定（bucket 必须关闭「阻止公共访问」，见《系统架构设计》7.1
 * 与 application-local.yml.example 的准备事项）。
 */
@Service
@ConditionalOnProperty(name = "app.file.storage-type", havingValue = "oss")
public class OssFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(OssFileStorageService.class);

    private final OSS client;

    private final String bucket;

    private final String endpoint;

    public OssFileStorageService(@Value("${app.file.oss.endpoint:}") String endpoint,
                                 @Value("${app.file.oss.bucket:}") String bucket,
                                 @Value("${app.file.oss.access-key-id:}") String accessKeyId,
                                 @Value("${app.file.oss.access-key-secret:}") String accessKeySecret) {
        // 配置缺失在启动时即失败（fail-fast），而不是等到首次上传
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)
                || !StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new IllegalStateException(
                    "storage-type=oss 需要完整配置 app.file.oss.{endpoint,bucket,access-key-id,access-key-secret}");
        }
        this.endpoint = endpoint;
        this.bucket = bucket;
        // OSSClient 线程安全，单例复用（SDK 推荐用法）
        this.client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @Override
    public String store(Long userId, MultipartFile file, String extension) {
        String key = userId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        try (InputStream in = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(file.getContentType());
            client.putObject(bucket, key, in, metadata);
        }
        catch (Exception ex) {
            log.error("OSS 上传失败：{}", key, ex);
            throw new BusinessException(50000, 500, "文件保存失败，请稍后重试");
        }
        setPublicReadAcl(key);
        return key;
    }

    @Override
    public void deleteQuietly(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            client.deleteObject(bucket, key);
        }
        catch (Exception ex) {
            log.warn("OSS 删除失败（已忽略）：{}", key);
        }
    }

    @Override
    public String publicUrl(String key) {
        return "https://" + bucket + "." + endpoint + "/" + key;
    }

    @Override
    public byte[] read(String key) {
        try (OSSObject object = client.getObject(bucket, key)) {
            return object.getObjectContent().readAllBytes();
        }
        catch (Exception ex) {
            // NoSuchKey 等对象不存在的错误码按"资源不存在"返回；其余（网络、鉴权）按失败处理
            if (ex instanceof OSSException ossEx && "NoSuchKey".equals(ossEx.getErrorCode())) {
                throw BusinessException.notFound("简历文件不存在");
            }
            log.error("OSS 读取失败：{}", key, ex);
            throw new BusinessException(50000, 500, "文件读取失败，请稍后重试");
        }
    }

    /** 对象级公共读；失败仅记日志（bucket 已公共读或策略受限时不影响上传本身） */
    private void setPublicReadAcl(String key) {
        try {
            client.setObjectAcl(bucket, key, CannedAccessControlList.PublicRead);
        }
        catch (Exception ex) {
            log.warn("OSS 对象 ACL 设置失败（头像将无法匿名访问，请检查 bucket 的「阻止公共访问」配置）：{}；原因：{}",
                    key, ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        client.shutdown();
    }

}

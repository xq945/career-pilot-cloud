package com.careerpilot.service.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储设施（《系统架构设计》7.1，M2 定死）：本地磁盘与阿里云 OSS 两种实现，
 * 由配置 app.file.storage-type 选择（local 为缺省）。
 *
 * 数据库统一保存**与实现无关的存储标识**（{@code {userId}/{uuid}.{ext}}）：
 * 本地实现将其解释为相对 storage-root 的路径，OSS 实现将其解释为对象 key。
 * 切换实现后既有文件不可读，属已知限制。
 *
 * 文件按**可访问性**分两类，走两条不同的通路：
 *
 * - **公开文件**（头像）：`store` → `publicUrl` 得到可匿名访问的地址，由 `/uploads/**`
 *   静态映射（本地）或 OSS 公共读 ACL 提供；
 * - **私有文件**（简历原文）：`store` → `read` 读出内容，由**带鉴权与归属校验的业务接口**
 *   下发（见《API接口设计》§4.7），**不得**经 `publicUrl` 暴露。
 */
public interface FileStorageService {

    /**
     * 保存文件，返回存储标识（{@code {userId}/{uuid}.{ext}}）。
     *
     * @param userId    当前用户 ID（来自认证上下文，不是前端传参）
     * @param file      上传的文件
     * @param extension 扩展名（不含点号；由调用方依据文件魔数推导，不信任原始文件名）
     */
    String store(Long userId, MultipartFile file, String extension);

    /** 尽力删除：任何失败只记日志、不抛出（调用方流程不因清理失败而中断） */
    void deleteQuietly(String key);

    /** 存储标识 → 可直接用于 {@code <img src>} 的访问地址（本地为 /uploads/…，OSS 为完整 URL）；**仅用于公开文件** */
    String publicUrl(String key);

    /**
     * 读取文件内容（**私有文件通路**：不产生公开地址，由调用方完成归属校验后自行下发）。
     *
     * 简历有 10 MB 上限（上传时限制），读入内存可接受；若将来支持更大文件再改流式。
     *
     * @throws com.careerpilot.common.BusinessException 404/40400 文件不存在（含存储标识非法）
     */
    byte[] read(String key);

}

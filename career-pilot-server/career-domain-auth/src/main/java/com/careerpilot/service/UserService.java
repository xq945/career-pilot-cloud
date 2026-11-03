package com.careerpilot.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.UpdateProfileRequest;
import com.careerpilot.dto.UserProfile;
import com.careerpilot.entity.SysUser;
import com.careerpilot.mapper.SysUserMapper;
import com.careerpilot.service.storage.FileStorageService;

/**
 * 用户资料服务（《API接口设计》3.3 / 3.4 / 3.6 / 3.7）。
 *
 * 文件存储细节全部委托 {@link FileStorageService}；本类负责业务规则
 * （2 MB 上限、扩展名+MIME+魔数三重校验、上传即替换、移除幂等）与用户视图装配。
 *
 * 写路径约定：头像上传/移除**不加 @Transactional**——文件删除不是事务资源，
 * 顺序固定为「写新文件 → UPDATE（自动提交）→ 删旧文件」，删旧失败不阻塞。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 头像大小上限：2 MB（《API接口设计》3.6） */
    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    /** JPEG 家族的扩展名，落盘统一归一为 jpg */
    private static final Set<String> JPEG_EXTENSIONS = Set.of("jpg", "jpeg");

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** PNG 完整 8 字节签名（只比前 4 字节会放过部分损坏文件） */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final SysUserMapper userMapper;

    private final FileStorageService fileStorageService;

    public UserService(SysUserMapper userMapper, FileStorageService fileStorageService) {
        this.userMapper = userMapper;
        this.fileStorageService = fileStorageService;
    }

    /** 读取当前用户资料 */
    public UserProfile getProfile(Long userId) {
        return toView(requireUser(userId));
    }

    /** 更新昵称（空串或全空白 = 清空，写 NULL） */
    public UserProfile updateProfile(Long userId, UpdateProfileRequest request) {
        SysUser user = requireUser(userId);
        String nickname = normalizeNickname(request.getNickname());

        // 注意：updateById 默认忽略 null 字段，清空必须走 lambdaUpdate().set(...) 显式写 null
        int rows = userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .set(SysUser::getNickname, nickname));
        if (rows == 0) {
            throw BusinessException.notFound("用户不存在");
        }
        user.setNickname(nickname);
        return toView(user);
    }

    /** 上传头像：校验 → 存新文件 → 更新 DB → 删旧文件 */
    public UserProfile updateAvatar(Long userId, MultipartFile file) {
        SysUser user = requireUser(userId);
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择要上传的头像文件");
        }
        // 先大小后类型：超限文件不必读取内容
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw BusinessException.payloadTooLarge("头像文件不能超过 2 MB");
        }
        String extension = resolveExtension(file);

        String newKey = fileStorageService.store(userId, file, extension);
        try {
            int rows = userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getId, userId)
                    .set(SysUser::getAvatar, newKey));
            if (rows == 0) {
                throw BusinessException.notFound("用户不存在");
            }
        }
        catch (RuntimeException ex) {
            // DB 更新失败：删掉刚落盘的新文件，避免产生孤儿文件
            fileStorageService.deleteQuietly(newKey);
            throw ex;
        }

        String oldKey = user.getAvatar();
        if (oldKey != null && !oldKey.equals(newKey)) {
            fileStorageService.deleteQuietly(oldKey);
        }
        user.setAvatar(newKey);
        return toView(user);
    }

    /** 移除头像（幂等：无头像时直接返回当前资料） */
    public UserProfile removeAvatar(Long userId) {
        SysUser user = requireUser(userId);
        String oldKey = user.getAvatar();
        if (oldKey == null) {
            return toView(user);
        }
        int rows = userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .set(SysUser::getAvatar, null));
        if (rows == 0) {
            throw BusinessException.notFound("用户不存在");
        }
        fileStorageService.deleteQuietly(oldKey);
        user.setAvatar(null);
        return toView(user);
    }

    /**
     * 用户视图的**唯一装配点**（register / login / 资料接口共用）：
     * 数据库中的存储标识在此转换为可访问地址，其余各处不得重复实现该转换。
     * 登录响应也经此处——顶栏登录后即可直接渲染头像。
     */
    public UserProfile toView(SysUser user) {
        UserProfile profile = new UserProfile();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setEmail(user.getEmail());
        profile.setNickname(user.getNickname());
        profile.setAvatar(user.getAvatar() == null ? null : fileStorageService.publicUrl(user.getAvatar()));
        return profile;
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }

    /** 空串/全空白 → null（清空）；其余 trim 后返回 */
    private String normalizeNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String trimmed = nickname.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 三重校验（扩展名 + MIME + 魔数）且三者必须互相一致；
     * 返回依据魔数推导的规范化扩展名（jpeg → jpg），不信任原始文件名。
     */
    private String resolveExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        String extension = StringUtils.getFilenameExtension(name);
        extension = extension == null ? "" : extension.toLowerCase();
        String mimeType = file.getContentType();
        byte[] header = readHeader(file);

        if (JPEG_EXTENSIONS.contains(extension)
                && "image/jpeg".equals(mimeType)
                && startsWith(header, JPEG_MAGIC)) {
            return "jpg";
        }
        if ("png".equals(extension)
                && "image/png".equals(mimeType)
                && startsWith(header, PNG_MAGIC)) {
            return "png";
        }
        throw BusinessException.unsupportedMediaType("仅支持 JPG / PNG 格式的图片");
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(8);
        }
        catch (IOException ex) {
            log.warn("读取上传文件头失败");
            throw BusinessException.unsupportedMediaType("文件内容无法识别");
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

}

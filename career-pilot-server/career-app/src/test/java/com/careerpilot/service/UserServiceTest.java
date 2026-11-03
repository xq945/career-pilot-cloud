package com.careerpilot.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.UserProfile;
import com.careerpilot.entity.SysUser;
import com.careerpilot.mapper.SysUserMapper;
import com.careerpilot.service.storage.FileStorageService;

/**
 * 头像上传「三重校验」的单测（《测试与验收方案》§1.1 的「文件校验与路径」目标，
 * 判定规则出处《API接口设计》3.6；简历模块同构复用，见 {@link ResumeServiceTest}）。
 *
 * 覆盖：JPG/PNG 的一致通过路径与 jpeg → jpg 归一、三种不符的拒绝矩阵、
 * PNG 完整 8 字节签名、2 MB 大小边界（整与 +1 字节）与空文件、
 * 以及 400 / 413 / 415 错误码的分工。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    /** 与生产代码同值；测试内独立声明，保证「判定的依据」被独立验证 */
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private FileStorageService fileStorageService;

    private UserService service;

    @BeforeEach
    void setUp() {
        // 所有路径（含各类校验失败）都先经 requireUser，故此处统一打桩
        when(userMapper.selectById(1L)).thenReturn(user());
        service = new UserService(userMapper, fileStorageService);
    }

    // ---------------------------------------------------------------- 通过路径：一致与归一

    @Test
    void updateAvatar_jpegFamilyNormalizedToJpg() {
        stubSuccessfulUpdate();

        UserProfile profile = service.updateAvatar(1L, file(
                "头像.JPEG", "image/jpeg", header(JPEG_MAGIC, "压缩数据")));

        // jpg / jpeg / 大小写一律归一为 jpg（落盘标识与扩展名实现无关）
        verify(fileStorageService).store(eq(1L), any(), eq("jpg"));
        assertThat(profile.getAvatar()).isNotNull();
    }

    @Test
    void updateAvatar_png_passes() {
        stubSuccessfulUpdate();

        service.updateAvatar(1L, file("头像.png", "image/png", header(PNG_MAGIC, "压缩数据")));

        verify(fileStorageService).store(eq(1L), any(), eq("png"));
    }

    // ---------------------------------------------------------------- 拒绝矩阵：三者须互相一致

    @Test
    void updateAvatar_extensionMimeMismatch_throws415() {
        assertRejected415(file("头像.jpg", "image/png", header(JPEG_MAGIC, "数据")));
    }

    @Test
    void updateAvatar_forgedMagic_throws415() {
        // 声明为 JPEG 的文本文件——扩展名与 MIME 相符，内容特征不符
        assertRejected415(file("头像.jpg", "image/jpeg", "这不是图片".getBytes(UTF_8)));
    }

    @Test
    void updateAvatar_truncatedPngSignature_throws415() {
        // 前 4 字节为 PNG 特征、后 4 字节不符：PNG 比的是完整 8 字节签名，
        // 只比前 4 字节会放过部分损坏文件
        byte[] damaged = {(byte) 0x89, 'P', 'N', 'G', 0x00, 0x00, 0x00, 0x00, 0x01};
        assertRejected415(file("头像.png", "image/png", damaged));
    }

    @Test
    void updateAvatar_unsupportedType_throws415() {
        assertRejected415(file("头像.gif", "image/gif", "GIF89a".getBytes(UTF_8)));
    }

    // ---------------------------------------------------------------- 大小与空文件边界

    @Test
    void updateAvatar_exactTwoMb_passes() {
        stubSuccessfulUpdate();
        byte[] content = new byte[MAX_AVATAR_BYTES];
        System.arraycopy(JPEG_MAGIC, 0, content, 0, JPEG_MAGIC.length);

        service.updateAvatar(1L, file("头像.jpg", "image/jpeg", content));

        verify(fileStorageService).store(eq(1L), any(), eq("jpg"));
    }

    @Test
    void updateAvatar_overTwoMb_throws413() {
        byte[] content = new byte[MAX_AVATAR_BYTES + 1];

        assertThatThrownBy(() -> service.updateAvatar(1L, file("头像.jpg", "image/jpeg", content)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(41300));
    }

    @Test
    void updateAvatar_emptyFile_throws400() {
        assertThatThrownBy(() -> service.updateAvatar(1L, file("头像.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
    }

    @Test
    void updateAvatar_nullFile_throws400() {
        assertThatThrownBy(() -> service.updateAvatar(1L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
    }

    // ---------------------------------------------------------------- 测试数据

    private void stubSuccessfulUpdate() {
        when(fileStorageService.store(any(), any(), any())).thenReturn("1/new-avatar");
        when(userMapper.update(any(), any())).thenReturn(1);
        when(fileStorageService.publicUrl(any()))
                .thenReturn("http://localhost:8080/uploads/1/new-avatar.jpg");
    }

    private void assertRejected415(MockMultipartFile file) {
        assertThatThrownBy(() -> service.updateAvatar(1L, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(41500));
    }

    private static SysUser user() {
        SysUser user = new SysUser();
        user.setId(1L);
        return user;
    }

    private static MockMultipartFile file(String originalFilename, String contentType, byte[] content) {
        return new MockMultipartFile("file", originalFilename, contentType, content);
    }

    /** 魔数前缀 + 尾部内容，模拟真实文件头 */
    private static byte[] header(byte[] magic, String tail) {
        byte[] tailBytes = tail.getBytes(UTF_8);
        byte[] result = Arrays.copyOf(magic, magic.length + tailBytes.length);
        System.arraycopy(tailBytes, 0, result, magic.length, tailBytes.length);
        return result;
    }

}

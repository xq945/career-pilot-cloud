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
import com.careerpilot.dto.ResumeUploadRequest;
import com.careerpilot.dto.ResumeUploadResponse;
import com.careerpilot.entity.Resume;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.service.storage.FileStorageService;

/**
 * 简历上传「三重校验」的单测（《测试与验收方案》§1.1 的「文件校验与路径」目标，
 * 判定规则出处《API接口设计》4.1 的类型对照表）。
 *
 * 覆盖三块：
 * - 五种支持格式的一致通过路径与扩展名归一（htm → html）；
 * - 「扩展名 / MIME / 内容特征」两两不符的拒绝矩阵；
 * - 10 MB 大小边界（整与 +1 字节）、空文件，以及 400 / 413 / 415 错误码的分工。
 *
 * 用 MockMultipartFile 从 upload 入口驱动。文本提取被 mock 短路，因此
 * 「为空 / 过短」判定不在本类（属 TextExtractionService 职责，接口层另有用例）。
 */
@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {

    /** 与生产代码同值；测试内独立声明，保证「判定的依据」被独立验证 */
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    private static final byte[] OLE2_MAGIC =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private ResumeAnalysisMapper resumeAnalysisMapper;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private TextExtractionService textExtractionService;

    private ResumeService service;

    @BeforeEach
    void setUp() {
        service = new ResumeService(resumeMapper, resumeAnalysisMapper,
                fileStorageService, textExtractionService);
    }

    // ---------------------------------------------------------------- 支持格式：一致通过

    @Test
    void upload_pdf_passesAndKeepsType() {
        stubSuccessfulPipeline();

        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.pdf", "application/pdf", header(PDF_MAGIC, "正文内容")));

        assertThat(response.getFileType()).isEqualTo("PDF");
        verify(fileStorageService).store(eq(1L), any(), eq("pdf"));
    }

    @Test
    void upload_docx_passesAndKeepsType() {
        stubSuccessfulPipeline();

        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.docx", DOCX_MIME, header(ZIP_MAGIC, "word/document.xml 残留")));

        assertThat(response.getFileType()).isEqualTo("DOCX");
        verify(fileStorageService).store(eq(1L), any(), eq("docx"));
    }

    @Test
    void upload_doc_passesAndKeepsType() {
        stubSuccessfulPipeline();

        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.doc", "application/msword", header(OLE2_MAGIC, "复合文档残留")));

        assertThat(response.getFileType()).isEqualTo("DOC");
        verify(fileStorageService).store(eq(1L), any(), eq("doc"));
    }

    @Test
    void upload_htm_normalizesExtensionToHtml() {
        stubSuccessfulPipeline();

        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.htm", "text/html", "<!DOCTYPE html><html><body>正文</body></html>".getBytes(UTF_8)));

        assertThat(response.getFileType()).isEqualTo("HTML");
        // .htm 与 .html 同属 HTML，落盘统一归一为 html（与 M2 头像 jpeg → jpg 同法）
        verify(fileStorageService).store(eq(1L), any(), eq("html"));
    }

    @Test
    void upload_txt_passesAndKeepsType() {
        stubSuccessfulPipeline();

        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.txt", "text/plain", "纯文本简历正文".getBytes(UTF_8)));

        assertThat(response.getFileType()).isEqualTo("TXT");
        verify(fileStorageService).store(eq(1L), any(), eq("txt"));
    }

    // ---------------------------------------------------------------- 容错：大小写与 MIME 参数

    @Test
    void upload_uppercaseExtensionAndMimeMatchesCaseInsensitively() {
        stubSuccessfulPipeline();

        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.PDF", "Application/PDF", header(PDF_MAGIC, "正文")));

        assertThat(response.getFileType()).isEqualTo("PDF");
    }

    @Test
    void upload_mimeWithCharsetParam_stillMatches() {
        stubSuccessfulPipeline();

        // multipart 部件带参数时直接比较会误判为「类型不符」，须先剥离参数
        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.txt", "text/plain;charset=utf-8", "纯文本正文".getBytes(UTF_8)));

        assertThat(response.getFileType()).isEqualTo("TXT");
    }

    // ---------------------------------------------------------------- 拒绝矩阵：三者须互相一致

    @Test
    void upload_contentContradictsExtensionAndMime_throws415() {
        // 声明为 PDF 的文本文件（伪造魔数）——最常见的攻击面
        assertRejected415(file("简历.pdf", "application/pdf", "这其实是一个文本文件".getBytes(UTF_8)));
    }

    @Test
    void upload_mimeContradictsExtensionAndContent_throws415() {
        assertRejected415(file("简历.pdf", "text/plain", header(PDF_MAGIC, "正文")));
    }

    @Test
    void upload_extensionContradictsMimeAndContent_throws415() {
        assertRejected415(file("简历.txt", "application/pdf", header(PDF_MAGIC, "正文")));
    }

    @Test
    void upload_docxMimeWithNonZipContent_throws415() {
        assertRejected415(file("简历.docx", DOCX_MIME, "不是 ZIP 容器".getBytes(UTF_8)));
    }

    @Test
    void upload_htmlWithoutFeatureTag_throws415() {
        // HTML 无标准魔数，以特征标签替代——纯文本内容不满足
        assertRejected415(file("简历.html", "text/html", "只有正文，没有任何标签".getBytes(UTF_8)));
    }

    @Test
    void upload_txtWithNulByte_throws415() {
        // 文本类以「无 NUL 字节」为内容判定，二进制内容即使扩展名与 MIME 相符也拒绝
        assertRejected415(file("简历.txt", "text/plain", new byte[] {'a', 0, 'b'}));
    }

    @Test
    void upload_imageResume_throws415() {
        // JPG/PNG 图片简历需 OCR，《API接口设计》4.1 标记 TODO 留待后续版本——本版前后端均拒绝
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        assertRejected415(file("简历.png", "image/png", png));
    }

    @Test
    void upload_missingExtensionOrMime_throws415() {
        assertRejected415(file("简历", null, "无扩展名与 MIME".getBytes(UTF_8)));
    }

    // ---------------------------------------------------------------- 大小与空文件边界

    @Test
    void upload_exactTenMb_passes() {
        stubSuccessfulPipeline();
        byte[] content = new byte[MAX_FILE_BYTES];
        System.arraycopy(PDF_MAGIC, 0, content, 0, PDF_MAGIC.length);

        ResumeUploadResponse response = service.upload(1L, request(), file(
                "简历.pdf", "application/pdf", content));

        assertThat(response.getFileType()).isEqualTo("PDF");
    }

    @Test
    void upload_overTenMb_throws413() {
        byte[] content = new byte[MAX_FILE_BYTES + 1];

        assertThatThrownBy(() -> service.upload(1L, request(), file(
                "简历.pdf", "application/pdf", content)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(41300));
    }

    @Test
    void upload_emptyFile_throws400() {
        assertThatThrownBy(() -> service.upload(1L, request(), file(
                "简历.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
    }

    @Test
    void upload_nullFile_throws400() {
        assertThatThrownBy(() -> service.upload(1L, request(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
    }

    // ---------------------------------------------------------------- 测试数据

    private void stubSuccessfulPipeline() {
        when(fileStorageService.store(any(), any(), any())).thenReturn("1/stored-file");
        when(textExtractionService.extract(any())).thenReturn("提取出的简历正文");
        when(resumeMapper.insert(any(Resume.class))).thenReturn(1);
    }

    private void assertRejected415(MockMultipartFile file) {
        assertThatThrownBy(() -> service.upload(1L, request(), file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(41500));
    }

    private static MockMultipartFile file(String originalFilename, String contentType, byte[] content) {
        return new MockMultipartFile("file", originalFilename, contentType, content);
    }

    private static ResumeUploadRequest request() {
        // name 留空即走「原文件名兜底」，与格式校验互不影响
        return new ResumeUploadRequest();
    }

    /** 魔数前缀 + 尾部内容，模拟真实文件头 */
    private static byte[] header(byte[] magic, String tail) {
        byte[] tailBytes = tail.getBytes(UTF_8);
        byte[] result = Arrays.copyOf(magic, magic.length + tailBytes.length);
        System.arraycopy(tailBytes, 0, result, magic.length, tailBytes.length);
        return result;
    }

}

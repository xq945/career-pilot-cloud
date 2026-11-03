package com.careerpilot.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.dto.LlmProviderSaveRequest;
import com.careerpilot.dto.LlmProviderTestResult;
import com.careerpilot.dto.LlmProviderUpdateRequest;
import com.careerpilot.dto.LlmProviderView;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.LlmProviderService;

import jakarta.validation.Valid;

/**
 * 模型服务接口（《API接口设计》§3A.1–§3A.6，M14）。
 *
 * 用户身份一律取自认证上下文；归属校验失败一律 404（不区分"不存在"与"他人"）；
 * API Key 只回显掩码，任何响应不携带明文或密文（《系统架构设计》§7.4）。
 */
@RestController
@RequestMapping("/api/llm-providers")
public class LlmProviderController {

    private final LlmProviderService llmProviderService;

    public LlmProviderController(LlmProviderService llmProviderService) {
        this.llmProviderService = llmProviderService;
    }

    /** 服务列表（3A.1）：首次访问懒初始化内置三家；API Key 只回显掩码 */
    @GetMapping
    public ApiResponse<List<LlmProviderView>> list() {
        return ApiResponse.success(llmProviderService.list(CurrentUser.requireId()));
    }

    /** 新增自定义服务（3A.2）：OpenAI 兼容协议；providerKey 服务端生成 */
    @PostMapping
    public ApiResponse<LlmProviderView> create(@Valid @RequestBody LlmProviderSaveRequest request) {
        return ApiResponse.success(llmProviderService.create(CurrentUser.requireId(), request));
    }

    /** 更新服务（3A.3）：字段缺省或空串 = 不修改；内置服务的 baseUrl 忽略 */
    @PutMapping("/{id}")
    public ApiResponse<LlmProviderView> update(@PathVariable Long id,
                                               @Valid @RequestBody LlmProviderUpdateRequest request) {
        return ApiResponse.success(llmProviderService.update(CurrentUser.requireId(), id, request));
    }

    /** 删除服务（3A.4）：内置不可删、当前默认不可删 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        llmProviderService.delete(CurrentUser.requireId(), id);
        return ApiResponse.success();
    }

    /** 连通性测试（3A.5）：1 次最小真实调用；失败返回 ok:false 而非 502 */
    @PostMapping("/{id}/test")
    public ApiResponse<LlmProviderTestResult> test(@PathVariable Long id) {
        return ApiResponse.success(llmProviderService.test(CurrentUser.requireId(), id));
    }

    /** 切换默认服务（3A.6）：校验已启用且已配置 Key；切换后立即生效 */
    @PutMapping("/{id}/default")
    public ApiResponse<LlmProviderView> setDefault(@PathVariable Long id) {
        return ApiResponse.success(llmProviderService.setDefault(CurrentUser.requireId(), id));
    }

}

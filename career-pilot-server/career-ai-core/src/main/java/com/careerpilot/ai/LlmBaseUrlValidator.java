package com.careerpilot.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import com.careerpilot.common.BusinessException;

/**
 * 用户级模型服务 baseUrl 的 SSRF 防护（阶段 1 · B4）。
 *
 * <p>背景：{@code llm_provider.base_url} 由用户填写，服务端会主动请求该地址
 * （连通性测试与每一次模型调用）。若不设限，用户可让服务端访问本机、内网、
 * 云元数据（169.254.169.254）等地址。
 *
 * <p>规则：
 * <ul>
 *   <li>只允许 http / https；</li>
 *   <li>拒绝 localhost、回环、链路本地、站点本地（10/8、172.16/12、192.168/16）、
 *       0.0.0.0/8、组播、IPv6 唯一本地（fc00::/7）等地址；</li>
 *   <li>域名先解析为 IP 再判断（降低直接用内网 IP 绕过的风险；DNS rebinding 的
 *       完整防护需出站代理，属部署层，见阶段 3）。</li>
 * </ul>
 */
public final class LlmBaseUrlValidator {

    private LlmBaseUrlValidator() {
    }

    public static void validate(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isEmpty()) {
            throw BusinessException.badRequest("接口地址不能为空");
        }
        URI uri;
        try {
            uri = URI.create(value);
        }
        catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("接口地址格式不正确");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw BusinessException.badRequest("接口地址必须以 http:// 或 https:// 开头");
        }
        if (uri.getUserInfo() != null) {
            throw BusinessException.badRequest("接口地址不能包含用户信息");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw BusinessException.badRequest("接口地址缺少主机名");
        }
        String normalizedHost = host.toLowerCase();
        if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local")) {
            throw BusinessException.badRequest("接口地址不允许指向本机或内网");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalizedHost);
        }
        catch (UnknownHostException ex) {
            // 无法解析的域名本身无法建立连接；不阻断入库（离线单测/临时 DNS 故障）。
            // DNS rebinding 的完整防护属于出站代理层，见阶段 3。
            return;
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw BusinessException.badRequest("接口地址不允许指向本机或内网");
            }
        }
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        // IPv6 唯一本地地址 fc00::/7（JDK 的 isSiteLocalAddress 只覆盖 fec0::/10）
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}

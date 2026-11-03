package com.careerpilot.service.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * 面试技能（Skill）服务（M15，《API接口设计》§7.1 出题升级、《Agent设计》§6.5）。
 *
 * <p>技能定义的文件结构（classpath）：
 * <pre>
 * skills/&lt;skillId&gt;/SKILL.md        front matter（name/description/version）+ persona 正文
 * skills/&lt;skillId&gt;/skill.meta.yml  displayName + categories（key/label/priority/ref/shared）
 * skills/_shared/references/*.md   跨技能共享的参考要点（出题参考，非题目）
 * </pre>
 *
 * <p>三项职责：
 * <ol>
 *   <li><b>加载</b>：启动时扫描全部技能定义（缺失即启动失败——同提示词加载的 fail-fast 惯例）；
 *       解析 SKILL.md 的 front matter 与正文（正文即 persona，注入系统提示词）；</li>
 *   <li><b>题量分配</b>：{@link #calculateAllocation} 按分类优先级三阶计算配额——
 *       「ALWAYS_ONE 保底 → CORE 各 1 → NORMAL 各 1 → 剩余名额在 CORE 间轮转」；
 *       单题型直通（全部名额给该分类）。<b>配额由本算法决定，模型只负责按配额出题</b>；</li>
 *   <li><b>参考要点</b>：{@link #buildReferenceSection} 按配额取对应类别参考文件并截断
 *       （单文件 ≤ 3000 字符、总计 ≤ 6000 字符）。</li>
 * </ol>
 *
 * <p>裁剪说明（与参照实现的差异）：六题型枚举（JAVA/SPRING/MYSQL/REDIS/PROJECT/MIXED）
 * 与创建接口**完全不动**——单题型映射到同名分类，MIXED 走全分类分配；当前只有一个技能
 * （java-backend）覆盖全部分类，多技能时取第一个并告警。
 */
@Service
public class InterviewSkillService {

    private static final Logger log = LoggerFactory.getLogger(InterviewSkillService.class);

    /** 技能定义扫描模式（_shared 在扫描中跳过） */
    private static final String SKILL_SCAN_PATTERN = "classpath:skills/*/SKILL.md";

    /** 共享参考要点目录 */
    private static final String SHARED_REFERENCE_PREFIX = "classpath:skills/_shared/references/";

    /** 单份参考要点注入上限（字符） */
    private static final int REFERENCE_FILE_LIMIT = 3000;

    /** 参考要点注入总量上限（字符） */
    private static final int REFERENCE_TOTAL_LIMIT = 6000;

    /** SKILL.md 的 front matter 切分（同参照实现的约定：--- 包裹的 YAML 头 + 正文） */
    private static final Pattern FRONT_MATTER_PATTERN =
            Pattern.compile("(?s)^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?(.*)$");

    /** 分类优先级（题量分配三阶的依据，见 skill.meta.yml 的注释） */
    public enum Priority {
        ALWAYS_ONE,
        CORE,
        NORMAL
    }

    /** 一个分类的定义（skill.meta.yml 的 categories 项） */
    public record CategoryDefinition(String key, String label, Priority priority, String ref, boolean shared) {
    }

    /** 一个技能的定义 */
    public record SkillDefinition(String skillId, String persona, List<CategoryDefinition> categories) {

        /** 按 key 索引的分类表（key 为题型枚举值，如 JAVA / PROJECT） */
        public Map<String, CategoryDefinition> categoriesByKey() {
            Map<String, CategoryDefinition> map = new LinkedHashMap<>();
            for (CategoryDefinition category : categories) {
                map.put(category.key(), category);
            }
            return map;
        }
    }

    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /** 已加载的技能（当前约定取第一个；多于一个时加载期告警） */
    private SkillDefinition skill;

    /** 参考要点缓存（classpath 资源不可变，读一次永久缓存） */
    private final Map<String, String> referenceCache = new LinkedHashMap<>();

    @PostConstruct
    void loadSkills() {
        List<SkillDefinition> loaded = new ArrayList<>();
        try {
            for (Resource resource : resourceResolver.getResources(SKILL_SCAN_PATTERN)) {
                if (resource.getURI().toString().contains("/_shared/")) {
                    continue;
                }
                loaded.add(parseSkill(resource));
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("技能定义扫描失败：" + SKILL_SCAN_PATTERN, ex);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("未找到任何技能定义（" + SKILL_SCAN_PATTERN + "）");
        }
        if (loaded.size() > 1) {
            log.warn("发现 {} 个技能定义，当前实现取第一个：{}", loaded.size(), loaded.get(0).skillId());
        }
        this.skill = loaded.get(0);
        log.info("面试技能已加载：skillId={} 分类数={}", skill.skillId(), skill.categories().size());
    }

    private SkillDefinition parseSkill(Resource skillResource) throws IOException {
        String content = readResource(skillResource);
        String directoryId = extractDirectoryId(skillResource);

        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        String persona;
        String skillId = directoryId;
        if (matcher.matches()) {
            String frontMatter = matcher.group(1);
            persona = matcher.group(2).strip();
            for (String line : frontMatter.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("name:")) {
                    skillId = fieldValue(trimmed);
                }
            }
        }
        else {
            // 缺少 front matter 不阻止加载：persona 取全文（与参照实现的容错取向一致）
            log.warn("SKILL.md 缺少 front matter，persona 取全文：{}", directoryId);
            persona = content.strip();
        }

        Resource metaResource = skillResource.createRelative("skill.meta.yml");
        if (!metaResource.exists()) {
            throw new IllegalStateException("技能缺少 skill.meta.yml：" + directoryId);
        }
        List<CategoryDefinition> categories = parseMeta(readResource(metaResource), directoryId);
        if (categories.isEmpty()) {
            throw new IllegalStateException("技能未定义任何分类：" + directoryId);
        }
        return new SkillDefinition(skillId, persona, categories);
    }

    /** 当前技能定义（persona 注入用） */
    public SkillDefinition currentSkill() {
        return skill;
    }

    /**
     * 计算题量分配（确定性算法，与模型输出无关）：
     *
     * <ul>
     *   <li>单题型（技能分类中存在该 key 时）：全部名额直通该分类；</li>
     *   <li>MIXED（或未知 key）：三阶——ALWAYS_ONE 保底 1 → CORE 各 1 → NORMAL 各 1 →
     *       剩余名额在 CORE 间轮转（无 CORE 时退化为全分类轮转）；</li>
     *   <li>返回值之和恒等于 {@code count}，仅包含配额 &gt; 0 的分类，按技能定义顺序。</li>
     * </ul>
     */
    public Map<String, Integer> calculateAllocation(String questionType, int count) {
        Map<String, Integer> allocation = new LinkedHashMap<>();
        if (count <= 0) {
            return allocation;
        }
        SkillDefinition current = skill;
        Map<String, CategoryDefinition> byKey = current.categoriesByKey();

        if (questionType != null && byKey.containsKey(questionType)) {
            allocation.put(questionType, count);
            return allocation;
        }

        List<CategoryDefinition> categories = current.categories();
        int remaining = count;
        remaining = takeByPriority(categories, Priority.ALWAYS_ONE, remaining, allocation);
        remaining = takeByPriority(categories, Priority.CORE, remaining, allocation);
        remaining = takeByPriority(categories, Priority.NORMAL, remaining, allocation);

        List<String> rotationKeys = categories.stream()
                .filter(category -> category.priority() == Priority.CORE)
                .map(CategoryDefinition::key)
                .toList();
        if (rotationKeys.isEmpty()) {
            rotationKeys = categories.stream().map(CategoryDefinition::key).toList();
        }
        int cursor = 0;
        while (remaining > 0 && !rotationKeys.isEmpty()) {
            allocation.merge(rotationKeys.get(cursor % rotationKeys.size()), 1, Integer::sum);
            cursor++;
            remaining--;
        }
        return allocation;
    }

    private int takeByPriority(List<CategoryDefinition> categories, Priority priority,
                               int remaining, Map<String, Integer> allocation) {
        for (CategoryDefinition category : categories) {
            if (remaining <= 0) {
                break;
            }
            if (category.priority() == priority) {
                allocation.merge(category.key(), 1, Integer::sum);
                remaining--;
            }
        }
        return remaining;
    }

    /** 配额表文本（注入用户消息，模型按此出题） */
    public String buildAllocationDescription(Map<String, Integer> allocation) {
        Map<String, CategoryDefinition> byKey = skill.categoriesByKey();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : allocation.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            CategoryDefinition definition = byKey.get(entry.getKey());
            String label = definition == null ? entry.getKey() : definition.label();
            sb.append("- ").append(label).append("（").append(entry.getKey()).append("）：")
                    .append(entry.getValue()).append(" 题\n");
        }
        return sb.toString().strip();
    }

    /**
     * 参考要点文本（按配额取对应类别的参考文件；单文件 ≤ 3000、总计 ≤ 6000 字符截断）。
     * 配额为 0 或分类无 ref 时跳过；无内容时返回空串（调用方不注入空块）。
     */
    public String buildReferenceSection(Map<String, Integer> allocation) {
        Map<String, CategoryDefinition> byKey = skill.categoriesByKey();
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, Integer> entry : allocation.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            CategoryDefinition definition = byKey.get(entry.getKey());
            if (definition == null || definition.ref() == null || definition.ref().isBlank()) {
                continue;
            }
            String content = loadReference(definition);
            if (content.isBlank()) {
                continue;
            }
            String block = "### " + definition.label() + "（" + definition.key() + "）\n" + content;
            if (total + block.length() > REFERENCE_TOTAL_LIMIT) {
                int allowed = REFERENCE_TOTAL_LIMIT - total;
                if (allowed < 200) {
                    break;
                }
                block = block.substring(0, allowed) + "\n……（参考要点已截断）";
            }
            sb.append(block).append("\n\n");
            total += block.length();
        }
        return sb.toString().strip();
    }

    private String loadReference(CategoryDefinition definition) {
        return referenceCache.computeIfAbsent(definition.ref(), ref -> {
            try {
                Resource resource = resourceResolver.getResource(SHARED_REFERENCE_PREFIX + ref);
                if (!resource.exists()) {
                    log.warn("参考要点文件不存在：{}（分类 {}）", ref, definition.key());
                    return "";
                }
                String content = readResource(resource);
                if (content.length() > REFERENCE_FILE_LIMIT) {
                    return content.substring(0, REFERENCE_FILE_LIMIT) + "\n……（文件已截断）";
                }
                return content;
            }
            catch (IOException ex) {
                // 参考要点是增强项：加载失败只降级为空、不阻断出题
                log.warn("参考要点加载失败：{}（分类 {}）", ref, definition.key());
                return "";
            }
        });
    }

    // ---------------------------------------------------------------- 解析辅助

    /** 解析 skill.meta.yml（结构固定，手写解析以避免引入 YAML 依赖） */
    private List<CategoryDefinition> parseMeta(String content, String skillId) {
        List<CategoryDefinition> categories = new ArrayList<>();
        String key = null;
        String label = null;
        String ref = null;
        Priority priority = Priority.NORMAL;
        boolean shared = false;
        boolean inCategory = false;

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("categories:")
                    || line.startsWith("displayName:")) {
                continue;
            }
            if (line.startsWith("- key:")) {
                if (inCategory) {
                    categories.add(new CategoryDefinition(key, label, priority, ref, shared));
                }
                key = fieldValue(line.substring(line.indexOf("key:")));
                label = null;
                ref = null;
                priority = Priority.NORMAL;
                shared = false;
                inCategory = true;
            }
            else if (line.startsWith("label:")) {
                label = fieldValue(line);
            }
            else if (line.startsWith("priority:")) {
                priority = parsePriority(fieldValue(line), skillId);
            }
            else if (line.startsWith("ref:")) {
                ref = fieldValue(line);
            }
            else if (line.startsWith("shared:")) {
                shared = Boolean.parseBoolean(fieldValue(line));
            }
        }
        if (inCategory) {
            categories.add(new CategoryDefinition(key, label, priority, ref, shared));
        }
        return categories;
    }

    private Priority parsePriority(String value, String skillId) {
        try {
            return Priority.valueOf(value.toUpperCase());
        }
        catch (IllegalArgumentException ex) {
            log.warn("未知的 priority「{}」（技能 {}），按 NORMAL 处理", value, skillId);
            return Priority.NORMAL;
        }
    }

    /** 取「字段: 值」中的值并去除包裹引号 */
    private static String fieldValue(String line) {
        int index = line.indexOf(':');
        String value = index < 0 ? "" : line.substring(index + 1).strip();
        return value.replaceAll("^[\"']", "").replaceAll("[\"']$", "");
    }

    /** 从资源路径提取技能目录名（如 .../skills/java-backend/SKILL.md → java-backend） */
    private static String extractDirectoryId(Resource resource) throws IOException {
        String path = resource.getURI().toString();
        String trimmed = path.endsWith("SKILL.md") ? path.substring(0, path.length() - "SKILL.md".length()) : path;
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
    }

    private static String readResource(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}

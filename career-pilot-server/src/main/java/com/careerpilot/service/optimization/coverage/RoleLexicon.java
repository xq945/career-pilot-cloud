package com.careerpilot.service.optimization.coverage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 日期事件角色词表（修复 C，阶段 1）。
 *
 * <p>集中维护中英文别名，供 {@link DateEventRoleGuard} 使用。规则：
 * <ul>
 *   <li>词表之外的动词一律视为“未知角色”，不猜测；</li>
 *   <li>长别名优先（“取得学位”先于“取得”）；</li>
 *   <li>英文别名按小写存储，匹配时要求字母边界，避免 left 命中 leftover；</li>
 *   <li>{@link Role#INTERNSHIP} 是中性事件角色，不隐含起止方向。</li>
 * </ul>
 *
 * <p>本类不读取正文上下文，也不判断歧义；只回答“某个别名对应哪个角色”。
 */
public final class RoleLexicon {

    public enum Role {
        START_EMPLOYMENT,
        END_EMPLOYMENT,
        START_PROJECT,
        END_PROJECT,
        GRADUATION,
        ENROLLMENT,
        AWARD,
        APPLICATION,
        INTERNSHIP
    }

    public record Alias(String text, Role role, boolean ascii) {
    }

    /**
     * 角色词与日期的最大字符距离。超过则认为二者不在同一事件短语内，
     * 宁可判为 UNKNOWN，不做远距离猜测。
     */
    public static final int MAX_ROLE_DISTANCE = 24;

    private static final List<Alias> ALIASES = buildAliases();

    private RoleLexicon() {
    }

    /** 长别名优先；同长度按插入顺序，结果稳定。 */
    public static List<Alias> aliases() {
        return ALIASES;
    }

    public static int aliasCount() {
        return ALIASES.size();
    }

    private static List<Alias> buildAliases() {
        Map<String, Alias> unique = new LinkedHashMap<>();
        // 中文：最长别名优先在排序阶段统一处理（例如“取得学位”先于“取得”）
        add(unique, Role.START_EMPLOYMENT, "入职", "加入", "就职", "任职", "到岗", "上岗");
        add(unique, Role.END_EMPLOYMENT, "离职", "离开", "辞职", "卸任");
        add(unique, Role.START_PROJECT, "开始", "启动", "立项", "着手", "动工");
        add(unique, Role.END_PROJECT, "结束", "完成", "结项", "上线", "交付");
        add(unique, Role.GRADUATION, "毕业", "取得学位", "获颁学位");
        add(unique, Role.ENROLLMENT, "入学", "就读", "录取", "考入");
        add(unique, Role.AWARD, "获得", "取得", "获评", "颁发", "荣获", "获奖");
        add(unique, Role.APPLICATION, "报名", "申请", "报考", "参加考试");
        add(unique, Role.INTERNSHIP, "实习");

        // 英文：小写存储，匹配时再做词边界与大小写处理
        addAscii(unique, Role.START_EMPLOYMENT, "joined", "onboarded", "employed", "started at");
        addAscii(unique, Role.END_EMPLOYMENT, "resigned", "left", "departed");
        addAscii(unique, Role.START_PROJECT, "started", "began", "launched", "kick off");
        addAscii(unique, Role.END_PROJECT, "completed", "finished", "concluded", "delivered");
        addAscii(unique, Role.GRADUATION, "graduated");
        addAscii(unique, Role.ENROLLMENT, "enrolled");
        addAscii(unique, Role.AWARD, "awarded", "received", "won");
        addAscii(unique, Role.APPLICATION, "applied");
        addAscii(unique, Role.INTERNSHIP, "internship", "interned", "intern");

        List<Alias> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingInt((Alias alias) -> alias.text().length()).reversed());
        return List.copyOf(sorted);
    }

    private static void add(Map<String, Alias> unique, Role role, String... aliases) {
        for (String alias : aliases) {
            unique.putIfAbsent(alias, new Alias(alias, role, false));
        }
    }

    private static void addAscii(Map<String, Alias> unique, Role role, String... aliases) {
        for (String alias : aliases) {
            String normalized = alias.toLowerCase(Locale.ROOT);
            unique.putIfAbsent("ascii:" + normalized,
                    new Alias(normalized, role, true));
        }
    }

    /** 去重后的角色集合（供测试确认覆盖度）。 */
    public static Set<Role> roles() {
        Set<Role> roles = new LinkedHashSet<>();
        for (Alias alias : ALIASES) {
            roles.add(alias.role());
        }
        return roles;
    }
}

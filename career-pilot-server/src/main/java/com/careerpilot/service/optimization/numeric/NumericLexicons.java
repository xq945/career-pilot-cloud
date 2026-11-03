package com.careerpilot.service.optimization.numeric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.careerpilot.service.optimization.numeric.NumericMention.Direction;
import com.careerpilot.service.optimization.numeric.NumericMention.Qualifier;
import com.careerpilot.service.optimization.numeric.NumericMention.UnitFamily;

/**
 * 修复 B 的有界词表。这里只收录“明确声明”的单位、指标别名组、对象、主体、
 * 方向、限定词与统计口径；未列出的词不按大类自动互换。
 */
public final class NumericLexicons {

    private NumericLexicons() {
    }

    public record UnitRule(String canonical, UnitFamily family) {
    }

    public record UnitAlias(String alias, UnitRule rule) {
    }

    public record MetricRule(String key, String subject) {
    }

    public record MetricAlias(String alias, MetricRule rule) {
    }

    public static final List<UnitAlias> UNIT_ALIASES = buildUnits();

    public static final List<MetricAlias> METRIC_ALIASES = buildMetrics();

    public static final List<String> OBJECTS = sortedByLength(List.of(
            "用户", "客户", "订单", "项目", "模块", "接口", "数据", "页面", "功能", "服务",
            "文件", "请求", "商品", "课程", "视频", "文章", "任务", "用例", "测试", "需求",
            "文档", "报表", "账号", "节点", "服务器", "字段", "索引", "表", "环境", "系统"));

    public static final List<String> SUBJECTS = sortedByLength(List.of(
            "接口", "系统", "页面", "服务", "应用", "数据库", "缓存", "请求", "任务", "查询",
            "网络", "服务器", "首屏", "构建", "启动", "加载", "渲染", "模块", "项目", "客户端"));

    /** 指标/对象弱修饰词：只在这些词位于已知对象前时剥离 */
    public static final List<String> OBJECT_MODIFIERS = sortedByLength(List.of(
            "核心", "主要", "全部", "所有", "各个", "单个", "多个", "相关", "独立", "业务",
            "前端", "后端", "基础", "公共", "通用"));

    public static final List<String> COUNT_SUFFIXES = sortedByLength(List.of(
            "数量", "总数", "总量", "数", "量"));

    public static final Map<String, Direction> DIRECTIONS = buildDirections();

    public static final Map<String, Qualifier> QUALIFIERS = buildQualifiers();

    public static final Map<String, String> STATISTICS = buildStatistics();

    public static final Set<String> TIME_CLARIFICATION_METRICS = Set.of(
            "RESPONSE_LATENCY");

    public static final Set<String> CONJUNCTIONS = Set.of("和", "与", "及", "以及", "或", "跟");

    public static final Set<String> OBJECT_STOP_WORDS = Set.of(
            "返回", "提升", "提高", "降低", "减少", "增长", "增加", "下降", "达到", "支持",
            "完成", "实现", "负责", "参与", "使用", "期间", "左右", "以上", "以下", "内", "中",
            "并", "且", "同时", "目前", "现在", "平均", "约", "了", "着", "过");

    public static final Set<String> VERSION_ANCHOR_STOP_WORDS = Set.of(
            "qps", "tps", "rt", "ms", "s", "min", "h", "hr", "day", "days", "week", "weeks",
            "month", "months", "year", "years", "user", "users", "item", "items", "record",
            "records", "time", "times", "percent", "people", "person");

    /** 中文数字的常见非数量词；命中这些词时不提取为数字事实 */
    public static final List<String> CHINESE_FALSE_POSITIVES = List.of(
            "一下", "一些", "一定", "一般", "一起", "一致", "一向", "一度", "一番", "一系列",
            "一方面", "一站式", "一体化", "一线", "一贯", "一律", "一旦", "万一", "统一",
            "同一", "唯一", "之一");

    /**
     * 不作为项目/区块上下文的结构性标签（已是规范化后的形式，只含字母/汉字/数字）。
     * 命中时既不把该标签当上下文，也会清空跨行标题上下文，避免标题同义润色造成误报。
     */
    public static final Set<String> GENERIC_CONTEXT_LABELS = Set.of(
            "项目经历", "项目经验", "项目", "实习经历", "实习", "工作经历", "工作", "工作经验",
            "教育经历", "教育背景", "教育", "基本信息", "个人信息", "联系方式", "姓名", "电话",
            "手机", "邮箱", "性别", "年龄", "籍贯", "专业技能", "技能清单", "技能", "技术栈",
            "个人总结", "个人优势", "自我评价", "求职意向", "期望薪资", "工作地点", "荣誉证书",
            "荣誉奖项", "获奖情况", "荣誉", "证书", "认证", "核心工作", "工作内容", "主要职责",
            "职责", "成果", "业绩", "说明", "备注", "其他", "主修课程");

    /**
     * 复合指标后缀：短指标别名后接这些词时不能截断绑定（如“成本率”不能绑定为 COST）。
     * 只用于右侧前缀匹配的边界判断，不扩大可互换的指标范围。
     */
    public static final List<String> METRIC_COMPOUND_SUFFIXES = List.of(
            "时间", "耗时", "延迟", "成本", "费用", "收入", "利润", "速度", "比例", "占比",
            "率", "量", "数");

    public static final Pattern GENERIC_METRIC_SUFFIX = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z]{1,8}(?:率|量|时间|耗时|延迟|成本|费用|收入|利润|速度|比例|占比))$");

    public static UnitAlias unitAt(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }
        for (UnitAlias alias : UNIT_ALIASES) {
            int end = index + alias.alias().length();
            if (end > text.length()) {
                continue;
            }
            String candidate = text.substring(index, end);
            if (!candidate.equalsIgnoreCase(alias.alias())) {
                continue;
            }
            if (isAscii(candidate) && end < text.length()
                    && isAsciiLetter(text.charAt(end))) {
                continue;
            }
            return alias;
        }
        return null;
    }

    public static MetricAlias metricSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (MetricAlias alias : METRIC_ALIASES) {
            if (text.endsWith(alias.alias())
                    || text.toLowerCase(Locale.ROOT).endsWith(alias.alias().toLowerCase(Locale.ROOT))) {
                if (alias.alias().chars().allMatch(c -> c < 128)
                        && text.length() > alias.alias().length()) {
                    char before = text.charAt(text.length() - alias.alias().length() - 1);
                    if (isAsciiLetter(before)) {
                        continue;
                    }
                }
                return alias;
            }
        }
        return null;
    }

    public static MetricAlias metricPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (MetricAlias alias : METRIC_ALIASES) {
            if (text.regionMatches(true, 0, alias.alias(), 0, alias.alias().length())) {
                int end = alias.alias().length();
                if (isAscii(alias.alias()) && end < text.length()
                        && isAsciiLetter(text.charAt(end))) {
                    continue;
                }
                // “成本率/用户数量”等复合指标不能被截成“成本/用户数”后绑定
                if (!isAscii(alias.alias()) && hasMetricCompoundSuffix(text, end)) {
                    continue;
                }
                return alias;
            }
        }
        return null;
    }

    /** 中文短指标别名之后是否紧跟一个更长指标的后缀 */
    public static boolean hasMetricCompoundSuffix(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return false;
        }
        for (String suffix : METRIC_COMPOUND_SUFFIXES) {
            if (text.startsWith(suffix, index)) {
                return true;
            }
        }
        return false;
    }

    public static String objectSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String stripped = stripLeadingObjectModifiers(text);
        for (String object : OBJECTS) {
            if (stripped.startsWith(object)) {
                int after = object.length();
                for (String suffix : COUNT_SUFFIXES) {
                    if (stripped.startsWith(suffix, after)) {
                        return object;
                    }
                }
                return object;
            }
        }
        return null;
    }

    public static String objectPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String object : OBJECTS) {
            if (text.endsWith(object)) {
                return object;
            }
            for (String suffix : COUNT_SUFFIXES) {
                String phrase = object + suffix;
                if (text.endsWith(phrase)) {
                    return object;
                }
            }
        }
        return null;
    }

    public static String subjectSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String subject : SUBJECTS) {
            if (text.endsWith(subject)) {
                return subject;
            }
        }
        return null;
    }

    public static String stripLeadingObjectModifiers(String text) {
        String value = text;
        for (String modifier : OBJECT_MODIFIERS) {
            if (value.startsWith(modifier)) {
                return value.substring(modifier.length());
            }
        }
        return value;
    }

    public static String qualifierAliasAtSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String alias : QUALIFIERS.keySet()) {
            if (text.endsWith(alias)) {
                return alias;
            }
        }
        return null;
    }

    public static StatisticMatch statisticAtSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : STATISTICS.entrySet()) {
            if (text.endsWith(entry.getKey())
                    || text.toLowerCase(Locale.ROOT).endsWith(entry.getKey().toLowerCase(Locale.ROOT))) {
                return new StatisticMatch(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    public record StatisticMatch(String alias, String key) {
    }

    public static boolean isAscii(String value) {
        return value != null && value.chars().allMatch(c -> c < 128);
    }

    public static boolean isAsciiLetter(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    public static boolean isCjk(char ch) {
        return ch >= '一' && ch <= '鿿';
    }

    private static List<UnitAlias> buildUnits() {
        List<UnitAlias> units = new ArrayList<>();
        addUnit(units, "毫秒", "毫秒", UnitFamily.TIME);
        addUnit(units, "微秒", "微秒", UnitFamily.TIME);
        addUnit(units, "纳秒", "纳秒", UnitFamily.TIME);
        addUnit(units, "秒钟", "秒", UnitFamily.TIME);
        addUnit(units, "秒", "秒", UnitFamily.TIME);
        addUnit(units, "分钟", "分钟", UnitFamily.TIME);
        addUnit(units, "小时", "小时", UnitFamily.TIME);
        addUnit(units, "个月", "个月", UnitFamily.TIME);
        addUnit(units, "月", "月", UnitFamily.TIME);
        addUnit(units, "年", "年", UnitFamily.TIME);
        addUnit(units, "天", "天", UnitFamily.TIME);
        addUnit(units, "日", "日", UnitFamily.TIME);
        addUnit(units, "星期", "星期", UnitFamily.TIME);
        addUnit(units, "周", "周", UnitFamily.TIME);
        addUnit(units, "个", "个", UnitFamily.COUNT);
        addUnit(units, "项", "个", UnitFamily.COUNT);
        addUnit(units, "条", "条", UnitFamily.COUNT);
        addUnit(units, "次", "次", UnitFamily.COUNT);
        addUnit(units, "人", "人", UnitFamily.COUNT);
        addUnit(units, "名", "人", UnitFamily.COUNT);
        addUnit(units, "位", "人", UnitFamily.COUNT);
        addUnit(units, "家", "家", UnitFamily.COUNT);
        addUnit(units, "款", "款", UnitFamily.COUNT);
        addUnit(units, "篇", "篇", UnitFamily.COUNT);
        addUnit(units, "张", "张", UnitFamily.COUNT);
        addUnit(units, "份", "份", UnitFamily.COUNT);
        addUnit(units, "所", "所", UnitFamily.COUNT);
        addUnit(units, "门", "门", UnitFamily.COUNT);
        addUnit(units, "套", "套", UnitFamily.COUNT);
        addUnit(units, "台", "台", UnitFamily.COUNT);
        addUnit(units, "组", "组", UnitFamily.COUNT);
        addUnit(units, "行", "行", UnitFamily.COUNT);
        addUnit(units, "页", "页", UnitFamily.COUNT);
        addUnit(units, "字符", "字符", UnitFamily.COUNT);
        addUnit(units, "字", "字", UnitFamily.COUNT);
        addUnit(units, "倍", "倍", UnitFamily.COUNT);
        addUnit(units, "元", "元", UnitFamily.MONEY);
        addUnit(units, "块", "元", UnitFamily.MONEY);
        addUnit(units, "人民币", "元", UnitFamily.MONEY);
        addUnit(units, "美元", "美元", UnitFamily.MONEY);
        addUnit(units, "美金", "美元", UnitFamily.MONEY);
        addUnit(units, "%", "%", UnitFamily.PERCENT);
        addUnit(units, "‰", "‰", UnitFamily.PERCENT);
        addUnit(units, "个百分点", "个百分点", UnitFamily.PERCENT);
        addUnit(units, "QPS", "QPS", UnitFamily.RATE);
        addUnit(units, "TPS", "TPS", UnitFamily.RATE);
        addUnit(units, "MB", "MB", UnitFamily.SIZE);
        addUnit(units, "GB", "GB", UnitFamily.SIZE);
        addUnit(units, "KB", "KB", UnitFamily.SIZE);
        addUnit(units, "TB", "TB", UnitFamily.SIZE);
        addUnit(units, "字节", "字节", UnitFamily.SIZE);
        addUnit(units, "bit", "bit", UnitFamily.SIZE);
        addUnit(units, "ms", "毫秒", UnitFamily.TIME);
        addUnit(units, "us", "微秒", UnitFamily.TIME);
        addUnit(units, "ns", "纳秒", UnitFamily.TIME);
        addUnit(units, "s", "秒", UnitFamily.TIME);
        addUnit(units, "sec", "秒", UnitFamily.TIME);
        addUnit(units, "secs", "秒", UnitFamily.TIME);
        addUnit(units, "second", "秒", UnitFamily.TIME);
        addUnit(units, "seconds", "秒", UnitFamily.TIME);
        addUnit(units, "min", "分钟", UnitFamily.TIME);
        addUnit(units, "mins", "分钟", UnitFamily.TIME);
        addUnit(units, "minute", "分钟", UnitFamily.TIME);
        addUnit(units, "minutes", "分钟", UnitFamily.TIME);
        addUnit(units, "h", "小时", UnitFamily.TIME);
        addUnit(units, "hr", "小时", UnitFamily.TIME);
        addUnit(units, "hrs", "小时", UnitFamily.TIME);
        addUnit(units, "hour", "小时", UnitFamily.TIME);
        addUnit(units, "hours", "小时", UnitFamily.TIME);
        addUnit(units, "day", "天", UnitFamily.TIME);
        addUnit(units, "days", "天", UnitFamily.TIME);
        addUnit(units, "week", "周", UnitFamily.TIME);
        addUnit(units, "weeks", "周", UnitFamily.TIME);
        addUnit(units, "month", "个月", UnitFamily.TIME);
        addUnit(units, "months", "个月", UnitFamily.TIME);
        addUnit(units, "year", "年", UnitFamily.TIME);
        addUnit(units, "years", "年", UnitFamily.TIME);
        units.sort(Comparator.comparingInt((UnitAlias u) -> u.alias().length()).reversed());
        return List.copyOf(units);
    }

    private static void addUnit(List<UnitAlias> units, String alias, String canonical,
                                UnitFamily family) {
        units.add(new UnitAlias(alias, new UnitRule(canonical, family)));
    }

    private static List<MetricAlias> buildMetrics() {
        List<MetricAlias> metrics = new ArrayList<>();
        addMetric(metrics, "响应时间", "RESPONSE_LATENCY", "");
        addMetric(metrics, "响应耗时", "RESPONSE_LATENCY", "");
        addMetric(metrics, "接口耗时", "RESPONSE_LATENCY", "接口");
        addMetric(metrics, "请求耗时", "RESPONSE_LATENCY", "请求");
        addMetric(metrics, "延迟", "RESPONSE_LATENCY", "");
        addMetric(metrics, "时延", "RESPONSE_LATENCY", "");
        addMetric(metrics, "RT", "RESPONSE_LATENCY", "");
        addMetric(metrics, "处理耗时", "PROCESSING_TIME", "");
        addMetric(metrics, "处理时间", "PROCESSING_TIME", "");
        addMetric(metrics, "执行耗时", "PROCESSING_TIME", "");
        addMetric(metrics, "执行时间", "PROCESSING_TIME", "");
        addMetric(metrics, "编译时间", "BUILD_TIME", "");
        addMetric(metrics, "编译耗时", "BUILD_TIME", "");
        addMetric(metrics, "构建时间", "BUILD_TIME", "");
        addMetric(metrics, "构建耗时", "BUILD_TIME", "");
        addMetric(metrics, "加载时间", "LOAD_TIME", "");
        addMetric(metrics, "加载耗时", "LOAD_TIME", "");
        addMetric(metrics, "页面加载时间", "LOAD_TIME", "");
        addMetric(metrics, "首屏时间", "LOAD_TIME", "");
        addMetric(metrics, "启动时间", "STARTUP_TIME", "");
        addMetric(metrics, "查询时间", "QUERY_TIME", "");
        addMetric(metrics, "查询耗时", "QUERY_TIME", "");
        addMetric(metrics, "响应速度", "RESPONSE_SPEED", "");
        addMetric(metrics, "转化率", "TRANSFORM_RATE", "");
        addMetric(metrics, "转化比率", "TRANSFORM_RATE", "");
        addMetric(metrics, "留存率", "RETENTION_RATE", "");
        addMetric(metrics, "复购率", "REPURCHASE_RATE", "");
        addMetric(metrics, "点击率", "CLICK_RATE", "");
        addMetric(metrics, "完播率", "FINISH_RATE", "");
        addMetric(metrics, "准确率", "ACCURACY", "");
        addMetric(metrics, "精确率", "PRECISION", "");
        addMetric(metrics, "召回率", "RECALL", "");
        addMetric(metrics, "覆盖率", "COVERAGE", "");
        addMetric(metrics, "成功率", "SUCCESS_RATE", "");
        addMetric(metrics, "通过率", "PASS_RATE", "");
        addMetric(metrics, "错误率", "ERROR_RATE", "");
        addMetric(metrics, "故障率", "FAILURE_RATE", "");
        addMetric(metrics, "可用性", "AVAILABILITY", "");
        addMetric(metrics, "稳定性", "STABILITY", "");
        addMetric(metrics, "成本", "COST", "");
        addMetric(metrics, "开销", "COST", "");
        addMetric(metrics, "费用", "COST", "");
        addMetric(metrics, "花费", "COST", "");
        addMetric(metrics, "支出", "COST", "");
        addMetric(metrics, "收入", "REVENUE", "");
        addMetric(metrics, "营收", "REVENUE", "");
        addMetric(metrics, "利润", "PROFIT", "");
        addMetric(metrics, "利润率", "PROFIT_MARGIN", "");
        addMetric(metrics, "毛利率", "GROSS_MARGIN", "");
        addMetric(metrics, "净利率", "NET_MARGIN", "");
        addMetric(metrics, "用户数", "VOLUME_USER", "用户");
        addMetric(metrics, "用户量", "VOLUME_USER", "用户");
        addMetric(metrics, "活跃用户数", "VOLUME_USER", "用户");
        addMetric(metrics, "DAU", "VOLUME_USER", "");
        addMetric(metrics, "MAU", "VOLUME_USER", "");
        addMetric(metrics, "订单数", "VOLUME_ORDER", "订单");
        addMetric(metrics, "订单量", "VOLUME_ORDER", "订单");
        addMetric(metrics, "吞吐量", "THROUGHPUT", "");
        addMetric(metrics, "并发量", "CONCURRENCY", "");
        addMetric(metrics, "并发数", "CONCURRENCY", "");
        addMetric(metrics, "QPS", "QPS", "");
        addMetric(metrics, "TPS", "TPS", "");
        addMetric(metrics, "性能", "VAGUE", "");
        addMetric(metrics, "效率", "VAGUE", "");
        addMetric(metrics, "效果", "VAGUE", "");
        addMetric(metrics, "质量", "VAGUE", "");
        metrics.sort(Comparator.comparingInt((MetricAlias m) -> m.alias().length()).reversed());
        return List.copyOf(metrics);
    }

    private static void addMetric(List<MetricAlias> metrics, String alias, String key,
                                  String subject) {
        metrics.add(new MetricAlias(alias, new MetricRule(key, subject)));
    }

    private static Map<String, Direction> buildDirections() {
        Map<String, Direction> directions = new LinkedHashMap<>();
        directions.put("提升", Direction.UP);
        directions.put("提高", Direction.UP);
        directions.put("增长", Direction.UP);
        directions.put("增加", Direction.UP);
        directions.put("上升", Direction.UP);
        directions.put("降低", Direction.DOWN);
        directions.put("减少", Direction.DOWN);
        directions.put("下降", Direction.DOWN);
        directions.put("缩短", Direction.DOWN);
        directions.put("节省", Direction.DOWN);
        directions.put("压缩", Direction.DOWN);
        directions.put("优化", Direction.NEUTRAL);
        directions.put("改善", Direction.NEUTRAL);
        directions.put("调整", Direction.NEUTRAL);
        return sortMapByKeyLength(directions);
    }

    private static Map<String, Qualifier> buildQualifiers() {
        Map<String, Qualifier> qualifiers = new LinkedHashMap<>();
        qualifiers.put("不低于", Qualifier.AT_LEAST);
        qualifiers.put("不超过", Qualifier.AT_MOST);
        qualifiers.put("大约", Qualifier.APPROX);
        qualifiers.put("至少", Qualifier.AT_LEAST);
        qualifiers.put("至多", Qualifier.AT_MOST);
        qualifiers.put("超过", Qualifier.AT_LEAST);
        qualifiers.put("左右", Qualifier.APPROX);
        qualifiers.put("以上", Qualifier.ABOVE);
        qualifiers.put("以下", Qualifier.BELOW);
        qualifiers.put("达到", Qualifier.TARGET);
        qualifiers.put("约", Qualifier.APPROX);
        qualifiers.put("近", Qualifier.APPROX);
        qualifiers.put("余", Qualifier.ABOVE);
        qualifiers.put("至", Qualifier.TARGET);
        qualifiers.put("到", Qualifier.TARGET);
        return sortMapByKeyLength(qualifiers);
    }

    private static Map<String, String> buildStatistics() {
        Map<String, String> statistics = new LinkedHashMap<>();
        statistics.put("平均", "AVG");
        statistics.put("均值", "AVG");
        statistics.put("峰值", "PEAK");
        statistics.put("最大", "MAX");
        statistics.put("最小", "MIN");
        statistics.put("总计", "TOTAL");
        statistics.put("累计", "TOTAL");
        statistics.put("同比", "YOY");
        statistics.put("环比", "MOM");
        statistics.put("P95", "P95");
        statistics.put("P99", "P99");
        statistics.put("P90", "P90");
        statistics.put("P75", "P75");
        return sortMapByKeyLength(statistics);
    }

    private static List<String> sortedByLength(List<String> values) {
        List<String> copy = new ArrayList<>(new LinkedHashSet<>(values));
        copy.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(copy);
    }

    private static <T> Map<String, T> sortMapByKeyLength(Map<String, T> source) {
        List<Map.Entry<String, T>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, T> e) -> e.getKey().length()).reversed());
        Map<String, T> result = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}

package com.careerpilot.service.optimization.coverage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析后的章节化文档（修复 C）。
 *
 * <p>{@code text} 是 NFKC 归一后的全文；块偏移量以它为准。
 * 事实/内容词都来自块的事实文本，不读取 Prompt、JD 或数据库。
 *
 * <p>{@code complete=false} 表示文档超过块数上限、解析被提前终止：
 * 调用方必须按“不可解析”阻断，不能把截断结果当作完整文档。
 */
final class SectionedDocument {

    final String text;
    final List<CoverageBlock> blocks;
    final Map<String, List<CoverageBlock>> bySection;
    final Set<String> sectionKeys;
    final Set<String> vocabulary;
    final boolean complete;

    SectionedDocument(String text, List<CoverageBlock> blocks) {
        this(text, blocks, true);
    }

    SectionedDocument(String text, List<CoverageBlock> blocks, boolean complete) {
        this.text = text;
        this.blocks = List.copyOf(blocks);
        Map<String, List<CoverageBlock>> sections = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> words = new LinkedHashSet<>();
        for (CoverageBlock block : blocks) {
            sections.computeIfAbsent(block.sectionKey, k -> new ArrayList<>()).add(block);
            keys.add(block.sectionKey);
            if (!block.parentKey.isEmpty()) {
                keys.add(block.parentKey);
            }
            words.addAll(block.contentWords);
        }
        this.bySection = Map.copyOf(sections);
        this.sectionKeys = Set.copyOf(keys);
        this.vocabulary = Set.copyOf(words);
        this.complete = complete;
    }

    List<CoverageBlock> blocksInSection(String key) {
        return bySection.getOrDefault(key, List.of());
    }
}

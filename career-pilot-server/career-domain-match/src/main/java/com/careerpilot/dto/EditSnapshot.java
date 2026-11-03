package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 编辑历史快照（{@code resume_optimization.edit_history} 的元素，《数据库设计》5.11）。
 *
 * <p>每次保存编辑稿时，把被覆盖前的旧稿推入本结构；服务层保证最多保留 10 版
 * （超出丢弃最旧）。仅供工作台的「历史版本」抽屉展示与回填，不单独提供接口。
 */
public class EditSnapshot {

    /** 当时的编辑稿全文 */
    private String content;

    /** 该版本被替换的时间 */
    private LocalDateTime savedAt;

    public EditSnapshot() {
    }

    public EditSnapshot(String content, LocalDateTime savedAt) {
        this.content = content;
        this.savedAt = savedAt;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(LocalDateTime savedAt) {
        this.savedAt = savedAt;
    }

}

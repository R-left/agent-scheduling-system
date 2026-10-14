package com.leftrr.agentscheduling.dto.im;

/**
 * 从消息中提取出的资源引用。
 * fileName 为 null 表示资源无文件名。
 */
public record ExtractedResource(ResourceType type, String key, String fileName) {

    /** 资源类型字面量 'image' | 'file'。 */
    public enum ResourceType {
        IMAGE("image"),
        FILE("file");

        private final String id;

        ResourceType(String id) {
            this.id = id;
        }

        /** 飞书 API 要求的字面量 id，如 "image"。 */
        public String id() {
            return id;
        }
    }
}

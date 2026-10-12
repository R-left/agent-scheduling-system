package com.leftrr.agentscheduling.im;

/**
 * 对齐 TS 版 src/im/message-parser.ts 的 extractResourceKeys 返回元素。
 * fileName 为 null 表示 TS undefined（设计裁决 11）。
 */
public record ExtractedResource(ResourceType type, String key, String fileName) {

    /** 对齐 TS 字面量类型 'image' | 'file'，id 为 TS 字面量。 */
    public enum ResourceType {
        IMAGE("image"),
        FILE("file");

        private final String id;

        ResourceType(String id) {
            this.id = id;
        }

        /** TS 字面量，如 "image"。 */
        public String id() {
            return id;
        }
    }
}

package com.leftrr.agentscheduling.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopicTaskTest {
    private final TopicAddress message = new TopicAddress(
            "om_msg", "chat-001", "thread-009", "");

    @Test
    void prefersThreadId() {
        assertEquals("thread-009", TopicTask.topicIdOf(message));
    }

    @Test
    void fallsBackToRootIdThenMessageId() {
        TopicAddress noThread = new TopicAddress("om_msg", "chat-001", "", "ot_root");
        assertEquals("ot_root", TopicTask.topicIdOf(noThread));
        TopicAddress noThreadNoRoot = new TopicAddress("om_msg", "chat-001", "", "");
        assertEquals("om_msg", TopicTask.topicIdOf(noThreadNoRoot));
    }

    @Test
    void hashesTopicTaskIdToFixedVector() {
        // Node 基准: createHash('sha256').update('chat-001:thread-009').digest('hex').slice(0, 24)
        assertEquals("ca659c7d8acfcd8c97c93fe9", TopicTask.topicTaskId(message));
    }

    @Test
    void usesTopicIdAsHashInput() {
        // Node 基准: sha256('chat-001:msg-fallback-123') 前 24 位
        TopicAddress noThread = new TopicAddress("msg-fallback-123", "chat-001", "", "");
        assertEquals("dc58dd4c9fae8efe46e6e6c2", TopicTask.topicTaskId(noThread));
    }
}

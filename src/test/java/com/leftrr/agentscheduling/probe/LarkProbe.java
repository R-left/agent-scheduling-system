package com.leftrr.agentscheduling.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * M4 开工前置探针（对照 TS src/probe-lark.ts 的意图）：
 * 验证 oapi-sdk 2.4.7 的 WS 长连接、收发消息与卡片更新能力。
 *
 * 静态能力验证（已确认，2026-10-13）：
 * - com.lark.oapi.ws.Client.Builder(appId, appSecret).eventHandler(EventDispatcher).build().start()
 * - com.lark.oapi.Client：im.v1.message.reply/patch、im.v1.messageResource、drive.v1 FileSubscription/FileCommentReply
 * - drive.v2.commentReaction.updateReaction 在 Java SDK 缺失 → 走 Client 的 raw put 补位
 *
 * 实时连接验证需要 .env 凭证（FEISHU_CEO_ASSISTANT_APP_ID/SECRET、TEST_CHAT_ID）。
 * 缺凭证时本探针打印跳过说明并以 0 退出——不视为失败。
 *
 * 运行：mvn test-compile exec:java -Dexec.mainClass=com.leftrr.agentscheduling.probe.LarkProbe -Dexec.classpathScope=test
 */
public final class LarkProbe {

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadDotEnv();
        String appId = env.getOrDefault("FEISHU_CEO_ASSISTANT_APP_ID", System.getenv("FEISHU_CEO_ASSISTANT_APP_ID"));
        String appSecret = env.getOrDefault("FEISHU_CEO_ASSISTANT_APP_SECRET", System.getenv("FEISHU_CEO_ASSISTANT_APP_SECRET"));
        String chatId = env.getOrDefault("TEST_CHAT_ID", System.getenv("TEST_CHAT_ID"));

        if (isBlank(appId) || isBlank(appSecret) || isBlank(chatId)) {
            System.out.println("[probe] 跳过实时验证：缺少凭证（.env 未配置 FEISHU_CEO_ASSISTANT_APP_ID/SECRET 或 TEST_CHAT_ID）");
            System.out.println("[probe] 静态能力验证已在 M4 开工前完成：ws.Client / EventDispatcher / im.v1 reply+patch / raw put 补位 commentReaction 全部在位");
            return;
        }

        System.out.println("[probe] 1/3 REST 发送文本消息到 TEST_CHAT_ID …");
        var client = com.lark.oapi.Client.newBuilder(appId, appSecret).build();
        String messageJson = "{\"chat_id\":\"" + chatId + "\",\"msg_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"[probe] M4 SDK 探针测试消息\\\"}\"}";
        com.lark.oapi.core.response.RawResponse created = client.post(
                "/open-apis/im/v1/messages?uuid=probe-" + System.currentTimeMillis(), messageJson,
                com.lark.oapi.core.token.AccessTokenType.Tenant);
        System.out.println("[probe] create → " + created.getStatusCode());
        String messageId = extractField(created.getBody(), "message_id");
        if (messageId == null) {
            System.out.println("[probe] 未取到 message_id，响应体：" + bodyOrNull(created));
            return;
        }

        System.out.println("[probe] 2/3 卡片更新（im.v1.message.patch interactive card）…");
        String cardJson = "{\"chat_id\":\"" + chatId + "\",\"message_id\":\"" + messageId
                + "\",\"content\":\"{\\\"config\\\":{\\\"wide_screen_mode\\\":true},\\\"elements\\\":[{\\\"tag\\\":\\\"div\\\",\\\"text\\\":{\\\"tag\\\":\\\"plain_text\\\",\\\"content\\\":\\\"[probe] 卡片更新成功\\\"}}]}\"}";
        com.lark.oapi.core.response.RawResponse patched = client.patch(
                "/open-apis/im/v1/messages/" + messageId, cardJson,
                com.lark.oapi.core.token.AccessTokenType.Tenant);
        System.out.println("[probe] patch → " + patched.getStatusCode());

        System.out.println("[probe] 3/3 WS 长连接（10 秒窗口，等待事件回调）…");
        var dispatcher = com.lark.oapi.event.EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new com.lark.oapi.service.im.ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2MessageReceiveV1 event) {
                        System.out.println("[probe] 收到消息事件：message_id="
                                + event.getEvent().getMessage().getMessageId());
                    }
                })
                .build();
        var ws = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                .eventHandler(dispatcher)
                .autoReconnect(Boolean.FALSE)
                .build();
        Thread wsThread = Thread.ofPlatform().daemon(true).name("probe-ws").start(ws::start);
        wsThread.join(10_000);
        System.out.println("[probe] 完成（daemon 线程随 JVM 退出）");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 读取 .env（键值均为非敏感输出，值绝不打印）。查找顺序：Java 仓库根 → 上级（TS 项目根）。 */
    static Map<String, String> loadDotEnv() throws IOException {
        Map<String, String> result = new HashMap<>();
        for (Path candidate : new Path[]{Path.of(".env"), Path.of("..", ".env")}) {
            if (Files.isRegularFile(candidate)) {
                for (String line : Files.readAllLines(candidate)) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && !line.startsWith("#")) {
                        result.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
        }
        return result;
    }

    private static String extractField(byte[] data, String field) {
        if (data == null) {
            return null;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(data);
            var value = node.path("data").path(field);
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private static String bodyOrNull(com.lark.oapi.core.response.RawResponse response) {
        try {
            return response.getBody() == null ? "(无响应体)" : new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(响应体读取失败)";
        }
    }

    private LarkProbe() {
    }
}

package com.leftrr.agentscheduling.cli.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 测试专用子进程：由测试用当前 JVM 启动，跨平台、不依赖真实 claude/codex。
 * 用法：java -cp <test classpath> FakeCli <mode> [more modes...]
 * 模式（可叠加，按序执行）：
 *   emit:<file>            逐行输出文件内容（UTF-8）
 *   emit-fail:<file>:<code> 逐行输出文件内容后以 code 退出
 *   hang:<ms>              直接休眠 ms 后退出 0（测超时/取消）
 *   emit-hang:<file>:<ms>  先输出文件内容，再休眠 ms 后退出 0
 *   stderr:<text>[:<code>] 输出 stderr 后以 code（默认 1）退出
 *   echo-stdin             读入全部 stdin，作为一行 JSON 输出 {"stdin":"..."} 后退出 0
 *   stdin-record:<record>:<fixture>
 *                          读入全部 stdin 原样写入 record 文件后逐行输出 fixture 内容
 *                          （两路径均相对 cwd 解析，NativeCompact stdin 断言用；Task 8 授权新增）
 *   cwd                    输出一行 {"cwd":"<user.dir>"} 后退出 0
 *   app-server:<file>      模拟 codex app-server JSON-RPC（脚本 JSON：见下）
 * app-server 脚本字段：
 *   list       thread/list 的 result 载荷（对象）
 *   listError  设置后 thread/list 回 {"error":<listError>}（有无 message 均回错误）
 *   compactNotifications  thread/compact/start 后逐行原样打印的 JSON 文本数组
 *   resumeError           设置后 thread/resume 回 error
 *   record     设置后把收到的每行 JSON 追加写入该文件（协议序列断言用）
 *   stall      设置后 thread/list 不应答（模拟 app-server 挂起，测超时）
 *   exitAfterInit  设置后 initialize 回复完毕即以该值退出（测提前退出）
 */
public final class FakeCli {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int exit = 0;
        for (String arg : args) {
            exit = run(arg);
            if (exit != 0) {
                System.exit(exit);
            }
        }
        System.exit(exit);
    }

    private static int run(String arg) throws Exception {
        if (arg.startsWith("emit:")) {
            emit(Path.of(arg.substring(5)));
            return 0;
        }
        if (arg.startsWith("emit-fail:")) {
            String[] parts = arg.split(":", 3);
            emit(Path.of(parts[1]));
            return Integer.parseInt(parts[2]);
        }
        if (arg.startsWith("hang:")) {
            Thread.sleep(Long.parseLong(arg.substring(5)));
            return 0;
        }
        if (arg.startsWith("emit-hang:")) {
            String[] parts = arg.split(":", 3);
            emit(Path.of(parts[1]));
            Thread.sleep(Long.parseLong(parts[2]));
            return 0;
        }
        if (arg.startsWith("stderr:")) {
            String[] parts = arg.split(":", 3);
            System.err.println(parts[1]);
            return parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
        }
        if (arg.equals("echo-stdin")) {
            String all = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("stdin", all);
            System.out.println(MAPPER.writeValueAsString(out));
            return 0;
        }
        if (arg.startsWith("stdin-record:")) {
            String[] parts = arg.split(":", 3);
            String all = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(Path.of(parts[1]), all, StandardCharsets.UTF_8);
            emit(Path.of(parts[2]));
            return 0;
        }
        if (arg.equals("cwd")) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("cwd", System.getProperty("user.dir"));
            System.out.println(MAPPER.writeValueAsString(out));
            return 0;
        }
        if (arg.startsWith("app-server:")) {
            return appServer(arg.substring("app-server:".length()));
        }
        throw new IllegalArgumentException("未知 FakeCli 模式: " + arg);
    }

    private static void emit(Path file) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        System.out.flush();
    }

    private static int appServer(String scriptPath) throws Exception {
        JsonNode script = MAPPER.readTree(Files.readString(Path.of(scriptPath), StandardCharsets.UTF_8));
        Path recordPath = script.hasNonNull("record") ? Path.of(script.get("record").asText()) : null;
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (recordPath != null) {
                Files.writeString(recordPath, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            JsonNode message = MAPPER.readTree(line);
            if (!message.has("method")) {
                continue;
            }
            String method = message.path("method").asText();
            int id = message.path("id").asInt(0);
            switch (method) {
                case "initialize" -> {
                    reply(id, MAPPER.createObjectNode());
                    if (script.hasNonNull("exitAfterInit")) {
                        return script.get("exitAfterInit").asInt(0);
                    }
                }
                case "thread/list" -> {
                    if (script.path("stall").asBoolean(false)) {
                        continue; // 不应答，模拟 app-server 挂起（超时测试用）
                    }
                    JsonNode err = script.get("listError");
                    if (err != null) {
                        ObjectNode error = MAPPER.createObjectNode();
                        error.set("error", err);
                        System.out.println(MAPPER.writeValueAsString(error));
                    } else {
                        reply(id, script.get("list"));
                    }
                }
                case "thread/resume" -> {
                    JsonNode err = script.get("resumeError");
                    if (err != null && err.has("message")) {
                        ObjectNode error = MAPPER.createObjectNode();
                        error.set("error", err);
                        System.out.println(MAPPER.writeValueAsString(error));
                    } else {
                        reply(id, MAPPER.createObjectNode());
                    }
                }
                case "thread/compact/start" -> {
                    JsonNode notes = script.get("compactNotifications");
                    if (notes != null && notes.isArray()) {
                        for (JsonNode note : notes) {
                            System.out.println(note.asText());
                        }
                        System.out.flush();
                    }
                    reply(id, MAPPER.createObjectNode());
                }
                default -> { /* 未知 method 静默忽略，保持行为可预期 */ }
            }
        }
        return 0;
    }

    private static void reply(int id, JsonNode result) throws IOException {
        ObjectNode reply = MAPPER.createObjectNode();
        reply.put("id", id);
        reply.set("result", result);
        System.out.println(MAPPER.writeValueAsString(reply));
        System.out.flush();
    }
}

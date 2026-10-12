package com.leftrr.agentscheduling.im;

/**
 * 飞书消息资源下载结果（TS lark.ts:345-356 中 messageResource.get 的
 * 响应体字节 + content-type 头，落盘由 LarkBot.downloadResource 负责）。
 */
public record DownloadedResource(byte[] body, String contentType) {
}

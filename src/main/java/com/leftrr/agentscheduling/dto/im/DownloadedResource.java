package com.leftrr.agentscheduling.dto.im;

/**
 * 飞书消息资源下载结果（messageResource.get 的响应体字节 + content-type 头，
 * 落盘由 LarkBot.downloadResource 负责）。
 */
public record DownloadedResource(byte[] body, String contentType) {
}

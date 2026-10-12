package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;

/**
 * 单个 bot 的运行时三件套。对应 TS 版 src/app/runtime.ts 的 BotRuntime 接口（纯类型）。
 */
public record BotRuntime(BotRegistry.BotConfig config, Bot bot, BotIdentity identity) {}

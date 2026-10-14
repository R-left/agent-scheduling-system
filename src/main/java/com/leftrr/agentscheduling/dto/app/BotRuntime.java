package com.leftrr.agentscheduling.dto.app;

import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
import com.leftrr.agentscheduling.im.Bot;

/**
 * 单个 bot 的运行时三件套：配置、Bot 门面与身份。
 */
public record BotRuntime(BotRegistry.BotConfig config, Bot bot, BotIdentity identity) {}

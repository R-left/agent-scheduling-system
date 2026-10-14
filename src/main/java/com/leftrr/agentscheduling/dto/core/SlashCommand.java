package com.leftrr.agentscheduling.dto.core;

/**
 * 斜杠命令。
 * SimpleCommand 覆盖无参数命令；CompactCommand / CdCommand 各带一个可空参数。
 */
public sealed interface SlashCommand
        permits SlashCommand.SimpleCommand, SlashCommand.CompactCommand, SlashCommand.CdCommand {

    enum SimpleName { CLOSE, STATUS, HELP, NEW, RESUME, TEAM }

    record SimpleCommand(SimpleName name) implements SlashCommand {
    }

    record CompactCommand(String instructions) implements SlashCommand {
    }

    record CdCommand(String path) implements SlashCommand {
    }
}

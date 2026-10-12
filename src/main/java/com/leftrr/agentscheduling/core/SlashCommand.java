package com.leftrr.agentscheduling.core;

/**
 * 斜杠命令。对应 TS 版 src/core/command-parser.ts 的 SlashCommand 判别联合。
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

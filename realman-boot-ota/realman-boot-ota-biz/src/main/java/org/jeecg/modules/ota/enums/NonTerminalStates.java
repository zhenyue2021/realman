package org.jeecg.modules.ota.enums;

import org.jeecg.modules.ota.contract.enums.OtaTaskState;

import java.util.Set;

/**
 * 15 态状态机的终态/非终态判定，供固件包删除前置检查（PRD 4.2.4）与任务引用
 * 校验复用。终态：COMPLETED / FAILED / ROLLED_BACK / ROLLBACK_FAILED / CANCELLED；
 * 其余（含 PENDING/PENDING_ONLINE，PRD 原文未明确列入删除阻止清单但同属
 * "运行或等待中"，保守按非终态处理）均视为非终态。
 */
public final class NonTerminalStates {

    private static final Set<String> TERMINAL = Set.of(
            OtaTaskState.COMPLETED.name(),
            OtaTaskState.FAILED.name(),
            OtaTaskState.ROLLED_BACK.name(),
            OtaTaskState.ROLLBACK_FAILED.name(),
            OtaTaskState.CANCELLED.name());

    private NonTerminalStates() {
    }

    public static boolean isTerminal(String state) {
        return TERMINAL.contains(state);
    }

    public static Set<String> terminalStates() {
        return TERMINAL;
    }
}

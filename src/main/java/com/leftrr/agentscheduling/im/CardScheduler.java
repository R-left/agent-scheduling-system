package com.leftrr.agentscheduling.im;

/** 对齐 TS 版 ThrottledCardUpdater 用到的 setTimeout/clearTimeout 的最小调度抽象（M4 Task 8）。
 * 生产实现用单线程 ScheduledExecutorService 适配；测试用 fake 手动泵。 */
public interface CardScheduler {

    /** 延迟 delayMs 后执行 task，返回可取消句柄（对齐 setTimeout 的返回值 timer）。 */
    Cancellable schedule(Runnable task, long delayMs);

    /** 对齐 clearTimeout 的取消句柄。 */
    interface Cancellable {
        void cancel();
    }
}

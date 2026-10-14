package com.leftrr.agentscheduling.im;

/** ThrottledCardUpdater 用到的 setTimeout/clearTimeout 的最小调度抽象。
 * 生产实现用单线程 ScheduledExecutorService 适配。 */
public interface CardScheduler {

    /** 延迟 delayMs 后执行 task，返回可取消句柄。 */
    Cancellable schedule(Runnable task, long delayMs);

    /** 取消已调度任务的句柄（语义同 clearTimeout）。 */
    interface Cancellable {
        void cancel();
    }
}

package com.leftrr.agentscheduling.dto.core;

import com.leftrr.agentscheduling.core.Session;

/** 会话查找结果：既有或新建，isNew 表示本次是否新建。 */
public record ResolvedSession(Session session, boolean isNew) {}

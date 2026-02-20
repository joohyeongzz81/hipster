package com.hipster.global.logging.aop;

import org.springframework.stereotype.Component;

@Component
public class ApiQueryCounter {
    private final ThreadLocal<Long> queryCount = ThreadLocal.withInitial(() -> 0L);
    private final ThreadLocal<Long> queryTime = ThreadLocal.withInitial(() -> 0L);

    public void increaseCount() {
        queryCount.set(queryCount.get() + 1);
    }

    public void addTime(long timeMillis) {
        queryTime.set(queryTime.get() + timeMillis);
    }

    public long getCount() { return queryCount.get(); }
    public long getTime() { return queryTime.get(); }

    public void clear() {
        queryCount.remove();
        queryTime.remove();
    }
}

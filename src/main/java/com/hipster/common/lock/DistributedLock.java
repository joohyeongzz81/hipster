package com.hipster.common.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 분산 락을 획득하기 위한 커스텀 어노테이션
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 분산 락의 키 (Redis에 저장될 Key 이름)
     */
    String key();

    /**
     * 락의 시간 단위
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 락을 획득하기 위해 대기하는 시간 (초기값: 0)
     * - 0일 경우, 락 획득 실패 시 대기하지 않고 즉시 스레드가 포기(Fail-fast)합니다.
     */
    long waitTime() default 0;

    /**
     * 락을 획득한 후 보유하는 임대 시간 (초기값: 300초 = 5분)
     * - 배치가 예상 소요 시간 이상으로 비정상 종료되더라도, 이 시간이 지나면 락이 자동으로 해제됩니다.
     */
    long leaseTime() default 300;
}

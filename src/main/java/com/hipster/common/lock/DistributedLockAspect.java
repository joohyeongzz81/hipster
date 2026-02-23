package com.hipster.common.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private static final String REDISSON_LOCK_PREFIX = "LOCK:";

    @Around("@annotation(com.hipster.common.lock.DistributedLock)")
    public Object lock(final ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);

        String key = REDISSON_LOCK_PREFIX + distributedLock.key();
        RLock rLock = redissonClient.getLock(key);

        try {
            boolean available = rLock.tryLock(distributedLock.waitTime(), distributedLock.leaseTime(), distributedLock.timeUnit());
            if (!available) {
                log.info("🔒 [Fail-fast] 분산 락 획득 실패. (Key: {}) -> 현재 스레드는 스케줄 실행을 조용히 스킵합니다.", key);
                return null; // 실행 안하고 그냥 무시 (Fail-fast)
            }
            log.info("🔓 분산 락 획득 성공! 실행 시작 (Key: {})", key);
            return joinPoint.proceed();
        } catch (InterruptedException e) {
            log.error("락 획득 중 인터럽트 발생", e);
            throw e;
        } finally {
            if (rLock.isLocked() && rLock.isHeldByCurrentThread()) {
                rLock.unlock();
                log.info("🔓 분산 락 해제 완료 (Key: {})", key);
            }
        }
    }
}

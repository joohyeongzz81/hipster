package com.hipster.global.logging.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;

@Aspect
@Component
public class DataSourceAspect {

    private final ApiQueryCounter queryCounter;

    public DataSourceAspect(ApiQueryCounter queryCounter) {
        this.queryCounter = queryCounter;
    }

    @Around("execution(* javax.sql.DataSource.getConnection(..))")
    public Object interceptConnection(ProceedingJoinPoint pjp) throws Throwable {
        Connection connection = (Connection) pjp.proceed();
        return Proxy.newProxyInstance(
                connection.getClass().getClassLoader(),
                new Class[]{Connection.class},
                new ConnectionProxyHandler(connection, queryCounter)
        );
    }

    static class ConnectionProxyHandler implements InvocationHandler {
        private final Connection target;
        private final ApiQueryCounter queryCounter;

        public ConnectionProxyHandler(Connection target, ApiQueryCounter queryCounter) {
            this.target = target;
            this.queryCounter = queryCounter;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(target, args);
            if ("prepareStatement".equals(method.getName()) && result instanceof PreparedStatement) {
                return Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[]{PreparedStatement.class},
                        new PreparedStatementProxyHandler((PreparedStatement) result, queryCounter)
                );
            }
            return result;
        }
    }

    static class PreparedStatementProxyHandler implements InvocationHandler {
        private final PreparedStatement target;
        private final ApiQueryCounter queryCounter;

        public PreparedStatementProxyHandler(PreparedStatement target, ApiQueryCounter queryCounter) {
            this.target = target;
            this.queryCounter = queryCounter;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if ("execute".equals(methodName) || "executeQuery".equals(methodName) || 
                "executeUpdate".equals(methodName) || "executeBatch".equals(methodName)) {
                
                long startTime = System.currentTimeMillis();
                try {
                    return method.invoke(target, args);
                } finally {
                    long duration = System.currentTimeMillis() - startTime;
                    queryCounter.increaseCount();
                    queryCounter.addTime(duration);
                }
            }
            return method.invoke(target, args);
        }
    }
}

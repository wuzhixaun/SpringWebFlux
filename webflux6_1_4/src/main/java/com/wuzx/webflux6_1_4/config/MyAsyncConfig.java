package com.wuzx.webflux6_1_4.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class MyAsyncConfig implements AsyncConfigurer {

    // 为异步调用设置Executor
    @Override
    public Executor getAsyncExecutor() {
        // 使用包含两个核心线程的 ThreadPoolTaskExecutor，可以将核心线程增加到一百个。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(100);
        // 如果没有正确配置队列容量，线程池就无法增长。
        // 这是因为程序将转而使用 SynchronousQueue，而这限制了并发。
        executor.setQueueCapacity(5);
        executor.initialize();
        return executor;
    }

    // 为异步执行引发的异常配置异常处理程序。
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // 此处仅记录异常
        return new SimpleAsyncUncaughtExceptionHandler();
    }

}

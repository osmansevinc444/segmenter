package com.streameast.segmenter.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
    private ThreadPoolTaskScheduler scheduler;


    @Bean(name = "ffmpegStreamExecutor")
    @Primary
    public ThreadPoolTaskExecutor ffmpegStreamExecutor() {
        // new thread for each stream
        return createExecutor(
                "ffmpeg-stream-",
                Runtime.getRuntime().availableProcessors() * 2,  // Core pool size
                Runtime.getRuntime().availableProcessors() * 4,  // Max pool size
                500, // Queue capacity -
                300  // Keep alive seconds
        );
    }

    @Bean(name = "storageTaskExecutor")
    public ThreadPoolTaskExecutor storageTaskExecutor() {
        // Storage operations
        return createExecutor(
                "storage-",
                Runtime.getRuntime().availableProcessors() * 4,   // Core pool size - IO bound (we need extra thread)
                Runtime.getRuntime().availableProcessors() * 8,   // Max pool size
                1000, // Queue capacity
                180   // Keep alive seconds
        );
    }

    @Bean(name = "playlistTaskExecutor")
    public ThreadPoolTaskExecutor playlistTaskExecutor() {
        return createExecutor(
                "playlist-",
                Runtime.getRuntime().availableProcessors(),   // Core pool size
                Runtime.getRuntime().availableProcessors() * 2,   // Max pool size
                500, // Queue capacity
                120  // Keep alive seconds
        );
    }

    @Bean(name = "schedulerTaskExecutor")
    public ThreadPoolTaskExecutor schedulerTaskExecutor() {
        return createExecutor(
                "scheduler-",
                2,  // Core pool size
                4,  // Max pool size
                100, // Queue capacity
                60  // Keep alive seconds
        );
    }

    private ThreadPoolTaskExecutor createExecutor(
            String namePrefix,
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(namePrefix);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            log.error("Task rejected for executor {}: {}",
                    namePrefix, runnable.toString());
        });
        executor.setThreadFactory(r -> {
            Thread thread = new Thread(r);
            thread.setName(namePrefix + thread.getId());
            thread.setUncaughtExceptionHandler((t, e) ->
                    log.error("Uncaught exception in thread {}: ", t.getName(), e));
            return thread;
        });
        executor.initialize();
        executors.add(executor);
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Initiating graceful shutdown of thread pools...");

        executors.forEach(executor -> {
            try {
                log.info("Shutting down executor: {} - Active threads: {}, Queue size: {}",
                        executor.getThreadNamePrefix(),
                        executor.getActiveCount(),
                        executor.getQueueSize());

                executor.shutdown();
                log.info("Shutdown completed for executor: {}", executor.getThreadNamePrefix());
            } catch (Exception e) {
                log.error("Error shutting down executor {}: {}",
                        executor.getThreadNamePrefix(), e.getMessage());
            }
        });

        if (scheduler != null) {
            try {
                log.info("Shutting down scheduler - Active threads: {}",
                        scheduler.getActiveCount());
                scheduler.shutdown();
                log.info("Shutdown completed for scheduler");
            } catch (Exception e) {
                log.error("Error shutting down scheduler: {}", e.getMessage());
            }
        }
    }
}

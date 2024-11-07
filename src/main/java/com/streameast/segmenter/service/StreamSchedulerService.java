package com.streameast.segmenter.service;

import com.streameast.segmenter.model.StreamContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class StreamSchedulerService {

    private final RedisHelper redisHelper;
    private final StreamService streamService;
    private final ThreadPoolTaskExecutor schedulerExecutor;

    public StreamSchedulerService(@Qualifier("schedulerTaskExecutor") ThreadPoolTaskExecutor schedulerExecutor, RedisHelper redisHelper, StreamService streamService) {
        this.redisHelper = redisHelper;
        this.streamService = streamService;
        this.schedulerExecutor = schedulerExecutor;
    }


    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
    public void processScheduledStreams() {
        LocalDateTime now = LocalDateTime.now();
        List<StreamContext> streamContexts = redisHelper.getReadyScheduledContexts(now);
        for(StreamContext streamContext : streamContexts) {
            schedulerExecutor.execute(() -> processStream(streamContext));
        }
    }

    private void processStream(StreamContext stream) {
        try {
            log.info("Scheduled stream is starting: {}", stream.getId());
            streamService.startStream(
                    stream.getStreamUrl(),
                    stream.getStorageTypes(),
                    stream.getVideoQuality(),
                    stream.getWatermark(),
                    stream.getStartTime(),
                    stream.getId()
            );
        } catch (Exception e) {
            log.error("Failed to start scheduled stream {}: {}", stream.getId(), e.getMessage());
        }
    }


}

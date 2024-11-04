package com.streameast.segmenter.service;

import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.model.StreamContext;
import com.streameast.segmenter.model.Watermark;
import com.streameast.segmenter.model.enums.VideoQuality;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class StreamService {

    private static final Logger performanceLogger = LoggerFactory.getLogger("com.streameast.segmenter.performance");
    private final RedisHelper redisHelper;
    private final AppSettings appSettings;
    private final FFmpegService fFmpegService;

    public StreamService(AppSettings appSettings, RedisHelper redisHelper, FFmpegService fFmpegService) {
        this.appSettings = appSettings;
        this.redisHelper = redisHelper;
        this.fFmpegService = fFmpegService;
    }

    public CompletableFuture<List<String>> startStream(String streamUrl, VideoQuality quality, Watermark watermark, String providedStreamId) {

        String streamId = providedStreamId != null ? providedStreamId : UUID.randomUUID().toString();
        long startTimeMs = System.currentTimeMillis();
        try {
            if(redisHelper.getContext(streamId) == null) {
                redisHelper.saveContext(streamId, new StreamContext(streamId, streamUrl, quality, LocalDateTime.now(), watermark));
            }

            CompletableFuture<List<String>> resultFuture = new CompletableFuture<>();
            CompletableFuture<Void> readySignal = new CompletableFuture<>();

            processStream(streamId, streamUrl, readySignal, quality, watermark);

            readySignal.orTimeout(30, TimeUnit.SECONDS)
                    .thenApply(v -> Arrays.asList(streamId))
                    .whenComplete((urls, ex) -> {
                        if (ex != null) {
                            //stopStream(streamId);
                            resultFuture.completeExceptionally(
                                    new RuntimeException("Failed to start stream within timeout", ex));
                        } else {
                            resultFuture.complete(urls);
                        }

                        long duration = System.currentTimeMillis() - startTimeMs;
                        log.info("Stream start process completed in {} ms for streamId: {}", duration, streamId);
                    });
            return resultFuture;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTimeMs;
            log.error("Stream start failed in {} ms for streamId: {}", duration, streamId);
            throw e;
        }
    }



    protected void processStream(String streamId, String streamUrl, CompletableFuture<Void> readySignal,
                                 VideoQuality quality, Watermark watermark) {

        StreamContext context = redisHelper.getContext(streamId);
        if(streamId == null)
            return;

        Path tempDir = appSettings.resolvePath("streams", streamId);

        try {
            final WatchService watchService = FileSystems.getDefault().newWatchService();;
            Files.createDirectories(tempDir);
            Path segmentPattern = tempDir.resolve("segment_%d.ts");

            CompletableFuture<Void> ffmpegFuture = fFmpegService.startStreamProcessing(
                    streamId, streamUrl, segmentPattern, quality, watermark);

            setupWatchService(tempDir, watchService);

            CompletableFuture.runAsync(() -> {
                try {

                    WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                    if (key != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                                Path newPath = tempDir.resolve((Path) event.context());
                                String segmentName = newPath.getFileName().toString();
                                System.out.println(segmentName.concat("\n"));
                            }
                        }

                    }

                } catch (Exception e) {
                    log.error("Error in segment monitoring for stream {}: {}", streamId, e.getMessage());
                    if (!readySignal.isDone()) {
                        readySignal.completeExceptionally(e);
                    }
                }
            });

        } catch (Exception e) {
            log.error("Error in stream processing: {}", e.getMessage());
            if (!readySignal.isDone()) {
                readySignal.completeExceptionally(e);
            }
            //stopStream(streamId);
        }

    }

    private void setupWatchService(Path tempDir, WatchService watchService) throws IOException, InterruptedException {
        final int readyIfSegmentCount = appSettings.getRequiredParams().getReadyIfSegmentCount();
        for (int retry = 0; retry < readyIfSegmentCount; retry++) {
            try {
                tempDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
                break;
            } catch (Exception e) {
                if (retry == readyIfSegmentCount - 1) {
                    throw e;
                }
                log.warn("Failed to setup watch service, retrying in {} ms...", readyIfSegmentCount);
                Thread.sleep(1000);
            }
        }
    }
}

package com.streameast.segmenter.service;

import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.model.StreamContext;
import com.streameast.segmenter.model.Watermark;
import com.streameast.segmenter.model.enums.VideoQuality;
import com.streameast.segmenter.service.impl.StorageServiceFactory;
import com.streameast.segmenter.util.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class StreamService {

    private static final Logger performanceLogger = LoggerFactory.getLogger("com.streameast.segmenter.performance");
    private final RedisHelper redisHelper;
    private final AppSettings appSettings;
    private final FFmpegService fFmpegService;
    private final StorageServiceFactory storageServiceFactory;

    public StreamService(AppSettings appSettings, RedisHelper redisHelper, FFmpegService fFmpegService, StorageServiceFactory storageServiceFactory) {
        this.appSettings = appSettings;
        this.redisHelper = redisHelper;
        this.fFmpegService = fFmpegService;
        this.storageServiceFactory = storageServiceFactory;
    }

    public CompletableFuture<List<String>> startStream(String streamUrl, List<String> storageTypes, VideoQuality quality, Watermark watermark, String providedStreamId) {

        String streamId = providedStreamId != null ? providedStreamId : UUID.randomUUID().toString();
        long startTimeMs = System.currentTimeMillis();
        try {
            if(redisHelper.getContext(streamId) == null) {
                redisHelper.saveContext(streamId, new StreamContext(streamId, streamUrl, storageServiceFactory.getAvailableStorageServices(storageTypes), quality, LocalDateTime.now(), watermark));
            }

            CompletableFuture<List<String>> resultFuture = new CompletableFuture<>();
            CompletableFuture<Void> readySignal = new CompletableFuture<>();

            processStream(streamId, streamUrl, readySignal, quality, watermark);

            readySignal.orTimeout(30, TimeUnit.SECONDS)
                    .thenApply(v -> Arrays.asList(streamId))
                    .whenComplete((urls, ex) -> {
                        if (ex != null) {
                            stopStream(streamId);
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


    @Async
    protected void processStream(String streamId, String streamUrl, CompletableFuture<Void> readySignal,
                                 VideoQuality quality, Watermark watermark) {

        StreamContext context = redisHelper.getContext(streamId);
        if(streamId == null)
            return;

        Path tempDir = appSettings.resolvePath("streams", streamId);
        AtomicBoolean isReadyForWatch = new AtomicBoolean(false);

        try {
            final WatchService watchService = FileSystems.getDefault().newWatchService();
            Files.createDirectories(tempDir);
            Path segmentPattern = tempDir.resolve("segment_%d.ts");

            CompletableFuture<Void> ffmpegFuture = fFmpegService.startStreamProcessing(
                    streamId, streamUrl, segmentPattern, quality, watermark);

            setupWatchService(tempDir, watchService);
            context.setActive(true);

            CompletableFuture.runAsync(() -> {
                try {
                    while (context.isActive()) {
                        WatchKey key = watchService.take(); // Blocks until an event occurs
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                                Path newPath = tempDir.resolve((Path) event.context());
                                String segmentName = newPath.getFileName().toString();
                                //if it s ads no need to wait
                                while(segmentName.indexOf("segment") != -1 &&!Files.exists(tempDir.resolve(getNextSegment(segmentName) ))) {
                                    Thread.sleep(AppConstants.SEGMENT_PROCESSING_DELAY_MS);
                                }

                                log.info("SEGMENT:{} ready for upload, Stream ID ={}",segmentName,streamId);

                                processSegment(streamId, newPath, segmentName, isReadyForWatch, readySignal);

                            }
                        }
                        if (!key.reset()) {
                            log.warn("Watch key is no longer valid for stream: {}", streamId);
                            break;
                        }
                    }

                } catch (Exception e) {
                    log.error("Error in segment monitoring for stream {}: {}", streamId, e.getMessage());
                    if (!readySignal.isDone()) {
                        readySignal.completeExceptionally(e);
                    }
                }
            });

            ffmpegFuture.whenComplete((v, ex) -> {
                if (ex != null) {
                    log.error("FFmpeg processing failed for stream {}: {}", streamId, ex.getMessage());
                    //stopStream(streamId);
                }
                try {
                    if (watchService != null) {
                        watchService.close();
                    }
                } catch (Exception e) {
                    log.error("Error closing watch service for stream {}: {}", streamId, e.getMessage());
                }
            });

        } catch (Exception e) {
            log.error("Error in stream processing: {}", e.getMessage());
            if (!readySignal.isDone()) {
                readySignal.completeExceptionally(e);
            }
            stopStream(streamId);
        }

    }

    public void stopStream(String streamId) {
        StreamContext streamContext = redisHelper.getContext(streamId);
        if (streamContext != null) {
            streamContext.setActive(false);
            fFmpegService.stopProcess(streamId);
            redisHelper.saveContext(streamId, streamContext);

            List<StorageService> services = storageServiceFactory.getStorageServices(streamContext.getStorageTypes());
            services.forEach(item -> item.deleteStream(streamId));
        }

        //m3u8Service.clearStreamCache(streamId);

        cleanupStreamDirectory(streamId);
    }

    private void cleanupStreamDirectory(String streamId) {
        try {
            Path streamDir = appSettings.resolvePath("streams", streamId);
            if (Files.exists(streamDir)) {
                Files.walk(streamDir)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception e) {
                                log.warn("Failed to delete path: {}", path);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Error cleaning up stream directory: {}", e.getMessage());
        }
    }

    private void processSegment(String streamId, Path segmentPath, String segmentName, AtomicBoolean isReadyForWatch, CompletableFuture<Void> readySignal) {
        try {
            if (!Files.exists(segmentPath) || Files.size(segmentPath) == 0) {
                log.warn("Skipping empty or non-existent segment: {}", segmentPath);
                return;
            }

            List<CompletableFuture<String>> uploads = new ArrayList<>();
            //List<StorageService> services = storageServiceFactory.getStoragesForStream(streamId);
            StreamContext context = redisHelper.getContext(streamId);
            if(streamId == null)
                return;


            List<StorageService> services = storageServiceFactory.getStorageServices(context.getStorageTypes());
            for (StorageService service : services) {
                uploads.add(service.uploadSegment(segmentPath, streamId));
            }

            CompletableFuture.allOf(uploads.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        //m3u8Service.addSegment(streamId, segmentName);
                        if (isReadyForWatch.compareAndSet(false, true)) {
                            readySignal.complete(null);
                        }
                        log.debug("Successfully processed segment: {}", segmentName);
                    })
                    .exceptionally(e -> {
                        log.error("Error processing segment: {} - {}", segmentName, e.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.error("Error processing segment: {} - {}", segmentName, e.getMessage());
        }


    }

    private String getNextSegment (String path) {
        final int readyIfSegmentCount = appSettings.getRequiredParams().getReadyIfSegmentCount();
        Pattern pattern = Pattern.compile("segment_(\\d+)\\.ts");
        Matcher matcher = pattern.matcher(path);

        if (matcher.find()) {
            int segmentNumber = Integer.parseInt(matcher.group(1)) + readyIfSegmentCount;
            String newSegment = "segment_" + segmentNumber + ".ts";
            return matcher.replaceFirst(newSegment);
        }
        return null;
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

package com.streameast.segmenter.service.impl;

import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
//@ConditionalOnProperty(prefix = "storage.local", name = "enabled", havingValue = "true")
public class LocalStorageService implements StorageService {

    private final AppSettings appSettings;
    private final ThreadPoolTaskExecutor storageExecutor;

    public LocalStorageService(AppSettings appSettings, @Qualifier("storageTaskExecutor") ThreadPoolTaskExecutor storageExecutor) {
        this.appSettings = appSettings;
        this.storageExecutor = storageExecutor;
    }

    @Override
    public CompletableFuture<String> uploadSegment(Path segmentPath, String streamId) {
        return CompletableFuture.completedFuture(getSegmentUrl(streamId, segmentPath.getFileName().toString()));
    }

    @Override
    public void deleteStream(String streamId) {
        CompletableFuture.runAsync(() -> {
            try {
                Path segmentPath = appSettings.resolvePath(streamId);
                FileUtils.deleteDirectory(segmentPath.toFile());
                log.info("Deleted entire stream directory for streamId: {}", streamId);
            } catch (Exception e) {
                log.error("Error deleting local segment: {}", e.getMessage());
            }
        }, storageExecutor);
    }

    @Override
    public String getSegmentUrl(String streamId, String segmentName) {
        return String.format("%s/streams/%s/%s", appSettings.getRequiredParams().getServerUrl(), streamId, segmentName);
    }
}

package com.streameast.segmenter.service.impl;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage.azure", name = "enabled", havingValue = "true")
public class AzureStorageService implements StorageService {

    private final BlobContainerClient containerClient;
    private final String containerName;
    private final ThreadPoolTaskExecutor storageExecutor;

    public AzureStorageService(AppSettings appSettings,
                               @Qualifier("storageTaskExecutor") ThreadPoolTaskExecutor storageExecutor) {
        this.containerName = appSettings.getStorage().getAzure().getAzureContainer();
        this.storageExecutor = storageExecutor;
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(appSettings.getStorage().getAzure().getAzureConnectionString())
                .buildClient();
        this.containerClient = blobServiceClient.getBlobContainerClient(containerName);
    }

    @Override
    public CompletableFuture<String> uploadSegment(Path segmentPath, String streamId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String blobName = String.format("%s/%s", streamId, segmentPath.getFileName());
                containerClient.getBlobClient(blobName)
                        .uploadFromFile(segmentPath.toString(), true);

                return getSegmentUrl(streamId, segmentPath.getFileName().toString());
            } catch (Exception e) {
                log.error("Error uploading to Azure: {}", e.getMessage());
                throw new RuntimeException("Failed to upload to Azure", e);
            }
        }, storageExecutor);
    }

    @Override
    public void deleteStream(String streamId) {
        CompletableFuture.runAsync(() -> {
            try {
                containerClient.listBlobs()
                        .stream()
                        .filter(item -> item.getName().startsWith(streamId))
                        .forEach(item -> {
                            try {
                                containerClient.getBlobClient(item.getName()).delete();
                                log.info("Deleted Azure blob: {}", item.getName());
                            } catch (Exception e) {
                                log.error("Error deleting blob {}: {}", item.getName(), e.getMessage());
                            }
                        });
            } catch (Exception e) {
                log.error("Error deleting from Azure: {}", e.getMessage());
            }
        }, storageExecutor);
    }

    @Override
    public String getSegmentUrl(String streamId, String segmentName) {
        return containerClient.getBlobClient(String.format("%s/%s", streamId, segmentName))
                .getBlobUrl();
    }
}

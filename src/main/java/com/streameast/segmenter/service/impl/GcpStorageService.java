package com.streameast.segmenter.service.impl;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Cors;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.collect.ImmutableList;
import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "storage.gcp", name = "enabled", havingValue = "true")
public class GcpStorageService implements StorageService {

    private final Storage storage;
    private final String bucketName;
    private final String projectId;
    private final ThreadPoolTaskExecutor storageExecutor;

    public GcpStorageService(AppSettings appSettings, @Qualifier("storageTaskExecutor") ThreadPoolTaskExecutor storageExecutor) {
        this.storageExecutor = storageExecutor;
        this.bucketName = appSettings.getStorage().getGcp().getGcpBucket();
        this.projectId = appSettings.getStorage().getGcp().getGcpProjectId();

        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream serviceAccountStream = classLoader.getResourceAsStream("gcp-credentials.json")) {
            if (serviceAccountStream == null) {
                throw new FileNotFoundException("Service account key file not found in resources folder");
            }
            this.storage = StorageOptions.newBuilder()
                    .setProjectId(projectId)
                    .setCredentials(ServiceAccountCredentials.fromStream(serviceAccountStream))
                    .build()
                    .getService();
            configureBucketCors(bucketName, "Content-Type", 3600);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void configureBucketCors(String bucketName, String responseHeader, Integer maxAgeSeconds) {
        Bucket bucket = storage.get(bucketName);

        Cors cors = Cors.newBuilder()
                .setOrigins(ImmutableList.of(Cors.Origin.of("*")))
                .setMethods(ImmutableList.of(HttpMethod.GET))
                .setResponseHeaders(ImmutableList.of(responseHeader))
                .setMaxAgeSeconds(maxAgeSeconds)
                .build();

        bucket.toBuilder().setCors(ImmutableList.of(cors)).build().update();

        log.info("Bucket {} was updated with CORS config to allow GET requests", bucketName);
    }

    @Override
    public CompletableFuture<String> uploadSegment(Path segmentPath, String streamId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String objectName = String.format("%s/%s", streamId, segmentPath.getFileName());
                BlobId blobId = BlobId.of(bucketName, objectName);
                BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                        .setContentType("video/MP2T")
                        .build();

                storage.create(blobInfo, Files.readAllBytes(segmentPath));
                log.info("Successfully uploaded to GCP: {}", objectName);
                return getSegmentUrl(streamId, segmentPath.getFileName().toString());
            } catch (Exception e) {
                log.error("Error uploading to GCP: {}", e.getMessage());
                throw new RuntimeException("Failed to upload to GCP", e);
            }
        }, storageExecutor);
    }

    public void deleteStream(String streamId) {
        CompletableFuture.runAsync(() -> {
            try {
                // Specify the prefix for the objects to delete
                String prefix = streamId + "/";

                // Retrieve all blobs with the specified prefix
                Iterable<Blob> blobs = storage.list(bucketName, Storage.BlobListOption.prefix(prefix)).iterateAll();

                // Batch deletion with configurable batch size
                List<BlobId> blobsToDelete = new ArrayList<>();
                int batchSize = 100;  // Adjust batch size as needed

                for (Blob blob : blobs) {
                    blobsToDelete.add(blob.getBlobId());

                    // Delete in batches when reaching batchSize
                    if (blobsToDelete.size() >= batchSize) {
                        deleteBlobsInBatch(blobsToDelete);
                        blobsToDelete.clear();
                    }
                }

                // Delete remaining blobs if any
                if (!blobsToDelete.isEmpty()) {
                    deleteBlobsInBatch(blobsToDelete);
                }

                log.info("Deleted all objects for prefix: {}", prefix);

            } catch (Exception e) {
                log.error("Error deleting from GCP: {}", e.getMessage());
            }
        }, storageExecutor);
    }

    private void deleteBlobsInBatch(List<BlobId> blobsToDelete) {
        try {
            storage.delete(blobsToDelete);
            blobsToDelete.forEach(blobId -> log.info("Deleted GCP object: {}", blobId.getName()));
        } catch (Exception e) {
            log.error("Error deleting batch of objects from GCP: {}", e.getMessage());
        }
    }


    @Override
    public String getSegmentUrl(String streamId, String segmentName) {
        return String.format("https://storage.googleapis.com/%s/%s/%s",
                bucketName, streamId, segmentName);
    }
}

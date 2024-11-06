package com.streameast.segmenter.service.impl;

import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "storage.aws", name = "enabled", havingValue = "true")
public class AwsStorageService implements StorageService {

    private final S3Client s3Client;
    private final String bucket;
    private final ThreadPoolTaskExecutor storageExecutor;
    private static final int MAX_RETRIES = 3;
    private static final int WAIT_TIME_MS = 500;

    public AwsStorageService(AppSettings appSettings,
                             @Qualifier("storageTaskExecutor") ThreadPoolTaskExecutor storageExecutor) {
        this.bucket = appSettings.getStorage().getAws().getAwsBucket();
        this.storageExecutor = storageExecutor;
        this.s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> AwsBasicCredentials.create(
                        appSettings.getStorage().getAws().getAwsAccessKey(),
                        appSettings.getStorage().getAws().getAwsSecretKey()))
                .build();
    }

    @Override
    public CompletableFuture<String> uploadSegment(Path segmentPath, String streamId) {
        return CompletableFuture.supplyAsync(() -> {
            int retries = 0;
            Exception lastException = null;

            while (retries < MAX_RETRIES) {
                try {
                    Thread.sleep(WAIT_TIME_MS * (retries + 1));

                    if (!Files.exists(segmentPath)) {
                        throw new RuntimeException("File does not exist: " + segmentPath);
                    }

                    long fileSize = Files.size(segmentPath);
                    if (fileSize == 0) {
                        throw new RuntimeException("File is empty: " + segmentPath);
                    }

                    byte[] fileContent = Files.readAllBytes(segmentPath);
                    String key = String.format("%s/%s", streamId, segmentPath.getFileName());

                    PutObjectRequest request = PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build();

                    s3Client.putObject(request, RequestBody.fromBytes(fileContent));
                    log.info("Successfully uploaded segment to S3: {} (size: {} bytes)", key, fileSize);
                    return getSegmentUrl(streamId, segmentPath.getFileName().toString());
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Retry {}/{} - Error uploading to S3: {} - {}",
                            retries + 1, MAX_RETRIES, segmentPath, e.getMessage());
                    retries++;
                }
            }

            log.error("Failed to upload after {} retries: {} - {}",
                    MAX_RETRIES, segmentPath, lastException.getMessage());
            throw new RuntimeException("Failed to upload to S3 after " + MAX_RETRIES + " retries", lastException);
        }, storageExecutor);
    }

    @Override
    public void deleteStream(String streamId) {
        CompletableFuture.runAsync(() -> {
            try {
                String prefix = streamId + "/";
                ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .build();

                ListObjectsV2Response listResponse;
                do {
                    listResponse = s3Client.listObjectsV2(listRequest);
                    List<ObjectIdentifier> objectsToDelete = listResponse.contents().stream()
                            .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
                            .toList();

                    if (!objectsToDelete.isEmpty()) {
                        DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                                .bucket(bucket)
                                .delete(Delete.builder().objects(objectsToDelete).build())
                                .build();

                        s3Client.deleteObjects(deleteRequest);
                        log.info("Deleted {} objects from S3 for prefix: {}", objectsToDelete.size(), streamId);
                    }

                    // Prepare for the next batch if there are more objects
                    String token = listResponse.nextContinuationToken();
                    if (token == null) {
                        break;
                    }

                    listRequest = listRequest.toBuilder().continuationToken(token).build();

                } while (listResponse.isTruncated());

            } catch (Exception e) {
                log.error("Error deleting stream from S3: {}", e.getMessage());
                throw new RuntimeException("Failed to delete from S3", e);
            }
        }, storageExecutor);
    }


    @Override
    public String getSegmentUrl(String streamId, String segmentName) {
        return String.format("https://%s.s3.amazonaws.com/%s/%s",
                bucket, streamId, segmentName);
    }
}

package com.streameast.segmenter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Validated
public class AppSettings {

    @Valid
    @Getter
    private RequiredServices requiredServices = new RequiredServices();

    @Valid
    @Getter
    private RequiredParams requiredParams = new RequiredParams();

    @Valid
    @Getter
    private StorageParams storage = new StorageParams();

    @Getter
    @Setter
    public static class RequiredServices {

        @NotNull
        private String ffmpeg;

        @NotNull
        private String ffprobe;
    }

    @Getter
    @Setter
    public static class RequiredParams {

        @NotNull
        private Integer segmentDuration;

        @NotNull
        private String localTempPath;

        @NotNull
        private Integer readyIfSegmentCount;

        private String serverUrl= "http://localhost:8090";

    }

    @Getter
    @Setter
    public static class StorageParams {
        private AwsStorage aws = new AwsStorage();
        private AzureStorage azure = new AzureStorage();
        private GcpStorage gcp = new GcpStorage();

        @Getter
        @Setter
        public static class AwsStorage {
            private boolean enabled;
            private String awsAccessKey;
            private String awsSecretKey;
            private String awsBucket;
        }

        @Getter
        @Setter
        public static class AzureStorage {
            private boolean enabled;
            private String azureConnectionString;
            private String azureContainer;
        }

        @Getter
        @Setter
        public static class GcpStorage {
            private boolean enabled;
            private String gcpProjectId;
            private String gcpBucket;
        }
    }

    public Path resolvePath(String... parts) {
        return Path.of(requiredParams.localTempPath, parts);
    }

}

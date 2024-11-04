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

    }

    public Path resolvePath(String... parts) {
        return Path.of(requiredParams.localTempPath, parts);
    }

}

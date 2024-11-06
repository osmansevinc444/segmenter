package com.streameast.segmenter.web.dto;

import com.streameast.segmenter.model.Watermark;
import com.streameast.segmenter.model.enums.VideoQuality;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

import static com.streameast.segmenter.util.AppConstants.DATE_FORMATTER;

@Data
public class StreamRequest {

    @NotBlank(message = "Stream URL is required")
    @Pattern(regexp = "^(rtmp|rtsp|http|https)://.*", message = "Invalid stream URL format")
    private String streamUrl;

    private List<String> storageTypes;
    private VideoQuality videoQuality = VideoQuality.LOW;

    @Nullable
    private Watermark watermark;

    private String startTimeStr;

    public LocalDateTime getStartTime() {
        return startTimeStr != null ? LocalDateTime.parse(startTimeStr, DATE_FORMATTER) : null;
    }

    public void setStartTime(String startTimeStr) {
        this.startTimeStr = startTimeStr;
    }

}

package com.streameast.segmenter.web.dto;

import com.streameast.segmenter.model.Watermark;
import com.streameast.segmenter.model.enums.VideoQuality;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class StreamRequest {

    @NotBlank(message = "Stream URL is required")
    @Pattern(regexp = "^(rtmp|rtsp|http|https)://.*", message = "Invalid stream URL format")
    private String streamUrl;

    private List<String> storageTypes;
    private VideoQuality videoQuality = VideoQuality.LOW;

    @Nullable
    private Watermark watermark;

}

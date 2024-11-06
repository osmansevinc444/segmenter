package com.streameast.segmenter.web.dto;

import lombok.Builder;
import lombok.Data;
import lombok.With;
import org.springframework.web.multipart.MultipartFile;

@Data
@Builder(toBuilder = true)
public class AdvertisementRequest {
    private final MultipartFile file;
    private final String streamId;
    private final Integer startSegment;
    @With private final Integer duration;
    private final Type type;

    public enum Type {
        IMAGE,
        VIDEO,
        TS_FILE
    }
}

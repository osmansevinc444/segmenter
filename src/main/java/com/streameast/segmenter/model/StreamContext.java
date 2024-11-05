package com.streameast.segmenter.model;

import com.streameast.segmenter.model.enums.VideoQuality;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class StreamContext implements Serializable {
    private String id;
    private String streamUrl;
    private List<String> storageTypes;
    private VideoQuality videoQuality;
    private LocalDateTime startTime;
    private Watermark watermark;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicInteger segmentCount = new AtomicInteger(0);

    public StreamContext() {}

    public StreamContext(String id, String streamUrl,List<String> storageTypes, VideoQuality videoQuality, LocalDateTime startTime, Watermark watermark) {
        this.id = id;
        this.streamUrl = streamUrl;
        this.storageTypes = storageTypes;
        this.videoQuality = videoQuality;
        this.startTime = startTime;
        this.watermark = watermark;
    }

    public boolean isActive() {
        return active.get();
    }

    public void setActive(boolean value) {
        active.set(value);
    }


    public int incrementAndGetSegmentCount() {
        return this.segmentCount.incrementAndGet();
    }


}

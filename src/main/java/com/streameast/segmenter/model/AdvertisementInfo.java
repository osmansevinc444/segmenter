package com.streameast.segmenter.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdvertisementInfo {
    private final String path;
    private final int duration;
    private final List<String> segmentNames;
    private final boolean processed;

    public AdvertisementInfo(String path, int duration, List<String> segmentNames, boolean processed) {
        this.path = path;
        this.duration = Math.min(duration, 300); // Max 5 minutes
        this.segmentNames = new ArrayList<>(segmentNames);
        this.processed = processed;
    }
}

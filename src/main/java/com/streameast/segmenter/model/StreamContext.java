package com.streameast.segmenter.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.streameast.segmenter.model.enums.VideoQuality;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamContext implements Serializable {
    private String id;
    private String streamUrl;
    private List<String> storageTypes;
    private VideoQuality videoQuality;
    private LocalDateTime startTime;
    private Watermark watermark;
    private long pId = -1;
    private boolean isProccessing = false;
    private String desc;

    @JsonIgnore
    private final AtomicBoolean active = new AtomicBoolean(false);
    @JsonIgnore
    private final AtomicInteger segmentCount = new AtomicInteger(0);

    private TreeSet<Integer> streamSequences = new TreeSet<>();
    private Map<String, String> playlistContents = new HashMap<>();
    private Map<Integer, AdvertisementInfo> advertisementSegments = new HashMap<>();

    public StreamContext() {}

    public StreamContext(String id, String streamUrl, List<String> storageTypes, VideoQuality videoQuality,
                         LocalDateTime startTime, Watermark watermark) {
        this.id = id;
        this.streamUrl = streamUrl;
        this.storageTypes = storageTypes;
        this.videoQuality = videoQuality;
        this.startTime = startTime;
        this.watermark = watermark;
    }

    @JsonProperty("active")
    public boolean isActive() {
        return active.get();
    }

    public void setActive(boolean value) {
        active.set(value);
    }

    @JsonProperty("segmentCount")
    public int getSegmentCount() {
        return segmentCount.get();
    }

    @JsonProperty("segmentCount")
    public void setSegmentCount(int value) {
        segmentCount.set(value);
    }

    public int incrementAndGetSegmentCount() {
        return this.segmentCount.incrementAndGet();
    }

    public List<String> getUrls(String url) {
        return storageTypes.stream()
                .map(type -> String.format("%s/api/stream/%s/%s/playlist.m3u8", url, id, type.toLowerCase()))
                .toList();
    }

    public void addSequence(int sequence, int maxSegments) {
        streamSequences.add(sequence);
        while (streamSequences.size() > maxSegments) {
            streamSequences.pollFirst();
        }
    }

    public Integer getFirstSequence() {
        return streamSequences.isEmpty() ? 0 : streamSequences.first();
    }

    public TreeSet<Integer> getStreamSequences() {
        return new TreeSet<>(streamSequences);
    }

    public void setPlaylistContent(String storageType, String content) {
        playlistContents.put(storageType.toLowerCase(), content);
    }

    public String getPlaylistContent(String storageType) {
        return playlistContents.get(storageType.toLowerCase());
    }

    public void addAdvertisement(int startSegment, AdvertisementInfo adInfo) {
        advertisementSegments.put(startSegment, adInfo);
    }

    public Map<Integer, AdvertisementInfo> getAdvertisements() {
        return new HashMap<>(advertisementSegments);
    }
}

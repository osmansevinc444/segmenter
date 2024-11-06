package com.streameast.segmenter.service;

import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.model.AdvertisementInfo;
import com.streameast.segmenter.model.StreamContext;
import com.streameast.segmenter.service.impl.StorageServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PlaylistService {

    private final AppSettings appSettings;
    private final StorageServiceFactory storageServiceFactory;
    private final ThreadPoolTaskExecutor playlistTaskExecutor;
    private final RedisHelper redisHelper;

    public PlaylistService(@Qualifier("playlistTaskExecutor") ThreadPoolTaskExecutor playlistTaskExecutor,
                           StorageServiceFactory storageServiceFactory, RedisHelper redisHelper, AppSettings appSettings) {
        this.storageServiceFactory = storageServiceFactory;
        this.playlistTaskExecutor = playlistTaskExecutor;
        this.redisHelper = redisHelper;
        this.appSettings = appSettings;
    }

    public String getPlaylistContent(String streamId, String storageType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StreamContext context = redisHelper.getContext(streamId);
                if (context == null) {
                    return generateEmptyPlaylist(0);
                }

                String content = context.getPlaylistContent(storageType);
                return content != null ? content : generateEmptyPlaylist(0);
            } catch (Exception e) {
                log.error("Failed to get playlist content for stream {}, storage: {}",
                        streamId, storageType, e);
                return generateEmptyPlaylist(0);
            }
        }, playlistTaskExecutor).join();
    }

    public void addSegment(String streamId, String segmentName) {
        CompletableFuture.runAsync(() -> {
            try {
                int sequence = extractSequenceNumber(segmentName);
                StreamContext context = redisHelper.getContext(streamId);

                if (context != null) {
                    context.addSequence(sequence, appSettings.getRequiredParams().getMaxSegmentsInPlaylist());
                    updatePlaylist(context);
                    redisHelper.saveContext(streamId, context);
                }
            } catch (Exception e) {
                log.error("Failed to add segment {} for stream {}", segmentName, streamId, e);
            }
        }, playlistTaskExecutor);
    }

    private void updatePlaylist(StreamContext context) {
        try {
            if (context.getStreamSequences().isEmpty()) return;

            int mediaSequence = context.getFirstSequence();
            List<StorageService> services = storageServiceFactory.getStorageServices(context.getStorageTypes());
            Map<Integer, AdvertisementInfo> advertisements = context.getAdvertisements();

            int maxDuration = appSettings.getRequiredParams().getSegmentDuration();
            for (AdvertisementInfo adInfo : advertisements.values()) {
                maxDuration = Math.max(maxDuration,
                        Math.min(appSettings.getRequiredParams().getSegmentDuration(), adInfo.getDuration()));
            }

            for (StorageService service : services) {
                StringBuilder playlist = new StringBuilder();
                playlist.append("#EXTM3U\n")
                        .append("#EXT-X-VERSION:3\n")
                        .append("#EXT-X-TARGETDURATION:").append(maxDuration).append("\n")
                        .append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append("\n")
                        .append("#EXT-X-DISCONTINUITY-SEQUENCE:0\n");

                boolean wasAdvertisement = false;
                for (Integer sequence : context.getStreamSequences()) {
                    AdvertisementInfo adInfo = advertisements.get(sequence);

                    if (adInfo != null && !adInfo.getSegmentNames().isEmpty()) {
                        if (!wasAdvertisement) {
                            playlist.append("#EXT-X-DISCONTINUITY\n");
                        }

                        for (String segmentName : adInfo.getSegmentNames()) {
                            int segmentDuration = Math.min(
                                    appSettings.getRequiredParams().getSegmentDuration(),
                                    adInfo.getDuration() - (adInfo.getSegmentNames().indexOf(segmentName) *
                                            appSettings.getRequiredParams().getSegmentDuration())
                            );

                            playlist.append("#EXTINF:").append(segmentDuration).append(".0,\n")
                                    .append(service.getSegmentUrl(context.getId(), segmentName)).append("\n");
                        }
                        wasAdvertisement = true;
                    } else {
                        if (wasAdvertisement) {
                            playlist.append("#EXT-X-DISCONTINUITY\n");
                        }
                        String segmentName = String.format("segment_%d.ts", sequence);
                        playlist.append("#EXTINF:").append(appSettings.getRequiredParams().getSegmentDuration()).append(".0,\n")
                                .append(service.getSegmentUrl(context.getId(), segmentName)).append("\n");
                        wasAdvertisement = false;
                    }
                }

                context.setPlaylistContent(service.getStorageType(), playlist.toString());
            }
        } catch (Exception e) {
            log.error("Failed to update playlist for stream {}", context.getId(), e);
        }
    }

    private String generateEmptyPlaylist(int mediaSequence) {
        return String.format("""
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:%d
            #EXT-X-MEDIA-SEQUENCE:%d
            """, appSettings.getRequiredParams().getSegmentDuration(), mediaSequence);
    }

    private int extractSequenceNumber(String segmentName) {
        try {
            // Use regex to match either "segment_" or "advertisement_" followed by a sequence of digits
            Matcher matcher = Pattern.compile("(segment|advertisement)_(\\d+)").matcher(segmentName);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(2)); // group(2) captures the sequence number
            }
            log.warn("No sequence number found in segment name: {}", segmentName);
            return 0;
        } catch (Exception e) {
            log.warn("Failed to extract sequence number from segment name: {}", segmentName, e);
            return 0;
        }
    }

}

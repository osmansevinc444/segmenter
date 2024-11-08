package com.streameast.segmenter.service;

import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.model.StreamContext;
import com.streameast.segmenter.model.enums.VideoQuality;
import com.streameast.segmenter.model.Watermark;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


@Slf4j
@Service
public class FFmpegService {

    private static final Logger performanceLogger = LoggerFactory.getLogger("com.streameast.segmenter.performance");
    private final AppSettings appSettings;
    private final String ffmpegPath;
    private final String ffprobePath;
    private final Integer defaultSegmentDuration;
    private final ThreadPoolTaskExecutor ffmpegStreamExecutor;
    private final RedisHelper redisHelper;

    public FFmpegService(@Qualifier("ffmpegStreamExecutor") ThreadPoolTaskExecutor ffmpegStreamExecutor, AppSettings appConfig, RedisHelper redisHelper) {
        this.ffmpegStreamExecutor = ffmpegStreamExecutor;
        this.appSettings = appConfig;
        this.ffmpegPath = appConfig.getRequiredServices().getFfmpeg();
        this.ffprobePath = appConfig.getRequiredServices().getFfprobe();
        this.defaultSegmentDuration = appConfig.getRequiredParams().getSegmentDuration();
        this.redisHelper = redisHelper;
    }

    public CompletableFuture<Void> startStreamProcessing(String streamId, String streamUrl, Path outputPattern, VideoQuality quality, Watermark watermark) {
        long startTime = System.currentTimeMillis();
        StreamContext context = redisHelper.getContext(streamId);
        if(context == null)
            throw new RuntimeException("FFmpeg process failed because of context is null: " + streamId);

        return CompletableFuture.runAsync(() -> {
            try{

                // Ensure output directory exists
                Files.createDirectories(outputPattern.getParent());

                List<String> command = buildFFmpegCommand(streamUrl, outputPattern, quality, watermark);
                log.info("Starting FFmpeg process with command: {}", String.join(" ", command));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.inheritIO();
                Process process = pb.start();
                context.setPId(process.pid());
                context.setActive(true);
                context.setProccessing(true);
                redisHelper.saveContext(streamId, context);

                int exitCode = process.waitFor();

                if (exitCode != 0 && exitCode != 255) { // 255 is for normal termination
                    performanceLogger.warn("FFmpeg stopped {}", streamId);
                }

                long duration = System.currentTimeMillis() - startTime;
                performanceLogger.info("FFmpeg process completed in {} ms for streamId: {}", duration, streamId);

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                performanceLogger.error("FFmpeg process failed in {} ms for streamId: {}", duration, streamId);
                log.error("Error in FFmpeg processing: {}", e.getMessage());
                throw new RuntimeException("Failed to process stream", e);
            } finally {
                context.setProccessing(false);
                context.setActive(false);
                redisHelper.saveContext(streamId, context);
            }

        }, ffmpegStreamExecutor);
    }

    private List<String> buildFFmpegCommand(String streamUrl, Path outputPattern,
                                            VideoQuality quality, Watermark watermark) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        // HLS için gerekli input ayarları
        command.add("-live_start_index");
        command.add("-1");
        command.add("-i");
        command.add(streamUrl);

        StringBuilder filterChain = new StringBuilder();

        if (watermark != null && StringUtils.isNotEmpty(watermark.getImagePath())) {
            command.add("-i");
            command.add(watermark.getImagePath());

            filterChain.append("[0:v]setpts=PTS-STARTPTS,")  // Timestamp'leri sıfırla
                    .append("select='not(mod(n\\,2))'[filtered];")
                    .append("[1:v]scale=-1:").append(watermark.getSize())
                    .append(",format=rgba,colorchannelmixer=aa=").append(watermark.getOpacity())
                    .append("[watermark];")
                    .append("[filtered][watermark]overlay=")
                    .append(watermark.getX()).append(":").append(watermark.getY())
                    .append("[outv]");
        } else if (watermark != null && StringUtils.isNotEmpty(watermark.getText())) {
            filterChain.append("[0:v]setpts=PTS-STARTPTS,")  // Timestamp'leri sıfırla
                    .append("select='not(mod(n\\,2))',")
                    .append("drawtext=text='").append(watermark.getText())
                    .append("':fontsize=").append(watermark.getSize())
                    .append(":fontcolor=").append(watermark.getColor())
                    .append("@").append(watermark.getOpacity())
                    .append(":x=").append(watermark.getX())
                    .append(":y=").append(watermark.getY())
                    .append("[outv]");
        } else {
            filterChain.append("[0:v]setpts=PTS-STARTPTS,select='not(mod(n\\,2))'[outv]");
        }

        command.add("-filter_complex");
        command.add(filterChain.toString());

        command.add("-map");
        command.add("[outv]");
        command.add("-map");
        command.add("0:a?");

        // Video ayarları
        command.add("-c:v");
        command.add("libx264");
        command.add("-b:v");
        command.add(quality.getVideoBitrateKbps() + "k");

        // Ses ayarları
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(quality.getAudioBitrateKbps() + "k");

        // Segment ayarları
        command.add("-f");
        command.add("segment");
        command.add("-segment_time");
        command.add(String.valueOf(defaultSegmentDuration));
        command.add("-segment_format");
        command.add("mpegts");
        command.add("-segment_list_size");
        command.add("0");
        command.add("-segment_list_flags");
        command.add("+live");

        // Timestamp düzeltmeleri
        command.add("-copyts");
        command.add("-start_at_zero");

        command.add(outputPattern.toString());

        return command;
    }


    public void stopProcess(String streamId) {
        StreamContext context = redisHelper.getContext(streamId);
        if(context == null)
            return;

        Optional<ProcessHandle> optionalProcessHandle = ProcessHandle.of(context.getPId());
        optionalProcessHandle.ifPresent(ProcessHandle::destroyForcibly);
        log.info("FFmpeg process stopped for streamId: {}", streamId);

    }
}

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
        command.add("-i");
        command.add(streamUrl);

        // Build the filter chain
        StringBuilder filterChain = new StringBuilder();

        if (watermark != null && StringUtils.isNotEmpty(watermark.getImagePath())) {
            // Add watermark image input
            command.add("-i");
            command.add(watermark.getImagePath());

            // Create complete filter chain with watermark
            filterChain.append("[0:v]select='not(mod(n\\,2))'[filtered];") // Select frames
                    .append("[1:v]scale=-1:").append(watermark.getSize())
                    .append(",format=rgba,colorchannelmixer=aa=").append(watermark.getOpacity())
                    .append("[watermark];") // Prepare watermark
                    .append("[filtered][watermark]overlay=")
                    .append(watermark.getX()).append(":").append(watermark.getY())
                    .append("[outv]"); // Final output label
        } else if (watermark != null && StringUtils.isNotEmpty(watermark.getText())) {
            // Text watermark filter chain
            filterChain.append("[0:v]select='not(mod(n\\,2))',") // Select frames
                    .append("drawtext=text='").append(watermark.getText())
                    .append("':fontsize=").append(watermark.getSize())
                    .append(":fontcolor=").append(watermark.getColor())
                    .append("@").append(watermark.getOpacity())
                    .append(":x=").append(watermark.getX())
                    .append(":y=").append(watermark.getY())
                    .append("[outv]"); // Final output label
        } else {
            // Simple selection filter chain
            filterChain.append("[0:v]select='not(mod(n\\,2))'[outv]");
        }

        command.add("-filter_complex");
        command.add(filterChain.toString());

        // Map the final video output
        command.add("-map");
        command.add("[outv]"); // Use the final output label

        // Map all audio streams from input
        command.add("-map");
        command.add("0:a?"); // The ? makes it optional in case there's no audio

        // Video settings
        command.add("-c:v");
        command.add("libx264");
        command.add("-b:v");
        command.add(quality.getVideoBitrateKbps() + "k");

        // Audio settings
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(quality.getAudioBitrateKbps() + "k");

        // Segmenting options
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

        // Use output pattern for segments
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

package com.streameast.segmenter.service;

import com.streameast.segmenter.config.AppSettings;
import com.streameast.segmenter.model.StreamContext;
import com.streameast.segmenter.model.enums.VideoQuality;
import com.streameast.segmenter.model.Watermark;
import lombok.extern.slf4j.Slf4j;
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
                log.debug("Starting FFmpeg process with command: {}", String.join(" ", command));

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

        if (watermark != null) {
            addWatermarkParameters(command, watermark);
        }

        // Dynamic filtering to skip segments (example: skip every other segment)
        command.add("-filter_complex");
        command.add("[0:v]select='not(mod(n\\,2))'[v]"); // Replace with logic specific to ad markers

        // Map the filtered output
        command.add("-map");
        command.add("[v]");

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
        command.add("-map");
        command.add("0");
        command.add(outputPattern.toString());

        return command;
    }


    private void addWatermarkParameters(List<String> command, Watermark watermark) {
        if (watermark.getImagePath() != null) {
            command.add("-i");
            command.add(watermark.getImagePath());
            command.add("-filter_complex");
            command.add(String.format(
                    "[1:v]scale=-1:%d,format=rgba,colorchannelmixer=aa=%f[watermark];" +
                            "[0:v][watermark]overlay=%d:%d",
                    watermark.getSize(), watermark.getOpacity(),
                    watermark.getX(), watermark.getY()
            ));
        } else if (watermark.getText() != null) {
            command.add("-vf");
            command.add(String.format(
                    "drawtext=text='%s':fontsize=%d:fontcolor=%s@%f:x=%d:y=%d",
                    watermark.getText(), watermark.getSize(), watermark.getColor(),
                    watermark.getOpacity(), watermark.getX(), watermark.getY()
            ));
        }
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

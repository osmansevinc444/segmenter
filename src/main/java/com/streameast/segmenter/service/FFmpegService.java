package com.streameast.segmenter.service;

import com.streameast.segmenter.config.AppSettings;
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

    public FFmpegService(@Qualifier("ffmpegStreamExecutor") ThreadPoolTaskExecutor ffmpegStreamExecutor, AppSettings appConfig) {
        this.ffmpegStreamExecutor = ffmpegStreamExecutor;
        this.appSettings = appConfig;
        this.ffmpegPath = appConfig.getRequiredServices().getFfmpeg();
        this.ffprobePath = appConfig.getRequiredServices().getFfprobe();
        this.defaultSegmentDuration = appConfig.getRequiredParams().getSegmentDuration();
    }

    public CompletableFuture<Void> startStreamProcessing(String streamId, String streamUrl, Path outputPattern, VideoQuality quality, Watermark watermark) {
        long startTime = System.currentTimeMillis();
        return CompletableFuture.runAsync(() -> {
            try{

                // Ensure output directory exists
                Files.createDirectories(outputPattern.getParent());

                List<String> command = buildFFmpegCommand(streamUrl, outputPattern, quality, watermark);
                log.debug("Starting FFmpeg process with command: {}", String.join(" ", command));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.inheritIO();
                Process process = pb.start();
                //activeProcesses.put(streamId, process);

                int exitCode = process.waitFor();

                if (exitCode != 0 && exitCode != 255) { // 255 is for normal termination
                    performanceLogger.error("FFmpeg process failed   for streamId: {}", streamId);
                    throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode);
                }

                long duration = System.currentTimeMillis() - startTime;
                performanceLogger.info("FFmpeg process completed in {} ms for streamId: {}", duration, streamId);

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                performanceLogger.error("FFmpeg process failed in {} ms for streamId: {}", duration, streamId);
                log.error("Error in FFmpeg processing: {}", e.getMessage());
                throw new RuntimeException("Failed to process stream", e);
            } finally {
                //activeProcesses.remove(streamId);
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

        command.add("-c:v");
        command.add("libx264");
        command.add("-b:v");
        command.add(quality.getVideoBitrateKbps() + "k");

        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(quality.getAudioBitrateKbps() + "k");

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
}

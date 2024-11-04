package com.streameast.segmenter.web.controller;

import com.streameast.segmenter.service.StreamService;
import com.streameast.segmenter.web.dto.StreamRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final StreamService streamService;

    public StreamController(StreamService streamService) {
        this.streamService = streamService;
    }

    @PostMapping("/start")
    public ResponseEntity<?> startStream(@RequestBody StreamRequest request) {

        try {
            List<String> urls = streamService.startStream(
                    request.getStreamUrl(),
                    request.getVideoQuality(),
                    request.getWatermark(),
                    null
            ).get(30, TimeUnit.SECONDS);

            return ResponseEntity.ok().body(urls);
        } catch (Exception e) {
            log.error("Failed to start stream", e);
            return ResponseEntity.internalServerError()
                    .body("Failed to start stream: " + e.getMessage());
        }

    }

}

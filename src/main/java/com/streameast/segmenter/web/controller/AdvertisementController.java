package com.streameast.segmenter.web.controller;

import com.streameast.segmenter.web.dto.AdvertisementRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/advertisement")
@RequiredArgsConstructor
public class AdvertisementController {
    //private final AdvertisementService advertisementService;

    @PostMapping("/insert")
    public ResponseEntity<String> insertAdvertisement(
            @RequestParam("file") MultipartFile file,
            @RequestParam("streamId") String streamId,
            @RequestParam("startSegment") Integer startSegment,
            @RequestParam("duration") Integer duration,
            @RequestParam(value = "type", defaultValue = "VIDEO") String type) {

        try {
            AdvertisementRequest request = AdvertisementRequest.builder()
                    .file(file)
                    .streamId(streamId)
                    .startSegment(startSegment)
                    .duration(duration)
                    .type(AdvertisementRequest.Type.valueOf(type.toUpperCase()))
                    .build();

            //String result = advertisementService.insertAdvertisement(request);
            //return ResponseEntity.ok(result);
            return ResponseEntity.ok("Test");
        } catch (Exception e) {
            log.error("Failed to insert advertisement", e);
            return ResponseEntity.internalServerError()
                    .body("Failed to insert advertisement: " + e.getMessage());
        }
    }
}

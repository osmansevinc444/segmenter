package com.streameast.segmenter.web.controller;

import com.streameast.segmenter.service.PlaylistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class StreamContentController {
    private final PlaylistService m3u8Service;

    @GetMapping("/{streamId}/{storageType}/playlist.m3u8")
    public ResponseEntity<String> getPlaylist(
            @PathVariable String streamId,
            @PathVariable String storageType) {
        return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .body(m3u8Service.getPlaylistContent(streamId, storageType));
    }
}

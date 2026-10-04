package com.chatapp.imageservice.controller;

import com.chatapp.imageservice.dto.PresignedDownloadResponse;
import com.chatapp.imageservice.dto.PresignedUploadRequest;
import com.chatapp.imageservice.dto.PresignedUploadResponse;
import com.chatapp.imageservice.service.ImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @PostMapping("/presign/upload")
    public ResponseEntity<PresignedUploadResponse> getUploadUrl(
            Principal principal,
            @Valid @RequestBody PresignedUploadRequest request) {
        log.info(">>> POST /api/images/presign/upload user={}, filename={}, contentType={}",
                principal.getName(), request.filename(), request.contentType());
        var response = imageService.generateUploadUrl(principal.getName(), request);
        log.info("<<< POST /api/images/presign/upload 200 key={}", response.imageKey());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/presign/download")
    public ResponseEntity<PresignedDownloadResponse> getDownloadUrl(
            Principal principal,
            @RequestParam String imageKey) {
        log.info(">>> GET /api/images/presign/download user={}, imageKey={}", principal.getName(), imageKey);
        var response = imageService.refreshDownloadUrl(imageKey);
        log.info("<<< GET /api/images/presign/download 200 refreshed key={}", imageKey);
        return ResponseEntity.ok(response);
    }
}

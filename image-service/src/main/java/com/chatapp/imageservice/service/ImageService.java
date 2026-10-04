package com.chatapp.imageservice.service;

import com.chatapp.imageservice.dto.PresignedDownloadResponse;
import com.chatapp.imageservice.dto.PresignedUploadRequest;
import com.chatapp.imageservice.dto.PresignedUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ImageService {

    private final S3Presigner s3Presigner;
    private final String bucket;
    private final int uploadExpirationMinutes;
    private final int downloadExpirationMinutes;
    private final List<String> allowedContentTypes;

    public ImageService(
            S3Presigner s3Presigner,
            @Value("${aws.s3.bucket}") String bucket,
            @Value("${aws.presigned-url.upload-expiration-minutes}") int uploadExpirationMinutes,
            @Value("${aws.presigned-url.download-expiration-minutes}") int downloadExpirationMinutes,
            @Value("${image.allowed-content-types:image/jpeg,image/png,image/gif,image/webp}") String allowedContentTypesStr) {
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
        this.uploadExpirationMinutes = uploadExpirationMinutes;
        this.downloadExpirationMinutes = downloadExpirationMinutes;
        this.allowedContentTypes = Arrays.asList(allowedContentTypesStr.split(","));
    }

    public PresignedUploadResponse generateUploadUrl(String username, PresignedUploadRequest request) {
        // Validate content type
        if (!allowedContentTypes.contains(request.contentType().toLowerCase())) {
            throw new IllegalArgumentException(
                    "Unsupported content type: " + request.contentType() +
                    ". Allowed: " + String.join(", ", allowedContentTypes));
        }

        // Extract file extension from original filename
        String extension = "";
        String filename = request.filename();
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = filename.substring(dotIndex).toLowerCase();
        }

        // Generate a unique S3 key: images/{username}/{uuid}{extension}
        String imageKey = "images/" + username + "/" + UUID.randomUUID() + extension;

        // Generate presigned PUT URL for upload
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)                     // which bucket
                .key(imageKey)                      // where in the bucket
                .contentType(request.contentType()) // S3 enforces this content type on upload
                .build();

        // Wrap it in a presign request with expiry
        PutObjectPresignRequest presignPutRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(uploadExpirationMinutes))
                .putObjectRequest(putRequest)
                .build();

        // Ask S3Presigner to sign it → get the URL
        String uploadUrl = s3Presigner.presignPutObject(presignPutRequest).url().toString();

        // Generate presigned GET URL for download (used when displaying the image)
        String downloadUrl = generateDownloadUrl(imageKey);

        log.info("Generated presigned upload URL: user={}, key={}, contentType={}",
                username, imageKey, request.contentType());

        return new PresignedUploadResponse(uploadUrl, imageKey, downloadUrl);
    }

    public PresignedDownloadResponse refreshDownloadUrl(String imageKey) {
        String downloadUrl = generateDownloadUrl(imageKey);
        log.info("Refreshed presigned download URL: key={}", imageKey);
        return new PresignedDownloadResponse(downloadUrl);
    }

    private String generateDownloadUrl(String imageKey) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(imageKey)
                .responseCacheControl("public, max-age=" + (downloadExpirationMinutes * 60) + ", immutable") // tells browser to cache for 7 days
                .build();

        GetObjectPresignRequest presignGetRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(downloadExpirationMinutes))
                .getObjectRequest(getRequest)
                .build();

        return s3Presigner.presignGetObject(presignGetRequest).url().toString();
    }
}

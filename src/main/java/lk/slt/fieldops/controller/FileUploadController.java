package lk.slt.fieldops.controller;

import lk.slt.fieldops.entity.JobPhoto;
import lk.slt.fieldops.repository.JobPhotoRepository;
import lk.slt.fieldops.service.FileStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic photo upload — used for fault evidence photos (Client) and
 * job after-service photos (Technician). Returns URLs to reference in
 * the fault-report / job-completion payloads.
 */
@RestController
@RequestMapping("/api/uploads")
public class FileUploadController {

    private final FileStorageService fileStorageService;
    private final JobPhotoRepository jobPhotoRepository;

    public FileUploadController(FileStorageService fileStorageService,
                                 JobPhotoRepository jobPhotoRepository) {
        this.fileStorageService = fileStorageService;
        this.jobPhotoRepository = jobPhotoRepository;
    }

    @PostMapping("/photos")
    @PreAuthorize("hasAnyRole('CLIENT','TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<Map<String, Object>> uploadPhotos(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "photo_type", required = false) String photoType) {

        if (files.size() > 5) {
            throw new RuntimeException("A maximum of 5 photos can be uploaded at a time.");
        }

        // JOB-006 — photo_type was previously accepted and silently discarded. Persisted here,
        // unclaimed (jobId null), so JobService.updateJobStatus can look each URL's type back up
        // when the job that used it reaches COMPLETED — this endpoint has no job to attach to yet.
        JobPhoto.PhotoType type = parsePhotoType(photoType);

        List<String> urls = files.stream()
                .map(f -> {
                    String url = fileStorageService.store(f, "photos");
                    jobPhotoRepository.save(JobPhoto.builder()
                            .url(url)
                            .photoType(type)
                            .build());
                    return url;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("urls", urls));
    }

    private JobPhoto.PhotoType parsePhotoType(String photoType) {
        if (photoType == null || photoType.isBlank()) {
            return JobPhoto.PhotoType.AFTER;
        }
        try {
            return JobPhoto.PhotoType.valueOf(photoType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return JobPhoto.PhotoType.AFTER;
        }
    }
}

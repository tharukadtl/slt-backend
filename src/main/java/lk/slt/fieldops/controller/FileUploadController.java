package lk.slt.fieldops.controller;

import lk.slt.fieldops.service.FileStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public FileUploadController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/photos")
    @PreAuthorize("hasAnyRole('CLIENT','TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> uploadPhotos(
            @RequestParam("files") List<MultipartFile> files) {

        if (files.size() > 5) {
            throw new RuntimeException("A maximum of 5 photos can be uploaded at a time.");
        }

        List<String> urls = files.stream()
                .map(f -> fileStorageService.store(f, "photos"))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("urls", urls));
    }
}

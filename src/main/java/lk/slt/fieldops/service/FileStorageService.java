package lk.slt.fieldops.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * FileStorageService — saves uploaded images to local disk and returns a
 * URL path served by the static resource handler at /uploads/**.
 *
 * Simple by design: local disk instead of cloud storage, since this app
 * runs as a single instance. Swap for S3/Cloud Storage later if needed.
 */
@Service
public class FileStorageService {

    @Value("${app.uploads.dir:uploads}")
    private String uploadsDir;

    public String store(MultipartFile file, String subfolder) {
        String contentType = file.getContentType();
        if (contentType == null ||
            !(contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/png"))) {
            throw new RuntimeException("Only JPEG/PNG images are allowed: " + file.getOriginalFilename());
        }

        String ext = contentType.equalsIgnoreCase("image/png") ? ".png" : ".jpg";
        String filename = UUID.randomUUID() + ext;

        try {
            Path dir = Paths.get(uploadsDir, subfolder);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            file.transferTo(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store uploaded file: " + e.getMessage());
        }

        return "/uploads/" + subfolder + "/" + filename;
    }
}

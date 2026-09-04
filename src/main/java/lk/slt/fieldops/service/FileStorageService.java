package lk.slt.fieldops.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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

    // No spring.servlet.multipart.max-file-size is set in application.yml, and MockMvc never
    // runs the servlet container's multipart parser anyway (FAULT-005), so this is the only
    // place an oversized photo is actually rejected. 5MB matches the mobile PhotoPicker's
    // 1024x1024 JPEG/PNG capture (well under this in practice) with headroom for real photos.
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    // JOB-008: the declared Content-Type is attacker-controlled, so it is only a first filter —
    // the file's real bytes decide whether it is stored. A JPEG always starts FF D8 FF; a PNG
    // always starts with the 8-byte signature 89 50 4E 47 0D 0A 1A 0A.
    private static final byte[] JPEG_SIGNATURE = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] PNG_SIGNATURE  = {
        (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
        (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };
    private static final int SIGNATURE_BYTES_TO_READ = 8;

    @Value("${app.uploads.dir:uploads}")
    private String uploadsDir;

    public String store(MultipartFile file, String subfolder) {
        if (file.isEmpty()) {
            throw new RuntimeException(
                "Uploaded file is empty: " + file.getOriginalFilename());
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new RuntimeException(
                "File exceeds the maximum size of 5MB: " + file.getOriginalFilename());
        }

        String contentType = file.getContentType();
        if (contentType == null ||
            !(contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/png"))) {
            throw new RuntimeException("Only JPEG/PNG images are allowed: " + file.getOriginalFilename());
        }

        // The declared type said "image"; now verify the content actually is one.
        String ext = detectImageExtension(file);
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

    /**
     * Reads the first bytes of the uploaded file and returns the extension for the image format
     * they actually are — ".jpg" or ".png". Anything else (a script, a document, an archive
     * renamed to .jpg, or an SVG that slipped past the declared-type check) is rejected here,
     * regardless of what the Content-Type header or the filename claims.
     */
    private String detectImageExtension(MultipartFile file) {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(SIGNATURE_BYTES_TO_READ);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file: " + e.getMessage());
        }

        if (startsWith(header, JPEG_SIGNATURE)) {
            return ".jpg";
        }
        if (startsWith(header, PNG_SIGNATURE)) {
            return ".png";
        }
        throw new RuntimeException(
            "File content is not a valid JPEG/PNG image: " + file.getOriginalFilename());
    }

    private static boolean startsWith(byte[] header, byte[] signature) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}

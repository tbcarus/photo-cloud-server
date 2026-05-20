package ru.tbcarus.photocloudserver.service.storage;

import org.springframework.stereotype.Component;

@Component
public class FilenameSanitizer {

    private static final int MAX_COMPONENT_LENGTH = 255;

    public String safeName(String originalFilename) {
        String filename = originalFilename == null ? "" : originalFilename;
        filename = filename.replace('\\', '/');
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        filename = filename.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (filename.equals(".") || filename.equals("..") || filename.isBlank()) {
            return "file";
        }
        return filename;
    }

    public String extension(String originalFilename, String mimeType) {
        String safeName = safeName(originalFilename);
        int dot = safeName.lastIndexOf('.');
        if (dot >= 0 && dot < safeName.length() - 1) {
            return sanitizeExtension(safeName.substring(dot + 1));
        }
        return extensionFromMimeType(mimeType);
    }

    public String baseName(String originalFilename) {
        String safeName = safeName(originalFilename);
        int dot = safeName.lastIndexOf('.');
        String base = dot > 0 ? safeName.substring(0, dot) : safeName;
        if (base.isBlank() || base.equals(".") || base.equals("..")) {
            return "file";
        }
        return base;
    }

    public String buildStorageFilename(String originalFilename, String mimeType, String uuid) {
        String extension = extension(originalFilename, mimeType);
        String suffix = "." + uuid + (extension.isBlank() ? "" : "." + extension);
        int maxBaseLength = MAX_COMPONENT_LENGTH - suffix.length();
        String baseName = baseName(originalFilename);
        if (maxBaseLength < 1) {
            maxBaseLength = 1;
        }
        if (baseName.length() > maxBaseLength) {
            baseName = baseName.substring(0, maxBaseLength);
        }
        return baseName + suffix;
    }

    private String sanitizeExtension(String extension) {
        String value = extension.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (value.length() > 20) {
            value = value.substring(0, 20);
        }
        return value;
    }

    private String extensionFromMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "audio/mpeg" -> "mp3";
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            default -> "";
        };
    }
}

package ru.tbcarus.photocloudserver.service.storage;

import org.springframework.stereotype.Component;

@Component
public class FilenameSanitizer {

    private static final int MAX_EXTENSION_LENGTH = 20;
    private static final int MAX_COMPONENT_LENGTH = 255;

    public String safeName(String originalFilename) {
        String filename = originalFilename == null ? "" : originalFilename;
        filename = filename.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_");
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

    public String limitOriginalNameWithExtension(String originalFilename, int maxLength) {
        String safeName = safeName(originalFilename);
        if (safeName.length() <= maxLength) {
            return safeName;
        }

        int dot = safeName.lastIndexOf('.');
        if (dot > 0 && dot < safeName.length() - 1) {
            String extensionWithDot = safeName.substring(dot);
            int maxBaseLength = Math.max(1, maxLength - extensionWithDot.length());
            return safeName.substring(0, Math.min(dot, maxBaseLength)) + extensionWithDot;
        }

        return safeName.substring(0, Math.max(1, maxLength));
    }

    public String buildPhysicalFilename(String originalFilename, String mimeType, String uuid, int originalNameMaxLength) {
        String extension = extension(originalFilename, mimeType);
        String limitedOriginalName = limitOriginalNameWithExtension(originalFilename, originalNameMaxLength);
        String suffix = "_" + uuid + (extension.isBlank() ? "" : "." + extension);
        int maxOriginalNameLength = MAX_COMPONENT_LENGTH - suffix.length();
        if (limitedOriginalName.length() > maxOriginalNameLength) {
            // TODO: если UUID/расширение оставят слишком мало места, сохранить расширение исходного имени при повторной обрезке.
            limitedOriginalName = limitedOriginalName.substring(0, Math.max(1, maxOriginalNameLength));
        }
        return limitedOriginalName + suffix;
    }

    private String sanitizeExtension(String extension) {
        String value = extension.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (value.length() > MAX_EXTENSION_LENGTH) {
            value = value.substring(0, MAX_EXTENSION_LENGTH);
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

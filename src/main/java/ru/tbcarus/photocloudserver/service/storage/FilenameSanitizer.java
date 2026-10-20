package ru.tbcarus.photocloudserver.service.storage;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class FilenameSanitizer {

    private static final int MAX_EXTENSION_LENGTH = 20;
    // Лимит компонента пути в ext4/XFS/APFS — 255 байт (имена в UTF-8); NTFS считает 255 UTF-16 units, это не строже.
    private static final int MAX_COMPONENT_BYTES = 255;
    // Имена устройств Windows запрещены как часть имени до первой точки, даже с расширением: CON.jpg ≡ CON.
    private static final Pattern WINDOWS_RESERVED_STEM =
            Pattern.compile("(?i)CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9¹²³]|LPT[0-9¹²³]");

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
        // Имя всегда заканчивается UUID или расширением [a-z0-9], поэтому запрет Windows на точку/пробел в конце не возникает.
        String suffix = "_" + uuid + (extension.isBlank() ? "" : "." + extension);
        String originalPart = limitOriginalPart(
                physicalSafeName(originalFilename),
                originalNameMaxLength,
                MAX_COMPONENT_BYTES - utf8Length(suffix)
        );
        return escapeWindowsReservedStem(originalPart) + suffix;
    }

    // Имя маркера владельца {safeEmail}.json. Сырой email нельзя делать именем: @Email допускает "/", а в кавычках — и "..".
    public String ownerMarkerFilename(String email) {
        String suffix = ".json";
        // У email из БД часть до первой точки содержит "@" и не совпадёт с именем устройства Windows;
        // окончание ".json" исключает точку или пробел в конце имени.
        return truncate(physicalSafeName(email), Integer.MAX_VALUE, MAX_COMPONENT_BYTES - utf8Length(suffix)) + suffix;
    }

    private String physicalSafeName(String originalFilename) {
        String filename = originalFilename == null ? "" : originalFilename;
        // Regex идёт по code points: корректная суррогатная пара — один символ, \p{Cs} находит только одиночные суррогаты,
        // которые не кодируются в UTF-8 и не могут быть частью имени файла.
        filename = filename.replaceAll("\\p{Cs}", "_");
        // NFC: одинаково выглядящие имена из разных ОС (например, NFD с macOS) дают одинаковые байты на диске.
        filename = Normalizer.normalize(filename, Normalizer.Form.NFC);
        return safeName(filename);
    }

    // Укорачивает только исходную часть, по возможности сохраняя её расширение; режет по границам code points.
    private String limitOriginalPart(String name, int maxChars, int maxBytes) {
        if (name.length() <= maxChars && utf8Length(name) <= maxBytes) {
            return name;
        }

        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String extensionWithDot = name.substring(dot);
            String base = truncate(
                    name.substring(0, dot),
                    maxChars - extensionWithDot.length(),
                    maxBytes - utf8Length(extensionWithDot)
            );
            if (!base.isEmpty()) {
                return base + extensionWithDot;
            }
        }

        return truncate(name, maxChars, maxBytes);
    }

    private String truncate(String value, int maxChars, int maxBytes) {
        int chars = 0;
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int codePointChars = Character.charCount(codePoint);
            int codePointBytes = utf8Length(codePoint);
            if (chars + codePointChars > maxChars || bytes + codePointBytes > maxBytes) {
                break;
            }
            chars += codePointChars;
            bytes += codePointBytes;
            end += codePointChars;
        }
        return value.substring(0, end);
    }

    private String escapeWindowsReservedStem(String originalPart) {
        int dot = originalPart.indexOf('.');
        // Без точки за исходной частью сразу идёт "_UUID", и имя до первой точки не совпадёт с устройством.
        if (dot < 0 || !WINDOWS_RESERVED_STEM.matcher(originalPart.substring(0, dot).stripTrailing()).matches()) {
            return originalPart;
        }
        // Заменяем точку символом того же размера, чтобы не нарушить уже посчитанные лимиты.
        return originalPart.substring(0, dot) + "_" + originalPart.substring(dot + 1);
    }

    private static int utf8Length(String value) {
        return value.codePoints().map(FilenameSanitizer::utf8Length).sum();
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private String sanitizeExtension(String extension) {
        // Locale.ROOT: в турецкой локали "I".toLowerCase() даёт "ı", и буква пропала бы из расширения.
        String value = extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (value.length() > MAX_EXTENSION_LENGTH) {
            value = value.substring(0, MAX_EXTENSION_LENGTH);
        }
        return value;
    }

    private String extensionFromMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
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

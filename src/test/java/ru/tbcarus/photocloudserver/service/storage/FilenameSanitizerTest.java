package ru.tbcarus.photocloudserver.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FilenameSanitizerTest {

    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final int ORIGINAL_NAME_MAX_LENGTH = 80;
    private static final int MAX_COMPONENT_BYTES = 255;

    private final FilenameSanitizer sanitizer = new FilenameSanitizer();

    @Test
    void regularNameKeepsOriginalNameUuidAndRepeatedExtension() {
        assertThat(build("IMG_20260609_202840.jpg", "image/jpeg"))
                .isEqualTo("IMG_20260609_202840.jpg_" + UUID + ".jpg");
    }

    @Test
    void finalExtensionIsLowercasedAndOriginalPartIsKeptAsIs() {
        assertThat(build("PHOTO.JPG", "image/jpeg")).isEqualTo("PHOTO.JPG_" + UUID + ".jpg");
        assertThat(build("archive.tar.gz", "application/gzip")).isEqualTo("archive.tar.gz_" + UUID + ".gz");
    }

    @Test
    void extensionFallsBackToMimeTypeOrIsOmitted() {
        assertThat(build("scan", "image/png")).isEqualTo("scan_" + UUID + ".png");
        assertThat(build("notes", "application/octet-stream")).isEqualTo("notes_" + UUID);
    }

    @Test
    void finalExtensionDoesNotDependOnDefaultLocale() {
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(build("PHOTO.TIFF", "image/tiff")).endsWith("_" + UUID + ".tiff");
            assertThat(build("scan", "IMAGE/JPEG")).isEqualTo("scan_" + UUID + ".jpg");
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void cyrillicNameIsKeptWhenItFits() {
        assertThat(build("1. Черный кот с рыбкой.jpg", "image/jpeg"))
                .isEqualTo("1. Черный кот с рыбкой.jpg_" + UUID + ".jpg");
    }

    @Test
    void longAsciiNameIsLimitedByConfiguredLengthWithOriginalExtension() {
        String physical = build("a".repeat(300) + ".jpg", "image/jpeg");

        assertThat(physical).isEqualTo("a".repeat(76) + ".jpg_" + UUID + ".jpg");
    }

    @Test
    void longCyrillicNameIsLimitedByConfiguredLengthAndFitsByteLimit() {
        String physical = build("я".repeat(200) + ".jpg", "image/jpeg");

        assertThat(physical).isEqualTo("я".repeat(76) + ".jpg_" + UUID + ".jpg");
        assertThat(utf8Length(physical)).isLessThanOrEqualTo(MAX_COMPONENT_BYTES);
    }

    @Test
    void threeByteCharactersAreLimitedByBytesNotOnlyByCharacters() {
        // 76 иероглифов по 3 байта уложились бы в лимит символов, но вместе с суффиксом дали бы 273 байта.
        String physical = build("漢".repeat(200) + ".jpg", "image/jpeg");

        assertThat(physical).isEqualTo("漢".repeat(70) + ".jpg_" + UUID + ".jpg");
        assertThat(utf8Length(physical)).isEqualTo(MAX_COMPONENT_BYTES);
    }

    @Test
    void truncationDoesNotSplitSurrogatePairs() {
        // Лимит базы 76 UTF-16 units: после "a" и 37 эмодзи следующая пара уже не помещается целиком.
        String physical = build("a" + "😀".repeat(100) + ".png", "image/png");

        assertThat(physical).isEqualTo("a" + "😀".repeat(37) + ".png_" + UUID + ".png");
        assertThat(hasUnpairedSurrogate(physical)).isFalse();
    }

    @Test
    void byteLimitWinsOverLargeConfiguredLengthForFourByteCharacters() {
        String physical = sanitizer.buildPhysicalFilename("😀".repeat(100) + ".jpg", "image/jpeg", UUID, 1000);

        assertThat(physical).isEqualTo("😀".repeat(52) + ".jpg_" + UUID + ".jpg");
        assertThat(utf8Length(physical)).isLessThanOrEqualTo(MAX_COMPONENT_BYTES);
        assertThat(hasUnpairedSurrogate(physical)).isFalse();
    }

    @Test
    void unpairedSurrogateIsReplaced() {
        assertThat(build("photo\uD83D.jpg", "image/jpeg")).isEqualTo("photo_.jpg_" + UUID + ".jpg");
        assertThat(build("\uDE00photo.jpg", "image/jpeg")).isEqualTo("_photo.jpg_" + UUID + ".jpg");
    }

    @Test
    void decomposedUnicodeIsNormalizedToNfc() {
        // "й" в NFD (и + combining breve), как его может прислать macOS.
        String physical = build("й.jpg", "image/jpeg");

        assertThat(physical).isEqualTo("й.jpg_" + UUID + ".jpg");
    }

    @Test
    void charactersInvalidOnWindowsAreReplaced() {
        String physical = build("a<b>c:d\"e/f\\g|h?i*j\u0001k\u001F.jpg", "image/jpeg");

        assertThat(physical).isEqualTo("a_b_c_d_e_f_g_h_i_j_k_.jpg_" + UUID + ".jpg");
    }

    @Test
    void pathTraversalCannotProduceSeparators() {
        String physical = build("..\\evil/../../photo.jpg", "image/jpeg");

        assertThat(physical).isEqualTo(".._evil_.._.._photo.jpg_" + UUID + ".jpg");
    }

    @Test
    void windowsReservedDeviceNameBeforeFirstDotIsEscaped() {
        assertThat(build("CON.jpg", "image/jpeg")).isEqualTo("CON_jpg_" + UUID + ".jpg");
        assertThat(build("nul.tar.gz", "application/gzip")).isEqualTo("nul_tar.gz_" + UUID + ".gz");
        assertThat(build("com1 .png", "image/png")).isEqualTo("com1 _png_" + UUID + ".png");
        assertThat(build("LPT¹.txt", "text/plain")).isEqualTo("LPT¹_txt_" + UUID + ".txt");
    }

    @Test
    void namesThatOnlyLookLikeReservedAreNotChanged() {
        assertThat(build("CONSOLE.jpg", "image/jpeg")).isEqualTo("CONSOLE.jpg_" + UUID + ".jpg");
        assertThat(build("CON", "image/jpeg")).isEqualTo("CON_" + UUID + ".jpg");
        assertThat(build("my CON.jpg", "image/jpeg")).isEqualTo("my CON.jpg_" + UUID + ".jpg");
    }

    @Test
    void blankAndDotNamesFallBackToFile() {
        assertThat(build(null, "image/jpeg")).isEqualTo("file_" + UUID + ".jpg");
        assertThat(build("", "image/jpeg")).isEqualTo("file_" + UUID + ".jpg");
        assertThat(build("   ", "image/jpeg")).isEqualTo("file_" + UUID + ".jpg");
        assertThat(build(".", "image/jpeg")).isEqualTo("file_" + UUID + ".jpg");
        assertThat(build("..", null)).isEqualTo("file_" + UUID);
    }

    @Test
    void extensionLongerThanLimitDoesNotBreakNameLimits() {
        String physical = build("photo." + "x".repeat(300), "image/jpeg");

        assertThat(physical).isEqualTo("photo." + "x".repeat(74) + "_" + UUID + "." + "x".repeat(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Screenshot_20260317-112328 (1).png",
            "фото отпуска 2026 — море, закат и горы, снятые с балкона гостиницы в последний вечер.jpeg",
            "日本語のとても長いファイル名日本語のとても長いファイル名日本語のとても長いファイル名日本語のとても長いファイル名日本語のとても長いファイル名.heic",
            "👨‍👩‍👧‍👦🎉🏖️👨‍👩‍👧‍👦🎉🏖️👨‍👩‍👧‍👦🎉🏖️👨‍👩‍👧‍👦🎉🏖️👨‍👩‍👧‍👦🎉🏖️👨‍👩‍👧‍👦🎉🏖️.jpg",
            "trailing dot.",
            "trailing space ",
            ".hidden",
            "no-extension-at-all"
    })
    void physicalNameAlwaysEndsWithUuidAndExtensionWithinByteLimit(String originalName) {
        String extension = sanitizer.extension(originalName, "image/jpeg");
        String physical = build(originalName, "image/jpeg");

        assertThat(physical).endsWith("_" + UUID + (extension.isEmpty() ? "" : "." + extension));
        assertThat(utf8Length(physical)).isLessThanOrEqualTo(MAX_COMPONENT_BYTES);
        assertThat(hasUnpairedSurrogate(physical)).isFalse();
        assertThat(physical).doesNotContainPattern("[<>:\"/\\\\|?*\\p{Cntrl}]");
    }

    @Test
    void ownerMarkerFilenameKeepsRegularEmail() {
        assertThat(sanitizer.ownerMarkerFilename("user@example.com")).isEqualTo("user@example.com.json");
    }

    @Test
    void ownerMarkerFilenameReplacesCharactersInvalidOnFileSystems() {
        // Такие символы @Email допускает в локальной части без кавычек или в кавычках.
        assertThat(sanitizer.ownerMarkerFilename("a/b\\c|d?e*f<g>h:i\"j@x.ru")).isEqualTo("a_b_c_d_e_f_g_h_i_j@x.ru.json");
    }

    @Test
    void ownerMarkerFilenameCannotEscapeUserDirectory() {
        String name = sanitizer.ownerMarkerFilename("\"/../../5/x\"@d.ru");

        assertThat(name).isEqualTo("__.._.._5_x_@d.ru.json");
        assertThat(Path.of(name).getNameCount()).isEqualTo(1);
        assertThat(Path.of("7").resolve(name).normalize().getParent()).isEqualTo(Path.of("7"));
    }

    @Test
    void ownerMarkerFilenameNormalizesUnicode() {
        // "й" в NFD и одиночный суррогат.
        assertThat(sanitizer.ownerMarkerFilename("й@пример.рф"))
                .isEqualTo("й@пример.рф.json");
        assertThat(sanitizer.ownerMarkerFilename("a\uD83D@x.ru")).isEqualTo("a_@x.ru.json");
    }

    @Test
    void longOwnerMarkerFilenameFitsByteLimitAndKeepsJsonSuffix() {
        // Адрес на пределе БД (128 символов) из кириллицы занимает 254 байта, вместе с ".json" — больше лимита.
        String email = "я".repeat(64) + "@" + "д".repeat(60) + ".рф";

        String name = sanitizer.ownerMarkerFilename(email);

        assertThat(name).startsWith("я".repeat(64) + "@").endsWith(".json");
        assertThat(utf8Length(name)).isLessThanOrEqualTo(MAX_COMPONENT_BYTES);
        assertThat(hasUnpairedSurrogate(name)).isFalse();
    }

    private String build(String originalName, String mimeType) {
        return sanitizer.buildPhysicalFilename(originalName, mimeType, UUID, ORIGINAL_NAME_MAX_LENGTH);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.SURROGATE);
    }
}

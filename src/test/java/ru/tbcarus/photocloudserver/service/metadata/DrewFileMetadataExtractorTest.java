package ru.tbcarus.photocloudserver.service.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.tbcarus.photocloudserver.service.metadata.ExifJpegFixture.DATE_TIME_DIGITIZED;
import static ru.tbcarus.photocloudserver.service.metadata.ExifJpegFixture.DATE_TIME_ORIGINAL;
import static ru.tbcarus.photocloudserver.service.metadata.ExifJpegFixture.OFFSET_TIME_ORIGINAL;
import static ru.tbcarus.photocloudserver.service.metadata.ExifJpegFixture.SUBSEC_TIME_ORIGINAL;

class DrewFileMetadataExtractorTest {

    @TempDir
    Path tempDir;

    private final DrewFileMetadataExtractor extractor = new DrewFileMetadataExtractor();
    private TimeZone defaultTimeZone;

    @BeforeEach
    void rememberDefaultTimeZone() {
        defaultTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(defaultTimeZone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Moscow", "America/New_York", "Pacific/Kiritimati"})
    void dateWithoutOffsetIsKeptOnNewYearBoundaryInAnyServerZone(String serverZone) throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone(serverZone));

        // Раньше строка читалась как GMT и переводилась в пояс сервера: в Москве 31.12 23:30 становилось 2024 годом,
        // в Нью-Йорке 01.01 00:30 — 2023 годом.
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "2023:12:31 23:30:15")))
                .isEqualTo(LocalDateTime.of(2023, 12, 31, 23, 30, 15));
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "2024:01:01 00:30:00")))
                .isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 30, 0));
    }

    @Test
    void offsetTagDoesNotShiftLocalDateAndTime() throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertThat(capturedAt(Map.of(
                DATE_TIME_ORIGINAL, "2024:01:01 01:00:00",
                OFFSET_TIME_ORIGINAL, "+03:00")))
                .isEqualTo(LocalDateTime.of(2024, 1, 1, 1, 0, 0));

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        assertThat(capturedAt(Map.of(
                DATE_TIME_ORIGINAL, "2023:12:31 22:00:00",
                OFFSET_TIME_ORIGINAL, "-05:00")))
                .isEqualTo(LocalDateTime.of(2023, 12, 31, 22, 0, 0));
    }

    @Test
    void subsecondsArePreserved() throws IOException {
        assertThat(capturedAt(Map.of(
                DATE_TIME_ORIGINAL, "2023:12:31 23:59:59",
                SUBSEC_TIME_ORIGINAL, "123")))
                .isEqualTo(LocalDateTime.of(2023, 12, 31, 23, 59, 59, 123_000_000));
    }

    @Test
    void digitizedIsUsedWhenOriginalIsMissing() throws IOException {
        assertThat(capturedAt(Map.of(DATE_TIME_DIGITIZED, "2022:06:15 10:20:30")))
                .isEqualTo(LocalDateTime.of(2022, 6, 15, 10, 20, 30));
    }

    @Test
    void digitizedIsUsedWhenOriginalIsPlaceholder() throws IOException {
        assertThat(capturedAt(Map.of(
                DATE_TIME_ORIGINAL, "0000:00:00 00:00:00",
                DATE_TIME_DIGITIZED, "2022:06:15 10:20:30")))
                .isEqualTo(LocalDateTime.of(2022, 6, 15, 10, 20, 30));
        assertThat(capturedAt(Map.of(
                DATE_TIME_ORIGINAL, "    :  :     :  :  ",
                DATE_TIME_DIGITIZED, "2022:06:15 10:20:30")))
                .isEqualTo(LocalDateTime.of(2022, 6, 15, 10, 20, 30));
    }

    @Test
    void missingOrUnusableDateGivesNoCapturedAt() throws IOException {
        assertThat(capturedAt(Map.of())).isNull();
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "0000:00:00 00:00:00"))).isNull();
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "2023:02:30 10:00:00"))).isNull();
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "not a date"))).isNull();
    }

    @Test
    void nonStandardFormatsAreReadAsLocalTime() throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));

        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "2023-12-31T23:30:15+05:00")))
                .isEqualTo(LocalDateTime.of(2023, 12, 31, 23, 30, 15));
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "2023:12:31 23:30")))
                .isEqualTo(LocalDateTime.of(2023, 12, 31, 23, 30, 0));
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "2023:12:31 23:59:59.5")))
                .isEqualTo(LocalDateTime.of(2023, 12, 31, 23, 59, 59, 500_000_000));
    }

    @Test
    void validDatesAreNotFilteredByYear() throws IOException {
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "1970:01:01 00:00:00")))
                .isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0, 0));
        assertThat(capturedAt(Map.of(DATE_TIME_ORIGINAL, "2099:12:31 23:59:59")))
                .isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
    }

    private LocalDateTime capturedAt(Map<Integer, String> exifTags) throws IOException {
        Path file = Files.createTempFile(tempDir, "exif-", ".jpg");
        Files.write(file, ExifJpegFixture.jpegWithExifTags(exifTags));
        return extractor.extract(file, "image/jpeg").getCapturedAt();
    }
}

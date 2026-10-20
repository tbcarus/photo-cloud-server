package ru.tbcarus.photocloudserver.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.tbcarus.photocloudserver.model.FolderType;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class StorageKeyGeneratorTest {

    private final StorageKeyGenerator generator = new StorageKeyGenerator(new FilenameSanitizer(), new StorageProperties());

    @Test
    void cameraPathUsesCapturedAtYear() {
        assertThat(generator.generateFilePath(7L, FolderType.CAMERA, LocalDateTime.of(2023, 12, 31, 23, 30, 15)))
                .isEqualTo("7/camera/2023");
        assertThat(generator.generateFilePath(7L, FolderType.CAMERA, LocalDateTime.of(2024, 1, 1, 0, 0)))
                .isEqualTo("7/camera/2024");
    }

    @Test
    void cameraYearIsAlwaysFourDigits() {
        assertThat(generator.generateFilePath(7L, FolderType.CAMERA, LocalDateTime.of(999, 6, 1, 12, 0)))
                .isEqualTo("7/camera/0999");
    }

    @ParameterizedTest
    @EnumSource(value = FolderType.class, names = "CAMERA", mode = EnumSource.Mode.EXCLUDE)
    void otherFolderTypesUseFilesDirectoryWithoutYearAndSharding(FolderType folderType) {
        assertThat(generator.generateFilePath(7L, folderType, LocalDateTime.of(2023, 12, 31, 23, 30)))
                .isEqualTo("7/files");
    }

    @Test
    void userRootPathIsUserIdDirectory() {
        assertThat(generator.generateUserRootPath(7L)).isEqualTo("7");
    }

    @Test
    void pathDigitsDoNotDependOnDefaultLocale() {
        Locale defaultLocale = Locale.getDefault();
        try {
            // Локаль с тайскими цифрами: String.format без Locale.ROOT дал бы "๗/camera/๒๐๒๓".
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
            assertThat(generator.generateFilePath(7L, FolderType.CAMERA, LocalDateTime.of(2023, 1, 1, 0, 0)))
                    .isEqualTo("7/camera/2023");
            assertThat(generator.generateFilePath(7L, FolderType.FILES, LocalDateTime.of(2023, 1, 1, 0, 0)))
                    .isEqualTo("7/files");
            assertThat(generator.generateUserRootPath(7L)).isEqualTo("7");
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }
}

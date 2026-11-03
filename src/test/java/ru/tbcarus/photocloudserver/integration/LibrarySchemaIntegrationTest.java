package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ru.tbcarus.photocloudserver.model.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Контейнер каждого класса пуст: схему создаёт штатный Liquibase с нуля, а ddl-auto=validate сверяет её с моделью.
class LibrarySchemaIntegrationTest extends AbstractIntegrationTest {

    @Test
    void schemaHasOnlyTargetIndexes() {
        assertThat(indexes("folder"))
                .contains("uk_folder_user_root", "uk_folder_user_parent_lower_name", "idx_folder_user_type",
                        "uk_folder_user_backup_source", "uk_folder_user_auto_uploads")
                .doesNotContain("uk_folder_user_parent_name", "idx_folder_user_parent");
        assertThat(indexes("file_item"))
                .contains("idx_file_item_user_folder_checksum", "idx_file_item_user_folder_lower_original_name")
                .doesNotContain("uk_file_item_user_folder_checksum");
    }

    @Test
    void checksumAndFileNameHaveNoUniqueIndexes() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE tablename = 'file_item' AND indexdef LIKE 'CREATE UNIQUE%'
                """, String.class)).allMatch(indexDefinition -> indexDefinition.contains("file_item_pkey"));
    }

    @Test
    void folderNamesStayUniqueInDatabaseWithoutServiceChecks() {
        User user = createUser("schema@test.local", "pass1");
        jdbcTemplate.update("INSERT INTO folder (user_id, name, folder_type) VALUES (?, 'root', 'ROOT')", user.getId());
        Long rootId = jdbcTemplate.queryForObject("SELECT id FROM folder WHERE parent_id IS NULL", Long.class);
        String insert = "INSERT INTO folder (user_id, parent_id, name, folder_type) VALUES (?, ?, ?, 'USER')";
        jdbcTemplate.update(insert, user.getId(), rootId, "Отчёты");

        assertThatThrownBy(() -> jdbcTemplate.update(insert, user.getId(), rootId, "ОТЧЁТЫ"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void autoUploadsInvariantsHoldInDatabaseWithoutServiceChecks() {
        User user = createUser("schema-auto@test.local", "pass1");
        User other = createUser("schema-auto-2@test.local", "pass1");
        jdbcTemplate.update("INSERT INTO folder (user_id, name, folder_type) VALUES (?, 'root', 'ROOT')", user.getId());
        jdbcTemplate.update("INSERT INTO folder (user_id, name, folder_type) VALUES (?, 'root', 'ROOT')", other.getId());
        Long rootId = jdbcTemplate.queryForObject("SELECT id FROM folder WHERE user_id = ? AND parent_id IS NULL", Long.class, user.getId());
        Long otherRootId = jdbcTemplate.queryForObject("SELECT id FROM folder WHERE user_id = ? AND parent_id IS NULL", Long.class, other.getId());
        String insert = "INSERT INTO folder (user_id, parent_id, name, folder_type, backup_source) VALUES (?, ?, ?, ?, ?::uuid)";
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        jdbcTemplate.update(insert, user.getId(), rootId, "AutoUploads", "AUTO_UPLOADS", null);
        Long containerId = jdbcTemplate.queryForObject("SELECT id FROM folder WHERE folder_type = 'AUTO_UPLOADS'", Long.class);
        jdbcTemplate.update(insert, user.getId(), containerId, "Documents", "USER", uuid);
        jdbcTemplate.update(insert, other.getId(), otherRootId, "Documents", "USER", uuid);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, user.getId(), rootId, "Other", "AUTO_UPLOADS", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user.getId(), containerId, "Projects", "USER", uuid))
                .isInstanceOf(DataIntegrityViolationException.class);
        // У второго пользователя AutoUploads ещё нет: срабатывает только запрет backup_source у не-USER папки.
        assertThatThrownBy(() -> jdbcTemplate.update(insert, other.getId(), otherRootId, "Typed", "AUTO_UPLOADS",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> indexes(String table) {
        return jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }
}

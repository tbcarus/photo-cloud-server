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
                .contains("uk_folder_user_root", "uk_folder_user_parent_lower_name", "idx_folder_user_type")
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

    private List<String> indexes(String table) {
        return jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }
}

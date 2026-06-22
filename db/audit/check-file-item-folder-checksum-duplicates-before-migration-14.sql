-- Аудит дублей перед применением migration 14.
-- Migration 14 добавляет unique constraint:
-- (user_id, folder_id, checksum) на file_item.
--
-- Запускать ДО применения migration 14 на непустой БД.
-- Если запрос возвращает строки, migration 14 упадёт на ADD CONSTRAINT.
-- Автоматически удалять дубли нельзя: нужно отдельное продуктовое решение.

SELECT fi.user_id,
       fi.folder_id,
       so.checksum,
       COUNT(*) AS cnt,
       array_agg(fi.id ORDER BY fi.id) AS file_item_ids
FROM file_item fi
JOIN stored_object so ON so.id = fi.stored_object_id
GROUP BY fi.user_id, fi.folder_id, so.checksum
HAVING COUNT(*) > 1
ORDER BY cnt DESC, fi.user_id, fi.folder_id;

-- Быстрая проверка количества групп-дублей.
SELECT COUNT(*) AS duplicate_groups
FROM (
    SELECT 1
    FROM file_item fi
    JOIN stored_object so ON so.id = fi.stored_object_id
    GROUP BY fi.user_id, fi.folder_id, so.checksum
    HAVING COUNT(*) > 1
) d;

-- F3 fix: server_seq debe avanzar también en UPDATE (el pull incremental solo
-- ve cambios con server_seq > cursor). AUTO_INCREMENT solo actúa en INSERT, así
-- que la secuencia pasa a asignarse explícitamente desde un contador global.

ALTER TABLE entities MODIFY server_seq BIGINT NOT NULL;

CREATE TABLE IF NOT EXISTS app_counters (
    name VARCHAR(40) NOT NULL PRIMARY KEY,
    value BIGINT NOT NULL DEFAULT 0
);

-- Arranca desde el máximo existente para no repetir cursores.
INSERT INTO app_counters (name, value)
SELECT 'entities', COALESCE(MAX(server_seq), 0) FROM entities
ON DUPLICATE KEY UPDATE value = VALUES(value);

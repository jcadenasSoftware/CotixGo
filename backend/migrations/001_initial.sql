-- CotixGo F3 — esquema inicial de sincronización (desarrollo local).
-- Identidad de negocio: UUID CHAR(36). server_seq es SOLO cursor/orden del servidor.

CREATE TABLE IF NOT EXISTS schema_migrations (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS users (
    id CHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_tokens (
    token CHAR(64) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_auth_tokens_user (user_id)
);

-- Mecanismo LOCAL de prueba para recuperación de acceso: registra el correo que
-- se enviaría. No envía nada; el proveedor real queda pendiente (ver docs/05 §4).
CREATE TABLE IF NOT EXISTS mail_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME NOT NULL
);

-- Entidades sincronizables: columnas meta + snapshot JSON del cliente.
CREATE TABLE IF NOT EXISTS entities (
    entity_type VARCHAR(40) NOT NULL,
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    server_version INT NOT NULL DEFAULT 1,
    server_seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    server_order INT NULL,
    official_number VARCHAR(40) NULL,
    has_version_conflict TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(40) NULL,
    payload JSON NOT NULL,
    updated_at_server DATETIME NOT NULL,
    PRIMARY KEY (entity_type, id),
    INDEX idx_entities_user_seq (user_id, server_seq),
    INDEX idx_entities_user_type (user_id, entity_type)
);

-- Idempotencia: operation_id + hash del payload. Mismo id+payload → resultado
-- guardado; mismo id con payload distinto → IDEMPOTENCY_KEY_REUSED.
CREATE TABLE IF NOT EXISTS processed_operations (
    operation_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    result_json JSON NOT NULL,
    processed_at DATETIME NOT NULL,
    PRIMARY KEY (operation_id, user_id)
);

-- Secuencias oficiales: global por Usuario/Titular + tipo + período AAAAMM.
CREATE TABLE IF NOT EXISTS doc_number_sequences (
    user_id CHAR(36) NOT NULL,
    doc_type VARCHAR(10) NOT NULL,
    period VARCHAR(6) NOT NULL,
    last_seq INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, doc_type, period)
);

-- Números oficiales asignados por entity_id (idempotente).
CREATE TABLE IF NOT EXISTS doc_numbers (
    entity_id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    official_number VARCHAR(40) NOT NULL,
    assigned_at DATETIME NOT NULL
);

-- Trazabilidad mínima: quién, cuándo, entidad, operación, dispositivo.
CREATE TABLE IF NOT EXISTS audit_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    device_id CHAR(36) NULL,
    operation_id CHAR(36) NULL,
    entity_type VARCHAR(40) NULL,
    entity_id CHAR(36) NULL,
    action VARCHAR(60) NOT NULL,
    detail JSON NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_audit_user (user_id),
    INDEX idx_audit_entity (entity_type, entity_id)
);

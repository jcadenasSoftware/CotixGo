<?php

declare(strict_types=1);

/**
 * Sincronización F3: push de operaciones offline (resultado por operación,
 * transacción propia) y pull incremental por cursor server_seq.
 *
 * Idempotencia: operation_id + hash del payload (processed_operations).
 * Concurrencia: entity_id + base_server_version (independiente de idempotencia).
 * Propiedad: entity_id vinculado a user_id en la primera escritura aceptada;
 * una entidad de otra cuenta → FORBIDDEN_ENTITY.
 * Cotizaciones: matriz de transiciones del servidor, numeración oficial al
 * aceptar la primera EMITIDA/APROBADA, versiones concurrentes conservadas con
 * server_order autoritativo fuera del snapshot y resolución como versión nueva.
 */

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/domain.php';

const ENTITY_TYPES = [
    'user', 'profile', 'client', 'service', 'retention_config',
    'quote', 'quote_version',
    'job', 'job_quote', 'job_activity', 'job_material',
];

/** Dependencias: la entidad padre debe existir en el servidor antes de aplicar. */
const DEPENDENCIES = [
    'profile'          => [],
    'client'           => [],
    'service'          => [['profile', 'profileId']],
    'retention_config' => [],
    'quote'            => [['client', 'clientId'], ['profile', 'profileId']],
    'quote_version'    => [['quote', 'quoteId']],
    'job'              => [['client', 'clientId'], ['profile', 'profileId']],
    'job_quote'        => [['job', 'jobId'], ['quote', 'quoteId']],
    'job_activity'     => [['job', 'jobId']],
    'job_material'     => [['job', 'jobId']],
];

/** Estado remoto para resultados CONFLICT/REJECTED: el payload persistido
 *  más los metadatos del servidor (official_number, has_version_conflict),
 *  que no viajan dentro del payload, de modo que la resolución
 *  "conservar remoto" del cliente preserve el documento completo. */
function serverState(array $row): array
{
    $state = json_decode($row['payload'], true) ?: [];
    $state['official_number'] = $row['official_number'];
    $state['has_version_conflict'] = (int) ($row['has_version_conflict'] ?? 0) === 1;
    return $state;
}

function entityRow(PDO $pdo, string $type, string $id): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM entities WHERE entity_type = ? AND id = ?');
    $stmt->execute([$type, $id]);
    $row = $stmt->fetch();
    return $row === false ? null : $row;
}

function result(string $opId, string $status, ?string $code = null, ?string $message = null,
                ?int $serverVersion = null, $serverState = null, ?string $official = null): array
{
    $r = ['operation_id' => $opId, 'status' => $status];
    if ($code !== null) $r['code'] = $code;
    if ($message !== null) $r['message'] = $message;
    if ($serverVersion !== null) $r['server_version'] = $serverVersion;
    if ($serverState !== null) $r['server_state'] = $serverState;
    if ($official !== null) $r['official_number'] = $official;
    return $r;
}

function handlePush(): void
{
    $userId = authUserId();
    $in = jsonBody();
    $deviceId = (string) ($in['device_id'] ?? '');
    $ops = $in['operations'] ?? [];
    if (!is_array($ops)) {
        fail(400, 'VALIDATION_ERROR', 'operations debe ser un arreglo.');
    }
    $pdo = db();
    $results = [];
    foreach ($ops as $op) {
        $results[] = processOperation($pdo, $userId, $deviceId, is_array($op) ? $op : []);
    }
    respond(200, ['results' => $results]);
}

function processOperation(PDO $pdo, string $userId, string $deviceId, array $op): array
{
    $opId = (string) ($op['operation_id'] ?? '');
    $type = (string) ($op['entity_type'] ?? '');
    $entityId = (string) ($op['entity_id'] ?? '');
    $opType = (string) ($op['type'] ?? 'upsert');
    $payload = $op['payload'] ?? null;
    $base = (int) ($op['base_server_version'] ?? 0);
    $supersedes = $op['supersedes'] ?? null;

    if ($opId === '' || !in_array($type, ENTITY_TYPES, true) || $entityId === '' || !is_array($payload)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Operación incompleta o tipo de entidad desconocido.') + ['entity_id' => $entityId];
    }

    $payloadJson = json_encode($payload, JSON_UNESCAPED_UNICODE);
    $payloadHash = hash('sha256', $payloadJson);

    // Idempotencia: misma operación repetida devuelve su resultado previo.
    $stmt = $pdo->prepare('SELECT payload_hash, result_json FROM processed_operations WHERE operation_id = ? AND user_id = ?');
    $stmt->execute([$opId, $userId]);
    if (($prev = $stmt->fetch()) !== false) {
        if ($prev['payload_hash'] === $payloadHash) {
            $stored = json_decode($prev['result_json'], true);
            $stored['entity_id'] = $entityId;
            return $stored;
        }
        return result($opId, 'REJECTED', 'IDEMPOTENCY_KEY_REUSED',
            'El operation_id ya fue usado con un payload distinto.') + ['entity_id' => $entityId];
    }

    $res = applyOperation($pdo, $userId, $deviceId, $opId, $type, $entityId, $opType, $payload, $base, $supersedes, $payloadHash);
    $res['entity_id'] = $entityId;
    return $res;
}

function applyOperation(PDO $pdo, string $userId, string $deviceId, string $opId,
                        string $type, string $entityId, string $opType,
                        array $payload, int $base, $supersedes, string $payloadHash): array
{
    $pdo->beginTransaction();
    try {
        $res = applyOperationTx($pdo, $userId, $deviceId, $opId, $type, $entityId, $opType, $payload, $base, $supersedes);

        // Idempotencia para resultados definitivos (APPLIED/REJECTED): mismo
        // operation_id + payload → resultado memorizado; payload distinto →
        // IDEMPOTENCY_KEY_REUSED. Un CONFLICT no produjo cambio y la misma
        // operación puede reintentarse (base corregida tras resolución del
        // usuario): debe reevaluarse contra el estado vigente, no memorizarse.
        // La memorización se confirma en la misma transacción que la
        // mutación: un replay tras caída del proceso devuelve el resultado
        // almacenado en lugar de reevaluar sobre el estado ya modificado.
        if (in_array($res['status'] ?? '', ['APPLIED', 'REJECTED'], true)) {
            $pdo->prepare('INSERT INTO processed_operations (operation_id, user_id, payload_hash, result_json, processed_at) VALUES (?, ?, ?, ?, ?)')
                ->execute([$opId, $userId, $payloadHash, json_encode($res, JSON_UNESCAPED_UNICODE), nowUtc()]);
        }
        $pdo->commit();
        return $res;
    } catch (Throwable $e) {
        $pdo->rollBack();
        return result($opId, 'REJECTED', 'INTERNAL_ERROR', 'Error al aplicar la operación.');
    }
}

function applyOperationTx(PDO $pdo, string $userId, string $deviceId, string $opId,
                          string $type, string $entityId, string $opType,
                          array $payload, int $base, $supersedes): array
{
    // Propiedad: entity_id vinculado a user_id en la primera escritura aceptada.
    $current = entityRow($pdo, $type, $entityId);
    if ($current !== null && $current['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'La entidad pertenece a otra cuenta.');
    }

    // Dependencias: las referencias del payload deben existir en el servidor
    // y pertenecer a la misma cuenta (una referencia a entidad ajena se
    // rechaza igual que usar la entidad misma desde otra cuenta).
    foreach (DEPENDENCIES[$type] ?? [] as [$depType, $field]) {
        $depId = (string) ($payload[$field] ?? '');
        // Todas las dependencias declaradas son requeridas: un id vacío no
        // omite la validación (endurecido por la revisión del PR F4).
        if ($depId === '') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                "La referencia $field es obligatoria para $type.");
        }
        $dep = entityRow($pdo, $depType, $depId);
        if ($dep === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', "Falta la entidad $depType $depId referenciada.");
        }
        if ($dep['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', "La entidad $depType referenciada pertenece a otra cuenta.");
        }
    }

    if ($type === 'quote_version') {
        return applyQuoteVersion($pdo, $userId, $deviceId, $opId, $entityId, $payload, $opType === 'resolve');
    }

    if ($type === 'quote') {
        return applyQuote($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current, $base);
    }

    if ($type === 'job') {
        return applyJob($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current, $base);
    }

    // Vínculo Trabajo↔Cotización (F4): validado sobre las entidades ya
    // persistidas — nunca con datos declarados por el cliente —: la
    // Cotización debe estar APROBADA y pertenecer al mismo Cliente del
    // Trabajo. Existencia y propiedad de cuenta quedaron cubiertas por
    // DEPENDENCIES (Trabajo, Cotización y por tanto su Cliente son de la
    // misma cuenta autenticada).
    if ($type === 'job_quote') {
        $jobRow = entityRow($pdo, 'job', (string) $payload['jobId']);
        $quoteRow = entityRow($pdo, 'quote', (string) $payload['quoteId']);
        $jobClient = (string) (json_decode($jobRow['payload'], true)['clientId'] ?? '');
        $quoteClient = (string) (json_decode($quoteRow['payload'], true)['clientId'] ?? '');
        if ((string) $quoteRow['status'] !== 'APROBADA') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Solo se pueden vincular cotizaciones en estado APROBADA.');
        }
        if ($quoteClient === '' || $quoteClient !== $jobClient) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cotización pertenece a un cliente distinto del trabajo.');
        }
    }

    // Hijos del Trabajo (F4): valores enum del contrato.
    if ($type === 'job_activity'
        && !in_array((string) ($payload['status'] ?? ''), JOB_ACTIVITY_STATUSES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Estado de actividad desconocido.');
    }
    if ($type === 'job_material'
        && !in_array((string) ($payload['origin'] ?? ''), JOB_MATERIAL_ORIGINS, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Origen de material desconocido.');
    }

    // Entidades maestras: concurrencia optimista por base_server_version.
    if ($current === null) {
        if ($base !== 0) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'La entidad no existe en el servidor pero la base no es cero.', 0, null);
        }
        insertEntity($pdo, $type, $entityId, $userId, $payload);
        audit($pdo, $userId, $deviceId, $opId, $type, $entityId, 'CREATE', ['supersedes' => $supersedes]);
        return result($opId, 'APPLIED', null, null, 1);
    }
    if ($base !== (int) $current['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'La entidad cambió desde la versión conocida por el dispositivo.',
            (int) $current['server_version'], serverState($current));
    }
    updateEntity($pdo, $type, $entityId, $current, $payload);
    audit($pdo, $userId, $deviceId, $opId, $type, $entityId, 'UPDATE', ['supersedes' => $supersedes]);
    return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1);
}

function applyQuote(PDO $pdo, string $userId, string $deviceId, string $opId,
                    string $entityId, array $payload, ?array $current, int $base): array
{
    $newStatus = (string) ($payload['status'] ?? '');
    if (!in_array($newStatus, QUOTE_STATUSES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Estado de cotización desconocido.');
    }

    if ($current === null) {
        if ($base !== 0) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'La entidad no existe en el servidor pero la base no es cero.', 0, null);
        }
        $official = null;
        if (in_array($newStatus, QUOTE_NUMBERED_STATUSES, true)) {
            $official = ensureOfficialNumber($pdo, $userId, $entityId);
        }
        insertEntity($pdo, 'quote', $entityId, $userId, $payload, $newStatus, $official);
        audit($pdo, $userId, $deviceId, $opId, 'quote', $entityId, 'CREATE', ['status' => $newStatus]);
        return result($opId, 'APPLIED', null, null, 1, null, $official);
    }

    if ($base !== (int) $current['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'La cotización cambió desde la versión conocida por el dispositivo.',
            (int) $current['server_version'], serverState($current));
    }

    $oldStatus = (string) $current['status'];
    if (!validQuoteTransition($oldStatus, $newStatus)) {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            "Transición no permitida $oldStatus → $newStatus; estado actual $oldStatus.",
            (int) $current['server_version'], serverState($current));
    }
    // Documento en conflicto de versiones: bloquear cualquier transición
    // hacia un estado numerado (emisiones y aprobaciones nuevas) mientras
    // exista conflicto; el número oficial y los snapshots previos quedan
    // intactos hasta la resolución del usuario.
    if ((int) $current['has_version_conflict'] === 1
        && in_array($newStatus, QUOTE_NUMBERED_STATUSES, true)
        && $newStatus !== $oldStatus) {
        return result($opId, 'REJECTED', 'CONFLICT_BLOCKED',
            'La cotización tiene versiones en conflicto; resuélvelas antes de emitir o aprobar.',
            (int) $current['server_version']);
    }

    $official = $current['official_number'];
    if ($official === null && !in_array($oldStatus, QUOTE_NUMBERED_STATUSES, true)
        && in_array($newStatus, QUOTE_NUMBERED_STATUSES, true)) {
        $official = ensureOfficialNumber($pdo, $userId, $entityId);
    }
    updateEntity($pdo, 'quote', $entityId, $current, $payload, $newStatus, $official);
    audit($pdo, $userId, $deviceId, $opId, 'quote', $entityId,
        $oldStatus === $newStatus ? 'UPDATE' : 'TRANSITION',
        ['from' => $oldStatus, 'to' => $newStatus]);
    return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1, null, $official);
}

/**
 * F4 — Trabajo: matriz aprobada (docs/03 §6), número oficial TR- en la
 * primera aceptación de PROGRAMADO/EN_PROCESO, dirección de ejecución
 * obligatoria al pasar a PROGRAMADO y estados terminales sin edición.
 */
function applyJob(PDO $pdo, string $userId, string $deviceId, string $opId,
                  string $entityId, array $payload, ?array $current, int $base): array
{
    $newStatus = (string) ($payload['status'] ?? '');
    if (!in_array($newStatus, JOB_STATUSES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Estado de trabajo desconocido.');
    }

    // La dirección de ejecución es obligatoria al pasar a PROGRAMADO
    // (propia del Trabajo; puede quedar vacía en BORRADOR).
    if ($newStatus === 'PROGRAMADO' && trim((string) ($payload['executionAddress'] ?? '')) === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'La dirección de ejecución es obligatoria al pasar a PROGRAMADO.');
    }

    if ($current === null) {
        if ($base !== 0) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'La entidad no existe en el servidor pero la base no es cero.', 0, null);
        }
        $official = null;
        if (in_array($newStatus, JOB_NUMBERED_STATUSES, true)) {
            $official = ensureOfficialNumber($pdo, $userId, $entityId, 'TR');
        }
        insertEntity($pdo, 'job', $entityId, $userId, $payload, $newStatus, $official);
        audit($pdo, $userId, $deviceId, $opId, 'job', $entityId, 'CREATE', ['status' => $newStatus]);
        return result($opId, 'APPLIED', null, null, 1, null, $official);
    }

    if ($base !== (int) $current['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'El trabajo cambió desde la versión conocida por el dispositivo.',
            (int) $current['server_version'], serverState($current));
    }

    $oldStatus = (string) $current['status'];
    // Terminal: ni edición ni transición — el documento queda cerrado.
    if (in_array($oldStatus, JOB_TERMINAL_STATUSES, true)) {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            "El trabajo está $oldStatus (terminal) y no admite cambios.",
            (int) $current['server_version'], serverState($current));
    }
    if (!validJobTransition($oldStatus, $newStatus)) {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            "Transición no permitida $oldStatus → $newStatus; estado actual $oldStatus.",
            (int) $current['server_version'], serverState($current));
    }

    $official = $current['official_number'];
    if ($official === null && !in_array($oldStatus, JOB_NUMBERED_STATUSES, true)
        && in_array($newStatus, JOB_NUMBERED_STATUSES, true)) {
        $official = ensureOfficialNumber($pdo, $userId, $entityId, 'TR');
    }
    updateEntity($pdo, 'job', $entityId, $current, $payload, $newStatus, $official);
    audit($pdo, $userId, $deviceId, $opId, 'job', $entityId,
        $oldStatus === $newStatus ? 'UPDATE' : 'TRANSITION',
        ['from' => $oldStatus, 'to' => $newStatus]);
    return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1, null, $official);
}

function applyQuoteVersion(PDO $pdo, string $userId, string $deviceId, string $opId,
                           string $entityId, array $payload, bool $isResolve): array
{
    $quoteId = (string) ($payload['quoteId'] ?? '');
    $version = (int) ($payload['version'] ?? 0);
    $quote = entityRow($pdo, 'quote', $quoteId);
    if ($quote === null) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'La cotización no existe en el servidor.');
    }
    if ($quote['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'La cotización pertenece a otra cuenta.');
    }

    $snapshot = (string) ($payload['snapshotJson'] ?? '');
    // ¿Ya existe una versión con el mismo número cliente para esta cotización?
    $stmt = $pdo->prepare(
        "SELECT id, payload FROM entities WHERE entity_type = 'quote_version'
         AND user_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.quoteId')) = ?
         AND JSON_EXTRACT(payload, '$.version') = ?"
    );
    $stmt->execute([$userId, $quoteId, $version]);
    $existing = $stmt->fetch() ?: null;

    $nextOrderRaw = $pdo->query(
        "SELECT COALESCE(MAX(server_order), 0) + 1 FROM entities
         WHERE entity_type = 'quote_version' AND user_id = " . $pdo->quote($userId)
        . " AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.quoteId')) = " . $pdo->quote($quoteId)
    )->fetchColumn();
    $nextOrder = $nextOrderRaw === false ? 1 : (int) $nextOrderRaw;

    $concurrent = false;
    if ($existing !== null) {
        $prevPayload = json_decode($existing['payload'], true);
        if (($prevPayload['snapshotJson'] ?? '') === $snapshot) {
            return result($opId, 'APPLIED', null, null, null, null, null); // misma versión, dedupe
        }
        // Edición concurrente: se conserva completa y se marca el documento.
        $concurrent = true;
        $pdo->prepare('UPDATE entities SET has_version_conflict = 1, server_version = server_version + 1,
                       server_seq = ?, updated_at_server = ? WHERE entity_type = ? AND id = ?')
            ->execute([nextServerSeq($pdo), nowUtc(), 'quote', $quoteId]);
    }

    insertEntity($pdo, 'quote_version', $entityId, $userId, $payload, null, null, $nextOrder);

    if ($isResolve) {
        // Resolución aprobada: versión nueva que queda vigente; snapshots previos intactos.
        $emitted = in_array($quote['status'], QUOTE_NUMBERED_STATUSES, true) ? $version : (int) json_decode($quote['payload'], true)['emittedVersion'];
        $newQuotePayload = json_decode($quote['payload'], true);
        $newQuotePayload['currentVersion'] = $version;
        $newQuotePayload['emittedVersion'] = $emitted;
        $newQuotePayload['hasVersionConflict'] = false;
        $pdo->prepare('UPDATE entities SET has_version_conflict = 0, server_version = server_version + 1,
                       server_seq = ?, status = status, payload = ?, updated_at_server = ? WHERE entity_type = ? AND id = ?')
            ->execute([nextServerSeq($pdo), json_encode($newQuotePayload, JSON_UNESCAPED_UNICODE), nowUtc(), 'quote', $quoteId]);
        audit($pdo, $userId, $deviceId, $opId, 'quote_version', $entityId, 'RESOLVE_VERSION_CONFLICT',
            ['quote_id' => $quoteId, 'version' => $version]);
        return result($opId, 'APPLIED', null, null, (int) $quote['server_version'] + 1);
    }

    audit($pdo, $userId, $deviceId, $opId, 'quote_version', $entityId,
        $concurrent ? 'VERSION_CONFLICT' : 'CREATE',
        ['quote_id' => $quoteId, 'version' => $version, 'server_order' => $nextOrder]);

    return $concurrent
        ? result($opId, 'APPLIED', 'VERSION_CONFLICT',
            'Versión concurrente conservada; la cotización requiere resolución del usuario.')
        : result($opId, 'APPLIED');
}

/**
 * server_seq explícito: AUTO_INCREMENT no avanza en UPDATE y el pull
 * incremental depende de que todo cambio produzca un cursor nuevo.
 */
function nextServerSeq(PDO $pdo): int
{
    $pdo->exec(
        "INSERT INTO app_counters (name, value) VALUES ('entities', LAST_INSERT_ID(value + 1))
         ON DUPLICATE KEY UPDATE value = LAST_INSERT_ID(value + 1)"
    );
    return (int) $pdo->query('SELECT LAST_INSERT_ID()')->fetchColumn();
}

function insertEntity(PDO $pdo, string $type, string $id, string $userId, array $payload,
                      ?string $status = null, ?string $official = null, ?int $serverOrder = null): void
{
    $pdo->prepare(
        'INSERT INTO entities (entity_type, id, user_id, server_version, server_seq, server_order, official_number, status, payload, updated_at_server)
         VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?)'
    )->execute([
        $type, $id, $userId, nextServerSeq($pdo), $serverOrder, $official, $status,
        json_encode($payload, JSON_UNESCAPED_UNICODE), nowUtc(),
    ]);
}

function updateEntity(PDO $pdo, string $type, string $id, array $current, array $payload,
                      ?string $status = null, ?string $official = null): void
{
    $pdo->prepare(
        'UPDATE entities SET payload = ?, server_version = server_version + 1,
         server_seq = ?, status = COALESCE(?, status), official_number = COALESCE(?, official_number),
         updated_at_server = ? WHERE entity_type = ? AND id = ?'
    )->execute([
        json_encode($payload, JSON_UNESCAPED_UNICODE), nextServerSeq($pdo), $status, $official,
        nowUtc(), $type, $id,
    ]);
}

function handlePull(): void
{
    $userId = authUserId();
    $cursor = (int) ($_GET['cursor'] ?? 0);
    $stmt = db()->prepare(
        'SELECT entity_type, id, server_version, server_seq, server_order, official_number,
                has_version_conflict, payload
         FROM entities WHERE user_id = ? AND server_seq > ?
         ORDER BY server_seq ASC LIMIT 500'
    );
    $stmt->execute([$userId, $cursor]);
    $changes = [];
    $next = $cursor;
    foreach ($stmt->fetchAll() as $row) {
        $changes[] = [
            'entity_type' => $row['entity_type'],
            'entity_id' => $row['id'],
            'server_version' => (int) $row['server_version'],
            'server_seq' => (int) $row['server_seq'],
            'server_order' => $row['server_order'] === null ? null : (int) $row['server_order'],
            'official_number' => $row['official_number'],
            'has_version_conflict' => (int) $row['has_version_conflict'] === 1,
            'payload' => json_decode($row['payload'], true),
        ];
        $next = max($next, (int) $row['server_seq']);
    }
    respond(200, ['changes' => $changes, 'next_cursor' => $next]);
}

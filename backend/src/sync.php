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
    'report', 'report_job', 'report_version',
    'collection_account', 'collection_method', 'payment', 'payment_assignment',
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
    'report'           => [['client', 'clientId'], ['profile', 'profileId']],
    'report_job'       => [['report', 'reportId'], ['job', 'jobId']],
    'report_version'   => [['report', 'reportId']],
    // F6: la Cuenta exige Cliente y Perfil; los orígenes (Trabajos /
    // Cotizaciones) se validan con datos persistidos en applyCollectionAccount.
    'collection_account' => [['client', 'clientId'], ['profile', 'profileId']],
    'collection_method'  => [],
    'payment'            => [['client', 'clientId']],
    // El evento exige Pago y Cuenta destino; la Cuenta de origen de una
    // reasignación (fromAccountId) se valida opcionalmente en el handler.
    'payment_assignment' => [['payment', 'paymentId'], ['collection_account', 'accountId']],
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

/**
 * Variante con bloqueo de fila (FOR UPDATE): las operaciones financieras
 * F6 leen y actualizan los saldos de Pago/Cuenta atómicamente dentro de la
 * transacción de la operación — dos eventos concurrentes se serializan en
 * la fila y el segundo ve el estado ya aplicado por el primero.
 */
function entityRowForUpdate(PDO $pdo, string $type, string $id): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM entities WHERE entity_type = ? AND id = ? FOR UPDATE');
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

    if ($type === 'report') {
        return applyReport($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current, $base);
    }

    if ($type === 'report_version') {
        return applyReportVersion($pdo, $userId, $deviceId, $opId, $entityId, $payload, $opType === 'resolve');
    }

    if ($type === 'collection_account') {
        return applyCollectionAccount($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current, $base);
    }

    if ($type === 'payment') {
        return applyPayment($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current);
    }

    if ($type === 'payment_assignment') {
        return applyPaymentAssignment($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current);
    }

    // Métodos de Cobro (F6): nombre requerido; el resto sigue la vía
    // genérica de entidad maestra con concurrencia optimista.
    if ($type === 'collection_method' && trim((string) ($payload['name'] ?? '')) === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El nombre del método de cobro es obligatorio.');
    }

    // Vínculo Informe↔Trabajo (F5): validado sobre las entidades ya
    // persistidas — nunca con datos declarados por el cliente —: el
    // Trabajo debe compartir el Cliente Y el Perfil Profesional del
    // Informe (regla aprobada, docs/03 §6), y el Informe debe seguir en
    // BORRADOR porque un emitido es inmutable.
    if ($type === 'report_job') {
        $reportRow = entityRow($pdo, 'report', (string) $payload['reportId']);
        $jobRow = entityRow($pdo, 'job', (string) $payload['jobId']);
        $reportData = json_decode($reportRow['payload'], true) ?: [];
        $jobData = json_decode($jobRow['payload'], true) ?: [];
        if ((string) $reportRow['status'] !== 'BORRADOR') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Solo se pueden asociar trabajos a un informe en estado BORRADOR.');
        }
        if (($jobData['clientId'] ?? '') === ''
            || $jobData['clientId'] !== ($reportData['clientId'] ?? null)
            || $jobData['profileId'] !== ($reportData['profileId'] ?? null)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El trabajo no coincide en cliente y perfil con el informe.');
        }
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
 * F5 — Informe de Trabajo (docs/03 §6): estados BORRADOR/EMITIDO, título y
 * destino obligatorios, emitido inmutable (un EMITIDO→EMITIDO solo puede
 * archivar/desarchivar), corrección como reapertura del borrador y número
 * oficial INF-AAAAMM-NNN sobre el mismo entity_id en la primera emisión
 * aceptada. La emisión exige al menos un Trabajo asociado ya persistido.
 */
function applyReport(PDO $pdo, string $userId, string $deviceId, string $opId,
                     string $entityId, array $payload, ?array $current, int $base): array
{
    $newStatus = (string) ($payload['status'] ?? '');
    if (!in_array($newStatus, REPORT_STATUSES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Estado de informe desconocido.');
    }
    if (!in_array((string) ($payload['destination'] ?? ''), REPORT_DESTINATIONS, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Destino de informe desconocido.');
    }
    if (trim((string) ($payload['title'] ?? '')) === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'El título del informe es obligatorio.');
    }

    if ($current === null) {
        if ($base !== 0) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'La entidad no existe en el servidor pero la base no es cero.', 0, null);
        }
        // Un Informe nace en BORRADOR: emitir exige trabajos asociados ya
        // persistidos, imposibles antes de crear el Informe → un EMITIDO en
        // creación es fabricado y se rechaza (misma regla de abajo).
        if ($newStatus === 'EMITIDO') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Un informe EMITIDO requiere al menos un trabajo asociado persistido.');
        }
        insertEntity($pdo, 'report', $entityId, $userId, $payload, $newStatus);
        audit($pdo, $userId, $deviceId, $opId, 'report', $entityId, 'CREATE', ['status' => $newStatus]);
        return result($opId, 'APPLIED', null, null, 1);
    }

    if ($base !== (int) $current['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'El informe cambió desde la versión conocida por el dispositivo.',
            (int) $current['server_version'], serverState($current));
    }

    $oldStatus = (string) $current['status'];
    if (!validReportTransition($oldStatus, $newStatus)) {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            "Transición no permitida $oldStatus → $newStatus; estado actual $oldStatus.",
            (int) $current['server_version'], serverState($current));
    }

    // Emitido inmutable: un EMITIDO→EMITIDO solo puede cambiar el flag
    // `archived` (y updatedAt). Cualquier otra diferencia de contenido es
    // una edición fabricada del documento emitido y se rechaza.
    if ($oldStatus === 'EMITIDO' && $newStatus === 'EMITIDO') {
        $prevData = json_decode($current['payload'], true) ?: [];
        foreach (['title', 'destination', 'content', 'clientId', 'profileId',
                  'provisionalNumber', 'currentVersion', 'emittedVersion'] as $f) {
            if (($prevData[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                    'El informe emitido es inmutable; la corrección crea una versión nueva.',
                    (int) $current['server_version'], serverState($current));
            }
        }
    }

    // Conflicto de versiones: bloquear una nueva emisión hasta que el
    // usuario resuelva (espejo de la regla de Cotización).
    if ((int) $current['has_version_conflict'] === 1
        && $newStatus === 'EMITIDO' && $oldStatus !== 'EMITIDO') {
        return result($opId, 'REJECTED', 'CONFLICT_BLOCKED',
            'El informe tiene versiones en conflicto; resuélvelas antes de emitir.',
            (int) $current['server_version']);
    }

    // Emisión (BORRADOR→EMITIDO y reemisión tras corrección): el Informe
    // debe tener al menos un Trabajo asociado persistido en el servidor —
    // nunca confiando en lo declarado por el cliente.
    if ($newStatus === 'EMITIDO' && $oldStatus !== 'EMITIDO') {
        $links = $pdo->prepare(
            "SELECT COUNT(*) FROM entities WHERE entity_type = 'report_job'
             AND user_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reportId')) = ?"
        );
        $links->execute([$userId, $entityId]);
        if ((int) $links->fetchColumn() === 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El informe requiere al menos un trabajo asociado para emitirse.');
        }
    }

    $official = $current['official_number'];
    if ($official === null && $oldStatus !== 'EMITIDO'
        && in_array($newStatus, REPORT_NUMBERED_STATUSES, true)) {
        $official = ensureOfficialNumber($pdo, $userId, $entityId, 'INF');
    }
    updateEntity($pdo, 'report', $entityId, $current, $payload, $newStatus, $official);
    audit($pdo, $userId, $deviceId, $opId, 'report', $entityId,
        $oldStatus === $newStatus ? 'UPDATE' : 'TRANSITION',
        ['from' => $oldStatus, 'to' => $newStatus]);
    return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1, null, $official);
}

/**
 * F5 — Versión de Informe: snapshot inmutable por entity_id, dedupe de
 * replays, versiones concurrentes conservadas con server_order autoritativo
 * y resolución aprobada como versión nueva (espejo de applyQuoteVersion).
 */
function applyReportVersion(PDO $pdo, string $userId, string $deviceId, string $opId,
                            string $entityId, array $payload, bool $isResolve): array
{
    $reportId = (string) ($payload['reportId'] ?? '');
    $version = (int) ($payload['version'] ?? 0);
    $report = entityRow($pdo, 'report', $reportId);
    if ($report === null) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'El informe no existe en el servidor.');
    }
    if ($report['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'El informe pertenece a otra cuenta.');
    }

    $snapshot = (string) ($payload['snapshotJson'] ?? '');
    $stmt = $pdo->prepare(
        "SELECT id, payload FROM entities WHERE entity_type = 'report_version'
         AND user_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reportId')) = ?
         AND JSON_EXTRACT(payload, '$.version') = ?"
    );
    $stmt->execute([$userId, $reportId, $version]);
    $existing = $stmt->fetch() ?: null;

    $nextOrderRaw = $pdo->query(
        "SELECT COALESCE(MAX(server_order), 0) + 1 FROM entities
         WHERE entity_type = 'report_version' AND user_id = " . $pdo->quote($userId)
        . " AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reportId')) = " . $pdo->quote($reportId)
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
            ->execute([nextServerSeq($pdo), nowUtc(), 'report', $reportId]);
    }

    insertEntity($pdo, 'report_version', $entityId, $userId, $payload, null, null, $nextOrder);

    if ($isResolve) {
        // Resolución aprobada: versión nueva que queda vigente; snapshots previos intactos.
        $reportData = json_decode($report['payload'], true) ?: [];
        $emitted = $report['status'] === 'EMITIDO' ? $version : (int) ($reportData['emittedVersion'] ?? 0);
        $reportData['currentVersion'] = $version;
        $reportData['emittedVersion'] = $emitted;
        $reportData['hasVersionConflict'] = false;
        $pdo->prepare('UPDATE entities SET has_version_conflict = 0, server_version = server_version + 1,
                       server_seq = ?, status = status, payload = ?, updated_at_server = ? WHERE entity_type = ? AND id = ?')
            ->execute([nextServerSeq($pdo), json_encode($reportData, JSON_UNESCAPED_UNICODE), nowUtc(), 'report', $reportId]);
        audit($pdo, $userId, $deviceId, $opId, 'report_version', $entityId, 'RESOLVE_VERSION_CONFLICT',
            ['report_id' => $reportId, 'version' => $version]);
        return result($opId, 'APPLIED', null, null, (int) $report['server_version'] + 1);
    }

    audit($pdo, $userId, $deviceId, $opId, 'report_version', $entityId,
        $concurrent ? 'VERSION_CONFLICT' : 'CREATE',
        ['report_id' => $reportId, 'version' => $version, 'server_order' => $nextOrder]);

    return $concurrent
        ? result($opId, 'APPLIED', 'VERSION_CONFLICT',
            'Versión concurrente conservada; el informe requiere resolución del usuario.')
        : result($opId, 'APPLIED');
}

/**
 * F6 — Cuenta de Cobro (docs/03 §6): nace PENDIENTE con número oficial
 * `CC-AAAAMM-NNN` en la primera aceptación; editable solo en PENDIENTE sin
 * pagos aplicados; PARCIAL/PAGADA las deriva el servidor desde los eventos
 * de pago; CANCELADA es terminal y solo sin pagos. Asociaciones Trabajos O
 * Cotizaciones (nunca ambos), mismo Cliente/Perfil, un origen en a lo
 * sumo una Cuenta no cancelada, Cotizaciones solo APROBADA — todo validado
 * con datos persistidos, nunca con lo declarado por el cliente.
 */
function applyCollectionAccount(PDO $pdo, string $userId, string $deviceId, string $opId,
                                string $entityId, array $payload, ?array $current, int $base): array
{
    $newStatus = (string) ($payload['status'] ?? '');
    if (!in_array($newStatus, COLLECTION_ACCOUNT_STATUSES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Estado de cuenta de cobro desconocido.');
    }
    $items = $payload['items'] ?? null;
    if (!is_array($items) || count($items) === 0) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'La cuenta de cobro requiere al menos una línea.');
    }
    foreach ($items as $it) {
        if (!is_array($it)
            || !in_array((string) ($it['conceptType'] ?? ''), COLLECTION_LINE_TYPES, true)
            || trim((string) ($it['description'] ?? '')) === '') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Línea inválida: tipo o descripción desconocidos.');
        }
    }
    $jobIds = array_values(array_filter(
        array_map('strval', (array) ($payload['jobIds'] ?? [])), 'strlen'));
    $quoteIds = array_values(array_filter(
        array_map('strval', (array) ($payload['quoteIds'] ?? [])), 'strlen'));
    if (count($jobIds) !== count(array_unique($jobIds))
        || count($quoteIds) !== count(array_unique($quoteIds))) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Orígenes duplicados.');
    }
    if ($jobIds !== [] && $quoteIds !== []) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'La cuenta no puede mezclar trabajos y cotizaciones.');
    }

    // Validación de orígenes con datos persistidos: mismo Cliente/Perfil
    // declarados (comparados contra el payload persistido del origen),
    // Cotización solo en estado persistido APROBADA, y un origen en a lo
    // sumo una Cuenta no cancelada.
    foreach ($jobIds as $jobId) {
        $job = entityRow($pdo, 'job', $jobId);
        if ($job === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', "Falta el trabajo $jobId referenciado.");
        }
        if ($job['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'El trabajo referenciado pertenece a otra cuenta.');
        }
        $jd = json_decode($job['payload'], true) ?: [];
        if (($jd['clientId'] ?? '') !== (string) ($payload['clientId'] ?? '')
            || ($jd['profileId'] ?? '') !== (string) ($payload['profileId'] ?? '')) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El trabajo no coincide en cliente y perfil con la cuenta.');
        }
        if (collectionSourceTaken($pdo, $userId, $jobId, 'jobIds', $entityId)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El trabajo ya pertenece a otra cuenta de cobro no cancelada.');
        }
    }
    foreach ($quoteIds as $quoteId) {
        $quote = entityRow($pdo, 'quote', $quoteId);
        if ($quote === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', "Falta la cotización $quoteId referenciada.");
        }
        if ($quote['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'La cotización referenciada pertenece a otra cuenta.');
        }
        if ((string) $quote['status'] !== 'APROBADA') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Solo se pueden asociar cotizaciones en estado APROBADA.');
        }
        $qd = json_decode($quote['payload'], true) ?: [];
        if (($qd['clientId'] ?? '') !== (string) ($payload['clientId'] ?? '')
            || ($qd['profileId'] ?? '') !== (string) ($payload['profileId'] ?? '')) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cotización no coincide en cliente y perfil con la cuenta.');
        }
        if (collectionSourceTaken($pdo, $userId, $quoteId, 'quoteIds', $entityId)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cotización ya pertenece a otra cuenta de cobro no cancelada.');
        }
    }

    if ($current === null) {
        if ($base !== 0) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'La entidad no existe en el servidor pero la base no es cero.', 0, null);
        }
        if ($newStatus !== 'PENDIENTE') {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'La cuenta de cobro nace en PENDIENTE; los demás estados los deriva el servidor.');
        }
        // Importes derivados: el servidor conserva el neto declarado pero
        // recalcula aplicado/saldo/estado desde sus propios datos — nunca
        // confía en los importes del payload.
        $payload['appliedAmount'] = '0.00';
        $payload['balance'] = (string) ($payload['net'] ?? '0');
        $payload['status'] = 'PENDIENTE';
        $official = ensureOfficialNumber($pdo, $userId, $entityId, 'CC');
        insertEntity($pdo, 'collection_account', $entityId, $userId, $payload, 'PENDIENTE', $official);
        audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'CREATE',
            ['status' => 'PENDIENTE']);
        return result($opId, 'APPLIED', null, null, 1, null, $official);
    }

    if ($base !== (int) $current['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'La cuenta de cobro cambió desde la versión conocida por el dispositivo.',
            (int) $current['server_version'], serverState($current));
    }

    $oldStatus = (string) $current['status'];
    $prevData = json_decode($current['payload'], true) ?: [];

    // Inmutable tras pagos aplicados o cancelada: solo `archived` puede
    // cambiar; líneas, totales, asociaciones y snapshots quedan cerrados
    // (y el estado derivado lo gobierna el servidor).
    if (in_array($oldStatus, ['PARCIAL', 'PAGADA', 'CANCELADA'], true)) {
        if ($newStatus !== $oldStatus) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                "La cuenta está $oldStatus y no admite la transición declarada.",
                (int) $current['server_version'], serverState($current));
        }
        foreach (['clientId', 'profileId', 'provisionalNumber', 'currency',
                  'gross', 'totalRetained', 'net', 'issuedAt', 'dueDateEpochDay',
                  'conditions', 'clientSnapshotJson', 'profileSnapshotJson',
                  'retentionsSnapshotJson'] as $f) {
            if (($prevData[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                    'La cuenta con pagos aplicados (o cancelada) es inmutable; solo puede archivarse.',
                    (int) $current['server_version'], serverState($current));
            }
        }
        // items se compara estructuralmente (contenido completo de cada
        // línea); jobIds/quoteIds son listas de texto y se normalizan.
        $norm = fn($v) => array_values(array_map('strval', (array) ($v ?? [])));
        if (($prevData['items'] ?? []) != ($payload['items'] ?? [])
            || $norm($prevData['jobIds'] ?? []) != $norm($payload['jobIds'] ?? [])
            || $norm($prevData['quoteIds'] ?? []) != $norm($payload['quoteIds'] ?? [])) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'La cuenta con pagos aplicados (o cancelada) es inmutable; solo puede archivarse.',
                (int) $current['server_version'], serverState($current));
        }
        $payload['status'] = $oldStatus;
        $payload['appliedAmount'] = $prevData['appliedAmount'] ?? '0.00';
        $payload['balance'] = $prevData['balance'] ?? $payload['net'];
        updateEntity($pdo, 'collection_account', $entityId, $current, $payload,
            $oldStatus, $current['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'UPDATE',
            ['archived' => (bool) ($payload['archived'] ?? false)]);
        return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1);
    }

    // Persistida en PENDIENTE: edición libre o cancelación (solo sin pagos
    // aplicados — garantizado: un pago la habría derivado a PARCIAL/PAGADA).
    if ($newStatus === 'CANCELADA') {
        $applied = toCents((string) ($prevData['appliedAmount'] ?? '0'));
        if ($applied > 0) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'Solo se puede cancelar una cuenta sin pagos aplicados.',
                (int) $current['server_version'], serverState($current));
        }
        $payload['status'] = 'CANCELADA';
        updateEntity($pdo, 'collection_account', $entityId, $current, $payload,
            'CANCELADA', $current['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'TRANSITION',
            ['from' => 'PENDIENTE', 'to' => 'CANCELADA']);
        return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1);
    }
    if ($newStatus !== 'PENDIENTE') {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            'PARCIAL y PAGADA las deriva el servidor desde los pagos aplicados.',
            (int) $current['server_version'], serverState($current));
    }
    $payload['appliedAmount'] = $prevData['appliedAmount'] ?? '0.00';
    $payload['balance'] = (string) ($payload['net'] ?? $prevData['net'] ?? '0');
    updateEntity($pdo, 'collection_account', $entityId, $current, $payload,
        'PENDIENTE', $current['official_number']);
    audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'UPDATE');
    return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1);
}

/** ¿El origen ya está ligado a otra Cuenta no cancelada del usuario? */
function collectionSourceTaken(PDO $pdo, string $userId, string $sourceId,
                               string $field, string $selfId): bool
{
    $stmt = $pdo->prepare(
        "SELECT id, status, payload FROM entities
         WHERE entity_type = 'collection_account' AND user_id = ? AND status != 'CANCELADA'"
    );
    $stmt->execute([$userId]);
    foreach ($stmt->fetchAll() as $row) {
        if ($row['id'] === $selfId) {
            continue;
        }
        $data = json_decode($row['payload'], true) ?: [];
        if (in_array($sourceId, array_map('strval', (array) ($data[$field] ?? [])), true)) {
            return true;
        }
    }
    return false;
}

/**
 * F6 — Pago append-only: se registra una vez y nunca se edita ni elimina.
 * Los importes derivados los calcula el servidor desde sus propios eventos
 * de asignación; el payload se normaliza (applied/unassigned/destino) antes
 * de persistirse. Un entity_id existente con campos estables distintos es
 * una mutación fabricada → REJECTED (sin sobrescritura financiera).
 */
function applyPayment(PDO $pdo, string $userId, string $deviceId, string $opId,
                      string $entityId, array $payload, ?array $current): array
{
    $amount = toCents((string) ($payload['amount'] ?? ''));
    if ($amount <= 0) {
        // Formato inválido (-1) o importe no positivo: un solo rechazo.
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El importe del pago debe ser un decimal positivo.');
    }
    $methodId = (string) ($payload['collectionMethodId'] ?? '');
    if ($methodId !== '') {
        $method = entityRow($pdo, 'collection_method', $methodId);
        if ($method === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', 'El método de cobro referenciado no existe.');
        }
        if ($method['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'El método de cobro pertenece a otra cuenta.');
        }
    }

    if ($current !== null) {
        // Idempotencia de entidad (replay con otro operation_id, p. ej. tras
        // re-vinculación de cuenta): campos estables idénticos → dedupe.
        $prev = json_decode($current['payload'], true) ?: [];
        foreach (['clientId', 'collectionMethodId', 'amount', 'paidAtEpochDay',
                  'reference', 'notes'] as $f) {
            if (($prev[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'IMMUTABLE_FINANCIAL',
                    'El pago es un evento financiero append-only y no admite edición.');
            }
        }
        return result($opId, 'APPLIED', null, null, (int) $current['server_version']);
    }

    // Normalización autoritativa: nace sin aplicación; los eventos de
    // asignación son la única vía que mueve su dinero.
    $payload['appliedAmount'] = '0.00';
    $payload['unassignedAmount'] = fromCents($amount);
    $payload['assignedAccountId'] = null;
    $payload['assignmentStatus'] = 'UNASSIGNED';
    insertEntity($pdo, 'payment', $entityId, $userId, $payload, 'UNASSIGNED');
    audit($pdo, $userId, $deviceId, $opId, 'payment', $entityId, 'CREATE',
        ['amount' => (string) ($payload['amount'] ?? '')]);
    return result($opId, 'APPLIED', null, null, 1);
}

/**
 * F6 — Evento de asignación de Pago (append-only, auditado, atómico):
 * assign aplica solo hasta el saldo pendiente (el excedente queda
 * UNASSIGNED), unassign retira la aplicación vigente y reassign libera de
 * la origen aplicando al destino — todo en una transacción con las filas
 * de Pago y Cuentas bloqueadas FOR UPDATE, de modo que la validación de
 * saldo sea atómica frente a eventos concurrentes.
 */
function applyPaymentAssignment(PDO $pdo, string $userId, string $deviceId, string $opId,
                                string $entityId, array $payload, ?array $current): array
{
    if ($current !== null) {
        // Append-only: mismo entity_id + campos estables → dedupe; cualquier
        // diferencia es una mutación fabricada del historial financiero.
        $prev = json_decode($current['payload'], true) ?: [];
        foreach (['paymentId', 'action', 'accountId', 'fromAccountId', 'reason'] as $f) {
            if (($prev[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'IMMUTABLE_FINANCIAL',
                    'La asignación es un evento append-only y no admite edición.');
            }
        }
        return result($opId, 'APPLIED', null, null, (int) $current['server_version']);
    }

    $action = (string) ($payload['action'] ?? '');
    if (!in_array($action, PAYMENT_ASSIGNMENT_ACTIONS, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Acción de asignación desconocida.');
    }
    if (trim((string) ($payload['reason'] ?? '')) === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'Toda asignación, retiro o reasignación requiere un motivo.');
    }
    $requested = toCents((string) ($payload['amount'] ?? '0'));
    $released = toCents((string) ($payload['releasedAmount'] ?? '0'));

    // Bloqueos atómicos: el Pago y la(s) Cuenta(s) se leen FOR UPDATE.
    $payment = entityRowForUpdate($pdo, 'payment', (string) $payload['paymentId']);
    if ($payment === null || $payment['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'El pago referenciado no existe.');
    }
    $account = entityRowForUpdate($pdo, 'collection_account', (string) $payload['accountId']);
    if ($account === null) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'La cuenta referenciada no existe.');
    }
    $p = json_decode($payment['payload'], true) ?: [];
    $a = json_decode($account['payload'], true) ?: [];

    if (($a['clientId'] ?? '') !== ($p['clientId'] ?? '')) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'La cuenta destino pertenece a un cliente distinto del pago.');
    }

    $pApplied = toCents((string) ($p['appliedAmount'] ?? '0'));
    $pUnassigned = toCents((string) ($p['unassignedAmount'] ?? '0'));
    $pTotal = toCents((string) ($p['amount'] ?? '0'));
    $pAssignedTo = $p['assignedAccountId'] ?? null;
    $aApplied = toCents((string) ($a['appliedAmount'] ?? '0'));
    $aBalance = toCents((string) ($a['balance'] ?? '0'));
    $aNet = toCents((string) ($a['net'] ?? '0'));
    $aStatus = (string) $account['status'];
    $now = nowUtc();

    $appliedNow = 0;
    $releasedNow = 0;
    $fromRow = null;
    $fromData = null;

    if ($action === 'assign') {
        if ($pAssignedTo !== null && $pAssignedTo !== (string) $payload['accountId']) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago ya está aplicado a otra cuenta; usa reasignar.');
        }
        if ($pUnassigned <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago no tiene dinero sin asignar.');
        }
        if (!in_array($aStatus, COLLECTION_ACCOUNT_RECEIVABLE, true) || $aBalance <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cuenta no puede recibir pagos (sin saldo, pagada o cancelada).');
        }
        // Solo lo necesario: min(solicitado, remanente del pago, saldo).
        $appliedNow = min($requested, $pUnassigned, $aBalance);
        if ($appliedNow <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La asignación no aplicó importe alguno.');
        }
    } elseif ($action === 'unassign') {
        if ($pAssignedTo === null || $pAssignedTo !== (string) $payload['accountId'] || $pApplied <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago no tiene dinero aplicado a esta cuenta.');
        }
        $releasedNow = $pApplied;
    } else { // reassign
        $fromId = (string) ($payload['fromAccountId'] ?? '');
        if ($fromId === '' || $pAssignedTo !== $fromId || $pApplied <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago no está aplicado a la cuenta de origen indicada.');
        }
        if ($fromId === (string) $payload['accountId']) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Origen y destino son la misma cuenta.');
        }
        $fromRow = entityRowForUpdate($pdo, 'collection_account', $fromId);
        if ($fromRow === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', 'La cuenta de origen no existe.');
        }
        $fromData = json_decode($fromRow['payload'], true) ?: [];
        if (!in_array($aStatus, COLLECTION_ACCOUNT_RECEIVABLE, true) || $aBalance <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cuenta destino no puede recibir pagos.');
        }
        $releasedNow = $pApplied;
        $appliedNow = min($releasedNow, $aBalance);
    }

    // Actualización derivada del Pago (siempre un solo destino vigente):
    // assign SUMA sobre lo ya aplicado a la misma cuenta; unassign retira
    // todo; reassign mueve lo liberado (la parte que cupo en el destino).
    $newApplied = match ($action) {
        'assign' => $pApplied + $appliedNow,
        'unassign' => 0,
        default => $appliedNow,
    };
    $newUnassigned = $pTotal - $newApplied;
    $p['appliedAmount'] = fromCents($newApplied);
    $p['unassignedAmount'] = fromCents($newUnassigned);
    $p['assignedAccountId'] = $newApplied > 0 ? (string) $payload['accountId'] : null;
    $p['assignmentStatus'] = deriveAssignmentStatus($newApplied, $newUnassigned);
    updateEntity($pdo, 'payment', (string) $payload['paymentId'], $payment, $p, $p['assignmentStatus']);

    // Cuenta(s) afectada(s) con estado derivado recalculado por el servidor.
    $aNewApplied = $aApplied + $appliedNow - ($action === 'unassign' ? $releasedNow : 0);
    $a['appliedAmount'] = fromCents($aNewApplied);
    $a['balance'] = fromCents($aNet - $aNewApplied);
    $aNewStatus = deriveCollectionStatus(
        $aStatus === 'CANCELADA' ? 'CANCELADA' : 'PENDIENTE', $aNewApplied, $aNet);
    $a['status'] = $aNewStatus;
    updateEntity($pdo, 'collection_account', (string) $payload['accountId'], $account, $a, $aNewStatus);

    if ($fromRow !== null) {
        $fApplied = toCents((string) ($fromData['appliedAmount'] ?? '0')) - $releasedNow;
        $fNet = toCents((string) ($fromData['net'] ?? '0'));
        $fromData['appliedAmount'] = fromCents($fApplied);
        $fromData['balance'] = fromCents($fNet - $fApplied);
        $fStatus = deriveCollectionStatus(
            (string) $fromRow['status'] === 'CANCELADA' ? 'CANCELADA' : 'PENDIENTE',
            $fApplied, $fNet);
        $fromData['status'] = $fStatus;
        updateEntity($pdo, 'collection_account', $fromId, $fromRow, $fromData, $fStatus);
    }

    // El evento persiste los importes autoritativos del servidor.
    $payload['amount'] = fromCents($appliedNow);
    $payload['releasedAmount'] = fromCents($releasedNow);
    insertEntity($pdo, 'payment_assignment', $entityId, $userId, $payload);
    audit($pdo, $userId, $deviceId, $opId, 'payment_assignment', $entityId,
        'PAYMENT_' . strtoupper($action),
        ['payment_id' => (string) $payload['paymentId'],
         'account_id' => (string) $payload['accountId'],
         'from_account_id' => $payload['fromAccountId'] ?? null,
         'applied' => fromCents($appliedNow), 'released' => fromCents($releasedNow),
         'reason' => (string) ($payload['reason'] ?? '')]);
    return result($opId, 'APPLIED', null, null, 1);
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

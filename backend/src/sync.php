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
    // F7
    'consolidated_collection', 'payment_adjustment', 'payment_refund',
    // F8
    'material', 'purchase', 'inventory_movement', 'inventory_stock',
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
    // El evento exige el Pago; el destino es una Cuenta (`accountId`) o un
    // Consolidado (`consolidatedId`) y se valida en el handler (F7).
    'payment_assignment' => [['payment', 'paymentId']],
    // F7: el Consolidado exige Cliente; las Cuentas miembro se validan con
    // datos persistidos en applyConsolidatedCollection.
    'consolidated_collection' => [['client', 'clientId']],
    // Ajuste y Reembolso cuelgan del Pago (append-only, auditados).
    'payment_adjustment' => [['payment', 'paymentId']],
    'payment_refund'     => [['payment', 'paymentId']],
    // F8: el catálogo de Materiales es global por titular (sin Perfil);
    // las líneas de la Compra referencian Materiales validados con datos
    // persistidos en applyPurchase; el movimiento exige su Material y las
    // referencias opcionales por origen se validan en el handler.
    'material'            => [],
    'purchase'            => [],
    'inventory_movement'  => [['material', 'materialId']],
    // Proyección derivada del servidor: solo lectura para los clientes.
    'inventory_stock'     => [],
];

/** Tipos cuya columna `entities.status` es el estado documental canónico
 *  que el cliente reconstruye desde `payload.status`. En los demás tipos
 *  la columna guarda otra semántica (payment → assignmentStatus,
 *  inventory_movement → movementType) y el payload no define `status`. */
const DOC_STATUS_TYPES = [
    'purchase', 'quote', 'job', 'report',
    'collection_account', 'consolidated_collection',
];

/** Proyección común (serverState/pull): la columna `status` autoritativa
 *  manda sobre el `status` del JSON persistido — un documento escrito por
 *  una ruta que restaura el payload previo (la anulación de Compra, que
 *  conservaba status=CONFIRMADA) no puede servirse con un estado que la
 *  columna ya superó. Solo lectura: el payload almacenado no se muta. */
function withAuthoritativeStatus(array $row, array $state): array
{
    if (in_array((string) ($row['entity_type'] ?? ''), DOC_STATUS_TYPES, true)
        && ($row['status'] ?? null) !== null) {
        $state['status'] = $row['status'];
    }
    return $state;
}

/** Estado remoto para resultados CONFLICT/REJECTED: el payload persistido
 *  más los metadatos del servidor (official_number, has_version_conflict),
 *  que no viajan dentro del payload, de modo que la resolución
 *  "conservar remoto" del cliente preserve el documento completo. */
function serverState(array $row): array
{
    $state = withAuthoritativeStatus($row, json_decode($row['payload'], true) ?: []);
    $state['official_number'] = $row['official_number'];
    $state['has_version_conflict'] = (int) ($row['has_version_conflict'] ?? 0) === 1;
    $state['server_version'] = (int) ($row['server_version'] ?? 0);
    return $state;
}

/**
 * Fila de la entidad SOLO si pertenece a la identidad autenticada: el
 * server_state expuesto en un rechazo debe ser autoritativo y visible para
 * el usuario; una fila de otra cuenta jamás se devuelve (regla aprobada).
 */
function ownEntityRow(PDO $pdo, string $userId, string $type, string $entityId): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM entities WHERE entity_type = ? AND id = ? AND user_id = ?');
    $stmt->execute([$type, $entityId, $userId]);
    $row = $stmt->fetch();
    return $row === false ? null : $row;
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

/**
 * Enriquecimiento de rechazos: se adjunta `server_state` (payload
 * persistido + metadatos del servidor) solo cuando la fila pertenece a
 * la identidad autenticada (autoritativo y visible). `FORBIDDEN_ENTITY`
 * nunca expone una fila ajena: `entity_foreign` sin estado cuando la
 * fila misma es de otra cuenta, `foreign_reference` cuando la fila es
 * propia o inexistente y el rechazo vino de una referencia ajena — ahí
 * el estado propio permite restaurar lo autoritativo.
 * Debe ejecutarse ANTES de memorizar el resultado (dentro de la misma
 * transacción): el replay idempotente devuelve `result_json` tal cual
 * se guardó, así que el primer intento y el replay producen respuestas
 * funcionalmente idénticas (revisión del PR #24).
 */
function enrichRejectedResult(PDO $pdo, string $userId, string $type, string $entityId, array $res): array
{
    $own = ownEntityRow($pdo, $userId, $type, $entityId);
    if (($res['code'] ?? '') === 'FORBIDDEN_ENTITY') {
        $any = entityRow($pdo, $type, $entityId);
        $res['forbidden_scope'] =
            ($any !== null && $any['user_id'] !== $userId) ? 'entity_foreign' : 'foreign_reference';
        if ($own !== null && !isset($res['server_state'])) {
            $res['server_state'] = serverState($own);
        }
    } elseif ($own !== null && !isset($res['server_state'])) {
        $res['server_state'] = serverState($own);
    }
    return $res;
}

function applyOperation(PDO $pdo, string $userId, string $deviceId, string $opId,
                        string $type, string $entityId, string $opType,
                        array $payload, int $base, $supersedes, string $payloadHash): array
{
    $pdo->beginTransaction();
    try {
        $res = applyOperationTx($pdo, $userId, $deviceId, $opId, $type, $entityId, $opType, $payload, $base, $supersedes);
        if (($res['status'] ?? '') === 'REJECTED' && ($res['code'] ?? '') !== 'INTERNAL_ERROR') {
            // Enriquecer ANTES de memorizar: el replay devuelve result_json
            // tal cual — con server_state/forbidden_scope idénticos.
            $res = enrichRejectedResult($pdo, $userId, $type, $entityId, $res);
        }

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

    if ($type === 'consolidated_collection') {
        return applyConsolidatedCollection($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current, $base);
    }

    if ($type === 'payment_adjustment') {
        return applyPaymentAdjustment($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current);
    }

    if ($type === 'payment_refund') {
        return applyPaymentRefund($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current);
    }

    // F8 — Inventario y Compras.
    if ($type === 'material') {
        return applyMaterial($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current, $base);
    }

    if ($type === 'purchase') {
        return applyPurchase($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current, $base);
    }

    if ($type === 'inventory_movement') {
        return applyInventoryMovement($pdo, $userId, $deviceId, $opId, $entityId, $payload, $current);
    }

    // La proyección de stock es derivada por el servidor (append-only de
    // movimientos → saldo); nunca se escribe desde el cliente.
    if ($type === 'inventory_stock') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El saldo proyectado de inventario lo deriva el servidor.');
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

    // Vínculo a Material inventariado (F8): solo consumos NUEVOS — un
    // job_material histórico no puede ganar materialId al editarse —; el
    // consumo siempre es de material propio (PROFESIONAL) con cantidad
    // positiva y condición de stock conocida; los campos del vínculo
    // (materialId, cantidad, unidad, condición) son inmutables porque la
    // salida de inventario es un evento append-only ya registrado.
    if ($type === 'job_material') {
        $linkId = trim((string) ($payload['materialId'] ?? ''));
        $prevLink = $current === null
            ? ''
            : trim((string) (json_decode($current['payload'], true)['materialId'] ?? ''));
        if ($linkId === '' && $prevLink !== '') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El consumo vinculado a inventario no admite desvinculación.');
        }
        if ($linkId !== '') {
            if ($prevLink === '' && $current !== null) {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'Un registro descriptivo existente no puede vincularse a inventario.');
            }
            if ((string) ($payload['origin'] ?? '') !== 'PROFESIONAL') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El consumo de inventario solo aplica a material propio (PROFESIONAL).');
            }
            if (!isPositiveDecimal((string) ($payload['quantity'] ?? ''))) {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El consumo vinculado requiere una cantidad positiva.');
            }
            if (!in_array(strtoupper((string) ($payload['condition'] ?? '')),
                    MATERIAL_CONDITIONS, true)) {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'La condición de stock (NUEVO/USADO) es obligatoria en el consumo.');
            }
            $mat = entityRow($pdo, 'material', $linkId);
            if ($mat === null) {
                return result($opId, 'REJECTED', 'DEPENDENCY',
                    'El material de inventario referenciado no existe.');
            }
            if ($mat['user_id'] !== $userId) {
                return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
                    'El material referenciado pertenece a otra cuenta.');
            }
            if ($prevLink !== '') {
                $prev = json_decode($current['payload'], true) ?: [];
                foreach (['materialId', 'quantity', 'unit', 'condition'] as $f) {
                    if (($prev[$f] ?? null) !== ($payload[$f] ?? null)) {
                        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                            'El consumo vinculado es append-only; registra un ajuste de inventario para corregirlo.');
                    }
                }
            }
        }
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
    // sumo una Cuenta no cancelada. Los orígenes se leen FOR UPDATE para
    // serializar la comprobación de reuso entre altas concurrentes, en
    // orden canónico (id ascendente): dos altas que declaren el mismo
    // conjunto en distinto orden adquirirían los locks cruzados y se
    // interbloquearían. El orden declarado del payload no se altera.
    $jobIdsSorted = $jobIds;
    sort($jobIdsSorted);
    foreach ($jobIdsSorted as $jobId) {
        $job = entityRowForUpdate($pdo, 'job', $jobId);
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
    $quoteIdsSorted = $quoteIds;
    sort($quoteIdsSorted);
    foreach ($quoteIdsSorted as $quoteId) {
        $quote = entityRowForUpdate($pdo, 'quote', $quoteId);
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
        // confía en los importes del payload. La identidad se normaliza con
        // valores canónicos (F7, revisión M2): id del sobre, titular
        // autenticado y createdAt declarado (o del servidor si falta).
        $payload['appliedAmount'] = '0.00';
        $payload['balance'] = (string) ($payload['net'] ?? '0');
        $payload['status'] = 'PENDIENTE';
        $payload = normalizeIdentityCreate($payload, $entityId, $userId);
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

    // La fila se bloquea antes de cualquier decisión sobre membresía: ordena
    // la espera contra altas/ediciones concurrentes del Consolidado, que ya
    // bloquean a sus miembros en applyConsolidatedCollection, y la base se
    // revalida contra la versión bloqueada (la lectura inicial del despacho
    // puede quedar obsoleta si un competidor confirmó durante la espera).
    $locked = entityRowForUpdate($pdo, 'collection_account', $entityId);
    if ($locked === null) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'La cuenta de cobro ya no existe.');
    }
    if ($base !== (int) $locked['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'La cuenta de cobro cambió desde la versión conocida por el dispositivo.',
            (int) $locked['server_version'], serverState($locked));
    }
    $oldStatus = (string) $locked['status'];
    $prevData = json_decode($locked['payload'], true) ?: [];

    // F7: una Cuenta miembro de un Consolidado activo conserva intactas sus
    // líneas, totales y asociaciones (el Consolidado tomó su saldo); solo
    // `archived` puede cambiar. La membresía se consulta tras bloquear la
    // fila para observar el estado vigente del servidor.
    if (accountInActiveConsolidated($pdo, $userId, $entityId)) {
        foreach (['clientId', 'profileId', 'provisionalNumber', 'currency',
                  'gross', 'totalRetained', 'net', 'issuedAt', 'dueDateEpochDay',
                  'conditions', 'clientSnapshotJson', 'profileSnapshotJson',
                  'retentionsSnapshotJson', 'clientNameSnapshot', 'status',
                  'appliedAmount', 'balance'] as $f) {
            if (($prevData[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                    'La cuenta pertenece a un consolidado activo; retírala del consolidado antes de editarla.',
                    (int) $locked['server_version'], serverState($locked));
            }
        }
        $norm = fn($v) => array_values(array_map('strval', (array) ($v ?? [])));
        if (($prevData['items'] ?? []) != ($payload['items'] ?? [])
            || $norm($prevData['jobIds'] ?? []) != $norm($payload['jobIds'] ?? [])
            || $norm($prevData['quoteIds'] ?? []) != $norm($payload['quoteIds'] ?? [])) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'La cuenta pertenece a un consolidado activo; retírala del consolidado antes de editarla.',
                (int) $locked['server_version'], serverState($locked));
        }
        $payload['status'] = $oldStatus;
        $payload['appliedAmount'] = $prevData['appliedAmount'] ?? '0.00';
        $payload['balance'] = $prevData['balance'] ?? $payload['net'];
        $payload['id'] = $prevData['id'] ?? $entityId;
        $payload['userId'] = $prevData['userId'] ?? $userId;
        $payload['createdAt'] = $prevData['createdAt'] ?? ($payload['createdAt'] ?? null);
        updateEntity($pdo, 'collection_account', $entityId, $locked, $payload,
            $oldStatus, $locked['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'UPDATE',
            ['archived' => (bool) ($payload['archived'] ?? false)]);
        return result($opId, 'APPLIED', null, null, (int) $locked['server_version'] + 1);
    }

    // Inmutable tras pagos aplicados o cancelada: solo `archived` puede
    // cambiar; líneas, totales, asociaciones y snapshots quedan cerrados
    // (y el estado derivado lo gobierna el servidor).
    if (in_array($oldStatus, ['PARCIAL', 'PAGADA', 'CANCELADA'], true)) {
        if ($newStatus !== $oldStatus) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                "La cuenta está $oldStatus y no admite la transición declarada.",
                (int) $locked['server_version'], serverState($locked));
        }
        foreach (['clientId', 'profileId', 'provisionalNumber', 'currency',
                  'gross', 'totalRetained', 'net', 'issuedAt', 'dueDateEpochDay',
                  'conditions', 'clientSnapshotJson', 'profileSnapshotJson',
                  'retentionsSnapshotJson', 'clientNameSnapshot'] as $f) {
            if (($prevData[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                    'La cuenta con pagos aplicados (o cancelada) es inmutable; solo puede archivarse.',
                    (int) $locked['server_version'], serverState($locked));
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
                (int) $locked['server_version'], serverState($locked));
        }
        $payload['status'] = $oldStatus;
        $payload['appliedAmount'] = $prevData['appliedAmount'] ?? '0.00';
        $payload['balance'] = $prevData['balance'] ?? $payload['net'];
        // Identidad, titular y creación tampoco puede cambiarlas el payload:
        // se restauran desde lo persistido (el user_id de la fila ya es
        // autoritativo y nunca lo toca updateEntity).
        $payload['id'] = $prevData['id'] ?? $entityId;
        $payload['userId'] = $prevData['userId'] ?? $userId;
        $payload['createdAt'] = $prevData['createdAt'] ?? ($payload['createdAt'] ?? null);
        updateEntity($pdo, 'collection_account', $entityId, $locked, $payload,
            $oldStatus, $locked['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'UPDATE',
            ['archived' => (bool) ($payload['archived'] ?? false)]);
        return result($opId, 'APPLIED', null, null, (int) $locked['server_version'] + 1);
    }

    // Persistida en PENDIENTE: edición libre o cancelación (solo sin pagos
    // aplicados — garantizado: un pago la habría derivado a PARCIAL/PAGADA).
    if ($newStatus === 'CANCELADA') {
        $applied = toCents((string) ($prevData['appliedAmount'] ?? '0'));
        if ($applied > 0) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'Solo se puede cancelar una cuenta sin pagos aplicados.',
                (int) $locked['server_version'], serverState($locked));
        }
        $payload['status'] = 'CANCELADA';
        updateEntity($pdo, 'collection_account', $entityId, $locked, $payload,
            'CANCELADA', $locked['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'TRANSITION',
            ['from' => 'PENDIENTE', 'to' => 'CANCELADA']);
        return result($opId, 'APPLIED', null, null, (int) $locked['server_version'] + 1);
    }
    if ($newStatus !== 'PENDIENTE') {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            'PARCIAL y PAGADA las deriva el servidor desde los pagos aplicados.',
            (int) $locked['server_version'], serverState($locked));
    }
    $payload['appliedAmount'] = $prevData['appliedAmount'] ?? '0.00';
    $payload['balance'] = (string) ($payload['net'] ?? $prevData['net'] ?? '0');
    // F7: identidad, titular y creación se normalizan también en edición
    // PENDIENTE desde los valores persistidos.
    $payload['id'] = $prevData['id'] ?? $entityId;
    $payload['userId'] = $prevData['userId'] ?? $userId;
    $payload['createdAt'] = $prevData['createdAt'] ?? ($payload['createdAt'] ?? null);
    updateEntity($pdo, 'collection_account', $entityId, $locked, $payload,
        'PENDIENTE', $locked['official_number']);
    audit($pdo, $userId, $deviceId, $opId, 'collection_account', $entityId, 'UPDATE',
        ['status' => 'PENDIENTE']);
    return result($opId, 'APPLIED', null, null, (int) $locked['server_version'] + 1);
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
    // asignación son la única vía que mueve su dinero. F7 añade el importe
    // efectivo (mutable solo vía ajustes), el acumulado reembolsado y el
    // destino genérico (Cuenta o Consolidado).
    $payload['appliedAmount'] = '0.00';
    $payload['unassignedAmount'] = fromCents($amount);
    $payload['effectiveAmount'] = (string) ($payload['amount'] ?? '0');
    $payload['refundedAmount'] = '0.00';
    $payload['annulled'] = false;
    $payload['assignedAccountId'] = null;
    $payload['assignedConsolidatedId'] = null;
    $payload['assignedTargetType'] = null;
    $payload['assignmentStatus'] = 'UNASSIGNED';
    // Identidad canónica en creación (revisión PR #15, M2): id del sobre y
    // titular autenticado pisan el payload; createdAt conserva el valor
    // declarado por el cliente o se completa con la hora del servidor.
    $payload = normalizeIdentityCreate($payload, $entityId, $userId);
    insertEntity($pdo, 'payment', $entityId, $userId, $payload, 'UNASSIGNED');
    audit($pdo, $userId, $deviceId, $opId, 'payment', $entityId, 'CREATE',
        ['amount' => (string) ($payload['amount'] ?? '')]);
    return result($opId, 'APPLIED', null, null, 1);
}

/**
 * Identidad canónica en creación (decisión F7, docs/03 §6): el `id` y el
 * `userId` del payload se reemplazan siempre por el entity_id del sobre y el
 * usuario autenticado — el payload jamás redefine la identidad ni el titular.
 * `createdAt` conserva el valor declarado por el cliente cuando existe (es el
 * instante de creación offline, un dato de negocio que el servidor no infiere
 * ni reescribe); solo si el cliente no lo envió se completa con la hora del
 * servidor. En edición se usa el valor persistido (ver rutas de UPDATE).
 */
function normalizeIdentityCreate(array $payload, string $entityId, string $userId): array
{
    $payload['id'] = $entityId;
    $payload['userId'] = $userId;
    if (trim((string) ($payload['createdAt'] ?? '')) === '') {
        $payload['createdAt'] = nowUtc();
    }
    return $payload;
}

/**
 * Normaliza el payload persistido del Pago con los campos F7; los Pagos
 * creados antes de F7 no los traen y se derivan de sus valores F6.
 * Devuelve [$p, effectiveCents, appliedCents, refundedCents, unassignedCents].
 */
function paymentFinancials(array $p): array
{
    $effective = toCents((string) ($p['effectiveAmount'] ?? $p['amount'] ?? '0'));
    $applied = toCents((string) ($p['appliedAmount'] ?? '0'));
    $refunded = toCents((string) ($p['refundedAmount'] ?? '0'));
    $unassigned = $effective - $applied - $refunded;
    return [$effective, $applied, $refunded, $unassigned];
}

/** ¿La Cuenta pertenece a un Consolidado no cancelado del usuario? */
function accountInActiveConsolidated(PDO $pdo, string $userId, string $accountId,
                                     string $selfId = ''): bool
{
    $stmt = $pdo->prepare(
        "SELECT id, payload FROM entities
         WHERE entity_type = 'consolidated_collection' AND user_id = ? AND status != 'CANCELADA'"
    );
    $stmt->execute([$userId]);
    foreach ($stmt->fetchAll() as $row) {
        if ($row['id'] === $selfId) {
            continue;
        }
        $data = json_decode($row['payload'], true) ?: [];
        if (in_array($accountId, array_map('strval', (array) ($data['accountIds'] ?? [])), true)) {
            return true;
        }
    }
    return false;
}

/**
 * Aplica el delta aplicado a una fila destino (Cuenta o Consolidado) con
 * estado derivado: balance = total − aplicado; el Consolidado usa
 * totalAmount como base, la Cuenta su net. Devuelve el nuevo status.
 */
function applyTargetDelta(array &$data, int $appliedDelta, int $totalCents,
                          string $currentStatus): string
{
    $applied = toCents((string) ($data['appliedAmount'] ?? '0')) + $appliedDelta;
    $data['appliedAmount'] = fromCents($applied);
    $data['balance'] = fromCents($totalCents - $applied);
    $newStatus = deriveCollectionStatus(
        $currentStatus === 'CANCELADA' ? 'CANCELADA' : 'PENDIENTE', $applied, $totalCents);
    $data['status'] = $newStatus;
    return $newStatus;
}

/**
 * F6+F7 — Evento de asignación de Pago (append-only, auditado, atómico):
 * assign aplica solo hasta el saldo pendiente (el excedente queda
 * UNASSIGNED), unassign retira la aplicación vigente y reassign libera de
 * la origen aplicando al destino — todo en una transacción con las filas
 * de Pago y destino bloqueadas FOR UPDATE.
 *
 * F7 (docs/03 §6): el destino es una Cuenta (`targetType=account`) o un
 * Consolidado (`targetType=consolidated`), el dinero disponible descuenta
 * el acumulado reembolsado (reserva compartida asignación/reembolso), un
 * Pago anulado es terminal, una Cuenta miembro de un Consolidado activo no
 * admite asignación directa y el dinero aplicado a una Cuenta miembro no
 * puede moverse mientras el Consolidado siga activo (congelado).
 */
function applyPaymentAssignment(PDO $pdo, string $userId, string $deviceId, string $opId,
                                string $entityId, array $payload, ?array $current): array
{
    if ($current !== null) {
        // Append-only: mismo entity_id + campos estables → dedupe; cualquier
        // diferencia es una mutación fabricada del historial financiero.
        $prev = json_decode($current['payload'], true) ?: [];
        foreach (['paymentId', 'action', 'targetType', 'accountId', 'consolidatedId',
                  'fromAccountId', 'fromConsolidatedId', 'reason'] as $f) {
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

    // Destino del evento: Cuenta (compatibilidad F6 cuando falta el tipo)
    // o Consolidado.
    $targetType = (string) ($payload['targetType'] ?? '');
    $accountId = (string) ($payload['accountId'] ?? '');
    $consolidatedId = (string) ($payload['consolidatedId'] ?? '');
    if ($targetType === '') {
        $targetType = $consolidatedId !== '' ? 'consolidated' : 'account';
    }
    if (!in_array($targetType, PAYMENT_TARGET_TYPES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Destino de asignación desconocido.');
    }
    $targetId = $targetType === 'account' ? $accountId : $consolidatedId;
    if ($targetId === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El evento requiere la cuenta o el consolidado de destino.');
    }
    $targetEntityType = $targetType === 'account' ? 'collection_account' : 'consolidated_collection';

    // Origen de una reasignación: se parsea antes de los bloqueos para
    // rechazar lo inválido sin esperar y para ordenar el conjunto de filas.
    $fromType = null;
    $fromId = null;
    $fromEntityType = null;
    if ($action === 'reassign') {
        $fromAccountId = (string) ($payload['fromAccountId'] ?? '');
        $fromConsolidatedId = (string) ($payload['fromConsolidatedId'] ?? '');
        $fromType = $fromConsolidatedId !== '' ? 'consolidated' : 'account';
        $fromId = $fromType === 'account' ? $fromAccountId : $fromConsolidatedId;
        if ($fromId === '') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La reasignación requiere el origen desde el que liberar.');
        }
        if ($fromType === $targetType && $fromId === $targetId) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Origen y destino son el mismo.');
        }
        $fromEntityType = $fromType === 'account' ? 'collection_account' : 'consolidated_collection';
    }

    // Bloqueos atómicos: el Pago primero y después el conjunto
    // destino+origen en orden canónico (entity_type, id) — serializa las
    // operaciones que compiten por las mismas Cuentas contra las altas y
    // ediciones del Consolidado (que bloquean a sus miembros ordenadas) y
    // evita interbloqueos entre reasignaciones en sentidos opuestos. Los
    // chequeos de membresía se ejecutan solo después, ya con locks propios,
    // y bajo READ COMMITTED observan el estado vigente del servidor.
    $payment = entityRowForUpdate($pdo, 'payment', (string) ($payload['paymentId'] ?? ''));
    if ($payment === null || $payment['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'El pago referenciado no existe.');
    }
    $lockSet = [[$targetEntityType, $targetId]];
    if ($fromEntityType !== null) {
        $lockSet[] = [$fromEntityType, $fromId];
    }
    usort($lockSet, fn(array $a, array $b) => strcmp($a[0] . "\0" . $a[1], $b[0] . "\0" . $b[1]));
    $lockedRows = [];
    foreach ($lockSet as [$lockType, $lockId]) {
        $lockedRows[$lockType . ':' . $lockId] = entityRowForUpdate($pdo, $lockType, $lockId);
    }
    $target = $lockedRows[$targetEntityType . ':' . $targetId];
    if ($target === null) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'El destino referenciado no existe.');
    }
    if ($target['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'El destino pertenece a otra cuenta.');
    }
    $fromRow = null;
    $fromData = null;
    if ($fromEntityType !== null) {
        $fromRow = $lockedRows[$fromEntityType . ':' . $fromId];
        if ($fromRow === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', 'El origen no existe.');
        }
        if ($fromRow['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY', 'El origen pertenece a otra cuenta.');
        }
        $fromData = json_decode($fromRow['payload'], true) ?: [];
    }
    $p = json_decode($payment['payload'], true) ?: [];
    $t = json_decode($target['payload'], true) ?: [];

    if (($t['clientId'] ?? '') !== ($p['clientId'] ?? '')) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El destino pertenece a un cliente distinto del pago.');
    }

    [$pEffective, $pApplied, $pRefunded, $pUnassigned] = paymentFinancials($p);
    if (($p['annulled'] ?? false) === true) {
        // ANNULMENT es terminal: tras él se rechazan asignaciones,
        // retiros y reasignaciones (docs/03 §6).
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            'El pago está anulado; es terminal y no admite más movimientos.');
    }
    // Destino vigente del Pago (Pagos F6 solo conocen `assignedAccountId`).
    $pAssignedType = $p['assignedTargetType'] ?? (($p['assignedAccountId'] ?? null) !== null ? 'account' : null);
    $pAssignedId = $pAssignedType === 'consolidated'
        ? ($p['assignedConsolidatedId'] ?? null)
        : ($p['assignedAccountId'] ?? null);

    $tStatus = (string) $target['status'];
    $tBalance = toCents((string) ($t['balance'] ?? '0'));
    $tTotal = $targetType === 'account'
        ? toCents((string) ($t['net'] ?? '0'))
        : toCents((string) ($t['totalAmount'] ?? '0'));
    $receivableStatuses = $targetType === 'account'
        ? COLLECTION_ACCOUNT_RECEIVABLE : CONSOLIDATED_RECEIVABLE;
    $targetReceivable = in_array($tStatus, $receivableStatuses, true) && $tBalance > 0;
    // Anti doble cobro: una Cuenta miembro de un Consolidado activo no
    // admite asignación directa (docs/03 §6).
    $memberOfConsolidated = $targetType === 'account'
        && accountInActiveConsolidated($pdo, $userId, $targetId);
    $targetReceivable = $targetReceivable && !$memberOfConsolidated;

    // El dinero aplicado a una Cuenta miembro de un Consolidado activo
    // queda congelado: no puede retirarse ni reasignarse mientras el
    // Consolidado conserve sus saldos (el Consolidado tomó ese saldo).
    $currentIsMember = $pAssignedType === 'account' && $pAssignedId !== null
        && accountInActiveConsolidated($pdo, $userId, (string) $pAssignedId);
    if ($currentIsMember && in_array($action, ['unassign', 'reassign'], true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El dinero aplicado pertenece a una cuenta dentro de un consolidado activo; '
            . 'retírala del consolidado antes de mover sus fondos.');
    }

    $appliedNow = 0;
    $releasedNow = 0;

    if ($action === 'assign') {
        if ($pAssignedId !== null
            && ($pAssignedType !== $targetType || $pAssignedId !== $targetId)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago ya está aplicado a otro destino; usa reasignar.');
        }
        if ($pUnassigned <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago no tiene dinero sin asignar.');
        }
        if (!$targetReceivable) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                $memberOfConsolidated
                    ? 'La cuenta pertenece a un consolidado activo; asigna al consolidado.'
                    : 'El destino no puede recibir pagos (sin saldo, pagado o cancelado).');
        }
        // Solo lo necesario: min(solicitado, remanente del pago, saldo).
        $appliedNow = min($requested, $pUnassigned, $tBalance);
        if ($appliedNow <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La asignación no aplicó importe alguno.');
        }
    } elseif ($action === 'unassign') {
        if ($pAssignedId === null || $pAssignedType !== $targetType
            || $pAssignedId !== $targetId || $pApplied <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago no tiene dinero aplicado a este destino.');
        }
        $releasedNow = $pApplied;
    } else { // reassign — origen ya bloqueado en el conjunto canónico
        if ($pAssignedType !== $fromType
            || $pAssignedId !== $fromId || $pApplied <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El pago no está aplicado al origen indicado.');
        }
        if (!$targetReceivable) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                $memberOfConsolidated
                    ? 'La cuenta destino pertenece a un consolidado activo.'
                    : 'El destino no puede recibir pagos.');
        }
        $releasedNow = $pApplied;
        $appliedNow = min($releasedNow, $tBalance);
    }

    // Actualización derivada del Pago (siempre un solo destino vigente).
    $newApplied = match ($action) {
        'assign' => $pApplied + $appliedNow,
        'unassign' => 0,
        default => $appliedNow,
    };
    $newUnassigned = $pEffective - $newApplied - $pRefunded;
    $p['appliedAmount'] = fromCents($newApplied);
    $p['unassignedAmount'] = fromCents($newUnassigned);
    $p['assignedTargetType'] = $newApplied > 0 ? $targetType : null;
    $p['assignedAccountId'] = $newApplied > 0 && $targetType === 'account' ? $targetId : null;
    $p['assignedConsolidatedId'] = $newApplied > 0 && $targetType === 'consolidated' ? $targetId : null;
    $p['assignmentStatus'] = deriveAssignmentStatus($newApplied, $newUnassigned);
    $p['effectiveAmount'] = fromCents($pEffective);
    $p['refundedAmount'] = fromCents($pRefunded);
    updateEntity($pdo, 'payment', (string) $payload['paymentId'], $payment, $p, $p['assignmentStatus']);

    // Destino afectado con estado derivado recalculado por el servidor.
    $tDelta = $appliedNow - ($action === 'unassign' ? $releasedNow : 0);
    $tNewStatus = applyTargetDelta($t, $tDelta, $tTotal, $tStatus);
    updateEntity($pdo, $targetEntityType, $targetId, $target, $t, $tNewStatus);

    if ($fromRow !== null) {
        $fTotal = $fromType === 'account'
            ? toCents((string) ($fromData['net'] ?? '0'))
            : toCents((string) ($fromData['totalAmount'] ?? '0'));
        $fStatus = applyTargetDelta($fromData, -$releasedNow, $fTotal, (string) $fromRow['status']);
        $fromEntityType = $fromType === 'account' ? 'collection_account' : 'consolidated_collection';
        updateEntity($pdo, $fromEntityType, $fromId, $fromRow, $fromData, $fStatus);
    }

    // El evento persiste los importes autoritativos del servidor.
    $payload['targetType'] = $targetType;
    $payload['accountId'] = $targetType === 'account' ? $targetId : null;
    $payload['consolidatedId'] = $targetType === 'consolidated' ? $targetId : null;
    $payload['amount'] = fromCents($appliedNow);
    $payload['releasedAmount'] = fromCents($releasedNow);
    $payload = normalizeIdentityCreate($payload, $entityId, $userId);
    insertEntity($pdo, 'payment_assignment', $entityId, $userId, $payload);
    audit($pdo, $userId, $deviceId, $opId, 'payment_assignment', $entityId,
        'PAYMENT_' . strtoupper($action),
        ['payment_id' => (string) $payload['paymentId'],
         'target_type' => $targetType, 'target_id' => $targetId,
         'from_type' => $fromType, 'from_id' => $fromId,
         'applied' => fromCents($appliedNow), 'released' => fromCents($releasedNow),
         'reason' => (string) ($payload['reason'] ?? '')]);
    return result($opId, 'APPLIED', null, null, 1);
}

/**
 * F7 — Consolidado de Cuentas de Cobro (docs/03 §6): agrupa ≥2 Cuentas no
 * canceladas con saldo pendiente positivo, mismo Cliente y moneda (los
 * Perfiles pueden diferir; cada Cuenta conserva su snapshot). Nace
 * PENDIENTE con número oficial `CONS-AAAAMM-NNN` en la primera aceptación
 * (idempotente sobre el entity_id); PARCIAL/PAGADA las deriva el servidor
 * desde los eventos de asignación; CANCELADA solo sin pagos aplicados y
 * libera las Cuentas. La membresía es editable solo en PENDIENTE sin
 * pagos. Todo se valida contra datos persistidos y las filas miembro se
 * bloquean FOR UPDATE.
 */
function applyConsolidatedCollection(PDO $pdo, string $userId, string $deviceId, string $opId,
                                     string $entityId, array $payload, ?array $current,
                                     int $base): array
{
    $newStatus = (string) ($payload['status'] ?? '');
    if (!in_array($newStatus, CONSOLIDATED_STATUSES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Estado de consolidado desconocido.');
    }
    $accountIds = array_values(array_unique(array_filter(
        array_map('strval', (array) ($payload['accountIds'] ?? [])), 'strlen')));
    if (count($accountIds) < 2) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El consolidado requiere al menos dos cuentas distintas.');
    }

    if ($current !== null) {
        if ($base !== (int) $current['server_version']) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'El consolidado cambió desde la versión conocida por el dispositivo.',
                (int) $current['server_version'], serverState($current));
        }
    } elseif ($base !== 0) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'La entidad no existe en el servidor pero la base no es cero.', 0, null);
    }

    $oldStatus = $current === null ? null : (string) $current['status'];
    $prevData = $current === null ? [] : (json_decode($current['payload'], true) ?: []);

    // Tras pagos aplicados o cancelado el Consolidado es inmutable salvo
    // `archived`; el estado derivado lo gobierna el servidor. Las Cuentas
    // declaradas no se revalidan aquí: la membresía ya quedó fijada y la
    // comparación estructural impide alterarla. La fila propia se bloquea y
    // la base/estado se revalidan con la versión bloqueada — la lectura
    // inicial del despacho pudo quedar obsoleta durante la espera.
    if ($oldStatus !== null && in_array($oldStatus, ['PARCIAL', 'PAGADA', 'CANCELADA'], true)) {
        $locked = entityRowForUpdate($pdo, 'consolidated_collection', $entityId);
        if ($locked === null || $base !== (int) $locked['server_version']) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'El consolidado cambió desde la versión conocida por el dispositivo.',
                (int) ($locked['server_version'] ?? $current['server_version']),
                $locked === null ? serverState($current) : serverState($locked));
        }
        $oldStatus = (string) $locked['status'];
        $prevData = json_decode($locked['payload'], true) ?: [];
        if ($newStatus !== $oldStatus) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                "El consolidado está $oldStatus y no admite la transición declarada.",
                (int) $locked['server_version'], serverState($locked));
        }
        foreach (['clientId', 'currency', 'provisionalNumber', 'clientNameSnapshot'] as $f) {
            if (($prevData[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                    'El consolidado con pagos aplicados (o cancelado) es inmutable; solo puede archivarse.',
                    (int) $locked['server_version'], serverState($locked));
            }
        }
        $normSorted = function ($v) {
            $l = array_values(array_map('strval', (array) ($v ?? [])));
            sort($l);
            return $l;
        };
        if ($normSorted($prevData['accountIds'] ?? []) != $normSorted($payload['accountIds'] ?? [])) {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'El consolidado con pagos aplicados (o cancelado) es inmutable; solo puede archivarse.',
                (int) $locked['server_version'], serverState($locked));
        }
        $payload['status'] = $oldStatus;
        $payload['accountIds'] = $prevData['accountIds'] ?? [];
        $payload['members'] = $prevData['members'] ?? [];
        $payload['totalAmount'] = $prevData['totalAmount'] ?? '0.00';
        $payload['appliedAmount'] = $prevData['appliedAmount'] ?? '0.00';
        $payload['balance'] = $prevData['balance'] ?? '0.00';
        $payload['id'] = $prevData['id'] ?? $entityId;
        $payload['userId'] = $prevData['userId'] ?? $userId;
        $payload['createdAt'] = $prevData['createdAt'] ?? ($payload['createdAt'] ?? null);
        updateEntity($pdo, 'consolidated_collection', $entityId, $locked, $payload,
            $oldStatus, $locked['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'consolidated_collection', $entityId, 'UPDATE',
            ['archived' => (bool) ($payload['archived'] ?? false)]);
        return result($opId, 'APPLIED', null, null, (int) $locked['server_version'] + 1);
    }

    // Persistido en PENDIENTE y destino CANCELADA: solo sin pagos aplicados
    // (garantizado: un pago lo derivaría a PARCIAL/PAGADA). La cancelación
    // libera las Cuentas conservando la membresía histórica del documento.
    // La fila propia se bloquea y se revalida la base con ella.
    if ($oldStatus === 'PENDIENTE' && $newStatus === 'CANCELADA') {
        $locked = entityRowForUpdate($pdo, 'consolidated_collection', $entityId);
        if ($locked === null || $base !== (int) $locked['server_version']) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'El consolidado cambió desde la versión conocida por el dispositivo.',
                (int) ($locked['server_version'] ?? $current['server_version']),
                $locked === null ? serverState($current) : serverState($locked));
        }
        $prevData = json_decode($locked['payload'], true) ?: [];
        $payload['status'] = 'CANCELADA';
        $payload['accountIds'] = $prevData['accountIds'] ?? [];
        $payload['members'] = $prevData['members'] ?? [];
        $payload['totalAmount'] = $prevData['totalAmount'] ?? '0.00';
        $payload['appliedAmount'] = $prevData['appliedAmount'] ?? '0.00';
        $payload['balance'] = $prevData['balance'] ?? '0.00';
        $payload['id'] = $prevData['id'] ?? $entityId;
        $payload['userId'] = $prevData['userId'] ?? $userId;
        $payload['createdAt'] = $prevData['createdAt'] ?? ($payload['createdAt'] ?? null);
        updateEntity($pdo, 'consolidated_collection', $entityId, $locked, $payload,
            'CANCELADA', $locked['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'consolidated_collection', $entityId, 'TRANSITION',
            ['from' => 'PENDIENTE', 'to' => 'CANCELADA']);
        return result($opId, 'APPLIED', null, null, (int) $locked['server_version'] + 1);
    }
    if ($newStatus !== 'PENDIENTE') {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            'PARCIAL y PAGADA las deriva el servidor desde los pagos aplicados.');
    }

    // Creación y edición en PENDIENTE: las filas miembro se bloquean
    // FOR UPDATE en orden estable (serializa contra altas/ediciones de
    // otros Consolidados y contra ediciones de la propia Cuenta, evitando
    // interbloqueos); la fila propia del Consolidado se bloquea después —
    // orden canónico collection_account antes que consolidated_collection —
    // y la base/estado se revalidan con la versión bloqueada. La elegibilidad
    // se valida con datos persistidos vigentes: no cancelada, saldo > 0,
    // mismo Cliente y moneda, sin doble vínculo.
    sort($accountIds);
    $members = [];
    foreach ($accountIds as $accountId) {
        $row = entityRowForUpdate($pdo, 'collection_account', $accountId);
        if ($row === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', "Falta la cuenta $accountId referenciada.");
        }
        if ($row['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
                'La cuenta referenciada pertenece a otra cuenta.');
        }
        $members[$accountId] = $row;
    }

    if ($current !== null) {
        $lockedSelf = entityRowForUpdate($pdo, 'consolidated_collection', $entityId);
        if ($lockedSelf === null || $base !== (int) $lockedSelf['server_version']) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'El consolidado cambió desde la versión conocida por el dispositivo.',
                (int) ($lockedSelf['server_version'] ?? $current['server_version']),
                $lockedSelf === null ? serverState($current) : serverState($lockedSelf));
        }
        if ((string) $lockedSelf['status'] !== 'PENDIENTE') {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'El consolidado ya no está pendiente y su membresía no puede editarse.',
                (int) $lockedSelf['server_version'], serverState($lockedSelf));
        }
        $prevData = json_decode($lockedSelf['payload'], true) ?: [];
    }

    $totalCents = 0;
    $memberSnapshots = [];
    $currency = (string) ($payload['currency'] ?? '');
    foreach ($members as $accountId => $row) {
        $d = json_decode($row['payload'], true) ?: [];
        if ((string) $row['status'] === 'CANCELADA') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cuenta está cancelada y no puede pertenecer al consolidado.');
        }
        $bal = toCents((string) ($d['balance'] ?? '0'));
        if ($bal <= 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cuenta no tiene saldo pendiente para consolidar.');
        }
        if (($d['clientId'] ?? '') !== (string) ($payload['clientId'] ?? '')) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cuenta pertenece a un cliente distinto del consolidado.');
        }
        if ($currency === '' || ($d['currency'] ?? '') !== $currency) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cuenta usa una moneda distinta del consolidado.');
        }
        if (accountInActiveConsolidated($pdo, $userId, $accountId, $entityId)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La cuenta ya pertenece a otro consolidado activo.');
        }
        $totalCents += $bal;
        $memberSnapshots[] = [
            'accountId' => $accountId,
            'number' => $row['official_number'] ?? ($d['provisionalNumber'] ?? ''),
            'net' => (string) ($d['net'] ?? '0'),
            'balance' => fromCents($bal),
        ];
    }

    if ($current === null) {
        $payload['accountIds'] = $accountIds;
        $payload['members'] = $memberSnapshots;
        $payload['totalAmount'] = fromCents($totalCents);
        $payload['appliedAmount'] = '0.00';
        $payload['balance'] = fromCents($totalCents);
        $payload['status'] = 'PENDIENTE';
        $payload = normalizeIdentityCreate($payload, $entityId, $userId);
        $official = ensureOfficialNumber($pdo, $userId, $entityId, 'CONS');
        insertEntity($pdo, 'consolidated_collection', $entityId, $userId, $payload, 'PENDIENTE', $official);
        audit($pdo, $userId, $deviceId, $opId, 'consolidated_collection', $entityId, 'CREATE',
            ['status' => 'PENDIENTE', 'accounts' => count($accountIds)]);
        return result($opId, 'APPLIED', null, null, 1, null, $official);
    }

    // Edición en PENDIENTE: la membresía se recalcula desde los saldos
    // persistidos actuales de las Cuentas elegidas; identidad, titular y
    // creación se restauran desde la fila bloqueada del Consolidado.
    $payload['accountIds'] = $accountIds;
    $payload['members'] = $memberSnapshots;
    $payload['totalAmount'] = fromCents($totalCents);
    $payload['appliedAmount'] = '0.00';
    $payload['balance'] = fromCents($totalCents);
    $payload['status'] = 'PENDIENTE';
    $payload['id'] = $prevData['id'] ?? $entityId;
    $payload['userId'] = $prevData['userId'] ?? $userId;
    $payload['createdAt'] = $prevData['createdAt'] ?? ($payload['createdAt'] ?? null);
    updateEntity($pdo, 'consolidated_collection', $entityId, $lockedSelf, $payload,
        'PENDIENTE', $lockedSelf['official_number']);
    audit($pdo, $userId, $deviceId, $opId, 'consolidated_collection', $entityId, 'UPDATE',
        ['accounts' => count($accountIds)]);
    return result($opId, 'APPLIED', null, null, (int) $lockedSelf['server_version'] + 1);
}

/**
 * F7 — Ajuste de Pago (`payment_adjustment`, append-only, docs/03 §6):
 * `CORRECTION` fija un nuevo importe efectivo — al alza el incremento
 * queda UNASSIGNED; a la baja consume primero lo UNASSIGNED y luego libera
 * la asignación vigente (la aplicación más reciente es la única vigente,
 * LIFO) — siempre dentro del piso del acumulado reembolsado. `ANNULMENT`
 * es terminal: una sola vez, libera atómicamente la aplicación vigente y
 * fija el efectivo al acumulado reembolsado (cero si no hubo). Todo en la
 * transacción de la operación con la fila del Pago FOR UPDATE.
 */
function applyPaymentAdjustment(PDO $pdo, string $userId, string $deviceId, string $opId,
                                string $entityId, array $payload, ?array $current): array
{
    if ($current !== null) {
        // Idempotencia de entidad: replay con campos estables → dedupe.
        $prev = json_decode($current['payload'], true) ?: [];
        foreach (['paymentId', 'kind', 'reason', 'newEffectiveAmount'] as $f) {
            if (($prev[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'IMMUTABLE_FINANCIAL',
                    'El ajuste es un evento append-only y no admite edición.');
            }
        }
        return result($opId, 'APPLIED', null, null, (int) $current['server_version']);
    }

    $kind = (string) ($payload['kind'] ?? '');
    if (!in_array($kind, PAYMENT_ADJUSTMENT_KINDS, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Tipo de ajuste desconocido.');
    }
    if (trim((string) ($payload['reason'] ?? '')) === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'El ajuste requiere un motivo.');
    }

    $payment = entityRowForUpdate($pdo, 'payment', (string) ($payload['paymentId'] ?? ''));
    if ($payment === null || $payment['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'El pago referenciado no existe.');
    }
    $p = json_decode($payment['payload'], true) ?: [];
    if (($p['annulled'] ?? false) === true) {
        // Terminal: ni una segunda anulación ni una corrección posterior.
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            'El pago ya está anulado; el ajuste es una operación posterior y se rechaza.');
    }

    [$pEffective, $pApplied, $pRefunded, ] = paymentFinancials($p);
    $releasedNow = 0;
    $releasedFromType = null;
    $releasedFromId = null;
    $newEffective = $pEffective;
    $newApplied = $pApplied;

    if ($kind === 'CORRECTION') {
        $target = toCents((string) ($payload['newEffectiveAmount'] ?? ''));
        if ($target < 0 || $target === $pEffective) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La corrección requiere un nuevo importe efectivo válido y distinto.');
        }
        // Piso: el efectivo nunca queda por debajo de lo ya reembolsado.
        if ($target < $pRefunded) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'El importe corregido no puede quedar por debajo de lo ya reembolsado.');
        }
        $newEffective = $target;
        $delta = $pEffective - $target;
        if ($delta > 0) {
            // A la baja: primero lo UNASSIGNED, luego la aplicación vigente.
            $free = $pEffective - $pApplied - $pRefunded;
            $releaseNeeded = $delta - max(0, $free);
            if ($releaseNeeded > 0) {
                $releasedNow = $releaseNeeded;
                $newApplied = $pApplied - $releaseNeeded;
                $releasedFromType = $p['assignedTargetType']
                    ?? (($p['assignedAccountId'] ?? null) !== null ? 'account' : null);
                $releasedFromId = $releasedFromType === 'consolidated'
                    ? ($p['assignedConsolidatedId'] ?? null)
                    : ($p['assignedAccountId'] ?? null);
            }
        }
        // Al alza el incremento queda UNASSIGNED: applied no cambia.
    } else { // ANNULMENT
        // Libera toda la aplicación vigente y fija el efectivo al piso de
        // reembolsados (cero si no hubo reembolsos).
        if ($pApplied > 0) {
            $releasedNow = $pApplied;
            $releasedFromType = $p['assignedTargetType']
                ?? (($p['assignedAccountId'] ?? null) !== null ? 'account' : null);
            $releasedFromId = $releasedFromType === 'consolidated'
                ? ($p['assignedConsolidatedId'] ?? null)
                : ($p['assignedAccountId'] ?? null);
        }
        $newEffective = $pRefunded;
        $newApplied = 0;
    }

    // Libera el destino vigente dentro de la misma transacción. La fila se
    // bloquea ANTES del chequeo de membresía: serializa la decisión contra
    // el alta/edición del Consolidado (que bloquea a sus miembros) y el
    // chequeo observa el estado vigente.
    if ($releasedNow > 0 && $releasedFromId !== null) {
        $fromEntityType = $releasedFromType === 'account'
            ? 'collection_account' : 'consolidated_collection';
        $fromRow = entityRowForUpdate($pdo, $fromEntityType, (string) $releasedFromId);
        if ($fromRow === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY', 'El destino aplicado no existe.');
        }
        if ($fromRow['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
                'El destino aplicado pertenece a otra cuenta.');
        }
        if ($releasedFromType === 'account'
            && accountInActiveConsolidated($pdo, $userId, (string) $releasedFromId)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'La operación liberaría fondos de una cuenta dentro de un consolidado activo; '
                . 'retírala del consolidado antes de ajustar el pago.');
        }
        $fromData = json_decode($fromRow['payload'], true) ?: [];
        $fTotal = $releasedFromType === 'account'
            ? toCents((string) ($fromData['net'] ?? '0'))
            : toCents((string) ($fromData['totalAmount'] ?? '0'));
        $fStatus = applyTargetDelta($fromData, -$releasedNow, $fTotal, (string) $fromRow['status']);
        updateEntity($pdo, $fromEntityType, (string) $releasedFromId, $fromRow, $fromData, $fStatus);
    }

    $newUnassigned = $newEffective - $newApplied - $pRefunded;
    $p['effectiveAmount'] = fromCents($newEffective);
    $p['appliedAmount'] = fromCents($newApplied);
    $p['unassignedAmount'] = fromCents($newUnassigned);
    if ($newApplied <= 0) {
        $p['assignedTargetType'] = null;
        $p['assignedAccountId'] = null;
        $p['assignedConsolidatedId'] = null;
    }
    $p['assignmentStatus'] = deriveAssignmentStatus($newApplied, $newUnassigned);
    if ($kind === 'ANNULMENT') {
        $p['annulled'] = true;
    }
    updateEntity($pdo, 'payment', (string) $payload['paymentId'], $payment, $p,
        $p['assignmentStatus']);

    $payload['effectiveBefore'] = fromCents($pEffective);
    $payload['effectiveAfter'] = fromCents($newEffective);
    $payload['releasedAmount'] = fromCents($releasedNow);
    $payload['releasedFromType'] = $releasedFromType;
    $payload['releasedFromId'] = $releasedFromId;
    $payload = normalizeIdentityCreate($payload, $entityId, $userId);
    insertEntity($pdo, 'payment_adjustment', $entityId, $userId, $payload);
    audit($pdo, $userId, $deviceId, $opId, 'payment_adjustment', $entityId,
        $kind === 'CORRECTION' ? 'PAYMENT_CORRECTION' : 'PAYMENT_ANNULMENT',
        ['payment_id' => (string) $payload['paymentId'],
         'effective_before' => fromCents($pEffective),
         'effective_after' => fromCents($newEffective),
         'released' => fromCents($releasedNow),
         'reason' => (string) ($payload['reason'] ?? '')]);
    return result($opId, 'APPLIED', null, null, 1);
}

/**
 * F7 — Reembolso (`payment_refund`, append-only, docs/03 §6): movimiento
 * nuevo vinculado al Pago; nunca edita ni elimina el Pago ni los eventos
 * previos. Solo hasta el remanente disponible — la misma reserva que las
 * asignaciones: efectivo − aplicado − reembolsado — de modo que para
 * reembolsar dinero aplicado antes debe liberarse con una operación
 * auditada. Irreversible: no existe reversión aprobada.
 */
function applyPaymentRefund(PDO $pdo, string $userId, string $deviceId, string $opId,
                            string $entityId, array $payload, ?array $current): array
{
    if ($current !== null) {
        $prev = json_decode($current['payload'], true) ?: [];
        foreach (['paymentId', 'amount', 'refundedAtEpochDay', 'collectionMethodId', 'reason'] as $f) {
            if (($prev[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'IMMUTABLE_FINANCIAL',
                    'El reembolso es un evento append-only y no admite edición.');
            }
        }
        return result($opId, 'APPLIED', null, null, (int) $current['server_version']);
    }

    $amount = toCents((string) ($payload['amount'] ?? ''));
    if ($amount <= 0) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El importe del reembolso debe ser un decimal positivo.');
    }
    if (trim((string) ($payload['reason'] ?? '')) === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'El reembolso requiere un motivo.');
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

    $payment = entityRowForUpdate($pdo, 'payment', (string) ($payload['paymentId'] ?? ''));
    if ($payment === null || $payment['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'El pago referenciado no existe.');
    }
    $p = json_decode($payment['payload'], true) ?: [];
    if (($p['annulled'] ?? false) === true) {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            'El pago está anulado; es terminal y no admite reembolsos.');
    }

    [$pEffective, $pApplied, $pRefunded, $pUnassigned] = paymentFinancials($p);
    if ($amount > $pUnassigned) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El reembolso excede el remanente disponible del pago.');
    }

    $p['refundedAmount'] = fromCents($pRefunded + $amount);
    $p['unassignedAmount'] = fromCents($pUnassigned - $amount);
    $p['effectiveAmount'] = fromCents($pEffective);
    $p['appliedAmount'] = fromCents($pApplied);
    $p['assignmentStatus'] = deriveAssignmentStatus($pApplied, $pUnassigned - $amount);
    updateEntity($pdo, 'payment', (string) $payload['paymentId'], $payment, $p,
        $p['assignmentStatus']);

    insertEntity($pdo, 'payment_refund', $entityId, $userId,
        normalizeIdentityCreate($payload, $entityId, $userId));
    audit($pdo, $userId, $deviceId, $opId, 'payment_refund', $entityId, 'PAYMENT_REFUND',
        ['payment_id' => (string) $payload['paymentId'],
         'amount' => fromCents($amount),
         'reason' => (string) ($payload['reason'] ?? '')]);
    return result($opId, 'APPLIED', null, null, 1);
}

/**
 * F8 — Material del catálogo global del Usuario/Titular (docs/03 §6):
 * nombre y unidad obligatorios, descripción opcional, archivado sin
 * efectos de stock. Entidad documental: concurrencia optimista por
 * base_server_version con ambas copias preservadas en conflicto.
 */
function applyMaterial(PDO $pdo, string $userId, string $deviceId, string $opId,
                       string $entityId, array $payload, ?array $current, int $base): array
{
    if (trim((string) ($payload['name'] ?? '')) === ''
        || trim((string) ($payload['unit'] ?? '')) === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El material requiere nombre y unidad.');
    }
    if ($current === null) {
        if ($base !== 0) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'La entidad no existe en el servidor pero la base no es cero.', 0, null);
        }
        $payload = normalizeIdentityCreate($payload, $entityId, $userId);
        insertEntity($pdo, 'material', $entityId, $userId, $payload);
        audit($pdo, $userId, $deviceId, $opId, 'material', $entityId, 'CREATE', []);
        return result($opId, 'APPLIED', null, null, 1);
    }
    if ($base !== (int) $current['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'El material cambió desde la versión conocida por el dispositivo.',
            (int) $current['server_version'], serverState($current));
    }
    $prev = json_decode($current['payload'], true) ?: [];
    $payload['id'] = $prev['id'] ?? $entityId;
    $payload['userId'] = $prev['userId'] ?? $userId;
    $payload['createdAt'] = $prev['createdAt'] ?? ($payload['createdAt'] ?? null);
    updateEntity($pdo, 'material', $entityId, $current, $payload);
    audit($pdo, $userId, $deviceId, $opId, 'material', $entityId, 'UPDATE',
        ['archived' => (bool) ($payload['archived'] ?? false)]);
    return result($opId, 'APPLIED', null, null, (int) $current['server_version'] + 1);
}

/**
 * F8 — Compra documental (docs/03 §6): nace en BORRADOR sin movimientos;
 * la confirmación exige fecha y al menos una línea válida y registra
 * entradas; la edición en CONFIRMADA es por diferencia con trazabilidad y
 * ANULADA es terminal e irreversible (solo `archived` puede cambiar). Los
 * movimientos de origen COMPRA los deriva SIEMPRE el servidor en la misma
 * transacción de la Compra — nunca se aceptan como operaciones del cliente:
 * así transición, movimientos y saldo proyectado confirman o revierten
 * juntos. El total y la moneda los deriva el servidor; el número oficial
 * `COM-AAAAMM-NNN` se asigna de forma idempotente en el primer registro
 * aceptado. Las líneas fotografían el nombre del material persistido para
 * que el historial no cambie con ediciones posteriores del catálogo.
 */
function applyPurchase(PDO $pdo, string $userId, string $deviceId, string $opId,
                       string $entityId, array $payload, ?array $current,
                       int $base): array
{
    $newStatus = (string) ($payload['status'] ?? '');
    if (!in_array($newStatus, PURCHASE_STATUSES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Estado de compra desconocido.');
    }
    // La fecha es un mínimo aprobado: requerida en cualquier estado.
    $purchaseDate = $payload['purchaseDateEpochDay'] ?? null;
    if (!is_numeric($purchaseDate) || (int) $purchaseDate <= 0) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'La fecha de la compra es obligatoria.');
    }
    $itemsRaw = $payload['items'] ?? [];
    if (!is_array($itemsRaw)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'Las líneas de la compra deben ser un arreglo.');
    }

    // Moneda global del titular: el servidor la fija, no el cliente.
    $userRow = entityRow($pdo, 'user', $userId);
    $currency = $userRow === null
        ? 'COP'
        : (string) ((json_decode($userRow['payload'], true) ?: [])['mainCurrency'] ?? 'COP');
    if ($currency === '') {
        $currency = 'COP';
    }

    // Líneas validadas con datos persistidos: el material existe y es del
    // titular; cantidad positiva, unidad y precio unitario requeridos;
    // condición de stock NUEVO/USADO (NUEVO por omisión). El nombre del
    // material queda fotografiado desde la fila persistida (trazabilidad
    // histórica); el total de línea y el total del documento se derivan
    // aquí — nunca se confía en los importes declarados.
    $normItems = [];
    $seenItemIds = [];
    $totalCents = 0;
    foreach (array_values($itemsRaw) as $i => $raw) {
        $it = is_array($raw) ? $raw : [];
        $matId = trim((string) ($it['materialId'] ?? ''));
        if ($matId === '') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Cada línea de la compra debe identificar un material.');
        }
        $mat = entityRow($pdo, 'material', $matId);
        if ($mat === null) {
            return result($opId, 'REJECTED', 'DEPENDENCY',
                'El material de una línea no existe en el servidor.');
        }
        if ($mat['user_id'] !== $userId) {
            return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
                'Una línea referencia un material de otra cuenta.');
        }
        $qty = decNorm((string) ($it['quantity'] ?? ''));
        if (!isPositiveDecimal($qty)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Cada línea requiere una cantidad positiva.');
        }
        $unit = trim((string) ($it['unit'] ?? ''));
        if ($unit === '') {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Cada línea requiere una unidad.');
        }
        $price = trim((string) ($it['unitPrice'] ?? ''));
        if (toCents($price) < 0) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Cada línea requiere un precio unitario válido (≥ 0).');
        }
        $condition = strtoupper(trim((string) ($it['condition'] ?? 'NUEVO')));
        if ($condition === '') {
            $condition = 'NUEVO';
        }
        if (!in_array($condition, MATERIAL_CONDITIONS, true)) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Condición de línea desconocida (NUEVO/USADO).');
        }
        $lineCents = purchaseLineCents($qty, $price);
        $totalCents += $lineCents;
        // Id de línea íntegro dentro del documento: el cliente sustituye la
        // lista completa al guardar y las claves por id; un id en blanco o
        // repetido colapsaría líneas al re-descargar. En blanco se genera
        // uno; un duplicado fabricado se rechaza entero.
        $itemId = trim((string) ($it['id'] ?? ''));
        if ($itemId === '') {
            $itemId = uuid();
        }
        if (isset($seenItemIds[$itemId])) {
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Las líneas de la compra no pueden repetir su identificador.');
        }
        $seenItemIds[$itemId] = true;
        $matData = json_decode($mat['payload'], true) ?: [];
        $normItems[] = [
            'id' => $itemId,
            'position' => $i + 1,
            'materialId' => $matId,
            'materialNameSnapshot' => (string) ($matData['name'] ?? ''),
            'quantity' => $qty,
            'unit' => $unit,
            'unitPrice' => fromCents(toCents($price)),
            'lineTotal' => fromCents($lineCents),
            'condition' => $condition,
            'createdAt' => $it['createdAt'] ?? null,
        ];
    }
    if ($newStatus === 'CONFIRMADA' && $normItems === []) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'Confirmar la compra requiere al menos una línea.');
    }

    $payload['items'] = $normItems;
    $payload['currency'] = $currency;
    $payload['totalAmount'] = fromCents($totalCents);
    $payload['purchaseDateEpochDay'] = (int) $purchaseDate;

    if ($current === null) {
        if ($base !== 0) {
            return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
                'La entidad no existe en el servidor pero la base no es cero.', 0, null);
        }
        if ($newStatus !== 'BORRADOR') {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'La compra nace en BORRADOR; confirmarla es una transición posterior.');
        }
        $payload['status'] = 'BORRADOR';
        $payload = normalizeIdentityCreate($payload, $entityId, $userId);
        // El primer registro aceptado fija el número oficial COM-AAAAMM-NNN.
        $official = ensureOfficialNumber($pdo, $userId, $entityId, 'COM');
        insertEntity($pdo, 'purchase', $entityId, $userId, $payload, 'BORRADOR', $official);
        audit($pdo, $userId, $deviceId, $opId, 'purchase', $entityId, 'CREATE',
            ['lines' => count($normItems), 'official' => $official]);
        return result($opId, 'APPLIED', null, null, 1, null, $official);
    }

    // La fila propia se bloquea y la base/estado se revalidan con la
    // versión bloqueada — la lectura inicial del despacho pudo quedar
    // obsoleta durante la espera de otro push concurrente.
    $locked = entityRowForUpdate($pdo, 'purchase', $entityId);
    if ($locked === null || $base !== (int) $locked['server_version']) {
        return result($opId, 'CONFLICT', 'STALE_BASE_VERSION',
            'La compra cambió desde la versión conocida por el dispositivo.',
            (int) ($locked['server_version'] ?? $current['server_version']),
            $locked === null ? serverState($current) : serverState($locked));
    }
    $oldStatus = (string) $locked['status'];
    $prevData = json_decode($locked['payload'], true) ?: [];

    // ANULADA es terminal e irreversible: solo `archived` puede cambiar;
    // todo lo demás se restaura desde lo persistido.
    if ($oldStatus === 'ANULADA') {
        if ($newStatus !== 'ANULADA') {
            return result($opId, 'REJECTED', 'INVALID_TRANSITION',
                'La compra está ANULADA (terminal) y no puede reactivarse.',
                (int) $locked['server_version'], serverState($locked));
        }
        $archived = (bool) ($payload['archived'] ?? false);
        $payload = $prevData;
        $payload['status'] = 'ANULADA';
        $payload['archived'] = $archived;
        updateEntity($pdo, 'purchase', $entityId, $locked, $payload,
            'ANULADA', $locked['official_number']);
        audit($pdo, $userId, $deviceId, $opId, 'purchase', $entityId, 'UPDATE',
            ['archived' => $archived]);
        return result($opId, 'APPLIED', null, null, (int) $locked['server_version'] + 1);
    }

    if (!validPurchaseTransition($oldStatus, $newStatus)) {
        return result($opId, 'REJECTED', 'INVALID_TRANSITION',
            "Transición no permitida $oldStatus → $newStatus; estado actual $oldStatus.",
            (int) $locked['server_version'], serverState($locked));
    }

    // Movimientos derivados por el servidor (misma transacción): la
    // confirmación registra entradas por línea; la edición en CONFIRMADA
    // calcula la diferencia neta por (material, condición) contra las
    // líneas persistidas — positiva PURCHASE_IN, negativa ADJUSTMENT_OUT —;
    // la anulación de una CONFIRMADA invierte cada línea. Los Materiales
    // afectados se bloquean en orden de id (orden canónico: purchase ya
    // bloqueada → material → inventory_stock), serializando las operaciones
    // de stock sobre el mismo Material.
    $prevItems = $prevData['items'] ?? [];
    $derived = [];
    if ($oldStatus === 'BORRADOR' && $newStatus === 'CONFIRMADA') {
        // Los locks de Material se adquieren en orden canónico de id (igual
        // que en edición/anulación); el orden declarado de las líneas solo
        // se conserva en el documento persistido, no en la adquisición de
        // bloqueos — dos confirmaciones concurrentes con líneas en orden
        // opuesto serializan sin interbloqueo.
        $ordered = $normItems;
        usort($ordered, fn ($a, $b) =>
            strcmp((string) ($a['materialId'] ?? ''), (string) ($b['materialId'] ?? ''))
                ?: strcmp((string) ($a['condition'] ?? ''), (string) ($b['condition'] ?? '')));
        foreach ($ordered as $it) {
            $derived[] = purchaseMovementPayload($it, 'PURCHASE_IN', $it['quantity'],
                $entityId, 'Entrada por compra confirmada');
        }
    } elseif ($oldStatus === 'CONFIRMADA' && $newStatus === 'CONFIRMADA') {
        $byKey = [];
        foreach ($prevItems as $it) {
            $key = ($it['materialId'] ?? '') . '|' . ($it['condition'] ?? 'NUEVO');
            $byKey[$key]['prev'] = bcadd($byKey[$key]['prev'] ?? '0', (string) ($it['quantity'] ?? '0'), 6);
            $byKey[$key]['item'] = $it;
        }
        foreach ($normItems as $it) {
            $key = ($it['materialId'] ?? '') . '|' . ($it['condition'] ?? 'NUEVO');
            $byKey[$key]['next'] = bcadd($byKey[$key]['next'] ?? '0', (string) ($it['quantity'] ?? '0'), 6);
            $byKey[$key]['item'] = $it;
        }
        ksort($byKey);
        foreach ($byKey as $group) {
            $delta = bcsub($group['next'] ?? '0', $group['prev'] ?? '0', 6);
            if (bccomp($delta, '0', 6) === 0) {
                continue;
            }
            $isIn = bccomp($delta, '0', 6) > 0;
            $derived[] = purchaseMovementPayload($group['item'],
                $isIn ? 'PURCHASE_IN' : 'ADJUSTMENT_OUT', decNorm($isIn ? $delta : bcsub('0', $delta, 6)),
                $entityId, 'Edición diferencial de compra confirmada');
        }
    } elseif ($oldStatus === 'CONFIRMADA' && $newStatus === 'ANULADA') {
        $inverse = $prevItems;
        usort($inverse, fn ($a, $b) => strcmp((string) ($a['materialId'] ?? ''), (string) ($b['materialId'] ?? '')));
        foreach ($inverse as $it) {
            $derived[] = purchaseMovementPayload($it, 'ADJUSTMENT_OUT', $it['quantity'] ?? '0',
                $entityId, 'Movimiento inverso por anulación de compra');
        }
    }

    // La anulación es terminal y conserva el documento exactamente como lo
    // persistió el servidor — las mismas líneas, instantáneas y total que
    // originan los movimientos inversos. Una operación que pretenda anular
    // cambiando líneas o datos del documento se rechaza entera: la Compra
    // queda en su estado previo. Solo `archived` puede variar (acción
    // aprobada separada de la anulación).
    if ($newStatus === 'ANULADA' && !purchaseDocMatches($payload, $prevData)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'La anulación conserva el documento persistido; no admite cambios de líneas ni datos.',
            (int) $locked['server_version'], serverState($locked));
    }
    if ($newStatus === 'ANULADA') {
        $archived = (bool) ($payload['archived'] ?? false);
        $payload = $prevData;
        // La columna pasa a ANULADA y el payload debe contar la misma
        // historia: el cliente reconstruye su estado desde payload.status.
        $payload['status'] = 'ANULADA';
        $payload['archived'] = $archived;
        $normItems = $prevItems; // trazabilidad del audit
    }

    // Identidad, titular, numeración provisional y creación se restauran
    // desde lo persistido; líneas/total/moneda ya fueron normalizados.
    $payload['id'] = $prevData['id'] ?? $entityId;
    $payload['userId'] = $prevData['userId'] ?? $userId;
    $payload['provisionalNumber'] = $prevData['provisionalNumber'] ?? ($payload['provisionalNumber'] ?? '');
    $payload['createdAt'] = $prevData['createdAt'] ?? ($payload['createdAt'] ?? null);
    updateEntity($pdo, 'purchase', $entityId, $locked, $payload,
        $newStatus, $locked['official_number']);
    audit($pdo, $userId, $deviceId, $opId, 'purchase', $entityId,
        $oldStatus === $newStatus ? 'UPDATE' : 'TRANSITION',
        ['from' => $oldStatus, 'to' => $newStatus, 'lines' => count($normItems),
         'movements' => count($derived)]);
    foreach ($derived as $mov) {
        recordMovementAndStock($pdo, $userId, $deviceId, $opId, $mov);
    }
    return result($opId, 'APPLIED', null, null,
        (int) $locked['server_version'] + 1, null, $locked['official_number']);
}

/**
 * Payload de un movimiento derivado de una línea de Compra (entrada,
 * diferencial o inverso): mismo formato que `inventory_movement`.
 */
function purchaseMovementPayload(array $item, string $type, string $qty,
                                 string $purchaseId, string $note): array
{
    $nowMs = (int) round(microtime(true) * 1000);
    return [
        'id' => uuid(),
        'materialId' => (string) ($item['materialId'] ?? ''),
        'condition' => (string) ($item['condition'] ?? 'NUEVO'),
        'movementType' => $type,
        'origin' => 'COMPRA',
        'quantity' => decNorm((string) $qty),
        'unit' => (string) ($item['unit'] ?? ''),
        'unitCost' => (string) ($item['unitPrice'] ?? ''),
        'purchaseId' => $purchaseId,
        'jobId' => null,
        'jobMaterialId' => null,
        'note' => $note,
        'occurredAt' => $nowMs,
        'createdAt' => $nowMs,
        'updatedAt' => $nowMs,
    ];
}

/**
 * ¿El documento entrante coincide con lo persistido en los campos de
 * contenido? Se usa para la anulación: la operación terminal conserva las
 * líneas e instantáneas que originaron los movimientos, así que líneas y
 * datos deben ser los mismos (campos derivados como snapshots/totales e
 * identidad se omiten — el servidor los restaura desde lo persistido).
 */
function purchaseDocMatches(array $payload, array $prev): bool
{
    foreach (['purchaseDateEpochDay', 'supplierPlace', 'reference', 'notes'] as $f) {
        if ((string) ($payload[$f] ?? '') !== (string) ($prev[$f] ?? '')) {
            return false;
        }
    }
    $items = $payload['items'] ?? [];
    $prevItems = $prev['items'] ?? [];
    if (!is_array($items) || count($items) !== count($prevItems)) {
        return false;
    }
    foreach (array_values($prevItems) as $i => $p) {
        $it = is_array($items[$i] ?? null) ? $items[$i] : [];
        if ((string) ($it['materialId'] ?? '') !== (string) ($p['materialId'] ?? '')
            || (string) ($it['unit'] ?? '') !== (string) ($p['unit'] ?? '')
            || strtoupper((string) ($it['condition'] ?? 'NUEVO'))
                !== strtoupper((string) ($p['condition'] ?? 'NUEVO'))
            || decNorm((string) ($it['quantity'] ?? '')) !== decNorm((string) ($p['quantity'] ?? ''))
            || toCents((string) ($it['unitPrice'] ?? '')) !== toCents((string) ($p['unitPrice'] ?? ''))
        ) {
            return false;
        }
    }
    return true;
}

/**
 * Registra un movimiento append-only y actualiza el saldo proyectado en la
 * misma transacción: bloquea la fila del Material (serializa todas las
 * operaciones de stock sobre él) y luego la proyección `inventory_stock`
 * (orden canónico material → stock). Devuelve el id del movimiento.
 */
function recordMovementAndStock(PDO $pdo, string $userId, string $deviceId,
                                ?string $opId, array $payload): string
{
    $materialId = (string) $payload['materialId'];
    $entityId = (string) $payload['id'];
    $mat = entityRowForUpdate($pdo, 'material', $materialId);
    if ($mat === null || $mat['user_id'] !== $userId) {
        throw new RuntimeException('Movimiento derivado sobre material inexistente o ajeno.');
    }
    insertEntity($pdo, 'inventory_movement', $entityId, $userId, $payload,
        (string) $payload['movementType']);
    audit($pdo, $userId, $deviceId, $opId, 'inventory_movement', $entityId,
        'INVENTORY_MOVEMENT',
        ['material_id' => $materialId, 'type' => $payload['movementType'],
         'origin' => $payload['origin'], 'condition' => $payload['condition'],
         'quantity' => $payload['quantity']]);

    $stock = entityRowForUpdate($pdo, 'inventory_stock', $materialId);
    $sd = $stock === null
        ? ['id' => $materialId, 'materialId' => $materialId, 'userId' => $userId,
           'stockNew' => '0', 'stockUsed' => '0']
        : (json_decode($stock['payload'], true) ?: []);
    $field = $payload['condition'] === 'NUEVO' ? 'stockNew' : 'stockUsed';
    $delta = in_array($payload['movementType'], MOVEMENT_IN_TYPES, true)
        ? $payload['quantity'] : '-' . $payload['quantity'];
    $sd[$field] = decNorm(bcadd((string) ($sd[$field] ?? '0'), $delta, 6));
    $sd['id'] = $materialId;
    $sd['materialId'] = $materialId;
    $sd['userId'] = $userId;
    $sd['updatedAt'] = $payload['updatedAt'] ?? ($payload['createdAt'] ?? null);
    if ($stock === null) {
        insertEntity($pdo, 'inventory_stock', $materialId, $userId, $sd);
    } else {
        updateEntity($pdo, 'inventory_stock', $materialId, $stock, $sd);
    }
    return $entityId;
}

/**
 * F8 — Movimiento de inventario (`inventory_movement`, append-only):
 * fuente de verdad del stock. Una vez aplicado no admite mutación — un
 * replay con campos estables idénticos se deduplica; con campos distintos
 * se rechaza IMMUTABLE_FINANCIAL. La fila del Material se bloquea FOR
 * UPDATE (serializa compras, consumos y ajustes concurrentes sobre el
 * mismo Material — orden canónico: material antes que inventory_stock) y
 * la proyección `inventory_stock` se actualiza en la misma transacción, de
 * modo que el saldo siempre puede reconstruirse desde los movimientos.
 * Stock negativo permitido: el inventario es control interno y nunca
 * bloquea la operación. Orígenes aceptados desde el cliente: TRABAJO
 * (consumo explícito), SOBRANTE y AJUSTE (manuales); los de origen COMPRA
 * los deriva el servidor dentro de la transacción de la Compra.
 */
function applyInventoryMovement(PDO $pdo, string $userId, string $deviceId, string $opId,
                                string $entityId, array $payload, ?array $current): array
{
    // Normalización previa al dedupe: el replay compara contra el payload
    // ya normalizado, así que las mismas reglas se aplican a lo entrante
    // antes de contrastar campos estables.
    $payload['materialId'] = trim((string) ($payload['materialId'] ?? ''));
    $payload['condition'] = strtoupper(trim((string) ($payload['condition'] ?? '')));
    $payload['quantity'] = decNorm((string) ($payload['quantity'] ?? ''));
    $unitCostRaw = trim((string) ($payload['unitCost'] ?? ''));
    $payload['unitCost'] = $unitCostRaw === '' ? null : fromCents(toCents($unitCostRaw));
    $payload['purchaseId'] = trim((string) ($payload['purchaseId'] ?? ''));
    $payload['jobId'] = trim((string) ($payload['jobId'] ?? ''));
    $payload['jobMaterialId'] = trim((string) ($payload['jobMaterialId'] ?? ''));
    $payload['note'] = trim((string) ($payload['note'] ?? ''));
    // `unit` se normaliza igual que en la escritura para que un replay con
    // solo espacios distintos deduplique en lugar de caer en inmutable.
    $payload['unit'] = trim((string) ($payload['unit'] ?? ''));
    if (($payload['occurredAt'] ?? null) === null) {
        $payload['occurredAt'] = $payload['createdAt'] ?? null;
    }

    if ($current !== null) {
        $prev = json_decode($current['payload'], true) ?: [];
        foreach (['materialId', 'condition', 'movementType', 'origin', 'quantity', 'unit',
                  'unitCost', 'purchaseId', 'jobId', 'jobMaterialId', 'note', 'occurredAt'] as $f) {
            if (($prev[$f] ?? null) !== ($payload[$f] ?? null)) {
                return result($opId, 'REJECTED', 'IMMUTABLE_FINANCIAL',
                    'El movimiento de inventario es append-only y no admite edición.');
            }
        }
        return result($opId, 'APPLIED', null, null, (int) $current['server_version']);
    }

    $materialId = $payload['materialId'];
    $mType = (string) ($payload['movementType'] ?? '');
    if (!in_array($mType, MOVEMENT_TYPES, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Tipo de movimiento desconocido.');
    }
    $origin = (string) ($payload['origin'] ?? '');
    if (!in_array($origin, MOVEMENT_ORIGINS, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR', 'Origen de movimiento desconocido.');
    }
    if (!in_array($payload['condition'], MATERIAL_CONDITIONS, true)) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'La condición de stock debe ser NUEVO o USADO.');
    }
    if (!isPositiveDecimal($payload['quantity'])) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El movimiento requiere una cantidad positiva.');
    }
    $unit = $payload['unit']; // ya normalizada antes del dedupe
    if ($unit === '') {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El movimiento requiere una unidad.');
    }
    if ($payload['unitCost'] !== null && toCents((string) $payload['unitCost']) < 0) {
        return result($opId, 'REJECTED', 'VALIDATION_ERROR',
            'El costo unitario histórico debe ser un importe válido.');
    }

    $purchaseId = $payload['purchaseId'];
    $jobId = $payload['jobId'];
    $jobMaterialId = $payload['jobMaterialId'];
    $note = $payload['note'];

    // Consistencia tipo ↔ origen y referencias validadas con datos
    // persistidos — nunca con datos declarados por el cliente.
    switch ($origin) {
        case 'COMPRA':
            // Los movimientos de compra (entradas, diferenciales e inversos)
            // los deriva el servidor dentro de la transacción de la Compra;
            // un cliente nunca los empuja directamente.
            return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                'Los movimientos de origen COMPRA los deriva el servidor junto a la compra.');
        case 'TRABAJO':
            if ($mType !== 'JOB_CONSUMPTION') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El consumo en Trabajo usa el tipo JOB_CONSUMPTION.');
            }
            if ($jobId === '' || $jobMaterialId === '') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El consumo en Trabajo requiere el trabajo y el registro de consumo.');
            }
            if ($purchaseId !== '') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'Un movimiento de TRABAJO no referencia compras.');
            }
            $job = entityRow($pdo, 'job', $jobId);
            if ($job === null) {
                return result($opId, 'REJECTED', 'DEPENDENCY', 'El trabajo referenciado no existe.');
            }
            if ($job['user_id'] !== $userId) {
                return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
                    'El trabajo referenciado pertenece a otra cuenta.');
            }
            // La fila del consumo se bloquea ANTES que la del Material
            // (orden canónico job_material → material → stock): serializa
            // todas las operaciones que declaran este jobMaterialId y hace
            // atómica la unicidad 1:1 — un segundo movimiento fabricado con
            // otro entity_id espera el lock y ve el ya aplicado.
            $jm = entityRowForUpdate($pdo, 'job_material', $jobMaterialId);
            if ($jm === null) {
                return result($opId, 'REJECTED', 'DEPENDENCY',
                    'El registro de consumo referenciado no existe.');
            }
            if ($jm['user_id'] !== $userId) {
                return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
                    'El registro de consumo pertenece a otra cuenta.');
            }
            $jmData = json_decode($jm['payload'], true) ?: [];
            if (($jmData['jobId'] ?? '') !== $jobId
                || ($jmData['materialId'] ?? '') !== $materialId) {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El registro de consumo no corresponde al trabajo y material declarados.');
            }
            // El movimiento debe ser el reflejo exacto del consumo
            // persistido: cantidad, unidad y condición no se declaran por
            // separado — se contrastan con la fila del Trabajo.
            if (decNorm((string) ($jmData['quantity'] ?? '')) !== $payload['quantity']
                || trim((string) ($jmData['unit'] ?? '')) !== $payload['unit']
                || strtoupper((string) ($jmData['condition'] ?? '')) !== $payload['condition']) {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El movimiento no coincide con la cantidad, unidad o condición del consumo registrado.');
            }
            // Unicidad 1:1 con el job_material: lectura de bloqueo (FOR
            // UPDATE = lectura vigente) bajo el lock ya retenido — un
            // segundo movimiento concurrente queda serializado por la fila
            // del consumo y encuentra el ya confirmado.
            $dup = $pdo->prepare(
                "SELECT id FROM entities WHERE entity_type = 'inventory_movement'
                  AND JSON_VALUE(payload, '$.jobMaterialId') = ? FOR UPDATE"
            );
            $dup->execute([$jobMaterialId]);
            if ($dup->fetch() !== false) {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El consumo vinculado ya tiene su movimiento de inventario registrado.');
            }
            break;
        case 'SOBRANTE':
            if ($mType !== 'ADJUSTMENT_IN') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El sobrante se registra como entrada ADJUSTMENT_IN.');
            }
            if ($note === '') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'La entrada manual de sobrante requiere una nota explicativa.');
            }
            if ($purchaseId !== '' || $jobMaterialId !== '') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'Un sobrante no referencia compras ni consumos.');
            }
            if ($jobId !== '') {
                $job = entityRow($pdo, 'job', $jobId);
                if ($job === null) {
                    return result($opId, 'REJECTED', 'DEPENDENCY',
                        'El trabajo referenciado por el sobrante no existe.');
                }
                if ($job['user_id'] !== $userId) {
                    return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
                        'El trabajo referenciado pertenece a otra cuenta.');
                }
            }
            break;
        default: // AJUSTE
            if (!in_array($mType, ['ADJUSTMENT_IN', 'ADJUSTMENT_OUT'], true)) {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El ajuste manual usa ADJUSTMENT_IN o ADJUSTMENT_OUT.');
            }
            if ($note === '') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'El ajuste de inventario requiere una nota explicativa.');
            }
            if ($purchaseId !== '' || $jobId !== '' || $jobMaterialId !== '') {
                return result($opId, 'REJECTED', 'VALIDATION_ERROR',
                    'Un ajuste manual no referencia otros documentos.');
            }
            break;
    }

    // Existencia y titular del Material se validan antes de bloquear;
    // recordMovementAndStock vuelve a verificarlas con el lock retenido.
    $matCheck = entityRow($pdo, 'material', $materialId);
    if ($matCheck === null) {
        return result($opId, 'REJECTED', 'DEPENDENCY', 'El material del movimiento no existe.');
    }
    if ($matCheck['user_id'] !== $userId) {
        return result($opId, 'REJECTED', 'FORBIDDEN_ENTITY',
            'El material del movimiento pertenece a otra cuenta.');
    }

    $payload['unit'] = $unit;
    $payload = normalizeIdentityCreate($payload, $entityId, $userId);
    recordMovementAndStock($pdo, $userId, $deviceId, $opId, $payload);
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
                has_version_conflict, status, payload
         FROM entities WHERE user_id = ? AND server_seq > ?
         ORDER BY server_seq ASC LIMIT 500'
    );
    $stmt->execute([$userId, $cursor]);
    $changes = [];
    $next = $cursor;
    foreach ($stmt->fetchAll() as $row) {
        $payload = json_decode($row['payload'], true);
        // Misma curación que serverState() (función común): la columna
        // `status` manda sobre el JSON persistido, de modo que una fila
        // escrita antes de la corrección (columna ANULADA / payload
        // CONFIRMADA) no resucite como confirmada en el cliente.
        if (is_array($payload)) {
            $payload = withAuthoritativeStatus($row, $payload);
        }
        $changes[] = [
            'entity_type' => $row['entity_type'],
            'entity_id' => $row['id'],
            'server_version' => (int) $row['server_version'],
            'server_seq' => (int) $row['server_seq'],
            'server_order' => $row['server_order'] === null ? null : (int) $row['server_order'],
            'official_number' => $row['official_number'],
            'has_version_conflict' => (int) $row['has_version_conflict'] === 1,
            'payload' => $payload,
        ];
        $next = max($next, (int) $row['server_seq']);
    }
    respond(200, ['changes' => $changes, 'next_cursor' => $next]);
}

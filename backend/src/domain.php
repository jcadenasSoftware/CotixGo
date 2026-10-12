<?php

declare(strict_types=1);

/**
 * Reglas de dominio del servidor para F3: matriz de transiciones de Cotización
 * (idéntica a la aprobada en docs/03 §6 y al código Android Domain.kt) y
 * numeración oficial provisional → oficial sobre el mismo entity_id.
 */

const QUOTE_STATUSES = ['BORRADOR', 'EMITIDA', 'APROBADA', 'RECHAZADA', 'VENCIDA', 'CANCELADA'];

/** Transiciones permitidas del servidor (validadas contra el estado persistido). */
const QUOTE_TRANSITIONS = [
    'BORRADOR'  => ['EMITIDA', 'CANCELADA'],
    'EMITIDA'   => ['APROBADA', 'RECHAZADA', 'CANCELADA', 'VENCIDA'],
    'RECHAZADA' => ['EMITIDA', 'CANCELADA'],
    'APROBADA'  => [],
    'VENCIDA'   => [],
    'CANCELADA' => [],
];

/** Estados que disparan la asignación del número oficial. */
const QUOTE_NUMBERED_STATUSES = ['EMITIDA', 'APROBADA'];

function validQuoteTransition(string $from, string $to): bool
{
    if ($from === $to) {
        return true; // edición sin cambio de estado
    }
    return in_array($to, QUOTE_TRANSITIONS[$from] ?? [], true);
}

/**
 * Asigna el número oficial a una Cotización la primera vez que el backend
 * acepta una versión EMITIDA/APROBADA. Idempotente por entity_id: si ya
 * existe, devuelve el asignado previamente.
 */
function ensureOfficialNumber(PDO $pdo, string $userId, string $entityId): ?string
{
    $stmt = $pdo->prepare('SELECT official_number FROM doc_numbers WHERE entity_id = ?');
    $stmt->execute([$entityId]);
    $row = $stmt->fetch();
    if ($row !== false) {
        return $row['official_number'];
    }

    $period = gmdate('Ym');
    // Secuencia global por usuario + tipo + período, atómica.
    $pdo->prepare(
        'INSERT INTO doc_number_sequences (user_id, doc_type, period, last_seq)
         VALUES (?, ?, ?, 1)
         ON DUPLICATE KEY UPDATE last_seq = last_seq + 1'
    )->execute([$userId, 'COT', $period]);
    $seq = (int) $pdo->query(
        "SELECT last_seq FROM doc_number_sequences
         WHERE user_id = " . $pdo->quote($userId) . " AND doc_type = 'COT' AND period = " . $pdo->quote($period)
    )->fetchColumn();

    $number = sprintf('COT-%s-%03d', $period, $seq);
    $pdo->prepare(
        'INSERT INTO doc_numbers (entity_id, user_id, official_number, assigned_at)
         VALUES (?, ?, ?, ?)'
    )->execute([$entityId, $userId, $number, nowUtc()]);
    return $number;
}

function audit(PDO $pdo, string $userId, ?string $deviceId, ?string $opId,
               ?string $entityType, ?string $entityId, string $action, ?array $detail): void
{
    $pdo->prepare(
        'INSERT INTO audit_events (user_id, device_id, operation_id, entity_type, entity_id, action, detail, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
    )->execute([
        $userId, $deviceId, $opId, $entityType, $entityId, $action,
        $detail === null ? null : json_encode($detail, JSON_UNESCAPED_UNICODE),
        nowUtc(),
    ]);
}

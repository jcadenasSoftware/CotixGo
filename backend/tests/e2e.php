<?php

declare(strict_types=1);

/**
 * Verificación e2e de la API F3 contra el servidor local (php -S).
 * Uso: php backend/tests/e2e.php [base_url]   (por defecto http://localhost:8080)
 * Datos exclusivamente sintéticos.
 */

$base = $argv[1] ?? 'http://localhost:8080';
$pass = 0;
$fail = 0;

function http(string $base, string $method, string $path, ?array $body = null, ?string $token = null): array
{
    $headers = "Content-Type: application/json\r\n";
    if ($token !== null) {
        $headers .= "Authorization: Bearer $token\r\n";
    }
    $ctx = stream_context_create(['http' => [
        'method' => $method,
        'header' => $headers,
        'content' => $body === null ? '' : json_encode($body),
        'ignore_errors' => true,
    ]]);
    $raw = @file_get_contents($base . $path, false, $ctx);
    $code = 0;
    foreach ($http_response_header ?? [] as $h) {
        if (preg_match('#HTTP/\S+\s+(\d+)#', $h, $m)) {
            $code = (int) $m[1];
        }
    }
    return [$code, $raw === false ? [] : (json_decode($raw, true) ?: [])];
}

function check(string $name, bool $cond, string $extra = ''): void
{
    global $pass, $fail;
    if ($cond) {
        $pass++;
        echo "PASS $name\n";
    } else {
        $fail++;
        echo "FAIL $name $extra\n";
    }
}

$run = substr(bin2hex(random_bytes(4)), 0, 6);
$email = "dev+$run@example.test";
$uid = fn(string $p) => sprintf('%s-4000-8000-8000-%012d', substr($p, 0, 8), $run ? crc32($run . $p) % 1000000000000 : 0);
$device = $uid('d0000000');
$u = fn(string $p) => $uid(str_pad($p, 8, '0'));

// --- auth ---
[$c, $r] = http($base, 'GET', '/health');
check('health', $c === 200 && ($r['status'] ?? '') === 'ok');

[$c, $reg] = http($base, 'POST', '/auth/register', ['email' => $email, 'password' => 'sintetico1', 'local_user_id' => $u('10000000')]);
check('register', $c === 200 && isset($reg['user_id'], $reg['token']));
$userId = $reg['user_id'];
$token = $reg['token'];

[$c, $dup] = http($base, 'POST', '/auth/register', ['email' => $email, 'password' => 'sintetico1']);
check('register_duplicate_ACCOUNT_EXISTS', $c === 409 && $dup['error']['code'] === 'ACCOUNT_EXISTS');

[$c, $bad] = http($base, 'POST', '/auth/login', ['email' => $email, 'password' => 'mala']);
check('login_wrong_INVALID_CREDENTIALS', $c === 401 && $bad['error']['code'] === 'INVALID_CREDENTIALS');

[$c, $log] = http($base, 'POST', '/auth/login', ['email' => $email, 'password' => 'sintetico1']);
check('login', $c === 200 && $log['user_id'] === $userId);

[$c, $rec] = http($base, 'POST', '/auth/recover', ['email' => $email]);
check('recover_local_outbox', $c === 200 && $rec['status'] === 'ok');

[$c] = http($base, 'GET', '/sync/pull?cursor=0');
check('pull_requires_auth', $c === 401);

// --- push maestras + documento ---
$ops = [];
$mk = function (string $id, string $type, string $et, array $payload, int $base = 0) use (&$ops) {
    $ops[] = ['operation_id' => $id, 'entity_type' => $et, 'entity_id' => $type,
              'type' => 'upsert', 'base_server_version' => $base, 'payload' => $payload];
};
$now = time() * 1000;
$mk($u('20000000'), $u('20000001'), 'profile', ['id' => $u('20000001'), 'userId' => $userId, 'businessName' => 'Perfil Sintético', 'createdAt' => $now, 'updatedAt' => $now]);
$mk($u('30000000'), $u('30000001'), 'client', ['id' => $u('30000001'), 'userId' => $userId, 'type' => 'EMPRESA', 'name' => 'Cliente Sintético', 'createdAt' => $now, 'updatedAt' => $now]);
$mk($u('40000000'), $u('40000001'), 'retention_config', ['id' => $u('40000001'), 'userId' => $userId, 'name' => 'Ret6', 'ratePercent' => '6', 'appliesTo' => 'SERVICIO', 'createdAt' => $now, 'updatedAt' => $now]);

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
$ok = $c === 200 && count($r['results'] ?? []) === 3 && array_column($r['results'], 'status') === ['APPLIED', 'APPLIED', 'APPLIED'];
check('push_masters', $ok, json_encode($r));

// dependencia faltante: quote antes del client? (client ya está; probamos servicio con profile inexistente)
$ops = [$ops[0]];
$ops[0]['operation_id'] = $u('50000000');
$ops[0]['entity_type'] = 'service';
$ops[0]['entity_id'] = $u('50000001');
$ops[0]['payload'] = ['id' => $u('50000001'), 'userId' => $userId, 'profileId' => $u('99999999'), 'name' => 'Svc', 'suggestedPrice' => '1', 'createdAt' => $now, 'updatedAt' => $now];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('dependency_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED' && $r['results'][0]['code'] === 'DEPENDENCY', json_encode($r));

// quote BORRADOR → version → EMITIDA con número oficial
$ops = [];
$quoteId = $u('60000001');
$mk($u('60000000'), $quoteId, 'quote', ['id' => $quoteId, 'userId' => $userId, 'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
    'provisionalNumber' => 'COT-PEND-TST1', 'status' => 'BORRADOR', 'issueDateEpochDay' => 21000, 'validityDays' => 15,
    'expiryDateEpochDay' => 21015, 'currency' => 'COP', 'currentVersion' => 1, 'emittedVersion' => 0,
    'gross' => '1400000.00', 'totalRetained' => '36000.00', 'net' => '1364000.00', 'createdAt' => $now, 'updatedAt' => $now]);
$mk($u('70000000'), $u('70000001'), 'quote_version', ['id' => $u('70000001'), 'quoteId' => $quoteId, 'version' => 1, 'snapshotJson' => '{"lines":2}', 'createdAt' => $now]);
$mk($u('80000000'), $quoteId, 'quote', ['id' => $quoteId, 'userId' => $userId, 'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
    'provisionalNumber' => 'COT-PEND-TST1', 'status' => 'EMITIDA', 'issueDateEpochDay' => 21000, 'validityDays' => 15,
    'expiryDateEpochDay' => 21015, 'currency' => 'COP', 'currentVersion' => 1, 'emittedVersion' => 1,
    'gross' => '1400000.00', 'totalRetained' => '36000.00', 'net' => '1364000.00', 'createdAt' => $now, 'updatedAt' => $now], 1);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('quote_flow_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED' && ($r['results'][1]['status'] ?? '') === 'APPLIED', json_encode($r));
$official = $r['results'][2]['official_number'] ?? null;
check('official_number_assigned', ($r['results'][2]['status'] ?? '') === 'APPLIED' && is_string($official) && str_starts_with($official, 'COT-'), json_encode($r));

// idempotencia: misma op repetida devuelve el mismo resultado
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$ops[2]]], $token);
check('idempotent_same_result', ($r['results'][0]['status'] ?? '') === 'APPLIED' && ($r['results'][0]['official_number'] ?? null) === $official, json_encode($r));

// mismo operation_id con payload distinto → IDEMPOTENCY_KEY_REUSED
$badOp = $ops[2];
$badOp['payload']['notes'] = 'cambiado';
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$badOp]], $token);
check('idempotency_key_reused', ($r['results'][0]['status'] ?? '') === 'REJECTED' && $r['results'][0]['code'] === 'IDEMPOTENCY_KEY_REUSED', json_encode($r));

// concurrencia: base_server_version obsoleto → CONFLICT con estado servidor
$stale = $ops[2];
$stale['operation_id'] = $u('90000000');
$stale['payload']['notes'] = 'desde base vieja';
$stale['base_server_version'] = 1;
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$stale]], $token);
check('stale_base_CONFLICT', ($r['results'][0]['status'] ?? '') === 'CONFLICT' && $r['results'][0]['code'] === 'STALE_BASE_VERSION'
    && isset($r['results'][0]['server_state']), json_encode($r));

// transición inválida (EMITIDA → BORRADOR) → REJECTED con estado actual
$badT = $ops[2];
$badT['operation_id'] = $u('a0000000');
$badT['payload']['status'] = 'BORRADOR';
$badT['base_server_version'] = 2;
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$badT]], $token);
check('invalid_transition_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED' && $r['results'][0]['code'] === 'INVALID_TRANSITION', json_encode($r));

// versión concurrente (otro "dispositivo" edita v1 con contenido distinto)
$ops = [['operation_id' => $u('b0000000'), 'entity_type' => 'quote_version', 'entity_id' => $u('b0000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('b0000001'), 'quoteId' => $quoteId, 'version' => 1, 'snapshotJson' => '{"lines":3}', 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('concurrent_version_flagged', ($r['results'][0]['status'] ?? '') === 'APPLIED' && ($r['results'][0]['code'] ?? '') === 'VERSION_CONFLICT', json_encode($r));

// emitir estando en conflicto → bloqueado (la quote ya está EMITIDA; probar APROBADA se permite por transición...
// CONFLICT_BLOCKED aplica solo cuando el estado actual NO es numerado; probamos el flag vía pull)
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
check('pull_all_changes', $c === 200 && count($r['changes'] ?? []) >= 6 && $r['next_cursor'] > 0, json_encode(array_column($r['changes'] ?? [], 'entity_type')));
$flagged = array_filter($r['changes'] ?? [], fn($x) => $x['entity_type'] === 'quote' && $x['has_version_conflict']);
check('conflict_flag_in_pull', count($flagged) === 1);

// resolución: nueva versión resolve → conflicto despejado
$ops = [['operation_id' => $u('c0000000'), 'entity_type' => 'quote_version', 'entity_id' => $u('c0000001'),
         'type' => 'resolve', 'base_server_version' => 0,
         'payload' => ['id' => $u('c0000001'), 'quoteId' => $quoteId, 'version' => 2, 'snapshotJson' => '{"lines":2,"resolved":true}', 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('resolve_conflict_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$flagged = array_filter($r['changes'] ?? [], fn($x) => $x['entity_type'] === 'quote' && $x['has_version_conflict']);
check('conflict_cleared', count($flagged) === 0);

// entidad de otra cuenta → FORBIDDEN_ENTITY
[$c, $reg2] = http($base, 'POST', '/auth/register', ['email' => "otro+$run@example.test", 'password' => 'sintetico1']);
$ops = [['operation_id' => $u('e0000000'), 'entity_type' => 'client', 'entity_id' => $u('30000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('30000001'), 'userId' => $reg2['user_id'], 'type' => 'EMPRESA', 'name' => 'Robo', 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('forbidden_entity', ($r['results'][0]['status'] ?? '') === 'REJECTED' && $r['results'][0]['code'] === 'FORBIDDEN_ENTITY', json_encode($r));

// --- correcciones de revisión F3 ---

// F1: el servidor adoptó el local_user_id del registro como identidad canónica.
check('register_adopts_local_user_id', $reg['user_id'] === $u('10000000'));

// F5: una referencia a entidad de OTRA cuenta también es FORBIDDEN_ENTITY.
$ops = [['operation_id' => $u('f0000000'), 'entity_type' => 'quote', 'entity_id' => $u('f0000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f0000001'), 'userId' => $reg2['user_id'], 'clientId' => $u('30000001'),
             'profileId' => $u('f0000002'), 'provisionalNumber' => 'COT-PEND-XX', 'status' => 'BORRADOR',
             'issueDateEpochDay' => 21000, 'validityDays' => 15, 'expiryDateEpochDay' => 21015,
             'currency' => 'COP', 'currentVersion' => 1, 'emittedVersion' => 0,
             'gross' => '1', 'totalRetained' => '0', 'net' => '1', 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('dependency_ownership_FORBIDDEN', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY', json_encode($r));
check('results_include_entity_id', ($r['results'][0]['entity_id'] ?? '') === $u('f0000001'), json_encode($r));

// F6: EMITIDA→APROBADA bloqueada mientras exista conflicto de versiones;
// tras resolver, la aprobación aplica y conserva el número oficial.
$q2 = $u('f1000001');
$ops = [
    ['operation_id' => $u('f1000000'), 'entity_type' => 'quote', 'entity_id' => $q2, 'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $q2, 'userId' => $userId, 'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
        'provisionalNumber' => 'COT-PEND-Q2', 'status' => 'BORRADOR', 'issueDateEpochDay' => 21000, 'validityDays' => 15,
        'expiryDateEpochDay' => 21015, 'currency' => 'COP', 'currentVersion' => 1, 'emittedVersion' => 0,
        'gross' => '10', 'totalRetained' => '0', 'net' => '10', 'createdAt' => $now, 'updatedAt' => $now]],
    ['operation_id' => $u('f2000000'), 'entity_type' => 'quote_version', 'entity_id' => $u('f2000001'), 'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $u('f2000001'), 'quoteId' => $q2, 'version' => 1, 'snapshotJson' => '{"q2":1}', 'createdAt' => $now]],
];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('q2_created', array_column($r['results'] ?? [], 'status') === ['APPLIED', 'APPLIED'], json_encode($r));

// emitir (sv=1 → 2) y luego versión concurrente (conflicto marcado, sv → 3)
$emitQ2 = $ops[0];
$emitQ2['operation_id'] = $u('f3000000');
$emitQ2['payload']['status'] = 'EMITIDA';
$emitQ2['payload']['emittedVersion'] = 1;
$emitQ2['base_server_version'] = 1;
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$emitQ2]], $token);
$official2 = $r['results'][0]['official_number'] ?? null;
check('q2_emitted', ($r['results'][0]['status'] ?? '') === 'APPLIED' && is_string($official2), json_encode($r));
$concQ2 = ['operation_id' => $u('f4000000'), 'entity_type' => 'quote_version', 'entity_id' => $u('f4000001'),
           'type' => 'upsert', 'base_server_version' => 0,
           'payload' => ['id' => $u('f4000001'), 'quoteId' => $q2, 'version' => 1, 'snapshotJson' => '{"q2":2}', 'createdAt' => $now]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$concQ2]], $token);
check('q2_version_conflict', ($r['results'][0]['code'] ?? '') === 'VERSION_CONFLICT', json_encode($r));

$approveQ2 = $emitQ2;
$approveQ2['operation_id'] = $u('f5000000');
$approveQ2['payload']['status'] = 'APROBADA';
$approveQ2['base_server_version'] = 3;
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$approveQ2]], $token);
check('approve_blocked_CONFLICT_BLOCKED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'CONFLICT_BLOCKED', json_encode($r));

// resolución como versión nueva → desbloquea y conserva snapshots anteriores
$resolveQ2 = ['operation_id' => $u('f6000000'), 'entity_type' => 'quote_version', 'entity_id' => $u('f6000001'),
              'type' => 'resolve', 'base_server_version' => 0,
              'payload' => ['id' => $u('f6000001'), 'quoteId' => $q2, 'version' => 2, 'snapshotJson' => '{"q2":1,"resolved":true}', 'createdAt' => $now]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$resolveQ2]], $token);
check('q2_resolve_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
// la resolución incrementa server_version del documento (3 → 4)
$approveQ2['operation_id'] = $u('f7000000');
$approveQ2['base_server_version'] = 4;
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => [$approveQ2]], $token);
check('approve_after_resolve_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
check('official_number_preserved', ($r['results'][0]['official_number'] ?? null) === $official2, json_encode($r));

// F8: la memorización idempotente se confirma con la mutación — la fila de
// processed_operations existe para cada operación definitiva (replay seguro
// tras caída del proceso: el servidor devuelve el resultado almacenado).
require_once __DIR__ . '/../src/bootstrap.php';
$pdo = db();
$memo = (int) $pdo->query('SELECT COUNT(*) FROM processed_operations WHERE operation_id = ' . $pdo->quote($u('80000000')))->fetchColumn();
check('idempotency_memo_committed', $memo === 1);
$memo2 = (int) $pdo->query('SELECT COUNT(*) FROM processed_operations WHERE operation_id = ' . $pdo->quote($u('f7000000')))->fetchColumn();
check('idempotency_memo_approve', $memo2 === 1);

// --- Regla de vencimiento (alcance aclarado en F3): →VENCIDA solo desde ---
// --- estados no terminales; APROBADA/CANCELADA permanecen terminales.   ---

$qPayload = fn(string $id, string $status, int $emitted = 0) => [
    'id' => $id, 'userId' => $userId, 'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
    'provisionalNumber' => 'COT-PEND-VX', 'status' => $status,
    'issueDateEpochDay' => 19000, 'validityDays' => 15, 'expiryDateEpochDay' => 19015,
    'currency' => 'COP', 'currentVersion' => 1, 'emittedVersion' => $emitted,
    'gross' => '10', 'totalRetained' => '0', 'net' => '10', 'createdAt' => $now, 'updatedAt' => $now];
$qop = fn(string $opId, string $id, string $status, int $base, int $emitted = 0) =>
    [['operation_id' => $opId, 'entity_type' => 'quote', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $base, 'payload' => $qPayload($id, $status, $emitted)]];

// BORRADOR→VENCIDA: la materialización del vencimiento se acepta y sincroniza.
$q3 = $u('d1000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d1000000'), $q3, 'BORRADOR', 0)], $token);
check('q3_borrador_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d2000000'), $q3, 'VENCIDA', 1)], $token);
check('borrador_to_vencida_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === null, json_encode($r));

// EMITIDA→VENCIDA conserva el número oficial ($quoteId quedó EMITIDA, sv=4).
$vencE = $qop($u('d3000000'), $quoteId, 'VENCIDA', 4, 1);
$vencE[0]['payload'] = array_merge($vencE[0]['payload'], [
    'provisionalNumber' => 'COT-PEND-TST1', 'issueDateEpochDay' => 21000,
    'expiryDateEpochDay' => 21015, 'gross' => '1400000.00', 'totalRetained' => '36000.00',
    'net' => '1364000.00']);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $vencE], $token);
check('emitida_to_vencida_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $official, json_encode($r));

// RECHAZADA→VENCIDA: flujo completo create→emit→reject→expire, número intacto.
$q4 = $u('d4000001');
http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d4000000'), $q4, 'BORRADOR', 0)], $token);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d5000000'), $q4, 'EMITIDA', 1, 1)], $token);
$official4 = $r['results'][0]['official_number'] ?? null;
check('q4_emitted', ($r['results'][0]['status'] ?? '') === 'APPLIED' && is_string($official4), json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d6000000'), $q4, 'RECHAZADA', 2, 1)], $token);
check('q4_rechazada', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d7000000'), $q4, 'VENCIDA', 3, 1)], $token);
check('rechazada_to_vencida_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $official4, json_encode($r));

// APROBADA→VENCIDA rechazada: $q2 quedó APROBADA (sv=5); el estado terminal se conserva.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d8000000'), $q2, 'VENCIDA', 5, 1)], $token);
check('aprobada_to_vencida_INVALID', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// CANCELADA→VENCIDA rechazada: mismo tratamiento terminal.
$q5 = $u('d9000001');
http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('d9000000'), $q5, 'BORRADOR', 0)], $token);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('da000000'), $q5, 'CANCELADA', 1)], $token);
check('q5_cancelada', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $qop($u('db000000'), $q5, 'VENCIDA', 2)], $token);
check('cancelada_to_vencida_INVALID', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// La misma regla consultada desde "otro dispositivo" (pull autoritativo):
// vencidas materializadas en VENCIDA; terminales conservan su estado.
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId = [];
foreach ($r['changes'] ?? [] as $ch) {
    $p = $ch['payload'];
    $byId[$ch['entity_id']] = (is_array($p) ? $p : json_decode($p, true))['status'] ?? null;
}
check('pull_vencidas_from_nonterminal', ($byId[$q3] ?? '') === 'VENCIDA'
    && ($byId[$quoteId] ?? '') === 'VENCIDA' && ($byId[$q4] ?? '') === 'VENCIDA',
    json_encode([$byId[$q3] ?? '?', $byId[$quoteId] ?? '?', $byId[$q4] ?? '?']));
check('pull_terminales_preserved', ($byId[$q2] ?? '') === 'APROBADA'
    && ($byId[$q5] ?? '') === 'CANCELADA', json_encode([$byId[$q2] ?? '?', $byId[$q5] ?? '?']));

// =====================  F4 — Trabajos  =====================

$jPayload = fn(string $id, string $status, string $addr = '') => [
    'id' => $id, 'userId' => $userId, 'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
    'provisionalNumber' => 'TR-PEND-' . strtoupper(substr($id, 0, 4)), 'status' => $status,
    'startDateEpochDay' => 21000, 'estimatedDateEpochDay' => 21007,
    'executionAddress' => $addr, 'notes' => 'trabajo sintético',
    'clientNameSnapshot' => 'Cliente Sintético', 'createdAt' => $now, 'updatedAt' => $now];
$jop = fn(string $opId, string $id, string $status, int $base, string $addr = '') =>
    [['operation_id' => $opId, 'entity_type' => 'job', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $base, 'payload' => $jPayload($id, $status, $addr)]];

// BORRADOR sin dirección: permitido.
$job1 = $u('e1000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e1000000'), $job1, 'BORRADOR', 0)], $token);
check('job_borrador_sin_direccion', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// →PROGRAMADO sin dirección: rechazado por regla aprobada.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e2000000'), $job1, 'PROGRAMADO', 1)], $token);
check('job_programado_sin_direccion_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// →PROGRAMADO con dirección: aplica y asigna TR-AAAAMM-NNN.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e3000000'), $job1, 'PROGRAMADO', 1, 'Calle 1 #2-3')], $token);
$tr1 = $r['results'][0]['official_number'] ?? null;
check('job_programado_TR_assigned', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && is_string($tr1) && str_starts_with($tr1, 'TR-'), json_encode($r));

// PROGRAMADO→BORRADOR (reversa aprobada): conserva el número ya asignado.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e4000000'), $job1, 'BORRADOR', 2, 'Calle 1 #2-3')], $token);
check('job_programado_to_borrador', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $tr1, json_encode($r));

// Creación directa en EN_PROCESO: numerada en esa aceptación.
$job2 = $u('e5000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e5000000'), $job2, 'EN_PROCESO', 0, 'Calle 4 #5-6')], $token);
$tr2 = $r['results'][0]['official_number'] ?? null;
check('job_directo_en_proceso_TR', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && is_string($tr2) && str_starts_with($tr2, 'TR-') && $tr2 !== $tr1, json_encode($r));

// EN_PROCESO→PROGRAMADO no existe (no aprobada) → INVALID_TRANSITION.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e6000000'), $job2, 'PROGRAMADO', 1, 'Calle 4 #5-6')], $token);
check('job_enproceso_to_programado_INVALID', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// EN_PROCESO→ENTREGADO aplica; terminal rechaza edición posterior.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e7000000'), $job2, 'ENTREGADO', 1, 'Calle 4 #5-6')], $token);
check('job_entregado', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $tr2, json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jop($u('e8000000'), $job2, 'ENTREGADO', 2, 'Calle 4 #5-6')], $token);
check('job_terminal_sin_edicion', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Dependencias: job con client ajeno → FORBIDDEN_ENTITY (segunda cuenta).
$ops = [['operation_id' => $u('e9000000'), 'entity_type' => 'job', 'entity_id' => $u('e9000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => array_merge($jPayload($u('e9000001'), 'BORRADOR'), ['userId' => $reg2['user_id']])]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('job_dependency_ownership_FORBIDDEN', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY', json_encode($r));

// Hijos: job_quote (vínculo a cotización APROBADA $q2), actividad y material.
$ops = [
    ['operation_id' => $u('ea000000'), 'entity_type' => 'job_quote', 'entity_id' => $u('ea000001'),
     'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $u('ea000001'), 'jobId' => $job1, 'quoteId' => $q2,
        'relationType' => 'ORIGEN', 'createdAt' => $now]],
    ['operation_id' => $u('eb000000'), 'entity_type' => 'job_activity', 'entity_id' => $u('eb000001'),
     'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $u('eb000001'), 'jobId' => $job1, 'description' => 'Actividad sintética',
        'status' => 'PENDIENTE', 'position' => 1, 'createdAt' => $now, 'updatedAt' => $now]],
    ['operation_id' => $u('ec000000'), 'entity_type' => 'job_material', 'entity_id' => $u('ec000001'),
     'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $u('ec000001'), 'jobId' => $job1, 'description' => 'Material sintético',
        'origin' => 'PROFESIONAL', 'quantity' => '2', 'unit' => 'und', 'notes' => '',
        'createdAt' => $now, 'updatedAt' => $now]],
];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_children_applied', array_column($r['results'] ?? [], 'status') === ['APPLIED', 'APPLIED', 'APPLIED'], json_encode($r));

// Actividad con estado fuera del enum → VALIDATION_ERROR.
$ops = [['operation_id' => $u('ed000000'), 'entity_type' => 'job_activity', 'entity_id' => $u('ed000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('ed000001'), 'jobId' => $job1, 'description' => 'x',
            'status' => 'PAUSADA', 'position' => 2, 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_activity_status_invalid', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Hijo de job ajeno → FORBIDDEN_ENTITY.
$ops = [['operation_id' => $u('ee000000'), 'entity_type' => 'job_activity', 'entity_id' => $u('ee000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('ee000001'), 'jobId' => $job1, 'description' => 'robo',
            'status' => 'PENDIENTE', 'position' => 3, 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('job_child_ownership_FORBIDDEN', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY', json_encode($r));

// Pull: los hijos y el número oficial viajan por el canal normal.
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$types = array_count_values(array_column($r['changes'] ?? [], 'entity_type'));
check('pull_f4_entities', ($types['job'] ?? 0) >= 2 && ($types['job_quote'] ?? 0) === 1
    && ($types['job_activity'] ?? 0) === 1 && ($types['job_material'] ?? 0) === 1, json_encode($types));
$jobRow = array_values(array_filter($r['changes'] ?? [], fn($x) => $x['entity_id'] === $job1))[0] ?? null;
check('pull_job_official_number', ($jobRow['official_number'] ?? null) === $tr1, json_encode($jobRow));

// ===== Corrección de la revisión F4: validación servidor de vínculos y dependencias =====

// Dependencia requerida vacía: job sin clientId → VALIDATION_ERROR (no se omite).
$ops = [['operation_id' => $u('ef000000'), 'entity_type' => 'job', 'entity_id' => $u('ef000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => array_merge($jPayload($u('ef000001'), 'BORRADOR'), ['clientId' => ''])]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_sin_clientId_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Dependencia requerida vacía: job sin profileId → VALIDATION_ERROR.
$ops = [['operation_id' => $u('ef000010'), 'entity_type' => 'job', 'entity_id' => $u('ef000011'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => array_merge($jPayload($u('ef000011'), 'BORRADOR'), ['profileId' => ''])]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_sin_profileId_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Referencia inexistente: clientId que no existe en el servidor → DEPENDENCY.
$ops = [['operation_id' => $u('ef000020'), 'entity_type' => 'job', 'entity_id' => $u('ef000021'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => array_merge($jPayload($u('ef000021'), 'BORRADOR'), ['clientId' => $u('fffff001')])]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_cliente_inexistente_DEPENDENCY', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'DEPENDENCY', json_encode($r));

// F3 endurecido: cotización sin clientId también se rechaza (cliente es requerido).
$ops = [['operation_id' => $u('ef000030'), 'entity_type' => 'quote', 'entity_id' => $u('ef000031'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => array_merge($qPayload($u('ef000031'), 'BORRADOR'), ['clientId' => ''])]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('quote_sin_clientId_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// job_quote a cotización NO aprobada ($q5 quedó CANCELADA, mismo cliente) → VALIDATION_ERROR.
$ops = [['operation_id' => $u('ef000040'), 'entity_type' => 'job_quote', 'entity_id' => $u('ef000041'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('ef000041'), 'jobId' => $job1, 'quoteId' => $q5,
            'relationType' => 'ADICIONAL', 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_quote_no_aprobada_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Segundo cliente de la MISMA cuenta + cotización APROBADA de ese cliente.
$cliente2 = $u('ef000051');
$q6 = $u('ef000052');
$ops = [
    ['operation_id' => $u('ef000050'), 'entity_type' => 'client', 'entity_id' => $cliente2,
     'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $cliente2, 'userId' => $userId, 'type' => 'EMPRESA', 'name' => 'Cliente Dos Sintético',
        'createdAt' => $now, 'updatedAt' => $now]],
    ['operation_id' => $u('ef000053'), 'entity_type' => 'quote', 'entity_id' => $q6,
     'type' => 'upsert', 'base_server_version' => 0,
     'payload' => array_merge($qPayload($q6, 'APROBADA'), ['clientId' => $cliente2])],
];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_quote_otro_cliente_setup', array_column($r['results'] ?? [], 'status') === ['APPLIED', 'APPLIED'], json_encode($r));

// job_quote a cotización APROBADA de OTRO cliente (misma cuenta) → VALIDATION_ERROR.
$ops = [['operation_id' => $u('ef000054'), 'entity_type' => 'job_quote', 'entity_id' => $u('ef000055'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('ef000055'), 'jobId' => $job1, 'quoteId' => $q6,
            'relationType' => 'ADICIONAL', 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('job_quote_otro_cliente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// =====================  F5 — Informes de Trabajo  =====================

// Trabajo del MISMO cliente+perfil para asociar (ya persistido arriba: $job1).
// Segundo trabajo del OTRO cliente ($cliente2) para la agrupación inválida.
$jobOtro = $u('f8000001');
$ops = [['operation_id' => $u('f8000000'), 'entity_type' => 'job', 'entity_id' => $jobOtro,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => array_merge($jPayload($jobOtro, 'BORRADOR'), ['clientId' => $cliente2])]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('f8_job_otro_cliente_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

$rPayload = fn(string $id, string $status, array $over = []) => array_merge([
    'id' => $id, 'userId' => $userId, 'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
    'provisionalNumber' => 'INF-PEND-' . strtoupper(substr($id, 0, 4)), 'status' => $status,
    'title' => 'Informe sintético', 'destination' => 'CLIENTE', 'content' => 'contenido',
    'currentVersion' => 0, 'emittedVersion' => 0, 'clientNameSnapshot' => 'Cliente Sintético',
    'archived' => false, 'createdAt' => $now, 'updatedAt' => $now], $over);
$rop = fn(string $opId, string $id, string $status, int $base, array $over = []) =>
    [['operation_id' => $opId, 'entity_type' => 'report', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $base, 'payload' => $rPayload($id, $status, $over)]];

// Informe BORRADOR creado sin número oficial.
$rep1 = $u('f9000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000000'), $rep1, 'BORRADOR', 0)], $token);
check('report_borrador_created', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === null, json_encode($r));

// Vínculo Informe↔Trabajo válido (mismo cliente y perfil, informe BORRADOR).
$ops = [['operation_id' => $u('f9000010'), 'entity_type' => 'report_job', 'entity_id' => $u('f9000011'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000011'), 'reportId' => $rep1, 'jobId' => $job1,
            'position' => 1, 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('report_job_valido', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Vínculo fabricado a trabajo de OTRO cliente → VALIDATION_ERROR (validado
// sobre datos persistidos del servidor, nunca declarados por el cliente).
$ops = [['operation_id' => $u('f9000020'), 'entity_type' => 'report_job', 'entity_id' => $u('f9000021'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000021'), 'reportId' => $rep1, 'jobId' => $jobOtro,
            'position' => 2, 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('report_job_otro_cliente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Emitir: BORRADOR→EMITIDO con ≥1 trabajo persistido → INF-AAAAMM-NNN.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000030'), $rep1, 'EMITIDO', 1,
        ['currentVersion' => 1, 'emittedVersion' => 1])], $token);
$inf1 = $r['results'][0]['official_number'] ?? null;
check('report_emit_INF_assigned', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && is_string($inf1) && str_starts_with($inf1, 'INF-'), json_encode($r));

// Snapshot v1 de la emisión.
$ops = [['operation_id' => $u('f9000040'), 'entity_type' => 'report_version', 'entity_id' => $u('f9000041'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000041'), 'reportId' => $rep1, 'version' => 1,
            'snapshotJson' => '{"r1":1}', 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('report_version_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Versión concurrente v1 con snapshot distinto → conservada + conflicto marcado.
$ops = [['operation_id' => $u('f9000050'), 'entity_type' => 'report_version', 'entity_id' => $u('f9000051'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000051'), 'reportId' => $rep1, 'version' => 1,
            'snapshotJson' => '{"r1":2}', 'previousVersionId' => $u('f9000041'), 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('report_version_concurrent', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['code'] ?? '') === 'VERSION_CONFLICT', json_encode($r));

// Emitido inmutable: edición fabricada de contenido EMITIDO→EMITIDO → INVALID_TRANSITION.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000060'), $rep1, 'EMITIDO', 3,
        ['currentVersion' => 1, 'emittedVersion' => 1, 'content' => 'edición fabricada'])], $token);
check('report_emitido_inmutable_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Archivar sí se permite sobre emitido (único campo mutable).
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000070'), $rep1, 'EMITIDO', 3,
        ['currentVersion' => 1, 'emittedVersion' => 1, 'archived' => true])], $token);
check('report_emitido_archivar_ok', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Desarchivar para seguir el flujo.
http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000080'), $rep1, 'EMITIDO', 4,
        ['currentVersion' => 1, 'emittedVersion' => 1, 'archived' => false])], $token);

// Corrección: EMITIDO→BORRADOR (reapertura aprobada) conserva el INF.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000090'), $rep1, 'BORRADOR', 5,
        ['currentVersion' => 1, 'emittedVersion' => 1])], $token);
check('report_correccion_reabre', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $inf1, json_encode($r));

// Con conflicto de versiones abierto, re-emitir → CONFLICT_BLOCKED.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000100'), $rep1, 'EMITIDO', 6,
        ['currentVersion' => 2, 'emittedVersion' => 2])], $token);
check('report_emit_CONFLICT_BLOCKED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'CONFLICT_BLOCKED', json_encode($r));

// Resolución del usuario: versión nueva enlazada → conflicto despejado.
$ops = [['operation_id' => $u('f9000110'), 'entity_type' => 'report_version', 'entity_id' => $u('f9000111'),
         'type' => 'resolve', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000111'), 'reportId' => $rep1, 'version' => 2,
            'snapshotJson' => '{"r1":1,"resolved":true}', 'previousVersionId' => $u('f9000041'),
            'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('report_resolve_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Re-emisión tras resolver: nueva versión, mismo número oficial INF.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000120'), $rep1, 'EMITIDO', 7,
        ['currentVersion' => 2, 'emittedVersion' => 2])], $token);
check('report_reemit_mismoINF', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $inf1, json_encode($r));

// Vínculo a informe EMITIDO → inmutable, rechazado.
$ops = [['operation_id' => $u('f9000130'), 'entity_type' => 'report_job', 'entity_id' => $u('f9000131'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000131'), 'reportId' => $rep1, 'jobId' => $job2,
            'position' => 2, 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('report_job_emitido_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// ===== Operaciones fabricadas sobre Informes =====

// EMITIDO en creación (sin trabajos persistidos) → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000140'), $u('f9000141'), 'EMITIDO', 0,
        ['currentVersion' => 1, 'emittedVersion' => 1])], $token);
check('report_emit_on_create_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Emitir un informe SIN vínculos persistidos → VALIDATION_ERROR.
$rep2 = $u('f9000151');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000150'), $rep2, 'BORRADOR', 0)], $token);
check('report2_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000160'), $rep2, 'EMITIDO', 1,
        ['currentVersion' => 1, 'emittedVersion' => 1])], $token);
check('report_emit_sin_trabajos_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Título vacío → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000170'), $u('f9000171'), 'BORRADOR', 0, ['title' => ' '])], $token);
check('report_sin_titulo_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Destino fuera del enum → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $rop($u('f9000180'), $u('f9000181'), 'BORRADOR', 0, ['destination' => 'PUBLICO'])], $token);
check('report_destino_invalido_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// report_job sobre informe de OTRA cuenta → FORBIDDEN_ENTITY.
$ops = [['operation_id' => $u('f9000190'), 'entity_type' => 'report_job', 'entity_id' => $u('f9000191'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000191'), 'reportId' => $rep1, 'jobId' => $job1,
            'position' => 3, 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('report_job_ownership_FORBIDDEN', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY', json_encode($r));

// report_version sobre informe de OTRA cuenta → FORBIDDEN_ENTITY.
$ops = [['operation_id' => $u('f9000200'), 'entity_type' => 'report_version', 'entity_id' => $u('f9000201'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('f9000201'), 'reportId' => $rep1, 'version' => 9,
            'snapshotJson' => '{}', 'createdAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('report_version_ownership_FORBIDDEN', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY', json_encode($r));

// Pull: las entidades F5 viajan por el canal normal con meta (INF, orden).
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$types = array_count_values(array_column($r['changes'] ?? [], 'entity_type'));
check('pull_f5_entities', ($types['report'] ?? 0) >= 2 && ($types['report_job'] ?? 0) === 1
    && ($types['report_version'] ?? 0) >= 3, json_encode($types));
$repRow = array_values(array_filter($r['changes'] ?? [], fn($x) => $x['entity_id'] === $rep1))[0] ?? null;
check('pull_report_inf', ($repRow['official_number'] ?? null) === $inf1, json_encode($repRow));
$repVerRows = array_filter($r['changes'] ?? [], fn($x) =>
    $x['entity_type'] === 'report_version' && ($x['payload']['reportId'] ?? '') === $rep1);
check('pull_report_versions_server_order', count($repVerRows) >= 3
    && !in_array(null, array_column($repVerRows, 'server_order'), true), json_encode($repVerRows));
check('pull_report_conflict_cleared', ($repRow['has_version_conflict'] ?? true) === false, json_encode($repRow));

echo "\n$pass passed, $fail failed\n";

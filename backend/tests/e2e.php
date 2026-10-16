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

// =====================  F6 — Cuentas de Cobro, Métodos y Pagos  =====================

$clienteId = $u('30000001'); // Cliente Sintético ya persistido
$perfilId = $u('20000001');

// Método de Cobro: nombre requerido; se crea y archiva por la vía genérica.
$cm = $u('c1000001');
$ops = [['operation_id' => $u('c1000000'), 'entity_type' => 'collection_method', 'entity_id' => $cm,
    'type' => 'upsert', 'base_server_version' => 0,
    'payload' => ['id' => $cm, 'userId' => $userId, 'name' => '', 'detail' => '',
        'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('f6_method_sin_nombre_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

$ops[0]['operation_id'] = $u('c1000002');
$ops[0]['payload']['name'] = 'Transferencia sintética';
$ops[0]['payload']['detail'] = 'Banco X 123';
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('f6_method_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Cuenta de Cobro con líneas y un Trabajo del mismo Cliente+Perfil ($job1
// quedó BORRADOR tras la reversa F4). CC oficial en la primera aceptación.
$cc1 = $u('c2000001');
$ccPayload = fn(string $id, string $status, array $over = []) => array_merge([
    'id' => $id, 'userId' => $userId, 'clientId' => $clienteId, 'profileId' => $perfilId,
    'provisionalNumber' => 'CC-PEND-' . strtoupper(substr($id, -4)), 'status' => $status,
    'currency' => 'COP', 'gross' => '120.00', 'totalRetained' => '6.00', 'net' => '114.00',
    'appliedAmount' => '0.00', 'balance' => '114.00', 'issuedAt' => $now,
    'dueDateEpochDay' => null, 'conditions' => 'a 30 días',
    'clientSnapshotJson' => '{"id":"' . $clienteId . '"}',
    'profileSnapshotJson' => '{"id":"' . $perfilId . '"}',
    'retentionsSnapshotJson' => '[]', 'clientNameSnapshot' => 'Cliente Sintético',
    'archived' => false,
    'items' => [
        ['id' => $u('c2i00001'), 'position' => 1, 'conceptType' => 'SERVICIO',
            'description' => 'Servicio ejecutado', 'quantity' => '2', 'unit' => 'u',
            'unitPrice' => '50.00', 'lineTotal' => '100.00'],
        ['id' => $u('c2i00002'), 'position' => 2, 'conceptType' => 'MATERIAL',
            'description' => 'Material', 'quantity' => '1', 'unit' => '',
            'unitPrice' => '20.00', 'lineTotal' => '20.00'],
    ],
    'jobIds' => [$job1], 'quoteIds' => [],
    'createdAt' => $now, 'updatedAt' => $now], $over);
$ccop = fn(string $opId, string $id, string $status, int $base, array $over = []) =>
    [['operation_id' => $opId, 'entity_type' => 'collection_account', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $base, 'payload' => $ccPayload($id, $status, $over)]];

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000000'), $cc1, 'PENDIENTE', 0)], $token);
$ccNum = $r['results'][0]['official_number'] ?? null;
check('f6_cc_created_CC_number', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && is_string($ccNum) && str_starts_with($ccNum, 'CC-'), json_encode($r));

// Numeración idempotente: replay de la misma operación conserva el número.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000000'), $cc1, 'PENDIENTE', 0)], $token);
check('f6_cc_number_idempotent', ($r['results'][0]['official_number'] ?? null) === $ccNum,
    json_encode($r));

// Nunca mezclar Trabajos y Cotizaciones.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000010'), $u('c2000011'), 'PENDIENTE', 0,
        ['quoteIds' => [$q2]])], $token);
check('f6_cc_mix_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Trabajo de otro Cliente ($jobOtro es de $cliente2) → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000020'), $u('c2000021'), 'PENDIENTE', 0,
        ['jobIds' => [$jobOtro]])], $token);
check('f6_cc_job_otro_cliente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Cotización no APROBADA persistida ($q5 CANCELADA) → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000030'), $u('c2000031'), 'PENDIENTE', 0,
        ['jobIds' => [], 'quoteIds' => [$q5]])], $token);
check('f6_cc_quote_no_aprobada_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Cotización APROBADA persistida del mismo Cliente+Perfil ($q2) → aceptada.
$cc2 = $u('c2000041');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000040'), $cc2, 'PENDIENTE', 0,
        ['jobIds' => [], 'quoteIds' => [$q2]])], $token);
check('f6_cc_quote_aprobada_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && str_starts_with($r['results'][0]['official_number'] ?? '', 'CC-'), json_encode($r));

// Doble vínculo: la misma Cotización $q2 ya pertenece a $cc2 no cancelada.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000050'), $u('c2000051'), 'PENDIENTE', 0,
        ['jobIds' => [], 'quoteIds' => [$q2]])], $token);
check('f6_cc_doble_vinculo_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Estado fabricado en creación (PAGADA directa) → INVALID_TRANSITION.
// Sin orígenes para no disparar antes la regla de doble vínculo.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000060'), $u('c2000061'), 'PAGADA', 0,
        ['jobIds' => []])], $token);
check('f6_cc_pagada_on_create_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// --- Pago append-only + eventos de asignación ---

$pay1 = $u('c3000001');
$payOp = fn(string $opId, array $over = []) =>
    [['operation_id' => $opId, 'entity_type' => 'payment', 'entity_id' => $pay1,
      'type' => 'upsert', 'base_server_version' => 0,
      'payload' => array_merge([
        'id' => $pay1, 'userId' => $userId, 'clientId' => $clienteId,
        'collectionMethodId' => $cm, 'amount' => '150.00', 'paidAtEpochDay' => 21000,
        'reference' => 'ref-sintética', 'notes' => '',
        'appliedAmount' => '0.00', 'unassignedAmount' => '150.00',
        'assignedAccountId' => null, 'assignmentStatus' => 'UNASSIGNED',
        'createdAt' => $now, 'updatedAt' => $now], $over)]];

// Cliente inexistente → DEPENDENCY.
$ops = [['operation_id' => $u('c3000010'), 'entity_type' => 'payment', 'entity_id' => $u('c3000011'),
    'type' => 'upsert', 'base_server_version' => 0,
    'payload' => ['id' => $u('c3000011'), 'userId' => $userId, 'clientId' => $u('fffff002'),
        'amount' => '10.00', 'paidAtEpochDay' => 21000, 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('f6_payment_cliente_inexistente_DEPENDENCY', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'DEPENDENCY', json_encode($r));

// Importe no positivo → VALIDATION_ERROR.
$ops[0]['operation_id'] = $u('c3000020');
$ops[0]['entity_id'] = $u('c3000021');
$ops[0]['payload']['id'] = $u('c3000021');
$ops[0]['payload']['clientId'] = $clienteId;
$ops[0]['payload']['amount'] = '0';
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('f6_payment_monto_invalido_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Pago válido sin asignar: el servidor normaliza sus importes derivados.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $payOp($u('c3000000'))], $token);
check('f6_payment_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Append-only: mismo entity_id con monto distinto → IMMUTABLE_FINANCIAL.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $payOp($u('c3000030'), ['amount' => '999.00'])], $token);
check('f6_payment_append_only_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'IMMUTABLE_FINANCIAL', json_encode($r));

// Evento de asignación: assign 200 a $cc1 (saldo 114): aplica solo 114 →
// la Cuenta deriva a PAGADA y el remanente 36 queda UNASSIGNED en el Pago.
$asg1 = $u('c4000001');
$asgOp = fn(string $opId, string $action, string $accId, string $amount, string $reason,
            ?string $fromId = null, string $released = '0.00') =>
    [['operation_id' => $opId, 'entity_type' => 'payment_assignment', 'entity_id' => $u('c4' . substr($opId, -6)),
      'type' => 'upsert', 'base_server_version' => 0,
      'payload' => ['id' => $u('c4' . substr($opId, -6)), 'userId' => $userId,
        'paymentId' => $pay1, 'action' => $action, 'accountId' => $accId,
        'fromAccountId' => $fromId, 'amount' => $amount, 'releasedAmount' => $released,
        'reason' => $reason, 'createdAt' => $now, 'updatedAt' => $now]]];

// Motivo obligatorio → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asgOp($u('c4000010'), 'assign', $cc1, '50.00', ' ')], $token);
check('f6_assign_sin_motivo_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// La asignación excede el saldo: el servidor aplica solo lo necesario.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asgOp($u('c4000020'), 'assign', $cc1, '200.00', 'Pago total intentado')], $token);
check('f6_assign_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId2 = [];
foreach ($r['changes'] ?? [] as $ch) { $byId2[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
$ccRow = $byId2["collection_account:$cc1"] ?? [];
$payRow = $byId2["payment:$pay1"] ?? [];
check('f6_cc_pagada_por_evento', ($ccRow['status'] ?? '') === 'PAGADA'
    && ($ccRow['appliedAmount'] ?? '') === '114.00' && ($ccRow['balance'] ?? '') === '0.00',
    json_encode($ccRow));
check('f6_payment_remanente_unassigned', ($payRow['appliedAmount'] ?? '') === '114.00'
    && ($payRow['unassignedAmount'] ?? '') === '36.00'
    && ($payRow['assignmentStatus'] ?? '') === 'PARTIAL_ASSIGNED', json_encode($payRow));
// El evento persiste el importe autoritativo aplicado (114), no el pedido (200).
$evRows = array_values(array_filter($r['changes'] ?? [], fn($x) => $x['entity_type'] === 'payment_assignment'));
check('f6_assignment_importe_autoritativo', count($evRows) === 1
    && ($evRows[0]['payload']['amount'] ?? '') === '114.00', json_encode($evRows));

// Cuenta con pagos aplicados es inmutable: edición fabricada → REJECTED.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000070'), $cc1, 'PAGADA', 2,
        ['conditions' => 'edición fabricada', 'appliedAmount' => '114.00', 'balance' => '0.00'])], $token);
check('f6_cc_pagada_inmutable_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Cancelar una Cuenta con pagos aplicados → INVALID_TRANSITION.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000080'), $cc1, 'CANCELADA', 2,
        ['appliedAmount' => '114.00', 'balance' => '0.00'])], $token);
check('f6_cc_pagada_no_cancelable', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Inmutabilidad de líneas (revisión PR #13): operación fabricada con el
// mismo NÚMERO de líneas pero contenido alterado (descripción y precio de
// la primera) → la comparación estructural debe rechazarla.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000072'), $cc1, 'PAGADA', 2,
        ['appliedAmount' => '114.00', 'balance' => '0.00',
         'items' => [
            ['id' => $u('c2i00001'), 'position' => 1, 'conceptType' => 'SERVICIO',
                'description' => 'Servicio alterado', 'quantity' => '2', 'unit' => 'u',
                'unitPrice' => '55.00', 'lineTotal' => '100.00'],
            ['id' => $u('c2i00002'), 'position' => 2, 'conceptType' => 'MATERIAL',
                'description' => 'Material', 'quantity' => '1', 'unit' => '',
                'unitPrice' => '20.00', 'lineTotal' => '20.00'],
         ]])], $token);
check('f6_cc_pagada_linea_alterada_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Archivado permitido sobre una Cuenta con pagos aplicados: mismo
// contenido persistido, solo `archived` cambia → APPLIED.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000074'), $cc1, 'PAGADA', 2,
        ['appliedAmount' => '114.00', 'balance' => '0.00', 'archived' => true])], $token);
check('f6_cc_pagada_archivado_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));

// Snapshot del nombre de Cliente también es inmutable (revisión PR #13):
// misma estructura persistida, solo clientNameSnapshot alterado → REJECTED.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000076'), $cc1, 'PAGADA', 3,
        ['appliedAmount' => '114.00', 'balance' => '0.00', 'archived' => true,
         'clientNameSnapshot' => 'Nombre Alterado'])], $token);
check('f6_cc_pagada_nombre_alterado_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Identidad fabricada (id/userId/createdAt distintos): el servidor los
// restaura desde lo persistido — no hay corrupción ni cambio de titular.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000078'), $cc1, 'PAGADA', 3,
        ['appliedAmount' => '114.00', 'balance' => '0.00', 'archived' => true,
         'id' => 'cc-fabricado', 'userId' => 'otro-titular', 'createdAt' => 1])], $token);
check('f6_cc_pagada_identidad_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId5 = [];
foreach ($r['changes'] ?? [] as $ch) { $byId5[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
$ccRow5 = $byId5["collection_account:$cc1"] ?? [];
check('f6_cc_pagada_identidad_intacta', ($ccRow5['id'] ?? '') === $cc1
    && ($ccRow5['userId'] ?? '') === $userId
    && ($ccRow5['createdAt'] ?? null) === $now
    && ($ccRow5['status'] ?? '') === 'PAGADA'
    && ($ccRow5['archived'] ?? false) === true, json_encode($ccRow5));

// Segunda Cuenta ($cc3) sin origen para reasignarle 36.
$cc3 = $u('c2000091');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000090'), $cc3, 'PENDIENTE', 0,
        ['jobIds' => [], 'net' => '50.00', 'balance' => '50.00',
         'items' => [['id' => $u('c2i00003'), 'position' => 1, 'conceptType' => 'SERVICIO',
            'description' => 'Otro servicio', 'quantity' => '1', 'unit' => '',
            'unitPrice' => '50.00', 'lineTotal' => '50.00']],
         'gross' => '50.00', 'totalRetained' => '0.00'])], $token);
check('f6_cc3_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Reasignar: libera 114 de $cc1, aplica min(114, saldo $cc3=50) → 50; el
// resto (64) queda UNASSIGNED dentro del mismo Pago. $cc1 vuelve PENDIENTE.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asgOp($u('c4000030'), 'reassign', $cc3, '50.00', 'Error de destino', $cc1, '114.00')], $token);
check('f6_reassign_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId3 = [];
foreach ($r['changes'] ?? [] as $ch) { $byId3[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
check('f6_reassign_saldos', ($byId3["payment:$pay1"]['appliedAmount'] ?? '') === '50.00'
    && ($byId3["payment:$pay1"]['unassignedAmount'] ?? '') === '100.00'
    && ($byId3["collection_account:$cc1"]['status'] ?? '') === 'PENDIENTE'
    && ($byId3["collection_account:$cc1"]['appliedAmount'] ?? '') === '0.00'
    && ($byId3["collection_account:$cc3"]['status'] ?? '') === 'PAGADA',
    json_encode([$byId3["payment:$pay1"], $byId3["collection_account:$cc1"], $byId3["collection_account:$cc3"]]));
// El evento reasignar registra lo liberado (114) y lo aplicado (50).
$evRe = array_values(array_filter($r['changes'] ?? [], fn($x) =>
    $x['entity_type'] === 'payment_assignment' && ($x['payload']['action'] ?? '') === 'reassign'));
check('f6_reassign_evento_auditado', count($evRe) === 1
    && ($evRe[0]['payload']['releasedAmount'] ?? '') === '114.00'
    && ($evRe[0]['payload']['amount'] ?? '') === '50.00'
    && ($evRe[0]['payload']['fromAccountId'] ?? '') === $cc1, json_encode($evRe));

// Retirar lo aplicado de $cc3: el Pago vuelve 150 UNASSIGNED; $cc3 PENDIENTE.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asgOp($u('c4000040'), 'unassign', $cc3, '50.00', 'Retiro total')], $token);
check('f6_unassign_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId4 = [];
foreach ($r['changes'] ?? [] as $ch) { $byId4[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
check('f6_unassign_saldos', ($byId4["payment:$pay1"]['appliedAmount'] ?? '') === '0.00'
    && ($byId4["payment:$pay1"]['unassignedAmount'] ?? '') === '150.00'
    && ($byId4["payment:$pay1"]['assignmentStatus'] ?? '') === 'UNASSIGNED'
    && ($byId4["collection_account:$cc3"]['appliedAmount'] ?? '') === '0.00',
    json_encode([$byId4["payment:$pay1"], $byId4["collection_account:$cc3"]]));

// Append-only también para eventos: mismo entity_id, motivo distinto.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('c4000050'), 'entity_type' => 'payment_assignment',
        'entity_id' => $u('c4' . substr($u('c4000020'), -6)),
        'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $u('c4' . substr($u('c4000020'), -6)), 'userId' => $userId,
            'paymentId' => $pay1, 'action' => 'assign', 'accountId' => $cc1,
            'fromAccountId' => null, 'amount' => '114.00', 'releasedAmount' => '0.00',
            'reason' => 'motivo fabricado', 'createdAt' => $now, 'updatedAt' => $now]]]], $token);
check('f6_assignment_append_only_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'IMMUTABLE_FINANCIAL', json_encode($r));

// Cancelación sin pagos aplicados libera la asociación: $cc2 (con $q2) se
// cancela y $q2 queda libre para otra Cuenta.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000100'), $cc2, 'CANCELADA', 1,
        ['jobIds' => [], 'quoteIds' => [$q2]])], $token);
check('f6_cc_cancel_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $ccop($u('c2000110'), $u('c2000111'), 'PENDIENTE', 0,
        ['jobIds' => [], 'quoteIds' => [$q2]])], $token);
check('f6_cc_cancel_libera_origen', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Auditoría: las asignaciones financieras quedaron registradas.
$aud = (int) $pdo->query(
    "SELECT COUNT(*) FROM audit_events WHERE entity_type = 'payment_assignment'
     AND action IN ('PAYMENT_ASSIGN','PAYMENT_UNASSIGN','PAYMENT_REASSIGN')"
)->fetchColumn();
check('f6_audit_events_registrados', $aud >= 3, (string) $aud);

// =====================  F7 — Consolidados, Ajustes y Reembolsos  =====================

// Cuentas miembro sintéticas sin orígenes (perfil/moneda compartidos).
$mkAcc = function (string $opId, string $id, string $net, int $base = 0,
                   string $status = 'PENDIENTE', array $over = []) use ($ccop) {
    return $ccop($opId, $id, $status, $base, array_merge([
        'jobIds' => [], 'quoteIds' => [], 'net' => $net, 'balance' => $net,
        'gross' => $net, 'totalRetained' => '0.00',
        'items' => [['id' => $id . '-i', 'position' => 1, 'conceptType' => 'SERVICIO',
            'description' => 'Servicio F7', 'quantity' => '1', 'unit' => '',
            'unitPrice' => $net, 'lineTotal' => $net]],
    ], $over));
};
$cca = $u('d1000001'); $ccb = $u('d1000002'); $ccc = $u('d1000003');
$ccd = $u('d1000004'); $cce = $u('d1000005');

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        $mkAcc($u('d1000010'), $cca, '80.00')[0],
        $mkAcc($u('d1000011'), $ccb, '60.00')[0],
        $mkAcc($u('d1000012'), $ccc, '40.00')[0],
        $mkAcc($u('d1000013'), $ccd, '30.00', 0, 'PENDIENTE', ['clientId' => $cliente2])[0],
        $mkAcc($u('d1000014'), $cce, '20.00')[0],
        // $cce nace PENDIENTE y se cancela: un Consolidado no la aceptará.
        $mkAcc($u('d1000015'), $cce, '20.00', 1, 'CANCELADA')[0],
    ]], $token);
check('f7_member_accounts_created', count(array_filter($r['results'] ?? [],
    fn($x) => ($x['status'] ?? '') === 'APPLIED')) === 6, json_encode($r));

$consPayload = fn(string $id, string $status, array $ids, array $over = []) => array_merge([
    'id' => $id, 'userId' => $userId, 'clientId' => $clienteId,
    'provisionalNumber' => 'CONS-PEND-' . strtoupper(substr($id, -4)), 'status' => $status,
    'currency' => 'COP', 'accountIds' => $ids,
    'totalAmount' => '0.00', 'appliedAmount' => '0.00', 'balance' => '0.00',
    'clientNameSnapshot' => 'Cliente Sintético', 'archived' => false,
    'createdAt' => $now, 'updatedAt' => $now], $over);
$consOp = fn(string $opId, string $id, string $status, array $ids, int $b, array $over = []) =>
    [['operation_id' => $opId, 'entity_type' => 'consolidated_collection', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $b, 'payload' => $consPayload($id, $status, $ids, $over)]];

// Elegibilidad: una sola Cuenta → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000010'), $u('d2000011'), 'PENDIENTE', [$cca], 0)], $token);
check('f7_cons_una_cuenta_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Cuenta CANCELADA como miembro → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000020'), $u('d2000021'), 'PENDIENTE', [$cca, $cce], 0)], $token);
check('f7_cons_cancelada_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Cuenta de otro Cliente → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000030'), $u('d2000031'), 'PENDIENTE', [$cca, $ccd], 0)], $token);
check('f7_cons_otro_cliente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Moneda distinta → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000032'), $u('d2000033'), 'PENDIENTE', [$cca, $ccc], 0,
        ['currency' => 'USD'])], $token);
check('f7_cons_moneda_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Estado fabricado en creación → INVALID_TRANSITION.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000034'), $u('d2000035'), 'PAGADA', [$cca, $ccb], 0)], $token);
check('f7_cons_pagada_on_create_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Creación válida [$cca 80 + $ccb 60 = 140]: CONS- oficial idempotente.
$cons1 = $u('d2000041');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000040'), $cons1, 'PENDIENTE', [$ccb, $cca], 0)], $token);
$consNum = $r['results'][0]['official_number'] ?? null;
check('f7_cons_created_CONS_number', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && is_string($consNum) && str_starts_with($consNum, 'CONS-'), json_encode($r));
// Replay idempotente: la MISMA operación (mismo op_id y payload) devuelve
// el resultado memorizado con el mismo CONS- oficial.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000040'), $cons1, 'PENDIENTE', [$ccb, $cca], 0)], $token);
check('f7_cons_number_idempotent', ($r['results'][0]['official_number'] ?? null) === $consNum,
    json_encode($r));

// Doble vínculo: $cca ya pertenece a un Consolidado activo.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000050'), $u('d2000051'), 'PENDIENTE', [$cca, $ccc], 0)], $token);
check('f7_cons_doble_vinculo_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Conflicto documental: edición con base fabricada → CONFLICT + server_state.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000060'), $cons1, 'PENDIENTE', [$cca, $ccb], 9)], $token);
check('f7_cons_stale_base_CONFLICT', ($r['results'][0]['status'] ?? '') === 'CONFLICT'
    && ($r['results'][0]['code'] ?? '') === 'STALE_BASE_VERSION', json_encode($r));

// Asignación directa a una Cuenta miembro de Consolidado activo → rechazada.
$pay2 = $u('d3000001');
$pay2Op = fn(string $opId) => [['operation_id' => $opId, 'entity_type' => 'payment',
    'entity_id' => $pay2, 'type' => 'upsert', 'base_server_version' => 0,
    'payload' => ['id' => $pay2, 'userId' => $userId, 'clientId' => $clienteId,
        'collectionMethodId' => $cm, 'amount' => '160.00', 'paidAtEpochDay' => 21001,
        'reference' => '', 'notes' => '', 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $pay2Op($u('d3000005'))], $token);
check('f7_payment2_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

$asgGen = fn(string $opId, string $action, ?string $acc, ?string $cons, string $amount,
             string $reason, ?string $fromAcc = null, ?string $fromCons = null) =>
    [['operation_id' => $opId, 'entity_type' => 'payment_assignment',
      'entity_id' => $u('d4' . substr($opId, -6)),
      'type' => 'upsert', 'base_server_version' => 0,
      'payload' => ['id' => $u('d4' . substr($opId, -6)), 'userId' => $userId,
        'paymentId' => $pay2, 'action' => $action,
        'targetType' => $cons !== null ? 'consolidated' : 'account',
        'accountId' => $acc, 'consolidatedId' => $cons,
        'fromAccountId' => $fromAcc, 'fromConsolidatedId' => $fromCons,
        'amount' => $amount, 'releasedAmount' => '0.00',
        'reason' => $reason, 'createdAt' => $now, 'updatedAt' => $now]]];

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asgGen($u('d4000010'), 'assign', $cca, null, '10.00', 'Directa a miembro')], $token);
check('f7_member_direct_assign_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Edición de una Cuenta miembro → INVALID_TRANSITION (congelada).
// Con $mkAcc para que la carga no choque con la regla de doble vínculo
// de orígenes (jobIds vacío): solo debe disparar la guarda de miembro.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mkAcc($u('d2000070'), $cca, '80.00', 1, 'PENDIENTE',
        ['conditions' => 'edición bloqueada'])], $token);
check('f7_member_edit_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Asignar 160 al Consolidado: aplica solo el saldo total 140 → PAGADA.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asgGen($u('d4000020'), 'assign', null, $cons1, '160.00', 'Pago consolidado')], $token);
check('f7_cons_assign_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId = [];
foreach ($r['changes'] ?? [] as $ch) { $byId[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
$consRow = $byId["consolidated_collection:$cons1"] ?? [];
$pay2Row = $byId["payment:$pay2"] ?? [];
check('f7_cons_pagada_derivada', ($consRow['status'] ?? '') === 'PAGADA'
    && ($consRow['totalAmount'] ?? '') === '140.00'
    && ($consRow['appliedAmount'] ?? '') === '140.00'
    && ($consRow['balance'] ?? '') === '0.00', json_encode($consRow));
// Sin distribución: los saldos de las Cuentas miembro no cambiaron.
check('f7_cons_sin_distribucion', ($byId["collection_account:$cca"]['balance'] ?? '') === '80.00'
    && ($byId["collection_account:$cca"]['appliedAmount'] ?? '') === '0.00'
    && ($byId["collection_account:$ccb"]['balance'] ?? '') === '60.00',
    json_encode([$byId["collection_account:$cca"] ?? [], $byId["collection_account:$ccb"] ?? []]));
check('f7_payment_parcial_unassigned', ($pay2Row['appliedAmount'] ?? '') === '140.00'
    && ($pay2Row['unassignedAmount'] ?? '') === '20.00'
    && ($pay2Row['assignmentStatus'] ?? '') === 'PARTIAL_ASSIGNED'
    && ($pay2Row['assignedConsolidatedId'] ?? '') === $cons1
    && ($pay2Row['effectiveAmount'] ?? '') === '160.00'
    && ($pay2Row['refundedAmount'] ?? '') === '0.00', json_encode($pay2Row));

// Consolidado con pagos es inmutable: edición y cancelación → INVALID_TRANSITION.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000080'), $cons1, 'PENDIENTE', [$cca, $ccc], 2,
        ['totalAmount' => '140.00', 'appliedAmount' => '140.00', 'balance' => '0.00'])], $token);
check('f7_cons_pagada_edit_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000090'), $cons1, 'CANCELADA', [$cca, $ccb], 2)], $token);
check('f7_cons_pagada_cancel_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// --- Reembolsos: reserva compartida y piso ---

$refundOp = fn(string $opId, string $payId, string $ent, string $amount, string $reason) =>
    [['operation_id' => $opId, 'entity_type' => 'payment_refund', 'entity_id' => $ent,
      'type' => 'upsert', 'base_server_version' => 0,
      'payload' => ['id' => $ent, 'userId' => $userId, 'paymentId' => $payId,
        'amount' => $amount, 'refundedAtEpochDay' => 21002, 'collectionMethodId' => $cm,
        'reason' => $reason, 'createdAt' => $now, 'updatedAt' => $now]]];

// Reembolsar dinero APLICADO (pay2: efectivo 160, aplicado 140, libre 20)
// sin liberar antes → VALIDATION_ERROR (reserva compartida).
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $refundOp($u('d5000010'), $pay2, $u('d5000011'), '50.00', 'Devolución')], $token);
check('f7_refund_aplicado_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Reembolso dentro del remanente libre (20) → APPLIED.
$ref1 = $u('d5000021');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $refundOp($u('d5000020'), $pay2, $ref1, '20.00', 'Devolución parcial')], $token);
check('f7_refund_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Idempotencia del evento: mismo entity_id, mismo payload → dedupe.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $refundOp($u('d5000022'), $pay2, $ref1, '20.00', 'Devolución parcial')], $token);
check('f7_refund_dedupe_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Mismo entity_id con importe distinto → IMMUTABLE_FINANCIAL.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $refundOp($u('d5000023'), $pay2, $ref1, '99.00', 'fabricado')], $token);
check('f7_refund_append_only_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'IMMUTABLE_FINANCIAL', json_encode($r));

// Liberar lo aplicado del Consolidado: unassign deja el Pago con 140 libres
// menos lo reembolsado → efectivo 160, aplicado 0, reembolsado 20.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asgGen($u('d4000030'), 'unassign', null, $cons1, '140.00', 'Retiro total')], $token);
check('f7_cons_unassign_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId = [];
foreach ($r['changes'] ?? [] as $ch) { $byId[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
$pay2Row = $byId["payment:$pay2"] ?? [];
check('f7_refund_reserva_compartida', ($pay2Row['effectiveAmount'] ?? '') === '160.00'
    && ($pay2Row['appliedAmount'] ?? '') === '0.00'
    && ($pay2Row['refundedAmount'] ?? '') === '20.00'
    && ($pay2Row['unassignedAmount'] ?? '') === '140.00', json_encode($pay2Row));
check('f7_cons_vuelve_pendiente', ($byId["consolidated_collection:$cons1"]['status'] ?? '') === 'PENDIENTE'
    && ($byId["consolidated_collection:$cons1"]['appliedAmount'] ?? '') === '0.00'
    && ($byId["consolidated_collection:$cons1"]['balance'] ?? '') === '140.00',
    json_encode($byId["consolidated_collection:$cons1"] ?? []));

// --- Correcciones sobre un Pago nuevo ($pay3 = 100) ---
$pay3 = $u('d3000011');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('d3000010'), 'entity_type' => 'payment',
        'entity_id' => $pay3, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $pay3, 'userId' => $userId, 'clientId' => $clienteId,
            'amount' => '100.00', 'paidAtEpochDay' => 21003, 'reference' => '', 'notes' => '',
            'createdAt' => $now, 'updatedAt' => $now]]]], $token);
check('f7_payment3_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

$adjOp = fn(string $opId, string $payId, string $ent, string $kind, ?string $newEff, string $reason) =>
    [['operation_id' => $opId, 'entity_type' => 'payment_adjustment', 'entity_id' => $ent,
      'type' => 'upsert', 'base_server_version' => 0,
      'payload' => array_filter([
        'id' => $ent, 'userId' => $userId, 'paymentId' => $payId,
        'kind' => $kind, 'reason' => $reason, 'newEffectiveAmount' => $newEff,
        'createdAt' => $now, 'updatedAt' => $now], fn($v) => $v !== null)]];

// Asignar 60 a $cc3 (saldo 50): aplica 50, quedan 50 UNASSIGNED.
$asg3 = fn(string $opId, string $action, string $amount, string $reason, ?string $fromAcc = null) =>
    [['operation_id' => $opId, 'entity_type' => 'payment_assignment',
      'entity_id' => $u('d4' . substr($opId, -6)),
      'type' => 'upsert', 'base_server_version' => 0,
      'payload' => ['id' => $u('d4' . substr($opId, -6)), 'userId' => $userId,
        'paymentId' => $pay3, 'action' => $action, 'targetType' => 'account',
        'accountId' => $cc3, 'consolidatedId' => null,
        'fromAccountId' => $fromAcc, 'fromConsolidatedId' => null,
        'amount' => $amount, 'releasedAmount' => '0.00',
        'reason' => $reason, 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asg3($u('d4000040'), 'assign', '60.00', 'Aplicación inicial')], $token);
check('f7_pay3_assign_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Corrección a la baja a 70: delta 30 consume solo UNASSIGNED (50 libres);
// la aplicación de 50 a $cc3 queda intacta (LIFO no alcanza a liberar).
$adj1 = $u('d6000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000005'), $pay3, $adj1, 'CORRECTION', '70.00', 'Sobrefacturado')], $token);
check('f7_correction_free_only', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId = [];
foreach ($r['changes'] ?? [] as $ch) { $byId[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
check('f7_correction_free_saldos', ($byId["payment:$pay3"]['effectiveAmount'] ?? '') === '70.00'
    && ($byId["payment:$pay3"]['appliedAmount'] ?? '') === '50.00'
    && ($byId["payment:$pay3"]['unassignedAmount'] ?? '') === '20.00'
    && ($byId["collection_account:$cc3"]['appliedAmount'] ?? '') === '50.00',
    json_encode([$byId["payment:$pay3"] ?? [], $byId["collection_account:$cc3"] ?? []]));

// Corrección a la baja a 30: delta 40 → libre 20 + libera 20 de la
// asignación vigente (la aplicación más reciente = única vigente).
$adj2 = $u('d6000011');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000010'), $pay3, $adj2, 'CORRECTION', '30.00', 'Baja con liberación')], $token);
check('f7_correction_lifo_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId = [];
foreach ($r['changes'] ?? [] as $ch) { $byId[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
check('f7_correction_lifo_saldos', ($byId["payment:$pay3"]['effectiveAmount'] ?? '') === '30.00'
    && ($byId["payment:$pay3"]['appliedAmount'] ?? '') === '30.00'
    && ($byId["payment:$pay3"]['unassignedAmount'] ?? '') === '0.00'
    && ($byId["collection_account:$cc3"]['appliedAmount'] ?? '') === '30.00'
    && ($byId["collection_account:$cc3"]['balance'] ?? '') === '20.00',
    json_encode([$byId["payment:$pay3"] ?? [], $byId["collection_account:$cc3"] ?? []]));
// El evento conserva antes/después y lo liberado con su origen.
$adjRow = $byId["payment_adjustment:$adj2"] ?? [];
check('f7_correction_evento_trazable', ($adjRow['kind'] ?? '') === 'CORRECTION'
    && ($adjRow['effectiveBefore'] ?? '') === '70.00'
    && ($adjRow['effectiveAfter'] ?? '') === '30.00'
    && ($adjRow['releasedAmount'] ?? '') === '20.00'
    && ($adjRow['releasedFromId'] ?? '') === $cc3, json_encode($adjRow));

// Corrección al alza a 90: el incremento queda UNASSIGNED (aplicado intacto).
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000020'), $pay3, $u('d6000021'), 'CORRECTION', '90.00', 'Monto mayor')], $token);
check('f7_correction_up_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId = [];
foreach ($r['changes'] ?? [] as $ch) { $byId[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
check('f7_correction_up_unassigned', ($byId["payment:$pay3"]['effectiveAmount'] ?? '') === '90.00'
    && ($byId["payment:$pay3"]['appliedAmount'] ?? '') === '30.00'
    && ($byId["payment:$pay3"]['unassignedAmount'] ?? '') === '60.00',
    json_encode($byId["payment:$pay3"] ?? []));

// Corrección sin cambio → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000030'), $pay3, $u('d6000031'), 'CORRECTION', '90.00', 'Igual')], $token);
check('f7_correction_igual_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Piso de reembolsos: retirar lo aplicado y reembolsar 10; luego corregir
// a 5 (< 10 reembolsado) → VALIDATION_ERROR.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $asg3($u('d4000050'), 'unassign', '30.00', 'Liberar para reembolso', null)], $token);
check('f7_pay3_unassign', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $refundOp($u('d5000030'), $pay3, $u('d5000031'), '10.00', 'Devuelvo 10')], $token);
check('f7_pay3_refund_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000040'), $pay3, $u('d6000041'), 'CORRECTION', '5.00', 'Bajo el piso')], $token);
check('f7_correction_bajo_reembolsado_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// --- ANNULMENT terminal sobre $pay4 = 200 con 50 aplicados a $cc3 ---
$pay4 = $u('d3000021');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('d3000020'), 'entity_type' => 'payment',
        'entity_id' => $pay4, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $pay4, 'userId' => $userId, 'clientId' => $clienteId,
            'amount' => '200.00', 'paidAtEpochDay' => 21004, 'reference' => '', 'notes' => '',
            'createdAt' => $now, 'updatedAt' => $now]],
        ['operation_id' => $u('d4000060'), 'entity_type' => 'payment_assignment',
         'entity_id' => $u('d4000061'), 'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('d4000061'), 'userId' => $userId,
            'paymentId' => $pay4, 'action' => 'assign', 'targetType' => 'account',
            'accountId' => $cc3, 'consolidatedId' => null, 'fromAccountId' => null,
            'fromConsolidatedId' => null, 'amount' => '50.00', 'releasedAmount' => '0.00',
            'reason' => 'Aplicación', 'createdAt' => $now, 'updatedAt' => $now]]]], $token);
check('f7_pay4_assigned', count($r['results'] ?? []) === 2
    && $r['results'][0]['status'] === 'APPLIED' && $r['results'][1]['status'] === 'APPLIED',
    json_encode($r));

// pay4: efectivo 200, aplicado 50 a $cc3 (saldo $cc3 tenía 50 → ocupa 30
// previos de pay3? No: pay3 quedó sin asignación tras el unassign; el
// saldo de $cc3 volvió a 50 → assign 50 aplica 50). ANNULMENT libera los
// 50 y fija el efectivo en reembolsado (0) → anulado terminal.
$ann1 = $u('d6000101');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000100'), $pay4, $ann1, 'ANNULMENT', null, 'Pago erróneo')], $token);
check('f7_annulment_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

[$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
$byId = [];
foreach ($r['changes'] ?? [] as $ch) { $byId[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch['payload']; }
check('f7_annulment_efecto', ($byId["payment:$pay4"]['annulled'] ?? false) === true
    && ($byId["payment:$pay4"]['effectiveAmount'] ?? '') === '0.00'
    && ($byId["payment:$pay4"]['appliedAmount'] ?? '') === '0.00'
    && ($byId["payment:$pay4"]['unassignedAmount'] ?? '') === '0.00'
    && ($byId["collection_account:$cc3"]['appliedAmount'] ?? '') === '0.00',
    json_encode([$byId["payment:$pay4"] ?? [], $byId["collection_account:$cc3"] ?? []]));

// Operaciones posteriores rechazadas: asignación, corrección, reembolso.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('d4000070'), 'entity_type' => 'payment_assignment',
        'entity_id' => $u('d4000071'), 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $u('d4000071'), 'userId' => $userId,
            'paymentId' => $pay4, 'action' => 'assign', 'targetType' => 'account',
            'accountId' => $cc3, 'consolidatedId' => null, 'fromAccountId' => null,
            'fromConsolidatedId' => null, 'amount' => '10.00', 'releasedAmount' => '0.00',
            'reason' => 'Tras anulación', 'createdAt' => $now, 'updatedAt' => $now]]]], $token);
check('f7_annulled_assign_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000110'), $pay4, $u('d6000111'), 'CORRECTION', '10.00', 'Tras anulación')], $token);
check('f7_annulled_correction_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $refundOp($u('d5000040'), $pay4, $u('d5000041'), '10.00', 'Tras anulación')], $token);
check('f7_annulled_refund_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// Una segunda anulación distinta → INVALID_TRANSITION (terminal una vez).
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000120'), $pay4, $u('d6000121'), 'ANNULMENT', null, 'Segunda')], $token);
check('f7_annulment_segunda_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));

// El replay del MISMO evento de anulación devuelve el resultado memorizado.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $adjOp($u('d6000100'), $pay4, $ann1, 'ANNULMENT', null, 'Pago erróneo')], $token);
check('f7_annulment_replay_idempotent', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// --- Cancelación del Consolidado libera a las Cuentas ---

// $cons2 con [$ccc 40 + $ccn 25]; cancelar libera a ambas.
$ccn = $u('d1000006');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [$mkAcc($u('d1000016'), $ccn, '25.00')[0]]], $token);
check('f7_ccn_created', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$cons2 = $u('d2000101');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000100'), $cons2, 'PENDIENTE', [$ccc, $ccn], 0)], $token);
check('f7_cons2_created', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && str_starts_with($r['results'][0]['official_number'] ?? '', 'CONS-'), json_encode($r));

// Edición de membresía en PENDIENTE: quitar $ccc, añadir… se mantiene ≥2.
// Quitar $ccc y dejar [$ccn, $cc3] (cc3 PENDIENTE saldo 50).
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000110'), $cons2, 'PENDIENTE', [$ccn, $cc3], 1)], $token);
check('f7_cons_edit_membership', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Tras editarse, $ccc queda libre: admite asignación directa de nuevo.
$pay5 = $u('d3000031');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('d3000030'), 'entity_type' => 'payment',
        'entity_id' => $pay5, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $pay5, 'userId' => $userId, 'clientId' => $clienteId,
            'amount' => '10.00', 'paidAtEpochDay' => 21005, 'reference' => '', 'notes' => '',
            'createdAt' => $now, 'updatedAt' => $now]],
        ['operation_id' => $u('d4000080'), 'entity_type' => 'payment_assignment',
         'entity_id' => $u('d4000081'), 'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('d4000081'), 'userId' => $userId,
            'paymentId' => $pay5, 'action' => 'assign', 'targetType' => 'account',
            'accountId' => $ccc, 'consolidatedId' => null, 'fromAccountId' => null,
            'fromConsolidatedId' => null, 'amount' => '10.00', 'releasedAmount' => '0.00',
            'reason' => 'Cuenta liberada', 'createdAt' => $now, 'updatedAt' => $now]]]], $token);
check('f7_freed_account_assignable', ($r['results'][1]['status'] ?? '') === 'APPLIED', json_encode($r));

// Cancelar $cons2 (PENDIENTE, sin pagos) → libera $ccn y $cc3.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000120'), $cons2, 'CANCELADA', [$ccn, $cc3], 2)], $token);
check('f7_cons_cancel_libera', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $consOp($u('d2000130'), $u('d2000131'), 'PENDIENTE', [$ccn, $ccc], 0)], $token);
check('f7_cons_reuse_after_cancel', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));

// Auditoría F7: correcciones, anulación y reembolso registrados.
$aud7 = (int) $pdo->query(
    "SELECT COUNT(*) FROM audit_events WHERE action IN
     ('PAYMENT_CORRECTION','PAYMENT_ANNULMENT','PAYMENT_REFUND') AND user_id = " . $pdo->quote($userId)
)->fetchColumn();
check('f7_audit_events_registrados', $aud7 >= 5, (string) $aud7);

// =====================  M2 (revisión PR #15): identidad canónica  =====================
// El payload no puede suplantar `id` (queda el entity_id del sobre) ni
// `userId` (titular autenticado); `createdAt` se conserva si el cliente lo
// declara y se completa con la hora del servidor si falta. En edición
// PENDIENTE se restauran desde lo persistido. Se verifica por pull: los
// valores almacenados son exactamente los que se propagan a los clientes.
$pullById = function () use ($base, $token) {
    [$c, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
    $m = [];
    foreach ($r['changes'] ?? [] as $ch) {
        $m[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch;
    }
    return $m;
};

// Cuenta PENDIENTE creada con identidad fabricada → APPLIED con canónicos.
$m2acc = $u('e1000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mkAcc($u('e1000010'), $m2acc, '10.00', 0, 'PENDIENTE',
        ['id' => 'FORGED-ACC-ID', 'userId' => 'forged-user', 'createdAt' => ''])], $token);
check('m2_cc_create_forged_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
$m2accP = $byId["collection_account:$m2acc"]['payload'] ?? [];
check('m2_cc_create_canonical_identity', ($m2accP['id'] ?? '') === $m2acc
    && ($m2accP['userId'] ?? '') === $userId
    && trim((string) ($m2accP['createdAt'] ?? '')) !== '',
    json_encode([$m2accP['id'] ?? null, $m2accP['userId'] ?? null]));

// Edición PENDIENTE con identidad fabricada → se restauran los persistidos.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mkAcc($u('e1000020'), $m2acc, '10.00', 1, 'PENDIENTE',
        ['id' => 'FORGED-ACC-ID', 'userId' => 'forged-user',
         'createdAt' => '1999-01-01T00:00:00Z', 'conditions' => 'Editada'])], $token);
check('m2_cc_edit_forged_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
$m2accP = $byId["collection_account:$m2acc"]['payload'] ?? [];
check('m2_cc_edit_canonical_identity', ($m2accP['id'] ?? '') === $m2acc
    && ($m2accP['userId'] ?? '') === $userId
    && ($m2accP['createdAt'] ?? '') !== '1999-01-01T00:00:00Z',
    json_encode([$m2accP['id'] ?? null, $m2accP['userId'] ?? null, $m2accP['createdAt'] ?? null]));

// Consolidado creado con identidad fabricada → canónicos persistidos.
$m2a = $u('e2000001'); $m2b = $u('e2000002'); $m2cons = $u('e2000003');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        $mkAcc($u('e2000010'), $m2a, '10.00')[0],
        $mkAcc($u('e2000011'), $m2b, '12.00')[0],
        $consOp($u('e2000020'), $m2cons, 'PENDIENTE', [$m2a, $m2b], 0,
            ['id' => 'FORGED-CONS-ID', 'userId' => 'forged-user', 'createdAt' => ''])[0],
    ]], $token);
check('m2_cons_create_forged_APPLIED', ($r['results'][2]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
$m2consP = $byId["consolidated_collection:$m2cons"]['payload'] ?? [];
check('m2_cons_create_canonical_identity', ($m2consP['id'] ?? '') === $m2cons
    && ($m2consP['userId'] ?? '') === $userId
    && trim((string) ($m2consP['createdAt'] ?? '')) !== '',
    json_encode([$m2consP['id'] ?? null, $m2consP['userId'] ?? null]));

// Pago creado con identidad fabricada → canónicos (misma clase, F6).
$m2pay = $u('e3000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('e3000010'), 'entity_type' => 'payment',
        'entity_id' => $m2pay, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => 'FORGED-PAY-ID', 'userId' => 'forged-user', 'clientId' => $clienteId,
            'collectionMethodId' => null, 'amount' => '25.00', 'paidAtEpochDay' => 21002,
            'reference' => '', 'notes' => '', 'createdAt' => '', 'updatedAt' => $now]]]], $token);
check('m2_pay_create_forged_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
$m2payP = $byId["payment:$m2pay"]['payload'] ?? [];
check('m2_pay_create_canonical_identity', ($m2payP['id'] ?? '') === $m2pay
    && ($m2payP['userId'] ?? '') === $userId
    && trim((string) ($m2payP['createdAt'] ?? '')) !== '',
    json_encode([$m2payP['id'] ?? null, $m2payP['userId'] ?? null]));

// Ajuste (CORRECTION al alza) y Reembolso con identidad fabricada → canónicos.
$m2adj = $u('e3000002'); $m2ref = $u('e3000003');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        ['operation_id' => $u('e3000020'), 'entity_type' => 'payment_adjustment',
         'entity_id' => $m2adj, 'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => 'FORGED-ADJ-ID', 'userId' => 'forged-user', 'paymentId' => $m2pay,
            'kind' => 'CORRECTION', 'reason' => 'Ajuste M2', 'newEffectiveAmount' => '30.00',
            'createdAt' => '', 'updatedAt' => $now]],
        ['operation_id' => $u('e3000030'), 'entity_type' => 'payment_refund',
         'entity_id' => $m2ref, 'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => 'FORGED-REF-ID', 'userId' => 'forged-user', 'paymentId' => $m2pay,
            'amount' => '5.00', 'refundedAtEpochDay' => 21002, 'collectionMethodId' => null,
            'reason' => 'Reembolso M2', 'createdAt' => '', 'updatedAt' => $now]],
    ]], $token);
check('m2_adj_ref_forged_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][1]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
$m2adjP = $byId["payment_adjustment:$m2adj"]['payload'] ?? [];
$m2refP = $byId["payment_refund:$m2ref"]['payload'] ?? [];
check('m2_adj_canonical_identity', ($m2adjP['id'] ?? '') === $m2adj
    && ($m2adjP['userId'] ?? '') === $userId
    && trim((string) ($m2adjP['createdAt'] ?? '')) !== '',
    json_encode([$m2adjP['id'] ?? null, $m2adjP['userId'] ?? null]));
check('m2_ref_canonical_identity', ($m2refP['id'] ?? '') === $m2ref
    && ($m2refP['userId'] ?? '') === $userId
    && trim((string) ($m2refP['createdAt'] ?? '')) !== '',
    json_encode([$m2refP['id'] ?? null, $m2refP['userId'] ?? null]));

// Política de `createdAt` (revisión PR #15, N3): el instante DECLARADO por
// el cliente es un dato de negocio (creación offline) que el servidor no
// infiere ni reescribe — se conserva tal cual en creación; solo el caso
// vacío se completa con la hora del servidor. Verificado por pull.
$declaredTs = '2025-06-15T10:30:00Z';
$m2pay2 = $u('e4000001');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('e4000010'), 'entity_type' => 'payment',
        'entity_id' => $m2pay2, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $m2pay2, 'userId' => $userId, 'clientId' => $clienteId,
            'collectionMethodId' => null, 'amount' => '7.50', 'paidAtEpochDay' => 21002,
            'reference' => '', 'notes' => '', 'createdAt' => $declaredTs,
            'updatedAt' => $now]]]], $token);
check('m2_pay_declared_ts_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));
$byId = $pullById();
check('m2_pay_createdAt_declared_preserved',
    ($byId["payment:$m2pay2"]['payload']['createdAt'] ?? null) === $declaredTs,
    json_encode($byId["payment:$m2pay2"]['payload']['createdAt'] ?? null));

// Consolidado con createdAt declarado: se conserva igual (entidad F7).
$m2c = $u('e4000011'); $m2d = $u('e4000012'); $m2cons2 = $u('e4000013');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        $mkAcc($u('e4000020'), $m2c, '10.00')[0],
        $mkAcc($u('e4000021'), $m2d, '12.00')[0],
        $consOp($u('e4000022'), $m2cons2, 'PENDIENTE', [$m2c, $m2d], 0,
            ['createdAt' => $declaredTs])[0],
    ]], $token);
check('m2_cons_declared_ts_APPLIED', ($r['results'][2]['status'] ?? '') === 'APPLIED',
    json_encode($r));
$byId = $pullById();
check('m2_cons_createdAt_declared_preserved',
    ($byId["consolidated_collection:$m2cons2"]['payload']['createdAt'] ?? null) === $declaredTs,
    json_encode($byId["consolidated_collection:$m2cons2"]['payload']['createdAt'] ?? null));

// =====================  F8 — Inventario y Compras  =====================
// Catálogo global de Materiales, Compras con numeración COM, movimientos
// append-only derivados por el servidor y saldo proyectado. Sintético.

$matA = $u('f8100001');
$matB = $u('f8100002');
$mkMat = fn(string $opId, string $id, array $over = [], int $base = 0) =>
    [['operation_id' => $opId, 'entity_type' => 'material', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $base,
      'payload' => array_merge(
          ['id' => $id, 'userId' => $userId, 'name' => 'Material Sintético',
           'unit' => 'kg', 'description' => '', 'archived' => false,
           'createdAt' => $now, 'updatedAt' => $now], $over)]];

// Catálogo: altas válidas y mínimos requeridos.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => array_merge(
        $mkMat($u('f8100010'), $matA, ['name' => 'Cemento gris']),
        $mkMat($u('f8100011'), $matB, ['name' => 'Varilla 3/8', 'unit' => 'und']))], $token);
check('f8_materials_APPLIED', array_column($r['results'] ?? [], 'status') === ['APPLIED', 'APPLIED'],
    json_encode($r));

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mkMat($u('f8100020'), $u('f8100021'), ['unit' => ''])], $token);
check('f8_material_sin_unidad_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mkMat($u('f8100030'), $u('f8100031'), ['name' => '  '])], $token);
check('f8_material_sin_nombre_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($r));

// Material de la segunda cuenta (aislamiento por titular).
$matX = $u('f8100091');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mkMat($u('f8100090'), $matX, ['userId' => $reg2['user_id'], 'name' => 'Ajeno'])],
    $reg2['token']);
check('f8_material_otra_cuenta_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));

// Compra en BORRADOR: dos líneas, número oficial COM- en el primer
// registro aceptado, total y moneda derivados por el servidor.
$pur1 = $u('f8200001');
$purPayload = fn(string $status, array $items, array $over = []) => array_merge([
    'id' => $pur1, 'userId' => $userId, 'provisionalNumber' => 'COM-PEND-TST1',
    'status' => $status, 'purchaseDateEpochDay' => 21005,
    'supplierPlace' => 'Depósito Sintético', 'reference' => 'FAC-1',
    'notes' => '', 'currency' => 'USD', 'totalAmount' => '99999.99', 'archived' => false,
    'items' => $items, 'createdAt' => $now, 'updatedAt' => $now], $over);
$line = fn(string $id, string $mat, string $qty, string $price, string $cond = 'NUEVO') => [
    'id' => $id, 'position' => 1, 'materialId' => $mat, 'quantity' => $qty,
    'unit' => 'kg', 'unitPrice' => $price, 'lineTotal' => '0.00',
    'condition' => $cond, 'createdAt' => $now];
$lA = $line($u('f8200011'), $matA, '10', '100.00');
$lB = array_merge($line($u('f8200012'), $matB, '5', '50.00', 'USADO'), ['position' => 2]);

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200000'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => $purPayload('BORRADOR', [$lA, $lB])]]], $token);
$comNum = $r['results'][0]['official_number'] ?? null;
check('f8_purchase_borrador_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && is_string($comNum) && preg_match('/^COM-\d{6}-\d{3}$/', $comNum) === 1, json_encode($r));
$byId = $pullById();
$p1 = $byId["purchase:$pur1"]['payload'] ?? [];
check('f8_purchase_total_moneda_derivados',
    ($p1['totalAmount'] ?? '') === '1250.00' && ($p1['currency'] ?? '') !== 'USD'
    && ($p1['items'][0]['materialNameSnapshot'] ?? '') === 'Cemento gris'
    && ($p1['items'][0]['lineTotal'] ?? '') === '1000.00',
    json_encode([$p1['totalAmount'] ?? null, $p1['currency'] ?? null]));
check('f8_borrador_sin_movimientos',
    empty($byId["inventory_stock:$matA"]) && empty($byId["inventory_stock:$matB"])
    && !array_filter($byId, fn($ch) => $ch['entity_type'] === 'inventory_movement'));

// Rechazos de mínimos y ciclo de vida.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200020'), 'entity_type' => 'purchase',
        'entity_id' => $u('f8200021'), 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => $purPayload('BORRADOR', [$lA], ['id' => $u('f8200021'),
            'purchaseDateEpochDay' => 0])]]], $token);
check('f8_purchase_sin_fecha_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200030'), 'entity_type' => 'purchase',
        'entity_id' => $u('f8200031'), 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => $purPayload('CONFIRMADA', [$lA], ['id' => $u('f8200031')])]]], $token);
check('f8_purchase_nace_confirmada_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200040'), 'entity_type' => 'purchase',
        'entity_id' => $u('f8200041'), 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => $purPayload('BORRADOR', [$line($u('f8200042'), $matX, '1', '1.00')],
            ['id' => $u('f8200041')])]]], $token);
check('f8_purchase_linea_ajena_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY', json_encode($r));

// Confirmar sin líneas → VALIDATION_ERROR; confirmar p1 → entradas atómicas.
$pur0 = $u('f8200051');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        ['operation_id' => $u('f8200050'), 'entity_type' => 'purchase', 'entity_id' => $pur0,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => $purPayload('BORRADOR', [], ['id' => $pur0])],
        ['operation_id' => $u('f8200052'), 'entity_type' => 'purchase', 'entity_id' => $pur0,
         'type' => 'upsert', 'base_server_version' => 1,
         'payload' => $purPayload('CONFIRMADA', [], ['id' => $pur0])],
    ]], $token);
check('f8_confirmar_sin_lineas_REJECTED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][1]['status'] ?? '') === 'REJECTED'
    && ($r['results'][1]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

$confirmPayload = $purPayload('CONFIRMADA', [$lA, $lB]);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200060'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 1,
        'payload' => $confirmPayload]]], $token);
check('f8_confirmar_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $comNum, json_encode($r));
$byId = $pullById();
$movsP1 = array_values(array_filter($byId,
    fn($ch) => $ch['entity_type'] === 'inventory_movement'
        && ($ch['payload']['purchaseId'] ?? null) === $pur1));
check('f8_confirmar_genera_entradas', count($movsP1) === 2
    && array_column(array_column($movsP1, 'payload'), 'movementType') === ['PURCHASE_IN', 'PURCHASE_IN']
    && array_column(array_column($movsP1, 'payload'), 'origin') === ['COMPRA', 'COMPRA'],
    json_encode(array_column($movsP1, 'payload')));
check('f8_stock_tras_confirmar',
    ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '10'
    && ($byId["inventory_stock:$matB"]['payload']['stockUsed'] ?? '') === '5',
    json_encode([$byId["inventory_stock:$matA"]['payload'] ?? null,
                 $byId["inventory_stock:$matB"]['payload'] ?? null]));

// Replay idempotente del mismo op: no duplica movimientos ni cambia número.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200060'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 1,
        'payload' => $confirmPayload]]], $token);
check('f8_confirmar_replay_idempotente', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][0]['official_number'] ?? null) === $comNum, json_encode($r));
$byId = $pullById();
check('f8_replay_sin_movimientos_extra', count(array_filter($byId,
    fn($ch) => $ch['entity_type'] === 'inventory_movement'
        && ($ch['payload']['purchaseId'] ?? null) === $pur1)) === 2);

// Edición diferencial en CONFIRMADA (base=2): A 10→12 y B se retira.
$lA2 = array_merge($lA, ['quantity' => '12']);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200070'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 2,
        'payload' => $purPayload('CONFIRMADA', [$lA2])]]], $token);
check('f8_edicion_confirmada_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));
$byId = $pullById();
$movsP1 = array_values(array_filter($byId,
    fn($ch) => $ch['entity_type'] === 'inventory_movement'
        && ($ch['payload']['purchaseId'] ?? null) === $pur1));
$diff = array_map(fn($m) => [$m['payload']['materialId'], $m['payload']['movementType'],
    $m['payload']['quantity']], array_slice($movsP1, 2));
usort($diff, fn($a, $b) => strcmp($a[0], $b[0]));
check('f8_edicion_diferencial', $diff === [
        [$matA, 'PURCHASE_IN', '2'], [$matB, 'ADJUSTMENT_OUT', '5']], json_encode($diff));
check('f8_stock_tras_edicion',
    ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '12'
    && ($byId["inventory_stock:$matB"]['payload']['stockUsed'] ?? '') === '0',
    json_encode([$byId["inventory_stock:$matA"]['payload'] ?? null,
                 $byId["inventory_stock:$matB"]['payload'] ?? null]));

// Base obsoleta sobre la compra → CONFLICT con estado servidor.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200080'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 1,
        'payload' => $purPayload('CONFIRMADA', [$lA2], ['notes' => 'viejo'])]]], $token);
check('f8_purchase_stale_CONFLICT', ($r['results'][0]['status'] ?? '') === 'CONFLICT'
    && ($r['results'][0]['code'] ?? '') === 'STALE_BASE_VERSION'
    && isset($r['results'][0]['server_state']), json_encode($r));

// Anular sustituyendo líneas → rechazo entero: el documento terminal debe
// conservar las líneas que originaron los movimientos; la compra sigue
// CONFIRMADA y el stock intacto.
$lATampered = array_merge($lA2, ['quantity' => '99']);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200085'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 3,
        'payload' => $purPayload('ANULADA', [$lATampered])]]], $token);
check('f8_anular_con_lineas_distintas_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));
$byId = $pullById();
check('f8_anulacion_adulterada_no_aplica',
    ($byId["purchase:$pur1"]['payload']['status'] ?? '') === 'CONFIRMADA'
    && ($byId["purchase:$pur1"]['payload']['items'][0]['quantity'] ?? '') === '12'
    && ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '12'
    && count(array_filter($byId,
        fn($ch) => $ch['entity_type'] === 'inventory_movement'
            && ($ch['payload']['purchaseId'] ?? null) === $pur1)) === 4,
    json_encode([$byId["purchase:$pur1"]['payload']['status'] ?? null,
        $byId["inventory_stock:$matA"]['payload'] ?? null]));

// Anulación: movimientos inversos atómicos; ANULADA es terminal y solo
// admite el cambio de `archived` (que no revierte nada).
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8200090'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 3,
        'payload' => $purPayload('ANULADA', [$lA2])]]], $token);
check('f8_anular_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
$movsP1 = array_values(array_filter($byId,
    fn($ch) => $ch['entity_type'] === 'inventory_movement'
        && ($ch['payload']['purchaseId'] ?? null) === $pur1));
$last = end($movsP1);
check('f8_anulacion_inversa', count($movsP1) === 5
    && ($last['payload']['movementType'] ?? '') === 'ADJUSTMENT_OUT'
    && ($last['payload']['origin'] ?? '') === 'COMPRA'
    && ($last['payload']['quantity'] ?? '') === '12',
    json_encode(array_column($movsP1, 'payload')));
check('f8_stock_cero_tras_anular',
    ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '0'
    && ($byId["inventory_stock:$matB"]['payload']['stockUsed'] ?? '') === '0');

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f82000a0'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 4,
        'payload' => $purPayload('BORRADOR', [$lA2])]]], $token);
check('f8_anulada_terminal_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'INVALID_TRANSITION', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f82000b0'), 'entity_type' => 'purchase',
        'entity_id' => $pur1, 'type' => 'upsert', 'base_server_version' => 4,
        'payload' => $purPayload('ANULADA', [$lA2], ['archived' => true])]]], $token);
check('f8_archivar_anulada_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));
$byId = $pullById();
check('f8_archivar_no_revierte',
    ($byId["purchase:$pur1"]['payload']['archived'] ?? false) === true
    && ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '0');

// ---- Payloads fabricados contra una Compra CONFIRMADA ----
// Regresión del defecto de edición: una actualización que declara
// `items: []` sobre una compra confirmada no puede persistirse — el
// documento, sus líneas, total, movimientos y stock quedan intactos.
// Material dedicado para no perturbar los saldos ya verificados arriba.
$matC = $u('f8100003');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mkMat($u('f8100040'), $matC, ['name' => 'Arena sílica'])], $token);
check('f8_matC_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$pur2 = $u('f82000c1');
$lC = $line($u('f82000c2'), $matC, '4', '25.00');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        ['operation_id' => $u('f82000c0'), 'entity_type' => 'purchase', 'entity_id' => $pur2,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => $purPayload('BORRADOR', [$lC], ['id' => $pur2])],
        ['operation_id' => $u('f82000c3'), 'entity_type' => 'purchase', 'entity_id' => $pur2,
         'type' => 'upsert', 'base_server_version' => 1,
         'payload' => $purPayload('CONFIRMADA', [$lC], ['id' => $pur2])],
    ]], $token);
check('f8_pur2_confirmada_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED'
    && ($r['results'][1]['status'] ?? '') === 'APPLIED', json_encode($r));
$movsP2 = count(array_filter($pullById(),
    fn($ch) => $ch['entity_type'] === 'inventory_movement'
        && ($ch['payload']['purchaseId'] ?? null) === $pur2));

[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f82000d0'), 'entity_type' => 'purchase',
        'entity_id' => $pur2, 'type' => 'upsert', 'base_server_version' => 2,
        'payload' => $purPayload('CONFIRMADA', [], ['id' => $pur2])]]], $token);
check('f8_confirmada_vaciar_lineas_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR'
    && isset($r['results'][0]['server_state']), json_encode($r));
$byId = $pullById();
check('f8_confirmada_vaciada_no_persiste',
    ($byId["purchase:$pur2"]['payload']['status'] ?? '') === 'CONFIRMADA'
    && count($byId["purchase:$pur2"]['payload']['items'] ?? []) === 1
    && ($byId["purchase:$pur2"]['payload']['totalAmount'] ?? '') === '100.00'
    && ($byId["inventory_stock:$matC"]['payload']['stockNew'] ?? '') === '4'
    && count(array_filter($byId,
        fn($ch) => $ch['entity_type'] === 'inventory_movement'
            && ($ch['payload']['purchaseId'] ?? null) === $pur2)) === $movsP2,
    json_encode([$byId["purchase:$pur2"]['payload'] ?? null]));

// Id de línea duplicado en el mismo documento → rechazo entero (un id
// repetido colapsaría las líneas al persistir por clave en el cliente).
$dupId = $u('f82000e2');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f82000e0'), 'entity_type' => 'purchase',
        'entity_id' => $u('f82000e1'), 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => $purPayload('BORRADOR',
            [$line($dupId, $matC, '1', '10.00'), $line($dupId, $matC, '2', '20.00')],
            ['id' => $u('f82000e1')])]]], $token);
check('f8_linea_id_duplicado_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Línea sin id: el servidor genera uno único en vez de persistir el
// blanco (el blanco colisionaría con otras líneas sin id del documento).
$pur3 = $u('f82000f1');
$noId = $line('', $matC, '2', '15.00');
unset($noId['id']);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f82000f0'), 'entity_type' => 'purchase',
        'entity_id' => $pur3, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => $purPayload('BORRADOR', [$noId], ['id' => $pur3])]]], $token);
check('f8_linea_sin_id_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
$genId = $byId["purchase:$pur3"]['payload']['items'][0]['id'] ?? '';
check('f8_linea_id_generado', is_string($genId) && $genId !== '', json_encode($genId));

// El borrador sí admite quedar sin líneas (mínimo aprobado solo exige
// línea para confirmar): una actualización legítima que las retira se
// aplica y no genera movimientos.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f82000f2'), 'entity_type' => 'purchase',
        'entity_id' => $pur3, 'type' => 'upsert', 'base_server_version' => 1,
        'payload' => $purPayload('BORRADOR', [], ['id' => $pur3])]]], $token);
check('f8_borrador_vaciar_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));
$byId = $pullById();
check('f8_borrador_vaciado_sin_movimientos',
    ($byId["purchase:$pur3"]['payload']['items'] ?? null) === []
    && !array_filter($byId,
        fn($ch) => $ch['entity_type'] === 'inventory_movement'
            && ($ch['payload']['purchaseId'] ?? null) === $pur3));

// ---- Movimientos append-only ----
$movOp = fn(string $opId, string $id, array $payload) =>
    [['operation_id' => $opId, 'entity_type' => 'inventory_movement', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => 0, 'payload' => $payload]];
$movBase = fn(array $over = []) => array_merge([
    'id' => $u('f8300099'), 'userId' => $userId, 'materialId' => $matA,
    'condition' => 'NUEVO', 'movementType' => 'ADJUSTMENT_IN', 'origin' => 'AJUSTE',
    'quantity' => '3', 'unit' => 'kg', 'unitCost' => null, 'purchaseId' => null,
    'jobId' => null, 'jobMaterialId' => null, 'note' => 'Conteo físico sintético',
    'occurredAt' => $now, 'createdAt' => $now, 'updatedAt' => $now], $over);

// Movimiento de origen COMPRA jamás lo empuja el cliente.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300000'), $u('f8300001'),
        $movBase(['id' => $u('f8300001'), 'origin' => 'COMPRA',
                  'movementType' => 'PURCHASE_IN', 'purchaseId' => $pur1]))], $token);
check('f8_movimiento_compra_cliente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// La proyección es solo lectura.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('f8300010'), 'entity_type' => 'inventory_stock',
        'entity_id' => $matA, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $matA, 'materialId' => $matA, 'stockNew' => '99',
            'stockUsed' => '0', 'createdAt' => $now, 'updatedAt' => $now]]]], $token);
check('f8_stock_no_escribible_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($r));

// Ajuste sin nota → rechazo; con nota → aplica y proyecta.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300020'), $u('f8300021'),
        $movBase(['id' => $u('f8300021'), 'note' => '']))], $token);
check('f8_ajuste_sin_nota_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300030'), $u('f8300031'),
        $movBase(['id' => $u('f8300031')]))], $token);
check('f8_ajuste_in_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
check('f8_stock_tras_ajuste', ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '3');

// Sobrante: nota obligatoria; referencia a Trabajo opcional pero debe existir.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300040'), $u('f8300041'),
        $movBase(['id' => $u('f8300041'), 'origin' => 'SOBRANTE', 'note' => '']))], $token);
check('f8_sobrante_sin_nota_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300050'), $u('f8300051'),
        $movBase(['id' => $u('f8300051'), 'origin' => 'SOBRANTE', 'quantity' => '2',
                  'jobId' => $u('ffffff51')]))], $token);
check('f8_sobrante_trabajo_inexistente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'DEPENDENCY', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300060'), $u('f8300061'),
        $movBase(['id' => $u('f8300061'), 'origin' => 'SOBRANTE', 'quantity' => '2',
                  'jobId' => $job1]))], $token);
check('f8_sobrante_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
check('f8_stock_tras_sobrante', ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '5');

// Material ajeno → FORBIDDEN; inexistente → DEPENDENCY.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300070'), $u('f8300071'),
        $movBase(['id' => $u('f8300071'), 'materialId' => $matX]))], $token);
check('f8_mov_material_ajeno_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300080'), $u('f8300081'),
        $movBase(['id' => $u('f8300081'), 'materialId' => $u('ffffff81')]))], $token);
check('f8_mov_material_inexistente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'DEPENDENCY', json_encode($r));

// Append-only: replay con campos idénticos → dedupe; con cambios → IMMUTABLE.
$movFix = $movBase(['id' => $u('f8300090'), 'quantity' => '1.500000']);
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300091'), $u('f8300090'), $movFix)], $token);
check('f8_mov_creado_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
$byId = $pullById();
check('f8_mov_cantidad_normalizada',
    ($byId['inventory_movement:' . $u('f8300090')]['payload']['quantity'] ?? '') === '1.5');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300092'), $u('f8300090'), $movFix)], $token);
check('f8_mov_dedupe_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300093'), $u('f8300090'),
        array_merge($movFix, ['quantity' => '2']))], $token);
check('f8_mov_inmutable_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'IMMUTABLE_FINANCIAL', json_encode($r));

// Replay con diferencia solo de espacios en la unidad → deduplica (no
// rechaza): la normalización se aplica antes de comparar campos estables.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8300094'), $u('f8300090'),
        array_merge($movFix, ['unit' => ' kg ']))], $token);
check('f8_mov_dedupe_espacios_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));

// ---- Consumo explícito en Trabajo (vínculo job_material → Material) ----
// jmDesc: histórico descriptivo (sin materialId); jmLink: consumo nuevo vinculado.
$jmDesc = $u('f8400001');
$jmLink = $u('f8400002');
$jmOp = fn(string $opId, string $id, array $payload, int $base = 0) =>
    [['operation_id' => $opId, 'entity_type' => 'job_material', 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $base, 'payload' => $payload]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => array_merge(
        $jmOp($u('f8400000'), $jmDesc,
            ['id' => $jmDesc, 'jobId' => $job1, 'description' => 'Material histórico',
             'origin' => 'PROFESIONAL', 'quantity' => '1', 'unit' => 'und', 'notes' => '',
             'createdAt' => $now, 'updatedAt' => $now]),
        $jmOp($u('f8400010'), $jmLink,
            ['id' => $jmLink, 'jobId' => $job1, 'description' => 'Consumo cemento',
             'origin' => 'PROFESIONAL', 'quantity' => '4', 'unit' => 'kg', 'notes' => '',
             'materialId' => $matA, 'condition' => 'NUEVO',
             'createdAt' => $now, 'updatedAt' => $now]))], $token);
check('f8_job_material_vinculado_APPLIED',
    array_column($r['results'] ?? [], 'status') === ['APPLIED', 'APPLIED'], json_encode($r));

// El histórico no gana materialId al editarse (sin reinterpretación).
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jmOp($u('f8400020'), $jmDesc,
        ['id' => $jmDesc, 'jobId' => $job1, 'description' => 'Material histórico',
         'origin' => 'PROFESIONAL', 'quantity' => '1', 'unit' => 'und', 'notes' => 'editado',
         'materialId' => $matA, 'condition' => 'NUEVO',
         'createdAt' => $now, 'updatedAt' => $now], 1)], $token);
check('f8_historico_no_vinculable_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// El vínculo ya creado es append-only: cambiar la cantidad se rechaza.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $jmOp($u('f8400030'), $jmLink,
        ['id' => $jmLink, 'jobId' => $job1, 'description' => 'Consumo cemento',
         'origin' => 'PROFESIONAL', 'quantity' => '9', 'unit' => 'kg', 'notes' => '',
         'materialId' => $matA, 'condition' => 'NUEVO',
         'createdAt' => $now, 'updatedAt' => $now], 1)], $token);
check('f8_vinculo_inmutable_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($r));

// Consumo JOB_CONSUMPTION referenciando el vínculo: aplica y descuenta.
$cons1 = $u('f8400041');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8400040'), $cons1,
        $movBase(['id' => $cons1, 'movementType' => 'JOB_CONSUMPTION', 'origin' => 'TRABAJO',
                  'quantity' => '4', 'jobId' => $job1, 'jobMaterialId' => $jmLink,
                  'note' => '']))], $token);
check('f8_consumo_trabajo_APPLIED', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));
$byId = $pullById();
check('f8_stock_tras_consumo', ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '2.5',
    json_encode($byId["inventory_stock:$matA"]['payload'] ?? null));

// Consumo sin vínculo coherente → rechazo (job_material no corresponde).
$cons2 = $u('f8400051');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8400050'), $cons2,
        $movBase(['id' => $cons2, 'movementType' => 'JOB_CONSUMPTION', 'origin' => 'TRABAJO',
                  'quantity' => '1', 'jobId' => $job1, 'jobMaterialId' => $jmDesc,
                  'note' => '']))], $token);
check('f8_consumo_vinculo_incoherente_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($r));

// Consumo con cantidad distinta a la del job_material persistido → rechazo
// (el servidor contrasta cantidad/unidad/condición, no solo el vínculo).
$consBad = $u('f8400052');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8400053'), $consBad,
        $movBase(['id' => $consBad, 'movementType' => 'JOB_CONSUMPTION', 'origin' => 'TRABAJO',
                  'quantity' => '9', 'jobId' => $job1, 'jobMaterialId' => $jmLink,
                  'note' => '']))], $token);
check('f8_consumo_cantidad_adulterada_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));

// Duplicado de consumo con entity_id y operation_id nuevos → rechazo:
// unicidad 1:1 movimiento ↔ job_material bajo lock del consumo.
$consDup = $u('f8400054');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8400055'), $consDup,
        $movBase(['id' => $consDup, 'movementType' => 'JOB_CONSUMPTION', 'origin' => 'TRABAJO',
                  'quantity' => '4', 'jobId' => $job1, 'jobMaterialId' => $jmLink,
                  'note' => '']))], $token);
check('f8_consumo_duplicado_REJECTED', ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR', json_encode($r));
$byId = $pullById();
check('f8_consumo_unico_en_stock',
    ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '2.5'
    && count(array_filter($byId,
        fn($ch) => $ch['entity_type'] === 'inventory_movement'
            && ($ch['payload']['jobMaterialId'] ?? null) === $jmLink)) === 1,
    json_encode($byId["inventory_stock:$matA"]['payload'] ?? null));

// Stock negativo permitido: ajuste de salida mayor que el saldo.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $movOp($u('f8400060'), $u('f8400061'),
        $movBase(['id' => $u('f8400061'), 'movementType' => 'ADJUSTMENT_OUT',
                  'quantity' => '100', 'note' => 'Merma sintética']))], $token);
check('f8_stock_negativo_permitido', ($r['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($r));
$byId = $pullById();
check('f8_stock_negativo_proyectado',
    ($byId["inventory_stock:$matA"]['payload']['stockNew'] ?? '') === '-97.5',
    json_encode($byId["inventory_stock:$matA"]['payload'] ?? null));

// Aislamiento: la otra cuenta no ve el material ni el stock en su pull.
[, $r2] = http($base, 'GET', '/sync/pull?cursor=0', null, $reg2['token']);
$foreign = array_filter($r2['changes'] ?? [],
    fn($ch) => $ch['entity_id'] === $matA || $ch['entity_id'] === $pur1
        || ($ch['entity_type'] === 'inventory_stock' && $ch['entity_id'] === $matA));
check('f8_aislamiento_titular', $foreign === []);

// --- PAUSED-resolution: server_state autoritativo y forbidden_scope ---

// Rechazo sobre fila propia existente: DEPENDENCY en un service que ya
// existe del titular (referencia a profile inexistente) → server_state
// incluye el estado persistido y su server_version.
$svcId = $u('e9a00001');
$mk2 = fn(string $op, string $id, string $et, array $payload, int $base = 0) =>
    [['operation_id' => $op, 'entity_type' => $et, 'entity_id' => $id,
      'type' => 'upsert', 'base_server_version' => $base, 'payload' => $payload]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mk2($u('e9a00000'), $svcId, 'service',
        ['id' => $svcId, 'userId' => $userId, 'profileId' => $u('20000001'),
         'name' => 'Svc Propio', 'suggestedPrice' => '10',
         'createdAt' => $now, 'updatedAt' => $now])], $token);
check('pr_svc_applied', ($r['results'][0]['status'] ?? '') === 'APPLIED', json_encode($r));
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mk2($u('e9a00002'), $svcId, 'service',
        ['id' => $svcId, 'userId' => $userId, 'profileId' => $u('99999998'),
         'name' => 'Svc Propio', 'suggestedPrice' => '10',
         'createdAt' => $now, 'updatedAt' => $now], 1)], $token);
check('pr_dependency_own_row_has_server_state',
    ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'DEPENDENCY'
    && isset($r['results'][0]['server_state'])
    && ($r['results'][0]['server_state']['id'] ?? '') === $svcId
    && ($r['results'][0]['server_state']['server_version'] ?? 0) >= 1,
    json_encode($r));

// FORBIDDEN_ENTITY sobre entidad de otra cuenta: scope entity_foreign y
// JAMÁS server_state (no se expone la fila ajena).
$ops = [['operation_id' => $u('e9b00000'), 'entity_type' => 'client', 'entity_id' => $u('30000001'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('30000001'), 'userId' => $reg2['user_id'], 'type' => 'EMPRESA',
             'name' => 'Intruso', 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('pr_forbidden_entity_foreign_scope_no_state',
    ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY'
    && ($r['results'][0]['forbidden_scope'] ?? '') === 'entity_foreign'
    && !isset($r['results'][0]['server_state']), json_encode($r));

// FORBIDDEN_ENTITY por referencia ajena sobre entidad nueva → scope
// foreign_reference, sin fila propia → sin server_state.
$ops = [['operation_id' => $u('e9b00001'), 'entity_type' => 'quote', 'entity_id' => $u('e9b00002'),
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $u('e9b00002'), 'userId' => $reg2['user_id'], 'clientId' => $u('30000001'),
             'profileId' => $u('e9b00003'), 'provisionalNumber' => 'COT-PEND-FR', 'status' => 'BORRADOR',
             'issueDateEpochDay' => 21000, 'validityDays' => 15, 'expiryDateEpochDay' => 21015,
             'currency' => 'COP', 'currentVersion' => 1, 'emittedVersion' => 0,
             'gross' => '1', 'totalRetained' => '0', 'net' => '1', 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $reg2['token']);
check('pr_forbidden_reference_scope',
    ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY'
    && ($r['results'][0]['forbidden_scope'] ?? '') === 'foreign_reference'
    && !isset($r['results'][0]['server_state']), json_encode($r));

// VALIDATION_ERROR sobre fila propia existente también lleva server_state.
$ops = [['operation_id' => $u('e9c00000'), 'entity_type' => 'job', 'entity_id' => $job1 ?? $u('e9c00001'),
         'type' => 'upsert', 'base_server_version' => 99,
         'payload' => ['id' => $job1 ?? $u('e9c00001'), 'userId' => $userId,
             'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
             'provisionalNumber' => 'TR-PEND-SS', 'status' => 'PROGRAMADO',
             'executionAddress' => '', 'createdAt' => $now, 'updatedAt' => $now]]];
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device, 'operations' => $ops], $token);
check('pr_rejected_own_row_state_present',
    ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && isset($r['results'][0]['server_state']['server_version']), json_encode($r));

// --- Regresión: el replay idempotente conserva el enriquecimiento ---
// Simula la pérdida de la primera respuesta: el cliente reenvía la MISMA
// operation_id con el MISMO payload; el resultado memorizado debe llevar
// server_state/forbidden_scope idénticos (funcionalmente indistinguible).

// Replay de la DEPENDENCY sobre fila propia (e9a00002): server_state intacto.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => $mk2($u('e9a00002'), $svcId, 'service',
        ['id' => $svcId, 'userId' => $userId, 'profileId' => $u('99999998'),
         'name' => 'Svc Propio', 'suggestedPrice' => '10',
         'createdAt' => $now, 'updatedAt' => $now], 1)], $token);
check('pr_replay_dependency_keeps_server_state',
    ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'DEPENDENCY'
    && ($r['results'][0]['server_state']['id'] ?? '') === $svcId
    && ($r['results'][0]['server_state']['server_version'] ?? 0) >= 1,
    json_encode($r));

// Replay del rechazo sobre fila propia (e9c00000): server_state intacto.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('e9c00000'), 'entity_type' => 'job',
        'entity_id' => $job1 ?? $u('e9c00001'), 'type' => 'upsert', 'base_server_version' => 99,
        'payload' => ['id' => $job1 ?? $u('e9c00001'), 'userId' => $userId,
            'clientId' => $u('30000001'), 'profileId' => $u('20000001'),
            'provisionalNumber' => 'TR-PEND-SS', 'status' => 'PROGRAMADO',
            'executionAddress' => '', 'createdAt' => $now, 'updatedAt' => $now]]]], $token);
check('pr_replay_own_row_keeps_server_state',
    ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && isset($r['results'][0]['server_state']['server_version']), json_encode($r));

// Replay de FORBIDDEN_ENTITY ajena (e9b00000): mismo scope, jamás estado.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('e9b00000'), 'entity_type' => 'client',
        'entity_id' => $u('30000001'), 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $u('30000001'), 'userId' => $reg2['user_id'], 'type' => 'EMPRESA',
            'name' => 'Intruso', 'createdAt' => $now, 'updatedAt' => $now]]]], $reg2['token']);
check('pr_replay_entity_foreign_no_state',
    ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY'
    && ($r['results'][0]['forbidden_scope'] ?? '') === 'entity_foreign'
    && !isset($r['results'][0]['server_state']), json_encode($r));

// Replay de FORBIDDEN_ENTITY por referencia (e9b00001): scope conservado.
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [['operation_id' => $u('e9b00001'), 'entity_type' => 'quote',
        'entity_id' => $u('e9b00002'), 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $u('e9b00002'), 'userId' => $reg2['user_id'], 'clientId' => $u('30000001'),
            'profileId' => $u('e9b00003'), 'provisionalNumber' => 'COT-PEND-FR', 'status' => 'BORRADOR',
            'issueDateEpochDay' => 21000, 'validityDays' => 15, 'expiryDateEpochDay' => 21015,
            'currency' => 'COP', 'currentVersion' => 1, 'emittedVersion' => 0,
            'gross' => '1', 'totalRetained' => '0', 'net' => '1', 'createdAt' => $now, 'updatedAt' => $now]]]], $reg2['token']);
check('pr_replay_foreign_reference_scope',
    ($r['results'][0]['code'] ?? '') === 'FORBIDDEN_ENTITY'
    && ($r['results'][0]['forbidden_scope'] ?? '') === 'foreign_reference'
    && !isset($r['results'][0]['server_state']), json_encode($r));

echo "\n$pass passed, $fail failed\n";

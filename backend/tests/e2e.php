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

echo "\n$pass passed, $fail failed\n";
exit($fail === 0 ? 0 : 1);

<?php

declare(strict_types=1);

/**
 * Prueba de carrera determinista (revisión PR #15, hallazgo M1): dos
 * transacciones/conexiones compiten por la misma Cuenta — la "ganadora" se
 * simula dentro de esta conexión (inserta el Consolidado y retiene el lock
 * de la fila miembro) mientras un proceso trabajador ejecuta el push real
 * de la operación perdedora contra la API. El trabajador se bloquea en el
 * lock de fila FOR UPDATE; se detecta su espera por information_schema
 * (condición, no temporización arbitraria), se confirma la ganadora y la
 * perdedora debe resolver sobre el estado vigente del servidor.
 *
 * Uso: php backend/tests/race.php [base_url]
 * Requiere el servidor local activo y acceso PDO a la misma base.
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
        'timeout' => 120,
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

require_once __DIR__ . '/../src/bootstrap.php';
$pdo = db();

// --- semilla: usuario, cliente y cuentas miembro sintéticas ---
$run = substr(bin2hex(random_bytes(4)), 0, 6);
$uid = fn(string $p) => sprintf('race-%s-%s', $run, $p);
$device = $uid('device');
$now = time() * 1000;

[$c, $reg] = http($base, 'POST', '/auth/register',
    ['email' => "race+$run@example.test", 'password' => 'sintetico1',
     'local_user_id' => $uid('local')]);
check('race_register', $c === 200 && isset($reg['token']));
$userId = $reg['user_id'] ?? '';
$token = $reg['token'] ?? '';

$clienteId = $uid('client');
$perfilId = $uid('profile');
$acc = fn(string $n) => $uid("acc-$n");
$accounts = [];
foreach (['b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l'] as $n) {
    $accounts[$n] = $acc($n);
}

$ops = [
    ['operation_id' => $uid('op-profile'), 'entity_type' => 'profile', 'entity_id' => $perfilId,
     'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $perfilId, 'userId' => $userId, 'businessName' => 'Perfil Race',
        'createdAt' => $now, 'updatedAt' => $now]],
    ['operation_id' => $uid('op-client'), 'entity_type' => 'client', 'entity_id' => $clienteId,
     'type' => 'upsert', 'base_server_version' => 0,
     'payload' => ['id' => $clienteId, 'userId' => $userId, 'type' => 'EMPRESA',
        'name' => 'Cliente Race', 'createdAt' => $now, 'updatedAt' => $now]],
];
foreach ($accounts as $id) {
    $ops[] = ['operation_id' => $uid('op-' . $id), 'entity_type' => 'collection_account',
        'entity_id' => $id, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $id, 'userId' => $userId, 'clientId' => $clienteId,
            'profileId' => $perfilId, 'provisionalNumber' => 'CC-PEND-' . strtoupper(substr($id, -3)),
            'status' => 'PENDIENTE', 'currency' => 'COP',
            'jobIds' => [], 'quoteIds' => [],
            'items' => [['id' => $id . '-i', 'position' => 1, 'conceptType' => 'SERVICIO',
                'description' => 'Servicio race', 'quantity' => '1', 'unit' => '',
                'unitPrice' => '50.00', 'lineTotal' => '50.00']],
            'gross' => '50.00', 'totalRetained' => '0.00', 'net' => '50.00',
            'balance' => '50.00', 'issuedAt' => '2025-01-01', 'dueDateEpochDay' => null,
            'conditions' => '', 'clientSnapshotJson' => '{}', 'profileSnapshotJson' => '{}',
            'retentionsSnapshotJson' => '[]', 'clientNameSnapshot' => 'Cliente Race',
            'createdAt' => $now, 'updatedAt' => $now]];
}
[$c, $r] = http($base, 'POST', '/sync/push',
    ['device_id' => $device, 'operations' => $ops], $token);
check('race_seed_applied', $c === 200
    && count(array_filter($r['results'] ?? [], fn($x) => ($x['status'] ?? '') === 'APPLIED'))
        === count($ops), json_encode($r));

// Pagos para escenarios de asignación/retiro/anulación.
$payE = $uid('pay-e');
$payG = $uid('pay-g');
$payK = $uid('pay-k');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        ['operation_id' => $uid('op-pay-e'), 'entity_type' => 'payment', 'entity_id' => $payE,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $payE, 'userId' => $userId, 'clientId' => $clienteId,
            'amount' => '10.00', 'paidAtEpochDay' => 21002, 'reference' => '', 'notes' => '',
            'createdAt' => $now, 'updatedAt' => $now]],
        ['operation_id' => $uid('op-pay-g'), 'entity_type' => 'payment', 'entity_id' => $payG,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $payG, 'userId' => $userId, 'clientId' => $clienteId,
            'amount' => '10.00', 'paidAtEpochDay' => 21002, 'reference' => '', 'notes' => '',
            'createdAt' => $now, 'updatedAt' => $now]],
        ['operation_id' => $uid('op-asg-g'), 'entity_type' => 'payment_assignment',
         'entity_id' => $uid('asg-g'), 'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $uid('asg-g'), 'userId' => $userId, 'paymentId' => $payG,
            'action' => 'assign', 'targetType' => 'account', 'accountId' => $accounts['g'],
            'consolidatedId' => null, 'fromAccountId' => null, 'fromConsolidatedId' => null,
            'amount' => '10.00', 'releasedAmount' => '0.00', 'reason' => 'Aplicación race',
            'createdAt' => $now, 'updatedAt' => $now]],
        ['operation_id' => $uid('op-pay-k'), 'entity_type' => 'payment', 'entity_id' => $payK,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $payK, 'userId' => $userId, 'clientId' => $clienteId,
            'amount' => '8.00', 'paidAtEpochDay' => 21002, 'reference' => '', 'notes' => '',
            'createdAt' => $now, 'updatedAt' => $now]],
        ['operation_id' => $uid('op-asg-k'), 'entity_type' => 'payment_assignment',
         'entity_id' => $uid('asg-k'), 'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $uid('asg-k'), 'userId' => $userId, 'paymentId' => $payK,
            'action' => 'assign', 'targetType' => 'account', 'accountId' => $accounts['k'],
            'consolidatedId' => null, 'fromAccountId' => null, 'fromConsolidatedId' => null,
            'amount' => '8.00', 'releasedAmount' => '0.00', 'reason' => 'Aplicación race',
            'createdAt' => $now, 'updatedAt' => $now]],
    ]], $token);
check('race_payments_seeded', $c === 200
    && count(array_filter($r['results'] ?? [], fn($x) => ($x['status'] ?? '') === 'APPLIED')) === 5,
    json_encode($r));

// --- utilidades de la prueba ---

/** Inserta un Consolidado PENDIENTE dentro de la transacción abierta del
 * orquestador (simula la "ganadora" a medio confirmar) y bloquea la fila
 * miembro indicada — el punto de serialización que el perdedor necesita. */
function beginWinnerTx(PDO $pdo, string $userId, string $consId, string $provisional,
                       array $accountIds, string $lockAccountId): void
{
    $pdo->beginTransaction();
    $pdo->exec(
        "INSERT INTO app_counters (name, value) VALUES ('entities', LAST_INSERT_ID(value + 1))
         ON DUPLICATE KEY UPDATE value = LAST_INSERT_ID(value + 1)"
    );
    $seq = (int) $pdo->query('SELECT LAST_INSERT_ID()')->fetchColumn();
    $now = date('Y-m-d H:i:s');
    $payload = json_encode([
        'id' => $consId, 'userId' => $userId,
        'provisionalNumber' => $provisional, 'status' => 'PENDIENTE', 'currency' => 'COP',
        'accountIds' => $accountIds,
        'members' => array_map(fn($a) => ['accountId' => $a, 'number' => '', 'net' => '50.00',
            'balance' => '50.00'], $accountIds),
        'totalAmount' => '100.00', 'appliedAmount' => '0.00', 'balance' => '100.00',
        'archived' => false, 'createdAt' => time() * 1000, 'updatedAt' => time() * 1000,
    ], JSON_UNESCAPED_UNICODE);
    $pdo->prepare(
        'INSERT INTO entities (entity_type, id, user_id, server_version, server_seq,
            server_order, official_number, status, payload, updated_at_server)
         VALUES (?, ?, ?, 1, ?, NULL, NULL, ?, ?, ?)'
    )->execute(['consolidated_collection', $consId, $userId, $seq, 'PENDIENTE', $payload, $now]);
    // El lock del miembro retiene al competidor hasta nuestro commit.
    $pdo->prepare('SELECT id FROM entities WHERE entity_type = ? AND id = ? FOR UPDATE')
        ->execute(['collection_account', $lockAccountId]);
}

/** Espera (condición, no temporización) a que la sentencia FOR UPDATE del
 * trabajador quede registrada en el processlist: es su estado de espera por
 * el lock que retenemos. */
function waitForWorkerBlocked(PDO $pdo): bool
{
    for ($i = 0; $i < 150; $i++) {
        $n = (int) $pdo->query(
            "SELECT COUNT(*) FROM information_schema.PROCESSLIST
             WHERE INFO LIKE '%FOR UPDATE%'"
        )->fetchColumn();
        if ($n > 0) {
            return true;
        }
        usleep(200_000);
    }
    return false;
}

/** Lanza el trabajador que ejecuta el push real por HTTP. */
function spawnWorker(string $base, string $token, string $device, array $operations): array
{
    $tmp = tempnam(sys_get_temp_dir(), 'race_push_');
    file_put_contents($tmp, json_encode(['device_id' => $device, 'operations' => $operations]));
    $proc = proc_open(
        [PHP_BINARY, __DIR__ . '/race_worker.php', $base, $token, $tmp],
        [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']],
        $pipes
    );
    fclose($pipes[0]);
    return [$proc, $pipes, $tmp];
}

function finishWorker(array $w): array
{
    [$proc, $pipes, $tmp] = $w;
    $out = stream_get_contents($pipes[1]);
    $err = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    proc_close($proc);
    @unlink($tmp);
    return [json_decode($out ?: '[]', true) ?: [], (string) $err];
}

function consOp(string $opId, string $id, string $userId, string $clienteId,
                array $accountIds, int $now): array
{
    return ['operation_id' => $opId, 'entity_type' => 'consolidated_collection',
        'entity_id' => $id, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $id, 'userId' => $userId, 'clientId' => $clienteId,
            'provisionalNumber' => 'CONS-PEND-' . strtoupper(substr($id, -3)),
            'status' => 'PENDIENTE', 'currency' => 'COP', 'accountIds' => $accountIds,
            'totalAmount' => '0.00', 'appliedAmount' => '0.00', 'balance' => '0.00',
            'clientNameSnapshot' => 'Cliente Race', 'archived' => false,
            'createdAt' => $now, 'updatedAt' => $now]];
}

function pullById(string $base, string $token): array
{
    [, $r] = http($base, 'GET', '/sync/pull?cursor=0', null, $token);
    $m = [];
    foreach ($r['changes'] ?? [] as $ch) {
        $m[$ch['entity_type'] . ':' . $ch['entity_id']] = $ch;
    }
    return $m;
}

// =====================  Escenario 1: doble membresía  =====================
// Ganadora: Consolidado C1 con {B,D} — retiene el lock de B sin confirmar.
// Perdedora: push de Consolidado C2 con {B,C}; debe bloquearse en B y, tras
// el commit de C1, rechazarse por doble vínculo (estado vigente, no snapshot).
$cons1 = $uid('cons-1');
beginWinnerTx($pdo, $userId, $cons1, 'CONS-PEND-RA1', [$accounts['b'], $accounts['d']], $accounts['b']);
$cons2 = $uid('cons-2');
$w = spawnWorker($base, $token, $device,
    [consOp($uid('op-cons2'), $cons2, $userId, $clienteId, [$accounts['b'], $accounts['c']], $now)]);
$blocked = waitForWorkerBlocked($pdo);
check('race1_worker_blocked_on_member', $blocked, 'el push no apareció esperando FOR UPDATE');
$pdo->commit();
[$res, $err] = finishWorker($w);
check('race1_second_consolidated_REJECTED',
    ($res['results'][0]['status'] ?? '') === 'REJECTED'
    && ($res['results'][0]['code'] ?? '') === 'VALIDATION_ERROR',
    json_encode($res) . $err);
$byId = pullById($base, $token);
$consWithB = [];
foreach ($byId as $key => $ch) {
    if (str_starts_with($key, 'consolidated_collection:')
        && in_array($accounts['b'], $ch['payload']['accountIds'] ?? [], true)) {
        $consWithB[] = $key;
    }
}
check('race1_single_active_membership', $consWithB === ["consolidated_collection:$cons1"],
    json_encode($consWithB));

// =====================  Escenario 2: asignación directa a miembro  =====================
// Ganadora: Consolidado C3 con {E,F} — retiene el lock de E.
// Perdedora: assign directo de payE a la Cuenta E; tras el commit debe
// rechazarse (la Cuenta ya pertenece a un Consolidado activo).
$cons3 = $uid('cons-3');
beginWinnerTx($pdo, $userId, $cons3, 'CONS-PEND-RA3', [$accounts['e'], $accounts['f']], $accounts['e']);
$w = spawnWorker($base, $token, $device,
    [['operation_id' => $uid('op-asg-e'), 'entity_type' => 'payment_assignment',
      'entity_id' => $uid('asg-e'), 'type' => 'upsert', 'base_server_version' => 0,
      'payload' => ['id' => $uid('asg-e'), 'userId' => $userId, 'paymentId' => $payE,
        'action' => 'assign', 'targetType' => 'account', 'accountId' => $accounts['e'],
        'consolidatedId' => null, 'fromAccountId' => null, 'fromConsolidatedId' => null,
        'amount' => '10.00', 'releasedAmount' => '0.00', 'reason' => 'Asignación race',
        'createdAt' => $now, 'updatedAt' => $now]]]);
$blocked = waitForWorkerBlocked($pdo);
check('race2_worker_blocked_on_member', $blocked, 'el push no apareció esperando FOR UPDATE');
$pdo->commit();
[$res, $err] = finishWorker($w);
check('race2_direct_assign_REJECTED',
    ($res['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($res) . $err);
$byId = pullById($base, $token);
check('race2_payment_still_unassigned',
    ($byId["payment:$payE"]['payload']['appliedAmount'] ?? '') === '0.00',
    json_encode($byId["payment:$payE"]['payload'] ?? []));

// ==========  Escenario 3: retiro de fondos de Cuenta que entra a Consolidado  ==========
// payG está aplicado a G. Ganadora: Consolidado C4 con {G,H} — retiene G.
// Perdedora: unassign de payG sobre G; tras el commit la membresía ya es
// vigente y el retiro debe rechazarse (dinero congelado del miembro).
$cons4 = $uid('cons-4');
beginWinnerTx($pdo, $userId, $cons4, 'CONS-PEND-RA4', [$accounts['g'], $accounts['h']], $accounts['g']);
$w = spawnWorker($base, $token, $device,
    [['operation_id' => $uid('op-unasg-g'), 'entity_type' => 'payment_assignment',
      'entity_id' => $uid('unasg-g'), 'type' => 'upsert', 'base_server_version' => 0,
      'payload' => ['id' => $uid('unasg-g'), 'userId' => $userId, 'paymentId' => $payG,
        'action' => 'unassign', 'targetType' => 'account', 'accountId' => $accounts['g'],
        'consolidatedId' => null, 'fromAccountId' => null, 'fromConsolidatedId' => null,
        'amount' => '0.00', 'releasedAmount' => '0.00', 'reason' => 'Retiro race',
        'createdAt' => $now, 'updatedAt' => $now]]]);
$blocked = waitForWorkerBlocked($pdo);
check('race3_worker_blocked_on_member', $blocked, 'el push no apareció esperando FOR UPDATE');
$pdo->commit();
[$res, $err] = finishWorker($w);
check('race3_unassign_member_REJECTED',
    ($res['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($res) . $err);
$byId = pullById($base, $token);
check('race3_payment_keeps_applied',
    ($byId["payment:$payG"]['payload']['appliedAmount'] ?? '') === '10.00',
    json_encode($byId["payment:$payG"]['payload'] ?? []));

// ==========  Escenario 5: anulación sobre fondos de Cuenta que entra a Consolidado  ==========
// payK está aplicado a K. Ganadora: Consolidado C6 con {K,L} — retiene K.
// Perdedora: ANNULMENT de payK que liberaría la aplicación vigente de K;
// tras el commit la membresía es vigente y el ajuste debe rechazarse.
$cons6 = $uid('cons-6');
beginWinnerTx($pdo, $userId, $cons6, 'CONS-PEND-RA6', [$accounts['k'], $accounts['l']], $accounts['k']);
$w = spawnWorker($base, $token, $device,
    [['operation_id' => $uid('op-annul-k'), 'entity_type' => 'payment_adjustment',
      'entity_id' => $uid('annul-k'), 'type' => 'upsert', 'base_server_version' => 0,
      'payload' => ['id' => $uid('annul-k'), 'userId' => $userId, 'paymentId' => $payK,
        'kind' => 'ANNULMENT', 'reason' => 'Anulación race',
        'createdAt' => $now, 'updatedAt' => $now]]]);
$blocked = waitForWorkerBlocked($pdo);
check('race5_worker_blocked_on_member', $blocked, 'el push no apareció esperando FOR UPDATE');
$pdo->commit();
[$res, $err] = finishWorker($w);
check('race5_annulment_member_REJECTED',
    ($res['results'][0]['status'] ?? '') === 'REJECTED',
    json_encode($res) . $err);
$byId = pullById($base, $token);
check('race5_payment_keeps_applied',
    ($byId["payment:$payK"]['payload']['appliedAmount'] ?? '') === '8.00'
    && ($byId["payment:$payK"]['payload']['annulled'] ?? true) === false,
    json_encode($byId["payment:$payK"]['payload'] ?? []));

// =====================  Control: el lock-wait no fuerza falso rechazo  =====================
// Se retiene el lock de I sin insertar Consolidado alguno y se revierte: el
// push perdedor solo esperó — debe aplicarse correctamente al liberarse.
$pdo->beginTransaction();
$pdo->prepare('SELECT id FROM entities WHERE entity_type = ? AND id = ? FOR UPDATE')
    ->execute(['collection_account', $accounts['i']]);
$cons5 = $uid('cons-5');
$w = spawnWorker($base, $token, $device,
    [consOp($uid('op-cons5'), $cons5, $userId, $clienteId, [$accounts['i'], $accounts['j']], $now)]);
$blocked = waitForWorkerBlocked($pdo);
check('race4_worker_blocked_on_member', $blocked, 'el push no apareció esperando FOR UPDATE');
$pdo->rollBack();
[$res, $err] = finishWorker($w);
check('race4_no_membership_APPLIED',
    ($res['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($res) . $err);
$byId = pullById($base, $token);
check('race4_consolidated_created',
    ($byId["consolidated_collection:$cons5"]['payload']['status'] ?? '') === 'PENDIENTE',
    json_encode($byId["consolidated_collection:$cons5"] ?? []));

echo "\n$pass passed, $fail failed\n";
exit($fail === 0 ? 0 : 1);

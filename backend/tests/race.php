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
foreach (['b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n'] as $n) {
    $accounts[$n] = $acc($n);
}
// Trabajos para el escenario de locks de orígenes (orden canónico).
$job1Id = $uid('job-j1');
$job2Id = $uid('job-j2');

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
// Trabajos compartidos por dos altas de Cuenta con el mismo conjunto de
// orígenes declarado en orden distinto (escenario de orden de bloqueo).
foreach ([$job1Id, $job2Id] as $jid) {
    $ops[] = ['operation_id' => $uid('op-' . $jid), 'entity_type' => 'job',
        'entity_id' => $jid, 'type' => 'upsert', 'base_server_version' => 0,
        'payload' => ['id' => $jid, 'userId' => $userId, 'clientId' => $clienteId,
            'profileId' => $perfilId,
            'provisionalNumber' => 'TR-PEND-' . strtoupper(substr($jid, -3)),
            'status' => 'BORRADOR', 'startDateEpochDay' => 21000,
            'estimatedDateEpochDay' => 21007, 'executionAddress' => '',
            'notes' => 'trabajo sintético race', 'clientNameSnapshot' => 'Cliente Race',
            'createdAt' => $now, 'updatedAt' => $now]];
}
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

/**
 * Espera (condición, no temporización) a que el trabajador quede bloqueado
 * en un lock de fila que retenemos. Criterio verificable en dos muestreos
 * consecutivos de PROCESSLIST (sin privilegio PROCESS — INNODB_TRX no es
 * accesible para este usuario):
 *  1. El hilo NO es esta conexión (`ID <> CONNECTION_ID()` — la consulta
 *     observadora contiene el literal 'FOR UPDATE' y se auto-coincidiría;
 *     sin esta exclusión la barrera es trivialmente cierta).
 *  2. Es del mismo usuario de base (la conexión del servidor local usa la
 *     misma cuenta), con la sentencia FOR UPDATE sobre `entities` en
 *     vuelo — la forma de todos los locks de los handlers.
 *  3. El MISMO hilo persiste ejecutando ese FOR UPDATE en dos muestreos
 *     separados por ~200 ms: un FOR UPDATE de una fila es instantáneo si
 *     no espera — persistir demuestra que su transacción está abierta y
 *     realmente bloqueada en el lock retenido, no solo pasando por él.
 * Devuelve los IDs de hilo detectados (diagnóstico y, p. ej., aborto de la
 * espera en el escenario de fallo transitorio). */
function waitForWorkerBlocked(PDO $pdo, int $limitIters = 150): array
{
    $seen = [];
    for ($i = 0; $i < $limitIters; $i++) {
        $ids = array_map('intval', $pdo->query(
            "SELECT ID FROM information_schema.PROCESSLIST
             WHERE ID <> CONNECTION_ID()
               AND USER = SUBSTRING_INDEX(USER(), '@', 1)
               AND INFO LIKE '%entities%FOR UPDATE%'"
        )->fetchAll(PDO::FETCH_COLUMN));
        $persistent = array_values(array_intersect($ids, $seen));
        if ($persistent !== []) {
            return $persistent;
        }
        $seen = $ids;
        usleep(200_000);
    }
    return [];
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
$blockedThreads = waitForWorkerBlocked($pdo);
check('race1_worker_blocked_on_member', $blockedThreads !== [], 'el push no apareció esperando FOR UPDATE');
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
$blockedThreads = waitForWorkerBlocked($pdo);
check('race2_worker_blocked_on_member', $blockedThreads !== [], 'el push no apareció esperando FOR UPDATE');
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
$blockedThreads = waitForWorkerBlocked($pdo);
check('race3_worker_blocked_on_member', $blockedThreads !== [], 'el push no apareció esperando FOR UPDATE');
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
$blockedThreads = waitForWorkerBlocked($pdo);
check('race5_worker_blocked_on_member', $blockedThreads !== [], 'el push no apareció esperando FOR UPDATE');
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
$blockedThreads = waitForWorkerBlocked($pdo);
check('race4_worker_blocked_on_member', $blockedThreads !== [], 'el push no apareció esperando FOR UPDATE');
$pdo->rollBack();
[$res, $err] = finishWorker($w);
check('race4_no_membership_APPLIED',
    ($res['results'][0]['status'] ?? '') === 'APPLIED',
    json_encode($res) . $err);
$byId = pullById($base, $token);
check('race4_consolidated_created',
    ($byId["consolidated_collection:$cons5"]['payload']['status'] ?? '') === 'PENDIENTE',
    json_encode($byId["consolidated_collection:$cons5"] ?? []));

// ==========  Escenario 6: orden canónico de locks de orígenes  ==========
// El orquestador retiene solo el lock de job-j1. El trabajador declara la
// Cuenta con orígenes [job-j2, job-j1] — el orden inverso al canónico.
// Con locking ordenado (id ascendente) el trabajador se bloquea en j1 SIN
// retener j2; con el orden declarado retendría j2 esperando j1 — y nuestro
// lock de j2 provocaría un interbloqueo real (InnoDB mataría a alguien).
$acc6 = $uid('acc-w');
$acc6Op = ['operation_id' => $uid('op-acc6'), 'entity_type' => 'collection_account',
    'entity_id' => $acc6, 'type' => 'upsert', 'base_server_version' => 0,
    'payload' => ['id' => $acc6, 'userId' => $userId, 'clientId' => $clienteId,
        'profileId' => $perfilId, 'provisionalNumber' => 'CC-PEND-W06',
        'status' => 'PENDIENTE', 'currency' => 'COP',
        'jobIds' => [$job2Id, $job1Id], 'quoteIds' => [],
        'items' => [['id' => $acc6 . '-i', 'position' => 1, 'conceptType' => 'SERVICIO',
            'description' => 'Servicio race6', 'quantity' => '1', 'unit' => '',
            'unitPrice' => '50.00', 'lineTotal' => '50.00']],
        'gross' => '50.00', 'totalRetained' => '0.00', 'net' => '50.00',
        'balance' => '50.00', 'issuedAt' => '2025-01-01', 'dueDateEpochDay' => null,
        'conditions' => '', 'clientSnapshotJson' => '{}', 'profileSnapshotJson' => '{}',
        'retentionsSnapshotJson' => '[]', 'clientNameSnapshot' => 'Cliente Race',
        'createdAt' => $now, 'updatedAt' => $now]];
$pdo->beginTransaction();
$pdo->prepare('SELECT id FROM entities WHERE entity_type = ? AND id = ? FOR UPDATE')
    ->execute(['job', $job1Id]);
$w = spawnWorker($base, $token, $device, [$acc6Op]);
$blockedThreads = waitForWorkerBlocked($pdo);
check('race6_worker_blocked_on_sorted_origin', $blockedThreads !== [],
    'el alta no apareció esperando el lock del primer origen ordenado');
// Bajo locking ordenado el trabajador no retiene j2: nuestro lock de j2
// debe adquirirse sin interbloqueo (bajo orden declarado provocaría un
// deadlock — InnoDB abortaría esta conexión o la del trabajador).
$j2Locked = true;
try {
    $pdo->prepare('SELECT id FROM entities WHERE entity_type = ? AND id = ? FOR UPDATE')
        ->execute(['job', $job2Id]);
} catch (Throwable $e) {
    $j2Locked = false;
}
check('race6_no_deadlock_j2_acquired', $j2Locked,
    'j2 no pudo bloquearse: el trabajador lo retenía esperando j1 (orden no canónico)');
$pdo->commit();
[$res, $err] = finishWorker($w);
check('race6_reversed_order_APPLIED',
    ($res['results'][0]['status'] ?? '') === 'APPLIED', json_encode($res) . $err);
$byId = pullById($base, $token);
check('race6_account_created', isset($byId["collection_account:$acc6"]),
    'la cuenta declarada con orígenes invertidos no quedó persistida');

// ==========  Escenario 7 (N6): fallo transitorio sin memoización  ==========
// El trabajador queda bloqueado en el lock del miembro m (ganadora:
// Consolidado C7 {m,n} a medio confirmar). Abortamos SU consulta con
// KILL QUERY — mismo usuario de base, permitido sin SUPER — produciendo
// un INTERNAL_ERROR transitorio real dentro de la transacción de la
// operación. La regla vigente: solo los resultados definitivos
// (APPLIED/REJECTED de negocio) se memorizan en processed_operations; un
// error interno hace rollback ANTES de la memorización y el mismo
// operation_id debe poder reintentarse y reevaluar el estado vigente.
$cons7 = $uid('cons-7');
beginWinnerTx($pdo, $userId, $cons7, 'CONS-PEND-RA7',
    [$accounts['m'], $accounts['n']], $accounts['m']);
$cons7w = $uid('cons-7w');
$op7 = consOp($uid('op-cons7w'), $cons7w, $userId, $clienteId,
    [$accounts['m'], $accounts['n']], $now);
$op7Id = $op7['operation_id'];
$w = spawnWorker($base, $token, $device, [$op7]);
$blockedThreads = waitForWorkerBlocked($pdo);
check('race7_worker_blocked_on_member', $blockedThreads !== [],
    'el push no apareció esperando FOR UPDATE');
if ($blockedThreads !== []) {
    // Aborto transitorio real: la sentencia en LOCK WAIT del trabajador.
    $pdo->exec('KILL QUERY ' . $blockedThreads[0]);
}
$pdo->commit();
[$res, $err] = finishWorker($w);
check('race7_first_attempt_INTERNAL_ERROR',
    ($res['results'][0]['status'] ?? '') === 'REJECTED'
    && ($res['results'][0]['code'] ?? '') === 'INTERNAL_ERROR',
    json_encode($res) . $err);
// Regla auditada: el INTERNAL_ERROR transitorio NO quedó memorizado —
// el rollback anterior a la memorización retiró cualquier rastro.
$memo = (int) $pdo->query(
    'SELECT COUNT(*) FROM processed_operations WHERE operation_id = ' . $pdo->quote($op7Id)
)->fetchColumn();
check('race7_no_memo_for_transient', $memo === 0,
    "processed_operations tiene $memo filas para $op7Id");
// Reintento con el MISMO operation_id + payload: debe reevaluar el estado
// vigente (Consolidado ya confirmado) y resolver sobre él — un rechazo de
// negocio distinto al INTERNAL_ERROR, no un replay de la falla transitoria.
[, $r] = http($base, 'POST', '/sync/push',
    ['device_id' => $device, 'operations' => [$op7]], $token);
check('race7_retry_reevaluates_business_rejection',
    ($r['results'][0]['status'] ?? '') === 'REJECTED'
    && ($r['results'][0]['code'] ?? '') === 'VALIDATION_ERROR',
    json_encode($r));
$byId = pullById($base, $token);
$consWithM = [];
foreach ($byId as $key => $ch) {
    if (str_starts_with($key, 'consolidated_collection:')
        && in_array($accounts['m'], $ch['payload']['accountIds'] ?? [], true)) {
        $consWithM[] = $key;
    }
}
check('race7_single_active_membership', $consWithM === ["consolidated_collection:$cons7"],
    json_encode($consWithM));

// =====================  Escenario 8 (F8): serialización sobre Material  =====================
// Una Compra CONFIRMADA deriva movimientos y saldo proyectado en la misma
// transacción, bloqueando la fila del Material (orden canónico). El
// orquestador retiene ese lock como "ganadora"; el trabajador empuja la
// confirmación y debe quedar bloqueado en el FOR UPDATE del movimiento
// derivado — tras el commit todo aplica junto: transición + movimiento
// PURCHASE_IN + saldo, nunca a medias.
$matR = $uid('mat-r');
$purR = $uid('pur-r');
[$c, $r] = http($base, 'POST', '/sync/push', ['device_id' => $device,
    'operations' => [
        ['operation_id' => $uid('op-mat-r'), 'entity_type' => 'material', 'entity_id' => $matR,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $matR, 'userId' => $userId, 'name' => 'Material Race',
            'unit' => 'kg', 'description' => '', 'archived' => false,
            'createdAt' => $now, 'updatedAt' => $now]],
        ['operation_id' => $uid('op-pur-r'), 'entity_type' => 'purchase', 'entity_id' => $purR,
         'type' => 'upsert', 'base_server_version' => 0,
         'payload' => ['id' => $purR, 'userId' => $userId, 'provisionalNumber' => 'COM-PEND-RA8',
            'status' => 'BORRADOR', 'purchaseDateEpochDay' => 21005,
            'supplierPlace' => '', 'reference' => '', 'notes' => '', 'currency' => 'COP',
            'totalAmount' => '0.00', 'archived' => false,
            'items' => [['id' => $purR . '-i', 'position' => 1, 'materialId' => $matR,
                'quantity' => '7', 'unit' => 'kg', 'unitPrice' => '10.00',
                'lineTotal' => '70.00', 'condition' => 'NUEVO', 'createdAt' => $now]],
            'createdAt' => $now, 'updatedAt' => $now]],
    ]], $token);
check('race8_seed_applied', array_column($r['results'] ?? [], 'status') === ['APPLIED', 'APPLIED'],
    json_encode($r));

$pdo->beginTransaction();
$pdo->prepare('SELECT id FROM entities WHERE entity_type = ? AND id = ? FOR UPDATE')
    ->execute(['material', $matR]);
$w = spawnWorker($base, $token, $device,
    [['operation_id' => $uid('op-pur-r-confirm'), 'entity_type' => 'purchase', 'entity_id' => $purR,
      'type' => 'upsert', 'base_server_version' => 1,
      'payload' => ['id' => $purR, 'userId' => $userId, 'provisionalNumber' => 'COM-PEND-RA8',
        'status' => 'CONFIRMADA', 'purchaseDateEpochDay' => 21005,
        'supplierPlace' => '', 'reference' => '', 'notes' => '', 'currency' => 'COP',
        'totalAmount' => '70.00', 'archived' => false,
        'items' => [['id' => $purR . '-i', 'position' => 1, 'materialId' => $matR,
            'quantity' => '7', 'unit' => 'kg', 'unitPrice' => '10.00',
            'lineTotal' => '70.00', 'condition' => 'NUEVO', 'createdAt' => $now]],
        'createdAt' => $now, 'updatedAt' => $now]]]);
$blockedThreads = waitForWorkerBlocked($pdo);
check('race8_confirm_blocked_on_material', $blockedThreads !== [],
    'la confirmación no apareció esperando el lock del material');
$pdo->commit();
[$res, $err] = finishWorker($w);
check('race8_confirm_APPLIED_atomic',
    ($res['results'][0]['status'] ?? '') === 'APPLIED', json_encode($res) . $err);
$byId = pullById($base, $token);
$movR = array_values(array_filter($byId,
    fn($ch) => $ch['entity_type'] === 'inventory_movement'
        && ($ch['payload']['purchaseId'] ?? null) === $purR));
check('race8_movement_and_stock_together',
    ($byId["purchase:$purR"]['payload']['status'] ?? '') === 'CONFIRMADA'
    && count($movR) === 1
    && ($movR[0]['payload']['movementType'] ?? '') === 'PURCHASE_IN'
    && ($movR[0]['payload']['quantity'] ?? '') === '7'
    && ($byId["inventory_stock:$matR"]['payload']['stockNew'] ?? '') === '7',
    json_encode([$byId["purchase:$purR"]['payload']['status'] ?? null,
        count($movR), $byId["inventory_stock:$matR"]['payload'] ?? null]));

echo "\n$pass passed, $fail failed\n";
exit($fail === 0 ? 0 : 1);

<?php

declare(strict_types=1);

/**
 * Bootstrap común de la API CotixGo (desarrollo local, solo datos sintéticos).
 * Config por variables de entorno con valores dev por defecto:
 *   COTIXGO_DB_DSN, COTIXGO_DB_USER, COTIXGO_DB_PASS
 */

// Los errores PHP se registran, nunca se imprimen: un warning antes de los
// headers rompería los códigos de respuesta (413/415/500 saldrían como 200).
ini_set('display_errors', '0');
ini_set('log_errors', '1');
error_reporting(E_ALL);

function db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $dsn = getenv('COTIXGO_DB_DSN') ?: 'mysql:host=127.0.0.1;port=3306;dbname=cotixgo_dev;charset=utf8mb4';
        $pdo = new PDO(
            $dsn,
            getenv('COTIXGO_DB_USER') ?: 'cotixgo',
            getenv('COTIXGO_DB_PASS') ?: 'devlocal',
            [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_EMULATE_PREPARES => false,
            ]
        );
        // READ COMMITTED: las validaciones estructurales son lecturas
        // consistentes que deben observar el estado vigente al ejecutarse,
        // no el snapshot fijado al inicio de la transacción. Con REPEATABLE
        // READ una alta concurrente de Consolidado confirmada durante la
        // espera por un bloqueo de fila quedaría invisible para los chequeos
        // de membresía posteriores (revisión PR #15, hallazgo M1). Las
        // mutaciones financieras ya se serializan con lecturas FOR UPDATE.
        // El nivel de aislamiento se ajusta solo en MySQL: otros drivers
        // locales de desarrollo (p. ej. SQLite para pruebas de transporte)
        // no aceptan la sintaxis SET SESSION.
        if ($pdo->getAttribute(PDO::ATTR_DRIVER_NAME) === 'mysql') {
            $pdo->exec('SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED');
        }
    }
    return $pdo;
}

function jsonBody(): array
{
    $raw = file_get_contents('php://input');
    if ($raw === false || trim($raw) === '') {
        return [];
    }
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function respond(int $code, array $body): void
{
    if (!headers_sent()) {
        http_response_code($code);
    }
    echo json_encode($body, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    exit;
}

function fail(int $http, string $code, string $message, ?array $details = null): void
{
    $error = ['code' => $code, 'message' => $message];
    if ($details !== null) {
        $error['details'] = $details;
    }
    respond($http, ['error' => $error]);
}

function uuid(): string
{
    $d = random_bytes(16);
    $d[6] = chr((ord($d[6]) & 0x0f) | 0x40);
    $d[8] = chr((ord($d[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($d), 4));
}

function nowUtc(): string
{
    return gmdate('Y-m-d H:i:s');
}

/** Devuelve el user_id autenticado por bearer token o responde 401. */
function authUserId(): string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer\s+(\S+)$/i', $header, $m)) {
        fail(401, 'UNAUTHENTICATED', 'Falta el token de sesión.');
    }
    $stmt = db()->prepare(
        'SELECT user_id FROM auth_tokens WHERE token = ? AND expires_at > ?'
    );
    $stmt->execute([$m[1], nowUtc()]);
    $row = $stmt->fetch();
    if ($row === false) {
        fail(401, 'UNAUTHENTICATED', 'Sesión inválida o expirada.');
    }
    return $row['user_id'];
}

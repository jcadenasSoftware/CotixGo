<?php

declare(strict_types=1);

/**
 * CotixGo API — front controller (F3, desarrollo local).
 * PHP puro + PDO MySQL/MariaDB. Solo datos sintéticos.
 * Contrato: shared/contracts/openapi.yaml · errores: shared/contracts/error-codes.md.
 */

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../src/bootstrap.php';
require_once __DIR__ . '/../src/auth.php';
require_once __DIR__ . '/../src/sync.php';
require_once __DIR__ . '/../src/domain.php';
require_once __DIR__ . '/../src/photos.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';

// El docroot puede ser backend/public o un subdirectorio en Hostinger;
// se normaliza la ruta respecto al directorio del script.
$scriptDir = str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? ''));
$scriptDir = rtrim($scriptDir, '/');
if ($scriptDir !== '' && str_starts_with($path, $scriptDir)) {
    $path = substr($path, strlen($scriptDir)) ?: '/';
}

try {
    if ($method === 'GET' && $path === '/health') {
        respond(200, ['status' => 'ok', 'service' => 'cotixgo-api']);
    }
    if ($method === 'POST' && $path === '/auth/register') {
        handleRegister();
    }
    if ($method === 'POST' && $path === '/auth/login') {
        handleLogin();
    }
    if ($method === 'POST' && $path === '/auth/recover') {
        handleRecover();
    }
    if ($method === 'POST' && $path === '/sync/push') {
        handlePush();
    }
    if ($method === 'GET' && $path === '/sync/pull') {
        handlePull();
    }
    if ($method === 'POST' && $path === '/photos/upload') {
        // El binario viaja fuera del JSON: el header JSON del front controller
        // no aplica al stream de descarga, pero sí a las respuestas de error.
        handlePhotoUpload();
    }
    if ($method === 'GET' && $path === '/photos/download') {
        handlePhotoDownload();
    }
    fail(404, 'NOT_FOUND', 'Endpoint no encontrado.');
} catch (Throwable $e) {
    error_log('[cotixgo] ' . $e->getMessage());
    fail(500, 'INTERNAL_ERROR', 'Error interno del servidor.');
}

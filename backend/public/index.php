<?php

declare(strict_types=1);

/**
 * CotixGo API — front controller (skeleton E0).
 *
 * PHP puro, sin framework: Slim 4 + Composer llegan en E1.
 * Contrato de errores: ver shared/contracts/error-codes.md.
 */

header('Content-Type: application/json; charset=utf-8');

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';

// El docroot puede ser backend/public o un subdirectorio en Hostinger;
// se normaliza la ruta respecto al directorio del script.
$scriptDir = str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? ''));
$scriptDir = rtrim($scriptDir, '/');
if ($scriptDir !== '' && str_starts_with($path, $scriptDir)) {
    $path = substr($path, strlen($scriptDir)) ?: '/';
}

if ($method === 'GET' && $path === '/health') {
    http_response_code(200);
    echo json_encode([
        'status' => 'ok',
        'service' => 'cotixgo-api',
    ], JSON_THROW_ON_ERROR);
    exit;
}

http_response_code(404);
echo json_encode([
    'error' => [
        'code' => 'NOT_FOUND',
        'message' => 'Endpoint no encontrado.',
    ],
], JSON_THROW_ON_ERROR);

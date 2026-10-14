<?php

declare(strict_types=1);

/**
 * Trabajador de la prueba de carrera (race.php): ejecuta UN push real contra
 * la API local con el cuerpo JSON dado en un archivo temporal y vuelca la
 * respuesta a stdout. Datos exclusivamente sintéticos.
 *
 * Uso: php race_worker.php <base_url> <token> <json_body_file>
 */

[$script, $base, $token, $bodyFile] = $argv;

$body = file_get_contents($bodyFile);
if ($body === false) {
    fwrite(STDERR, "no se pudo leer $bodyFile\n");
    exit(2);
}

$ctx = stream_context_create(['http' => [
    'method' => 'POST',
    'header' => "Content-Type: application/json\r\nAuthorization: Bearer $token\r\n",
    'content' => $body,
    'ignore_errors' => true,
    // El push puede quedar bloqueado en un lock de fila: sin límite corto de
    // cliente; el orquestador controla la liberación determinística.
    'timeout' => 120,
]]);
$raw = @file_get_contents($base . '/sync/push', false, $ctx);
if ($raw === false) {
    fwrite(STDERR, "push falló\n");
    exit(3);
}
echo $raw;

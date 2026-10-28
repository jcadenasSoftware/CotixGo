<?php

declare(strict_types=1);

/**
 * Fotos de evidencia de Trabajos: transporte binario autenticado.
 *
 * Los METADATOS viajan por el canal de sync ordinario (entidad `job_photo`
 * con payload JSON: jobId, fileName, createdAt, deleted). Este endpoint solo
 * mueve el binario:
 *
 *   POST /photos/upload?id=<job_photo entity id>   (cuerpo binario o multipart)
 *   GET  /photos/download?id=<job_photo entity id>
 *
 * Autorización: la fila `job_photo` de `entities` queda ligada al user_id en
 * su primera escritura aceptada — el archivo solo se lee/escribe si esa fila
 * pertenece a la cuenta autenticada. Fuera del docroot (backend/storage), sin
 * URLs públicas ni nombres predecibles.
 *
 * Tombstone: el borrado viaja como payload `{"deleted":true}` de la misma
 * entidad (sync ordinario); la descarga responde 404 sobre fotos borradas.
 */

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/sync.php';

const PHOTO_MAX_BYTES = 8 * 1024 * 1024; // 8 MB por foto

function photoStorageDir(string $userId): string
{
    $base = __DIR__ . '/../storage/photos/' . $userId;
    if (!is_dir($base)) {
        mkdir($base, 0700, true);
    }
    return $base;
}

/** Ruta del binario almacenado; null si no existe. */
function photoStoredPath(string $userId, string $photoId): ?string
{
    $dir = __DIR__ . '/../storage/photos/' . $userId;
    foreach (glob("$dir/$photoId.*") ?: [] as $f) {
        return $f;
    }
    return null;
}

/** Borra el binario almacenado (tombstone aceptado): sin huérfanos. La fila
 *  `job_photo` con deleted:true se conserva para propagar el borrado. */
function photoDeleteStoredBinary(string $userId, string $photoId): void
{
    $dir = __DIR__ . '/../storage/photos/' . $userId;
    foreach (glob("$dir/$photoId.*") ?: [] as $f) {
        @unlink($f);
    }
}

/** Fila job_photo del usuario autenticado o null (con existencia verificada). */
function photoEntityRow(PDO $pdo, string $userId, string $photoId): ?array
{
    $stmt = $pdo->prepare(
        'SELECT id, user_id, payload FROM entities WHERE entity_type = ? AND id = ?'
    );
    $stmt->execute(['job_photo', $photoId]);
    $row = $stmt->fetch();
    return ($row !== false && $row['user_id'] === $userId) ? $row : null;
}

function handlePhotoUpload(): void
{
    $userId = authUserId();
    $photoId = trim((string) ($_GET['id'] ?? ''));
    if (!preg_match('/^[0-9a-fA-F-]{36}$/', $photoId)) {
        fail(400, 'VALIDATION_ERROR', 'id de foto inválido.');
    }

    $pdo = db();
    $row = photoEntityRow($pdo, $userId, $photoId);
    if ($row === null) {
        // La entidad debe existir antes del binario: el cliente sincroniza el
        // metadato (push) antes de subir el archivo. 409 → reintento del cliente.
        fail(409, 'PHOTO_ENTITY_MISSING', 'La foto no está registrada; sincroniza primero su metadato.');
    }
    $state = json_decode($row['payload'], true) ?: [];
    if (($state['deleted'] ?? false) === true) {
        fail(409, 'PHOTO_DELETED', 'La foto está marcada como borrada.');
    }

    // Fuente binaria: multipart $_FILES['photo'] o cuerpo crudo del POST.
    if (isset($_FILES['photo']) && is_uploaded_file($_FILES['photo']['tmp_name'])) {
        $tmp = $_FILES['photo']['tmp_name'];
        $size = (int) $_FILES['photo']['size'];
        $move = fn(string $dst) => @move_uploaded_file($tmp, $dst);
    } else {
        $raw = file_get_contents('php://input');
        if ($raw === false || $raw === '') {
            fail(400, 'VALIDATION_ERROR', 'Cuerpo de imagen vacío.');
        }
        $size = strlen($raw);
        $tmp = tempnam(sys_get_temp_dir(), 'pg');
        file_put_contents($tmp, $raw);
        $move = fn(string $dst) => @rename($tmp, $dst);
    }

    if ($size <= 0 || $size > PHOTO_MAX_BYTES) {
        @unlink($tmp);
        fail(413, 'PHOTO_TOO_LARGE', 'La imagen supera el máximo de 8 MB.');
    }

    // Tipo REAL: nunca el nombre ni el MIME declarado por el cliente.
    $info = @getimagesize($tmp);
    if ($info === false || !in_array($info[2], [IMAGETYPE_JPEG, IMAGETYPE_PNG], true)) {
        @unlink($tmp);
        fail(415, 'PHOTO_TYPE_INVALID', 'Solo se aceptan imágenes JPEG o PNG reales.');
    }
    $ext = image_type_to_extension($info[2]);

    $dir = photoStorageDir($userId);
    // Limpieza de variantes previas del mismo id (idempotencia de reintento).
    foreach (glob("$dir/$photoId.*") ?: [] as $old) {
        @unlink($old);
    }
    if (!$move("$dir/$photoId$ext")) {
        @unlink($tmp);
        fail(500, 'INTERNAL_ERROR', 'No se pudo guardar la imagen.');
    }
    chmod("$dir/$photoId$ext", 0600);

    respond(200, ['status' => 'ok', 'bytes' => $size]);
}

function handlePhotoDownload(): void
{
    $userId = authUserId();
    $photoId = trim((string) ($_GET['id'] ?? ''));
    if (!preg_match('/^[0-9a-fA-F-]{36}$/', $photoId)) {
        fail(400, 'VALIDATION_ERROR', 'id de foto inválido.');
    }

    $pdo = db();
    $row = photoEntityRow($pdo, $userId, $photoId);
    if ($row === null) {
        fail(404, 'PHOTO_NOT_FOUND', 'Foto no encontrada.');
    }
    $state = json_decode($row['payload'], true) ?: [];
    if (($state['deleted'] ?? false) === true) {
        fail(404, 'PHOTO_NOT_FOUND', 'Foto no encontrada.');
    }

    $path = photoStoredPath($userId, $photoId);
    if ($path === null) {
        fail(404, 'PHOTO_NOT_FOUND', 'Foto no encontrada.');
    }

    $info = @getimagesize($path);
    $mime = $info !== false ? ($info['mime'] ?? 'application/octet-stream') : 'application/octet-stream';
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . filesize($path));
    header('Cache-Control: private, max-age=86400');
    readfile($path);
    exit;
}

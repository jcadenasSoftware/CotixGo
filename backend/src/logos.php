<?php

declare(strict_types=1);

/**
 * Logos del perfil profesional: transporte binario autenticado.
 *
 * La REFERENCIA (logoRef = nombre de archivo inmutable `logo_<uuid>.<ext>`)
 * viaja dentro del payload JSON de la entidad `profile` por el canal de
 * sync ordinario. Este endpoint solo mueve el binario:
 *
 *   POST /logos/upload?ref=logo_<uuid>.png   (cuerpo binario o multipart)
 *   GET  /logos/download?ref=logo_<uuid>.png
 *
 * Aislamiento: los archivos viven en backend/storage/logos/<user_id>/ —
 * cada cuenta solo lee y escribe su propio directorio, fuera del docroot,
 * sin URLs públicas. Los refs son identificadores únicos generados por el
 * cliente (immutables: un logo nuevo es un archivo nuevo, los snapshots
 * anteriores siguen apuntando a su archivo original).
 */

require_once __DIR__ . '/bootstrap.php';
require_once __DIR__ . '/sync.php';

const LOGO_MAX_BYTES = 4 * 1024 * 1024; // 4 MB por logo
const LOGO_REF_RE = '/^logo_[0-9a-f-]{8,64}\.(png|jpg|jpeg|webp)$/';

function logoStorageDir(string $userId): string
{
    $base = __DIR__ . '/../storage/logos/' . $userId;
    if (!is_dir($base)) {
        mkdir($base, 0700, true);
    }
    return $base;
}

function logoStoredPath(string $userId, string $ref): ?string
{
    $path = __DIR__ . '/../storage/logos/' . $userId . '/' . $ref;
    return is_file($path) ? $path : null;
}

function handleLogoUpload(): void
{
    $userId = authUserId();
    $ref = trim((string) ($_GET['ref'] ?? ''));
    if (!preg_match(LOGO_REF_RE, $ref)) {
        fail(400, 'VALIDATION_ERROR', 'Referencia de logo inválida.');
    }

    if (isset($_FILES['logo']) && is_uploaded_file($_FILES['logo']['tmp_name'])) {
        $tmp = $_FILES['logo']['tmp_name'];
        $size = (int) $_FILES['logo']['size'];
        $move = fn(string $dst) => @move_uploaded_file($tmp, $dst);
    } else {
        $raw = file_get_contents('php://input');
        if ($raw === false || $raw === '') {
            fail(400, 'VALIDATION_ERROR', 'Cuerpo de imagen vacío.');
        }
        $size = strlen($raw);
        $tmp = tempnam(sys_get_temp_dir(), 'lg');
        file_put_contents($tmp, $raw);
        $move = fn(string $dst) => @rename($tmp, $dst);
    }

    if ($size <= 0 || $size > LOGO_MAX_BYTES) {
        @unlink($tmp);
        fail(413, 'LOGO_TOO_LARGE', 'La imagen supera el máximo de 4 MB.');
    }

    // Tipo REAL: nunca el nombre ni el MIME declarado por el cliente.
    $info = @getimagesize($tmp);
    if ($info === false || !in_array($info[2], [IMAGETYPE_JPEG, IMAGETYPE_PNG, IMAGETYPE_WEBP], true)) {
        @unlink($tmp);
        fail(415, 'LOGO_TYPE_INVALID', 'Solo se aceptan imágenes JPEG, PNG o WebP reales.');
    }
    // La extensión del ref debe coincidir con el tipo real del contenido.
    $realExt = ltrim(image_type_to_extension($info[2]), '.');
    $refExt = strtolower(pathinfo($ref, PATHINFO_EXTENSION));
    if (($realExt === 'jpeg' ? 'jpg' : $realExt) !== ($refExt === 'jpeg' ? 'jpg' : $refExt)) {
        @unlink($tmp);
        fail(415, 'LOGO_TYPE_MISMATCH', 'La extensión no coincide con el contenido de la imagen.');
    }

    $dst = logoStorageDir($userId) . '/' . $ref;
    if (is_file($dst)) {
        // Inmutable: el ref ya tiene binario — idempotencia de reintento.
        @unlink($tmp);
        respond(200, ['status' => 'ok', 'bytes' => $size]);
    }
    if (!$move($dst)) {
        @unlink($tmp);
        fail(500, 'INTERNAL_ERROR', 'No se pudo guardar la imagen.');
    }
    chmod($dst, 0600);

    respond(200, ['status' => 'ok', 'bytes' => $size]);
}

function handleLogoDownload(): void
{
    $userId = authUserId();
    $ref = trim((string) ($_GET['ref'] ?? ''));
    if (!preg_match(LOGO_REF_RE, $ref)) {
        fail(400, 'VALIDATION_ERROR', 'Referencia de logo inválida.');
    }

    $path = logoStoredPath($userId, $ref);
    if ($path === null) {
        fail(404, 'LOGO_NOT_FOUND', 'El logo no está disponible.');
    }

    $mime = [
        'png' => 'image/png',
        'jpg' => 'image/jpeg',
        'jpeg' => 'image/jpeg',
        'webp' => 'image/webp',
    ][strtolower(pathinfo($ref, PATHINFO_EXTENSION))] ?? 'application/octet-stream';

    header('Content-Type: ' . $mime);
    header('Content-Length: ' . filesize($path));
    header('Cache-Control: private, max-age=31536000, immutable');
    readfile($path);
    exit;
}

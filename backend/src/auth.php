<?php

declare(strict_types=1);

/**
 * Autenticación F3: registro e inicio de sesión con email + contraseña y
 * recuperación por correo mediante mecanismo LOCAL de prueba (mail_outbox —
 * no envía correo real; proveedor pendiente). Solo datos sintéticos.
 */

require_once __DIR__ . '/bootstrap.php';

function issueToken(PDO $pdo, string $userId): array
{
    $token = bin2hex(random_bytes(32));
    $expires = gmdate('Y-m-d H:i:s', time() + 30 * 86400);
    $pdo->prepare('INSERT INTO auth_tokens (token, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)')
        ->execute([$token, $userId, $expires, nowUtc()]);
    return ['user_id' => $userId, 'token' => $token, 'expires_at' => str_replace(' ', 'T', $expires) . 'Z'];
}

function handleRegister(): void
{
    $in = jsonBody();
    $email = trim((string) ($in['email'] ?? ''));
    $password = (string) ($in['password'] ?? '');
    $localUserId = (string) ($in['local_user_id'] ?? '');

    if (!filter_var($email, FILTER_VALIDATE_EMAIL) || strlen($password) < 6) {
        fail(400, 'VALIDATION_ERROR', 'Email válido y contraseña de al menos 6 caracteres son obligatorios.');
    }
    $pdo = db();

    $stmt = $pdo->prepare('SELECT id FROM users WHERE email = ?');
    $stmt->execute([$email]);
    if ($stmt->fetch() !== false) {
        fail(409, 'ACCOUNT_EXISTS', 'Ya existe una cuenta con ese correo. Usa inicio de sesión.');
    }

    // La identidad canónica es la cuenta del servidor: se adopta el UUID local
    // si está libre; si ya pertenece a otra cuenta se emite uno nuevo.
    $userId = $localUserId !== '' && preg_match('/^[0-9a-fA-F-]{36}$/', $localUserId) ? $localUserId : '';
    if ($userId !== '') {
        $stmt = $pdo->prepare('SELECT id FROM users WHERE id = ?');
        $stmt->execute([$userId]);
        if ($stmt->fetch() !== false) {
            $userId = '';
        }
    }
    if ($userId === '') {
        $userId = uuid();
    }

    $pdo->prepare('INSERT INTO users (id, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)')
        ->execute([$userId, $email, password_hash($password, PASSWORD_DEFAULT), nowUtc(), nowUtc()]);
    audit($pdo, $userId, null, null, 'user', $userId, 'REGISTER', ['email' => $email]);

    respond(200, issueToken($pdo, $userId));
}

function handleLogin(): void
{
    $in = jsonBody();
    $email = trim((string) ($in['email'] ?? ''));
    $password = (string) ($in['password'] ?? '');

    $pdo = db();
    $stmt = $pdo->prepare('SELECT id, password_hash FROM users WHERE email = ?');
    $stmt->execute([$email]);
    $row = $stmt->fetch();
    if ($row === false || !password_verify($password, $row['password_hash'])) {
        fail(401, 'INVALID_CREDENTIALS', 'Correo o contraseña incorrectos.');
    }
    audit($pdo, $row['id'], null, null, 'user', $row['id'], 'LOGIN', null);
    respond(200, issueToken($pdo, $row['id']));
}

function handleRecover(): void
{
    $in = jsonBody();
    $email = trim((string) ($in['email'] ?? ''));
    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
        fail(400, 'VALIDATION_ERROR', 'Email no válido.');
    }

    $pdo = db();
    $stmt = $pdo->prepare('SELECT id FROM users WHERE email = ?');
    $stmt->execute([$email]);
    if ($stmt->fetch() !== false) {
        // Mecanismo LOCAL de prueba: el "correo" se registra, no se envía.
        $token = bin2hex(random_bytes(16));
        $pdo->prepare('INSERT INTO mail_outbox (email, subject, body, created_at) VALUES (?, ?, ?, ?)')
            ->execute([
                $email,
                'CotixGo — Recuperación de acceso',
                "Token de recuperación (entorno local de prueba): $token",
                nowUtc(),
            ]);
    }
    // Respuesta uniforme para no revelar si el correo existe.
    respond(200, ['status' => 'ok']);
}

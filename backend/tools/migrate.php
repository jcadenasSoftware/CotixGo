<?php

declare(strict_types=1);

/**
 * Aplica migraciones pendientes de backend/migrations (orden por nombre).
 * Uso dev local: php -c backend/dev.ini backend/tools/migrate.php
 */

require __DIR__ . '/../src/bootstrap.php';

$files = glob(__DIR__ . '/../migrations/*.sql') ?: [];
sort($files);
$pdo = db();

$pdo->exec(
    'CREATE TABLE IF NOT EXISTS schema_migrations (
        id INT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(255) NOT NULL UNIQUE,
        applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    )'
);

$applied = [];
foreach ($pdo->query('SELECT name FROM schema_migrations') as $row) {
    $applied[$row['name']] = true;
}

foreach ($files as $file) {
    $name = basename($file);
    if (isset($applied[$name])) {
        echo "skip  $name\n";
        continue;
    }
    $sql = file_get_contents($file);
    // División simple por ';' al final de línea (sin procedimientos ni triggers).
    foreach (preg_split('/;\s*\n/', (string) $sql) as $stmt) {
        $stmt = trim($stmt);
        if ($stmt !== '') {
            $pdo->exec($stmt);
        }
    }
    $pdo->prepare('INSERT INTO schema_migrations (name) VALUES (?)')->execute([$name]);
    echo "apply $name\n";
}
echo "done\n";

# CotixGo Backend

API REST en PHP sobre MySQL, desplegada en Hostinger, conforme a `docs/05-arquitectura-y-tecnologias.md`.

## Estado actual (F3, desarrollo local)

PHP puro sin framework ni Composer (elección técnica registrada en `docs/05` §5.1):

- `public/` — docroot público; `index.php` enruta `GET /health`, `POST /auth/register`, `POST /auth/login`, `POST /auth/recover`, `POST /sync/push`, `GET /sync/pull`.
- `src/` — `bootstrap.php` (PDO, JSON, auth por token bearer), `domain.php` (transiciones de Cotización y numeración oficial), `auth.php`, `sync.php`.
- `migrations/` — scripts versionados `NNN_*.sql` aplicados por `tools/migrate.php` (tabla de control `schema_migrations`).
- `tests/e2e.php` — verificación end-to-end del API con datos sintéticos.
- `dev.ini` — config PHP de desarrollo (habilita `pdo_mysql`).

## Desarrollo local

Requisito: PHP 8.x CLI con `pdo_mysql` y MariaDB/MySQL local en `127.0.0.1:3306` (base `cotixgo_dev`; credenciales de desarrollo en variables `COTIXGO_DB_*` o valores por defecto de `src/bootstrap.php`).

```powershell
php -c backend/dev.ini backend/tools/migrate.php
php -c backend/dev.ini -S 0.0.0.0:8080 -t backend/public
php -c backend/dev.ini backend/tests/e2e.php
```

Respuesta de `GET /health`: `{"status":"ok","service":"cotixgo-api"}`. La recuperación de acceso usa `mail_outbox` local — no envía correo real (proveedor pendiente). Solo datos sintéticos.

## Despliegue (Hostinger)

El docroot del virtual host debe apuntar a `backend/public/`. El archivo `.htaccess` dirige todas las rutas al front controller.

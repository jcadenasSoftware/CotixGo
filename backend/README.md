# CotixGo Backend

API REST en PHP sobre MySQL, desplegada en Hostinger, conforme a `docs/05-arquitectura-y-tecnologias.md`.

## Estado actual (E0)

Skeleton mínimo sin framework ni dependencias externas:

- `public/` — docroot público; `index.php` actúa como front controller y expone `GET /health`.
- `src/` — código de aplicación (vacío; llega en E1 junto con Slim 4 vía Composer).

## Desarrollo local

Requisito: PHP 8.x CLI.

```powershell
php -S localhost:8080 -t backend/public
curl http://localhost:8080/health
```

Respuesta esperada: HTTP 200 con `{"status":"ok","service":"cotixgo-api"}`.

## Despliegue (Hostinger)

El docroot del virtual host debe apuntar a `backend/public/`. El archivo `.htaccess` dirige todas las rutas al front controller.

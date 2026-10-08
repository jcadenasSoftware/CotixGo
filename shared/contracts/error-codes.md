# CotixGo — Convención de errores de API (v0.1)

Convención compartida entre backend PHP y clientes (Android/web). Borrador de E0; se extiende cuando se apruebe el contrato de endpoints completo.

## Envelope de error

Toda respuesta de error usa HTTP apropiado y cuerpo JSON:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "El campo 'client_id' es obligatorio.",
    "details": { "field": "client_id" }
  }
}
```

- `code`: identificador estable en `SCREAMING_SNAKE_CASE`; no cambia entre versiones menores.
- `message`: texto legible para el usuario/desarrollador.
- `details`: objeto opcional con contexto (campo, entidad, conflicto).

## Códigos iniciales

| HTTP | `code` | Uso |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Entrada inválida o incompleta. |
| 400 | `IDEMPOTENCY_KEY_REUSED` | Misma `operation_id` con payload distinto. |
| 401 | `UNAUTHENTICATED` | Sesión/token ausente o inválido. |
| 403 | `FORBIDDEN` | Autenticado pero sin permiso sobre el recurso. |
| 404 | `NOT_FOUND` | Recurso o endpoint inexistente. |
| 409 | `SYNC_CONFLICT` | Operación sincronizada rechazada por conflicto de estado/versión. |
| 409 | `INVALID_STATE_TRANSITION` | Transición no permitida según el estado actual de la entidad. |
| 422 | `BUSINESS_RULE_VIOLATION` | Regla de negocio incumplida (p. ej. intentar aplicar a una Cuenta o Consolidado un importe mayor que su saldo pendiente). |
| 500 | `INTERNAL_ERROR` | Error no controlado del servidor. |

## Reglas

- Los clientes deben tratar `code` como contrato estable y `message` como texto variable.
- Errores de sincronización deben incluir `details.entity_id` y `details.operation_id` cuando apliquen.
- Un error nunca deja al cliente con estado ambiguo: la operación fue aceptada, rechazada o marcada como conflicto.

### Pagos y saldos (D13-B)

- El importe **aplicado** a una Cuenta de Cobro o Consolidado no puede superar su saldo pendiente.
- El importe **total recibido** de un pago sí puede superar el saldo de un documento: al asignarse se aplica únicamente lo necesario para cubrirlo y el excedente permanece `UNASSIGNED` dentro del mismo pago, disponible para futuras asignaciones.
- El excedente `UNASSIGNED` no es un error de negocio: es un estado legítimo del pago y no debe devolverse como `BUSINESS_RULE_VIOLATION`.

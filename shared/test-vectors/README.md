# CotixGo — Test vectors compartidos (convención v0)

Vectores de prueba agnósticos de plataforma: Android (Kotlin) y backend (PHP) ejecutan los mismos casos y deben producir los mismos resultados. Fuente de verdad de comportamiento: `docs/03`, `docs/08` y demás documentos aprobados.

## Formato

Cada vector es un archivo JSON:

```json
{
  "id": "calc-mixed-service-material",
  "name": "Retención 6% solo sobre SERVICIO",
  "description": "Materiales $800.000 + servicios $600.000, retención 6% → $36.000; bruto $1.400.000, neto $1.364.000.",
  "category": "calc",
  "input": { "...": "..." },
  "expected": { "...": "..." }
}
```

## Directorios previstos

- `calc/` — motor de cálculo de documentos: retenciones por `applies_to`, múltiples retenciones, redondeo por línea, cálculo inverso desde neto, `OTRO` según configuración.
- `transitions/` — transiciones válidas/inválidas por entidad (Quote, Job, CollectionAccount, Report, Compra, Herramienta).
- `sync/` — idempotencia por `operation_id`, provisional `TIPO-PEND-XXXX` → oficial sobre el mismo `entity_id`, asignación de pagos con aplicación parcial (excedente `UNASSIGNED`), `payment_adjustments` (corrección/anulación), conflictos.

## Reglas

- Los vectores se agregan/actualizan en la etapa que implemente el comportamiento (E2+); en E0 solo existe esta convención.
- Todo vector cita la regla aprobada que valida (`description` o campo `rule_ref`).
- Un cambio de regla de negocio aprobado debe actualizar los vectores afectados en el mismo commit documental o de implementación.

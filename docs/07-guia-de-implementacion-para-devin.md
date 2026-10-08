# CotixGo — Guía de implementación para Devin

**Versión:** 1.0  
**Estado:** Guía de transferencia; no autoriza por sí sola el inicio del código.

## 1. Propósito

Implementar CotixGo siguiendo la especificación del producto. Devin implementa el código; estas decisiones de producto y contratos documentados son la fuente de verdad. No reinterpretar el diseño para cambiar reglas de negocio.

## 2. Lectura obligatoria y precedencia

Leer en este orden:

1. `01-reglas-de-negocio-v0.2.md`.
2. `03-anexo-decisiones-aprobadas-y-brechas.md`.
3. `04-alcance-y-modulos-v1.md`.
4. `05-arquitectura-y-tecnologias.md`.
5. `02-modelo-de-datos.md` y `06-tipos-y-contratos-de-datos.md`.
6. Diseño visual existente, como referencia visual complementaria.

Si hay contradicción, seguir en este orden: decisión aprobada explícita más reciente del anexo; regla aprobada de negocio v0.2; alcance/arquitectura; borrador del modelo. No usar una propuesta del modelo v0.1 para invalidar una regla aprobada.

## 3. Límites de decisión

- No inventar reglas cuando un comportamiento afecte cobros, inventario, impuestos/retenciones, historial, identidad, sincronización o datos del usuario.
- Ante **PENDIENTE DE DECISIÓN**, presentar la pregunta concreta y alternativas con sus efectos antes de implementar ese comportamiento.
- No cambiar silenciosamente una regla aprobada; documentar conflicto y solicitar resolución.
- No tratar un flujo dibujado o una pantalla como autorización para modificar datos o reglas no especificadas.
- No asumir una tasa fija, moneda obligatoria por país, existencia de inventario, relación obligatoria Trabajo-Cotización o pago automático al emitir una Cuenta de Cobro.
- No implementar Desktop Java ni convertir CotixGo en sistema contable/ERP.

## 4. Requisitos transversales no negociables

- Android offline-first; identidad pertenece a la cuenta, no al dispositivo.
- IDs globales generables offline y sincronización idempotente.
- Reintentos no duplican documentos, movimientos o pagos.
- Los errores/conflictos de sincronización deben ser visibles y trazables; no descartar ni sobrescribir silenciosamente cambios.
- Documento histórico conserva valores, moneda, perfil/logo y cálculos usados cuando fue emitido/registrado.
- Archivar solo oculta el registro y desarchivar vuelve a mostrarlo: nunca eliminar datos generados ni alterar movimientos, pagos, transacciones, relaciones o historial. La anulación es una acción distinta y trazable.
- Cotizado, ejecutado y consumido del inventario son realidades distintas.
- El inventario no bloquea la operación.
- El cálculo de Cotizaciones y Cuentas de Cobro comparte reglas.

## 5. Entregables de especificación antes del desarrollo completo

Antes de dar por cerrada la especificación, el equipo debe completar:

1. Estados y transiciones de Cotización, Compra e Informe, y transiciones de Trabajo/Cuenta de Cobro.
2. Contrato de cálculo monetario: impuestos, descuentos, retenciones, bruto/neto, moneda, precisión y redondeo.
3. Modelo de datos alineado con las relaciones de cotizaciones, trabajos, informes, cobros y pagos.
4. API REST: autenticación, recursos, validación, errores, idempotencia, paginación y sincronización.
5. Resolución de conflictos offline por entidad y operación.
6. Contrato de documentos y snapshots, numeración offline y generación de PDF.
7. Especificación de UX que vincule el diseño visual existente con estados, validaciones, guardado, offline y errores.
8. Seguridad, permisos, backups, retención/exportación y archivos.
9. Criterios de aceptación por módulo y estrategia de verificación.

Los puntos anteriores aún no definidos se registran en `03-anexo-decisiones-aprobadas-y-brechas.md` o en documentos especializados posteriores.

## 6. Forma de trabajo esperada al iniciar implementación

- Trabajar por módulos verticales y contratos explícitos, empezando por identidad/perfiles y persistencia compartida antes de flujos dependientes.
- Presentar el plan de implementación y los supuestos antes de construir componentes que dependan de decisiones pendientes.
- Mantener la misma semántica de datos entre Android, web y backend.
- Versionar migraciones y contratos; proteger snapshots y operaciones sincronizadas.
- Entregar cambios revisables por módulo, con notas de reglas cubiertas, pendientes encontrados y verificación realizada.

Este documento no define el orden final de releases ni autoriza un despliegue. Ambos se decidirán en su documentación propia.

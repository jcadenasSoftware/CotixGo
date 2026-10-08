# CotixGo — Matriz de decisiones: estados y cálculos

**Versión:** 1.2  
**Estado:** Respuestas del titular registradas. Las reglas confirmadas ya se trasladan al anexo de decisiones aprobadas; las aclaraciones abiertas se enumeran en la sección 8.

Este documento conserva las respuestas del titular y las decisiones pendientes para cerrar los ciclos de vida y cálculos. El estado vigente está resumido en la sección 8 y formalizado en el anexo de reglas aprobadas.

## 1. Estados ya aprobados

Estos valores sí están aprobados y no requieren una nueva decisión:

```text
Trabajo: BORRADOR | PROGRAMADO | EN PROCESO | ENTREGADO | CANCELADO
Cuenta de Cobro: PENDIENTE | PARCIAL | PAGADA | CANCELADA
Herramienta: DISPONIBLE | EN USO | MANTENIMIENTO | DAÑADA | FUERA DE SERVICIO
Foto del Trabajo: ANTES | DURANTE | DESPUÉS | OTRA
Material: NUEVO | USADO
```

El sistema controla las transiciones de Trabajo y Cuenta de Cobro; deben documentarse las condiciones residuales y validaciones que no estén cubiertas por las reglas aprobadas.

## 2. Cotizaciones — respuestas registradas

La regla aprobada establece que una Cotización vencida no se puede aprobar ni editar bajo sus condiciones anteriores y que la revalidación crea otra Cotización. Los estados aprobados se detallan enseguida; el sistema controla sus transiciones.

### Estados aprobados; transiciones aún por completar

```text
BORRADOR → EMITIDA → APROBADA
                    → RECHAZADA
                    → VENCIDA
                    → CANCELADA (si se define quién y cuándo puede cancelarla)
```

Al aprobarse la Cotización, puede originar uno o varios Trabajos; la creación de Trabajo no debe suponerse automática hasta aprobar esa regla. La Cotización vencida permanece histórica y se puede usar como base de una nueva.

**Respuestas registradas:**

1. ¿Aprobar `BORRADOR`, `EMITIDA`, `APROBADA`, `RECHAZADA`, `VENCIDA` y `CANCELADA` como estados de Cotización? - Aprobado
2. ¿La aprobación del Cliente se registra manualmente como acción del profesional, o se contempla una aceptación directa del Cliente en V1? Se registra manualmente como acción del profesional.
3. ¿Una Cotización emitida puede editarse antes de vencer? Las reglas dicen que las cotizaciones abiertas pueden modificarse y que esas modificaciones generan versiones internas; falta precisar si “abierta” equivale solo a `BORRADOR` o también `EMITIDA`. - También a `EMITIDA`.
4. ¿Quién puede cancelar una Cotización y en qué estados? - El profesional puede cancelar una Cotización en estado `BORRADOR` o `EMITIDA`.
5. ¿`VENCIDA` se calcula automáticamente por fecha, aunque el usuario no abra la app? La regla comercial de vencimiento está aprobada; el mecanismo técnico/visual no. - Sí, se calcula automáticamente por fecha.

## 3. Compras e Informes — respuestas registradas

### Compras

Está aprobado que un borrador no mueve inventario y que confirmar una Compra genera movimientos. Falta aprobar el conjunto de estados y si una Compra confirmada se puede anular mediante un movimiento inverso. - Sí, se puede anular mediante un movimiento inverso.

### Informes

Está aprobado que un Informe se construye desde la ejecución real y puede agrupar Trabajos. Falta definir estados (por ejemplo, borrador y emitido), edición después de generar/compartir, y si corregirlo requiere versión nueva o reemplazo. - Borrador y emitido. Edición después de generar/compartir no se permite. Corregirlo requiere versión nueva.

## 4. Pagos y transiciones de Cuenta de Cobro

Estados aprobados: `PENDIENTE`, `PARCIAL`, `PAGADA`, `CANCELADA`. Falta confirmar reglas para:

- si `PENDIENTE` pasa automáticamente a `PARCIAL` o `PAGADA` al asignar pagos; - Sí, pasa automáticamente.
- si se permite cancelar cuando hay pagos aplicados y cómo se conserva la trazabilidad; - No se permite cancelar cuando hay pagos aplicados.
- si se pueden corregir/anular pagos y qué evento revierte sus asignaciones; - Están aprobados para V1. Mecanismo documentado `payment_adjustments` (eventos de corrección/anulación con trazabilidad completa); su diseño detallado queda pendiente de confirmación final.
- si se acepta que un pago exceda el saldo o quede sin asignar; - Un pago registrado contra un documento no puede exceder su saldo ni quedar parcialmente sin aplicar a ese documento. Sí puede existir un Pago sin documento asociado (dinero recibido antes de crear la Cuenta de Cobro) que se asocia posteriormente; al asignarlo, si su importe supera el saldo del destino se aplica solo lo necesario y el excedente permanece sin asignar dentro del mismo pago (ejemplo: pago $500.000 sobre Cuenta de $300.000 → se aplican $300.000, Cuenta `PAGADA`, $200.000 sin asignar).
- cómo tratar reembolsos, anticipos y pagos asignados a varias Cuentas. - Un anticipo es un pago recibido (adelanto o pago parcial); puede existir sin Cuenta de Cobro previa y asociarse posteriormente al documento correspondiente. Para agrupar varias Cuentas se usa el Consolidado; no se aprueba el Pago directo sobre varias Cuentas. El Reembolso es un nuevo movimiento y no modifica el Pago original.

## 5. Motor de cálculo — componentes por confirmar

### Aprobado

- Retenciones configurables; no imponer tasa por país.
- En V1 se aplican a conceptos clasificados como `SERVICIO`.
- Cotización y Cuenta de Cobro comparten motor de cálculo.
- Se puede calcular el bruto requerido para alcanzar un neto deseado.
- El documento puede mostrar bruto, cada retención, total retenido y neto.

### Respuestas registradas (estado vigente en la sección 8)

1. **Impuestos:** no se aprobó un sistema de impuestos para V1. Tipos, porcentajes, administrador y configuración quedan pendientes; no son requisitos funcionales actuales.
2. **Descuentos:** ¿se permiten por línea, por documento o ambos? ¿Porcentaje, valor fijo o ambos? ¿Qué base reducen? - No se manjan descuentos en esta versión. El precio final se calcula solo con las retenciones y manualmente por el usuario.
3. **Orden:** el tratamiento de impuestos queda pendiente. Las retenciones siguen las reglas aprobadas y no deben combinarse con impuestos no aprobados.
4. **Retenciones múltiples:** ¿se calculan sobre el bruto original o en secuencia sobre saldo después de cada retención? -Las retenciones se calculan cada una sobre el total de conceptos `SERVICIO` del documento (los `MATERIAL` quedan fuera de la base), no en secuencia sobre el saldo retenido.
5. **Cálculo inverso:** al pedir un neto objetivo, ¿el sistema devuelve un bruto único considerando configuración vigente, y qué hace si distintas reglas producen varias soluciones? - El sistema devuelve un bruto único considerando configuración vigente.
6. **Redondeo:** precisión de cantidades/precios, número de decimales, etapa de redondeo y regla para diferencias entre suma de líneas y total. - El redondeo se realiza en la etapa final del cálculo.
7. **Totales:** las reglas que involucren impuestos no están aprobadas para V1; no se infiere una base ni un orden fiscal.
8. **Edición histórica:** si cambia una tasa/configuración, el documento existente conserva la tasa y resultados emitidos; las nuevas cotizaciones usan la configuración vigente. - Los documentos existentes conservan la tasa y resultados emitidos; las nuevas cotizaciones usan la configuración vigente.

## 6. Moneda y valores

Está aprobado que V1 use una moneda principal global por usuario y que cada documento histórico conserve su moneda. La estructura debe evitar mezclar monedas dentro de una operación.

**Respuesta registrada (estado vigente en la sección 8):** código ISO 4217, dos decimales, símbolo visible, pagos en la moneda principal del usuario y documentos existentes conservan su moneda cuando cambia la preferencia.

## 7. Protocolo para cerrar una decisión

Cuando se confirme un punto:

1. Cambiarlo de pendiente/propuesta a regla aprobada aquí.
2. Actualizar `03-anexo-decisiones-aprobadas-y-brechas.md`.
3. Alinear `02-modelo-de-datos.md`, `04-alcance-y-modulos-v1.md` y los contratos relacionados.
4. Mantener sin cambios los documentos históricos ya emitidos como referencia de auditoría.

## 8. Estado consolidado de respuestas

Las reglas confirmadas en el consolidado se trasladan a `03-anexo-decisiones-aprobadas-y-brechas.md`. Las respuestas históricas que contradigan ese consolidado, y las cuestiones fiscales no aprobadas, no constituyen reglas de producto vigentes.

### Reglas confirmadas

- La cartera de Clientes pertenece globalmente al Usuario/Titular. Un Cliente puede utilizarse desde distintos Perfiles Profesionales del mismo usuario y no se duplica por Perfil; cada operación/documento identifica su Perfil.
- El Inventario pertenece globalmente al Usuario/Titular, no a Perfiles Profesionales. Se divide en Materiales y Herramientas, cada tipo con categorías creadas libremente por el usuario. No hay categorías predeterminadas; el tipo determina el comportamiento y la categoría solo organiza.
- Los Métodos de Cobro pertenecen globalmente al Usuario/Titular, no a Perfiles Profesionales. El usuario los crea y administra. No hay métodos predeterminados; ayudas y ejemplos no se convierten en registros iniciales. Se usan para pagos recibidos y reembolsos.
- Cotización, Trabajo, Informe y Cuenta de Cobro no forman un flujo rígido. Un Informe puede ser interno o entregarse al Cliente y es opcional; un Trabajo puede entregarse y una Cuenta de Cobro generarse sin Informe.
- El congelamiento expresa cierre comercial/histórico, no una consecuencia automática de cada cambio de estado. La operación queda cerrada cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente está completamente pagada. Una garantía posterior no reabre automáticamente documentos.
- Mantener `Cotizar → Ejecutar → Documentar → Cobrar` como principio de producto y priorizar la simplicidad; las funciones adicionales deben facilitar ese flujo y no convertir CotixGo en un ERP rígido.
- Cotización: estados `BORRADOR`, `EMITIDA`, `APROBADA`, `RECHAZADA`, `VENCIDA`, `CANCELADA`; aprobación manual; vigencia elegida por el profesional al crear; rechazo reversible hasta aprobación; aprobación habilita crear Trabajo manualmente.
- El profesional puede ajustar el inventario para cargar existencias previas. Confirmar una Compra incrementa el inventario por sus líneas. Solo el consumo propio registrado en un Trabajo descuenta existencias; no se valida contra el saldo y este puede quedar negativo. Cotizar o informar no modifica inventario. Una Compra confirmada puede anularse mediante movimiento inverso, editarse o archivarse. Editarla ajusta el inventario por la diferencia entre cantidades anteriores y nuevas, con movimientos trazables. Archivar cualquier documento o registro solo lo oculta y desarchivarlo vuelve a mostrarlo; nunca se eliminan datos ni se revierten movimientos, pagos, transacciones o relaciones. La anulación es distinta del archivo.
- El comprobante de compra pertenece al registro de Compra; el Informe de Trabajo se genera desde el Trabajo. Son documentos distintos.
- Informe de Trabajo: `BORRADOR`/`EMITIDO`; sin edición después de emisión; corrección mediante versión nueva referenciada, conservando la anterior.
- Cuenta de Cobro: actualización automática a parcial/pagada; no cancelable con pagos aplicados; no admite pagos registrados por encima del saldo; al asignar un pago sin asignación, el excedente sobre el saldo permanece sin asignar dentro del mismo pago.
- Pagos: normalmente se registra un Pago contra una Cuenta. Un Pago también puede existir sin documento asociado (dinero recibido antes de la Cuenta), vinculado al Cliente, y se asocia posteriormente mediante operaciones auditadas (`assign`/`unassign`/`reassign`); si el pago supera el saldo del destino se aplica solo lo necesario y el excedente permanece sin asignar. Para agrupar varias del mismo Cliente se utiliza el Consolidado, que preserva las Cuentas originales y sus valores históricos y gestiona el saldo conjunto. Los Pagos contra el Consolidado pueden ser parciales y no se registran directamente sobre varias Cuentas.
- Un Reembolso es un nuevo movimiento de dinero y no modifica ni elimina el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro y observación. V1 no añade un estado `REEMBOLSADA`.
- V1 no ofrece descuentos. El profesional ingresa el precio como bruto. Las retenciones de V1 se calculan sobre los conceptos `SERVICIO`; los `MATERIAL` quedan fuera de la base. La base de cada retención se determina por su configuración `applies_to`; `OTRO` se comporta según esa configuración, sin estar incluido ni excluido universalmente. Ejemplo aprobado: materiales $800.000 + servicios $600.000 con retención del 6% → retención $36.000, bruto $1.400.000, neto $1.364.000. Impuestos, administrador, porcentajes predeterminados y configuración fiscal no están aprobados para V1 y no son requisitos.
- El sistema controla transiciones según estado actual. La corrección y anulación de Pagos están aprobadas para V1 mediante `payment_adjustments` (diseño detallado pendiente de confirmación): cada ajuste conserva valor anterior, valor nuevo, motivo, usuario, dispositivo, operación y fecha.
- Numeración documental: todo documento numerable recibe de inmediato un identificador provisional `TIPO-PEND-XXXX` (ej. `COT-PEND-8F3A`), visible en interfaz y utilizable en PDF provisional, sin competir con la secuencia oficial ni generar un segundo documento. El backend asigna el número oficial sobre el mismo documento/`entity_id` al sincronizarse (`COT-PEND-8F3A` → `COT-202610-023`). La secuencia oficial es global por Usuario/Titular + tipo + período, independiente del Perfil Profesional.
- El catálogo de servicios se administra por Perfil Profesional.
- Corrección/anulación de Pagos: el ajuste conserva el registro original intacto en el historial. Un Reembolso es distinto: es un nuevo movimiento de dinero sobre un Pago correcto y no utiliza el mecanismo de ajuste.
- Moneda: código ISO 4217, dos decimales, símbolo visible; Pagos en moneda principal del usuario; documentos mantienen moneda original tras cambio de preferencia.

### Registro de aclaraciones y respuestas

Las respuestas del titular se conservan junto a las preguntas originales. Los pendientes vigentes son únicamente los asuntos no resueltos en la sección 8 consolidada.

1. La matriz indica que un Informe se emite automáticamente al aprobar una Compra. Esto contradice el flujo de Informes basados en Trabajos y no hay relación Compra–Informe documentada; confirmar si es un Informe de Compra distinto, relación nueva o error de respuesta. - Una compra solo incide en el inventario, Un informe se genera a partir de un trabajo. Hay que distinguir los dos tipos de informes.
2. Presentación de posibles diferencias por redondeo entre líneas y total. - Se debe redondear por línea, luego el total es la suma de las líneas redondeadas.
3. Condiciones de anulación de Compra en presencia de Informes y estados/visibilidad del Informe asociado. - Aquí, una compra incide en el inventario, el informe viene siendo como un comporbante de compra, no informe de trabajo. Se puede anular, editar y archivar. El estado aprobado incrementa al inventario
4. Diseño final del mecanismo `payment_adjustments` y trazabilidad de Reembolsos. La capacidad de corregir/anular Pagos está aprobada para V1; el diseño detallado queda pendiente de confirmación.
5. Validaciones residuales en transiciones según estado actual. - Se deben validar las transiciones según el estado actual del documento
7. Impuestos, administrador y configuración predeterminada: permanecen pendientes; no fueron aprobados como funcionalidad de V1.

## 9. Historial de cambios

| Versión | Fecha | Cambio | Motivo | Estado |
|---|---|---|---|---|
| 1.0 | 30/09/2026 | Matriz de decisiones sobre estados y cálculos. | Registrar respuestas del titular. | Aprobado. |
| 1.1 | 08/10/2026 | Base de retención solo `SERVICIO` con ejemplo aprobado; pago sin Cuenta previa (anticipo); corrección/anulación de Pagos en V1 vía `payment_adjustments`; numeración provisional `TIPO-PEND-XXXX` y oficial global; catálogo por Perfil; aclaraciones 8 y 9. | Incorporación de decisiones aprobadas por el titular. | Aprobado. |
| 1.2 | 08/10/2026 | D13-B: asignación de pagos con aplicación parcial (excedente `UNASSIGNED`); D14-C: base de retención por `applies_to` configurable (`OTRO` sin comportamiento fijo); aclaraciones 8 y 9 resueltas y retiradas de pendientes. | Aprobación de las decisiones pendientes de la etapa V1.1. | Aprobado. |

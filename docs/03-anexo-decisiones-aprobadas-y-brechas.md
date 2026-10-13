# CotixGo — Anexo de decisiones aprobadas y revisión de brechas

**Versión:** 1.11  
**Estado:** Registro de decisiones aprobadas en el contexto del proyecto y revisión documental  
**Alcance:** Complementa `01-reglas-de-negocio-v0.2.md` y señala ajustes necesarios en el borrador `02-modelo-de-datos.md`.

## 1. Cómo usar este anexo

Las decisiones bajo **Aprobado** se consideran reglas de producto y complementan la versión v0.2. Si una frase anterior contradice una de estas decisiones explícitas, prevalece esta decisión. Este anexo no convierte en aprobadas las propuestas o campos tentativos del modelo de datos.

Los asuntos bajo **PENDIENTE DE DECISIÓN** no deben ser completados por Devin mediante supuestos. Deben resolverse en la documentación funcional o técnica correspondiente antes de implementar el comportamiento afectado.

## 2. Decisiones aprobadas que complementan v0.2

### 2.1 Cotizaciones

- Una cotización representa lo propuesto al cliente y puede incluir servicios, mano de obra y materiales. Puede cotizarse solo la mano de obra/servicio cuando el cliente suministra los materiales.
- Cotizar no depende de existencias de inventario.
- El catálogo de servicios es opcional para crear una cotización. Sus precios son sugeridos; los valores elegidos se guardan en la cotización y no cambian cuando cambia el catálogo.
- Las cotizaciones abiertas pueden modificarse y cada modificación debe conservar una versión interna.
- Una cotización vencida no se edita ni se aprueba bajo sus condiciones vencidas. Puede servir de base para crear una nueva cotización de revalidación; la original permanece intacta.

### 2.2 Trabajos y relación con cotizaciones

- Estados de Trabajo aprobados: `BORRADOR`, `PROGRAMADO`, `EN PROCESO`, `ENTREGADO` y `CANCELADO`. Transiciones aprobadas en la ficha F4: ver §6.
- Un Trabajo puede crearse con una Cotización o directamente para un Cliente sin Cotización.
- Un Trabajo puede relacionarse con varias Cotizaciones. Son válidos tanto `Cliente → Cotización → Trabajo` como `Cliente → Trabajo`.
- El Trabajo puede registrar información general, actividades, materiales utilizados, fotografías, observaciones y herramientas.
- Las fotografías pueden clasificarse como `ANTES`, `DURANTE`, `DESPUÉS` u `OTRA`.
- Al entregar el Trabajo, se registra que la ejecución terminó. El cierre comercial/histórico de la operación ocurre cuando también está completamente pagada la Cuenta de Cobro correspondiente. El Informe y la Cuenta de Cobro pueden generarse después.

### 2.3 Informes

- El Informe describe lo realmente ejecutado; la Cotización describe lo previsto. El Informe es opcional y puede ser interno o entregarse al Cliente.
- Un Informe puede agrupar varios Trabajos del mismo Cliente y del mismo Perfil Profesional (F5, §6). Cada Trabajo conserva su propio registro y trazabilidad.
- Solo las actividades en estado `COMPLETADA` de los Trabajos asociados se presentan como ejecución realizada en el Informe; las `PENDIENTE` o `EN PROCESO` no se presentan como ejecutadas (F5, §6).

### 2.4 Cuenta de Cobro y pagos

- El nombre funcional oficial es **Cuenta de Cobro**.
- Puede originarse desde una Cotización, desde un Trabajo o sin Cotización. Puede agrupar varias Cotizaciones **o** varios Trabajos, pero no mezclar ambos tipos en la misma Cuenta (regla afinada en la ficha F6, §6); todos los asociados deben ser del mismo Cliente y del mismo Perfil Profesional, y cada Trabajo o Cotización pertenece a lo sumo a una Cuenta no cancelada.
- Puede emitirse directamente para un Cliente sin Cotización ni Trabajo relacionado. Caso aprobado: atención de emergencia que el Cliente paga de inmediato; basta con registrar la Cuenta de Cobro y el Pago. No se exige crear Cotización, Trabajo, Informe ni fotografías para este flujo.
- Estados aprobados: `PENDIENTE`, `PARCIAL`, `PAGADA` y `CANCELADA`. Ciclo de vida aprobado en la ficha F6 (§6): inicio en `PENDIENTE`, edición solo `PENDIENTE` sin pagos aplicados, `PARCIAL`/`PAGADA` automáticos por pagos y saldo, cancelación solo sin pagos aplicados; numeración `CC-PEND-XXXX` → `CC-AAAAMM-NNN` en el primer registro aceptado por el backend.
- Crear una Cuenta de Cobro no registra un pago. Los pagos son eventos independientes.
- Normalmente se registra un Pago contra una Cuenta de Cobro. Para agrupar varias Cuentas del mismo Cliente se utiliza el **Consolidado de Cuentas de Cobro**, que conserva intactas las Cuentas originales, usa sus valores históricos, agrupa sus saldos y permite registrar pagos contra el saldo consolidado. No existe un mecanismo paralelo para registrar un Pago directamente sobre varias Cuentas.
- Todo Pago aplicado a un documento se registra contra una Cuenta individual o un Consolidado; no existen pagos asignados directamente a varias Cuentas originales.
- Un Pago también puede registrarse **sin documento asociado**: cuando el dinero se recibe antes de que exista la Cuenta de Cobro (anticipo), queda vinculado al Cliente y no afecta saldos hasta asociarse posteriormente al documento correspondiente. La asignación, desasignación y reasignación son operaciones propias y auditadas.
- Al asignar un Pago sin asignación a una Cuenta individual o a un Consolidado, si el importe del pago supera el saldo pendiente del destino se aplica únicamente lo necesario para cubrirlo y el excedente permanece sin asignar, disponible para una futura asignación; el excedente sigue perteneciendo al mismo Pago y no constituye un pago nuevo. Nunca puede existir dinero aplicado simultáneamente a dos documentos. Ejemplo aprobado: pago recibido $500.000 sobre una Cuenta de $300.000 → se aplican $300.000, la Cuenta queda `PAGADA` y $200.000 permanecen sin asignar.
- Al cancelar/anular un Pago, el sistema revierte su efecto en el documento asociado y lo excluye de su cálculo. Si era el primer Pago de una Cuenta individual, esta vuelve a `PENDIENTE` con el saldo total pendiente. El Pago cancelado se conserva en el historial; cancelar no elimina el registro.
- Corregir el importe de un Pago aplica la diferencia al mismo Pago y a la misma Cuenta individual o Consolidado al que se registró. Si aumenta, se suma la diferencia; si disminuye, se resta. El Pago y su historial se conservan; no se crea un Pago independiente ni se elimina el registro original.
- Un Reembolso es un nuevo movimiento de dinero; no modifica ni elimina el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro utilizado y observación. V1 no añade un estado `REEMBOLSADA`.
- La corrección y anulación de Pagos son funcionalidad aprobada de V1. Mecanismo propuesto documentado: `payment_adjustments`, eventos de ajuste que conservan valor anterior, valor nuevo, motivo, usuario, dispositivo, operación y fecha; el registro original no se modifica silenciosamente ni se elimina.

### 2.5 Retenciones y cálculo

- Las retenciones son configurables. No se asume una tasa fija por país.
- Los conceptos de las líneas se clasifican como `SERVICIO`, `MATERIAL` u `OTRO`. La base de cada retención se determina mediante su configuración `applies_to`, no por una regla rígida del modelo. Con las reglas actuales: `SERVICIO` entra en la base, `MATERIAL` queda excluido y `OTRO` se comporta según el `applies_to` configurado (no está incluido ni excluido universalmente). Ejemplo aprobado: materiales $800.000 + servicios $600.000 con retención del 6% → retención $36.000, bruto $1.400.000, neto $1.364.000.
- El cálculo debe permitir hallar el valor bruto necesario para obtener un valor neto objetivo.
- Cotizaciones y Cuentas de Cobro deben compartir un motor de cálculo.
- Cuando corresponda, los documentos deben poder desglosar bruto, cada retención, total de retenciones y neto.

### 2.6 Clientes y perfiles profesionales

- Existe una sola cartera de Clientes global al Usuario/Titular. Un Cliente no pertenece a un Perfil Profesional y puede utilizarse desde varios Perfiles del mismo usuario; no se duplica por Perfil. Cada operación/documento conserva el Perfil Profesional que le corresponde.
- Los datos comerciales del Cliente son valores predeterminados; cada operación conserva los valores que usó.
- El lugar de ejecución pertenece al Trabajo y puede diferir de la dirección fiscal o principal del Cliente.
- Un usuario puede administrar varios Perfiles Profesionales. Estos no representan automáticamente entidades jurídicas separadas; la identidad fiscal pertenece al titular/usuario.
- Los documentos históricos conservan la información del Perfil Profesional usada al emitirlos, incluido el logo.
- Campos mínimos aprobados de Cliente (F1): `nombre` obligatorio; `tipo` persona/empresa; `teléfono`, `correo`, `dirección` y `notas` opcionales; estado `archivado`.
- Campos mínimos aprobados de Perfil Profesional (F1): `nombre_comercial` obligatorio; `descripción` e `información_comercial` opcionales; `logo` como referencia local opcional en F1.

### 2.7 Inventario, compras y herramientas

- El inventario es control interno y no puede bloquear cotizar, trabajar, documentar, cobrar ni registrar pagos.
- El profesional puede registrar ajustes para reflejar el material que ya tiene en su bodega al iniciar el uso de CotixGo.
- El material puede existir como `NUEVO` o `USADO`; ambos estados pueden coexistir para el mismo material. Orígenes contemplados: compra, sobrante de trabajo y ajuste.
- Confirmar una Compra genera entradas al inventario por cada elemento y cantidad registrados.
- Solo el registro explícito del consumo de material propio dentro de un Trabajo genera una salida de inventario. Cotizar un material o incluirlo en un Informe no modifica existencias.
- El registro de material utilizado en un Trabajo es independiente de la existencia disponible. Se puede registrar cualquier cantidad efectivamente utilizada aunque supere el stock; el inventario puede quedar negativo y no bloquea ni altera el Trabajo o su Informe.
- Una Compra puede incluir varias líneas y conserva fecha, proveedor/lugar, referencia de factura o recibo, materiales, cantidades, unidades, precios unitarios, total y notas.
- Una Compra en borrador no mueve inventario; una Compra confirmada genera movimientos. No se requiere foto del recibo en V1.
- El seguimiento de herramientas es opcional y no bloquea la operación. Estados aprobados: disponible, en uso, mantenimiento, dañada y fuera de servicio.

### 2.8 Dashboard, moneda e historial

- El Dashboard prioriza acciones: `+ Cotizar`, `+ Cuenta de Cobro` y `+ Trabajo`; muestra pendientes accionables y evita priorizar gráficos.
- V1 usa una moneda principal global por usuario. El país puede sugerir una moneda, pero no imponerla. Cada documento histórico conserva su moneda.
- Una vez emitido o registrado un documento histórico, conserva la información de ese momento. Cambios posteriores en catálogos, precios, Clientes, materiales, servicios, perfiles o configuración no lo alteran retroactivamente.
- Archivar cualquier registro o documento solo lo oculta de las vistas operativas habituales. Cualquier documento archivado puede desarchivarse para volver a mostrarlo. Archivar o desarchivar no elimina datos ni altera movimientos de inventario, pagos, asignaciones, relaciones o historial. Ningún dato generado se elimina físicamente; archivar tampoco equivale a anular una operación.

### 2.9 Arquitectura prevista y trabajo offline

- Android: Kotlin, Jetpack Compose y Room; funcionamiento offline-first.
- Web: React, TypeScript y Next.js.
- Backend: API REST PHP; base de datos MySQL; servidor y archivos en Hostinger.
- No se contempla una aplicación Desktop Java.
- La identidad pertenece a la cuenta de usuario, no al dispositivo. Android debe permitir trabajo offline y sincronización posterior, con IDs globalmente únicos generables offline, operaciones idempotentes, cola, reintentos, manejo de conflictos, trazabilidad y recuperación al cambiar o perder el dispositivo.
- La prevención de duplicados y registros fantasma es un requisito de diseño, basado en los problemas observados en Xpendz.

### 2.10 Decisiones funcionales aclaradas recientemente

- La cartera de Clientes es única y global al Usuario/Titular. Un Cliente no pertenece a un Perfil Profesional y puede utilizarse desde varios Perfiles del mismo usuario; no se duplica por Perfil. El Perfil pertenece a cada operación/documento.
- El Inventario pertenece globalmente al Usuario/Titular, no a los Perfiles Profesionales. Se organiza en Materiales y Herramientas; el usuario crea categorías separadas para cada tipo, sin valores predeterminados. El tipo define el comportamiento y la categoría solo organiza.
- Los Métodos de Cobro pertenecen globalmente al Usuario/Titular y no a los Perfiles Profesionales. El usuario los crea y administra; CotixGo no inserta métodos predeterminados. Ayudas y ejemplos visuales no crean registros. Se pueden usar para pagos recibidos y reembolsos.
- El flujo `Cotizar → Ejecutar → Documentar → Cobrar` es orientador, no una secuencia rígida. Los documentos opcionales no deben bloquear los demás.
- El Informe puede entregarse al Cliente o ser solo para organización/histórico interno. Es opcional: un Trabajo puede entregarse y una Cuenta de Cobro generarse sin Informe.
- El congelamiento representa el cierre comercial e histórico, no una consecuencia automática de cada cambio de estado. La operación queda cerrada cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente se pagó completamente. Una garantía posterior no reabre automáticamente documentos.
- Se mantiene como principio de producto: `Cotizar → Ejecutar → Documentar → Cobrar`. Las funciones adicionales deben simplificar el trabajo principal y no convertir CotixGo en un ERP rígido.

## 3. Brechas y alineación requerida en el modelo de datos

| Tema | Estado del borrador actual | Alineación requerida |
|---|---|---|
| Trabajo sin Cotización | `Job` enumera cotización de origen; la sección de relaciones explica varias cotizaciones, pero no declara claramente que la relación es opcional. | Expresar que el Cliente basta para crear un Trabajo y que la asociación a Cotizaciones es opcional y muchos a muchos. |
| Estados de Trabajo | El borrador enumera estados conceptuales distintos y deja nombres/transiciones para después. | Resuelto: el modelo registra los cinco estados aprobados y las transiciones de Trabajo aprobadas en la ficha F4 (§6). |
| Cuenta de Cobro ↔ Cotización | El borrador modela asociación con Trabajos, pero no una relación entre Cuenta de Cobro y Cotizaciones. | Resuelto en la ficha F6 (§6): existe la relación asociativa `CollectionAccountQuote` y la Cuenta admite cero o varias Cotizaciones **o** cero o varios Trabajos (sin mezclar tipos), todos del mismo Cliente y Perfil; cada Trabajo/Cotización pertenece a lo sumo a una Cuenta no cancelada. La elegibilidad por estado de la Cotización sigue pendiente (§4.9). |
| Cuenta de Cobro independiente | El borrador enumera relaciones con Trabajos y no deja explícito el caso de cobro directo sin Cotización ni Trabajo. | Resuelto: la Cuenta de Cobro puede existir vinculada solo a Cliente y Perfil Profesional, sin Cotización ni Trabajo; el flujo de emergencia registra Cuenta y Pago sin exigir Informe ni fotografías (§2.4, `02` §38). |
| Retenciones e impuestos | Las retenciones están aprobadas; no se aprobó un sistema de impuestos para V1. | Mantener las reglas aprobadas de retenciones. El tratamiento de impuestos, administrador, porcentajes y configuración permanece pendiente y no es requisito de V1. |
| Perfil y Cliente | El borrador vinculaba Cliente con Perfil Profesional. | La cartera de Clientes pertenece al Usuario/Titular. Los documentos y operaciones usan el Perfil Profesional que les corresponde; un mismo Cliente puede utilizarse desde distintos perfiles. |
| Moneda | No se identifica claramente moneda global de usuario ni moneda capturada por documento. | Añadir moneda a configuración de usuario y snapshot del documento, sin conversión implícita. |
| Versiones de Cotización | El modelo tiene contenido y versiones potenciales, pero no especifica entidad o mecanismo para historial de modificaciones de cotizaciones abiertas. | Definir cómo se conserva cada versión antes de cerrar el modelo; cada versión debe preservar valores propios. |
| Compra en borrador | El modelo dice que una compra confirmada genera entradas, pero no enumera estados ni prohibición explícita para borrador. | Reflejar que el borrador no afecta inventario y que confirmar genera movimientos. |
| Condición de stock | El borrador menciona `Material` y movimientos, pero no representa claramente stock nuevo/usado coexistente y origen. | Modelar balances/movimientos por condición y origen sin convertir esa decisión en bloqueo de operación. |
| Adjuntos | El modelo registra adjuntos genéricos, pero no formaliza categorías fotográficas aprobadas. | Incluir `ANTES`, `DURANTE`, `DESPUÉS`, `OTRA` como clasificación de evidencia. |
| Cierre histórico | El modelo asumía que algunos estados individuales congelaban automáticamente documentos. | Representar el cierre de la operación cuando el Trabajo está entregado y la Cuenta de Cobro correspondiente está pagada; no asumir congelamiento automático por cada estado. |

La tabla describe cambios de documentación requeridos, no decisiones de implementación de tablas ni esquema SQL.

## 4. PENDIENTE DE DECISIÓN

Estos asuntos no quedan resueltos por las reglas aprobadas disponibles. Las respuestas posteriores de la sección 6 cierran varias decisiones que antes aparecían como pendientes.

1. Condiciones y validaciones residuales de las transiciones gestionadas por el sistema. (Cotización: resueltas para F2 en §6. Trabajo: resueltas para F4 en §6.)
2. Especificación visual/técnica del Consolidado de Cuentas de Cobro y detalles de su relación con operaciones históricas, sin crear un mecanismo de pago directo sobre varias Cuentas. **Sigue pendiente:** el Consolidado quedó asignado a F7 en la ficha F6 (§6); su especificación se decide allí.
3. Política de resolución de conflictos de sincronización por entidad y por campo, incluyendo cambios simultáneos. **Parcialmente resuelto:** la política de producto para las entidades de F3 está aprobada en §6 (detección explícita, preservación de ambas copias y resolución del usuario; versiones de Cotización con versión de resolución nueva) e implementada en local para esas entidades — cola `sync_operations`/`sync_conflicts`/`sync_meta`, `audit_events`, versionado del servidor y contrato (`05` §5.1). La misma política quedó extendida por decisión a las entidades de F4 en la ficha F4 (§6) y se implementó en local para ellas (`05` §5.2), extendida por decisión a las entidades de F5 (Informes) en la ficha F5 (§6) — resolución del usuario como versión nueva del Informe — e implementada en local para ellas (`05` §5.3), y extendida por decisión a las entidades documentales de F6 (`collection_account`, `collection_method`) en la ficha F6 (§6), donde además los eventos financieros (`payment`, asignaciones) quedan aprobados como append-only con validación atómica de saldo en el servidor. Permanece pendiente la extensión a las entidades de F7 (Consolidado, `payment_adjustments`, Reembolsos) y posteriores.
4. Si una Cuenta de Cobro puede combinar Cotizaciones y Trabajos en el mismo documento y cómo evitar doble cobro de un concepto. **Resuelto para F6 (§6):** no se mezclan ambos tipos en la misma Cuenta; cada Trabajo o Cotización solo puede pertenecer a una Cuenta no cancelada (cancelar libera la asociación). Permanece pendiente la elegibilidad por estado de la Cotización (ver punto 9) y cualquier consolidación/doble cobro más allá de estas restricciones (F7).
5. Confirmación final del diseño del mecanismo `payment_adjustments` para corregir o anular Pagos (la capacidad de corrección/anulación ya está aprobada para V1). **Sigue pendiente:** asignado a F7 en la ficha F6 (§6).
6. Retención y almacenamiento de fotografías/archivos, límites de tamaño, compresión, permisos y sincronización fallida. (Sigue pendiente: F5 — Informes — excluye explícitamente fotografías y archivos adjuntos, y F6 también los excluye.)
7. Campos definitivos y valores obligatorios de Trabajo y Cuenta de Cobro, aparte de los enumerados en reglas. (Cliente y Perfil Profesional: mínimos aprobados para F1 en §2.6. Cotización: campos de F2 aprobados en §6. Trabajo: avance en F4 — dirección de ejecución propia aprobada, obligatoria al pasar a `PROGRAMADO`, y numeración documental `TR` aprobada; ver §6. Informe: campos mínimos propuestos y aprobados para F5 en §6. Cuenta de Cobro: **campos mínimos aprobados para F6 en §6** — el avance de la lista definitiva de Trabajo sigue pendiente.)
8. Tecnología concreta de protección de datos locales en reposo (cifrado u otra solución). Requerida antes de usar datos personales o cuentas reales en el dispositivo; mientras tanto solo se permiten datos sintéticos (decisión de la ficha F3 en §6). Su definición no se completa por inferencia y no forma parte del alcance de F3 automáticamente. La ficha F6 (§6) registra además que F6 sin el mecanismo de corrección/anulación de pagos de F7 no es apta para datos reales.
9. Estado requerido de una Cotización para ser elegible como origen de una Cuenta de Cobro (por ejemplo, si debe estar `APROBADA`). **Pendiente y bloqueante:** crear una Cuenta desde Cotización no se implementa hasta que el titular lo resuelva (ficha F6, §6).

## 5. Próxima revisión documental

1. Alinear `02-modelo-de-datos.md` con las brechas de la sección 3 sin cerrar por suposición los pendientes de la sección 4.
2. Resolver las decisiones pendientes que bloquean integridad, cálculo, sincronización e historial.
3. Después preparar contratos funcionales de pantallas, API y sincronización para Devin. El diseño visual existente se tratará como referencia UX y no se recreará en esta etapa.

## 6. Decisiones aprobadas posteriores — estados y cálculos

Esta sección recoge respuestas confirmadas en `08-matriz-decision-estados-y-calculos.md` y prevalece sobre cualquier pendiente anterior de este anexo que contradiga estas reglas.

### Cotizaciones

- Estados: `BORRADOR`, `EMITIDA`, `APROBADA`, `RECHAZADA`, `VENCIDA`, `CANCELADA`.
- El profesional registra manualmente la aprobación del Cliente.
- `BORRADOR` y `EMITIDA` pueden modificarse antes del vencimiento; se conserva una versión interna de cada modificación.
- El profesional puede cancelar una Cotización en `BORRADOR` o `EMITIDA`.
- El profesional define la vigencia al crear la Cotización; el sistema calcula automáticamente la fecha de vencimiento.
- El sistema controla las transiciones de estado; aprobar una Cotización solo habilita crear un Trabajo manualmente, no lo crea automáticamente.
- Un rechazo es reversible hasta que la Cotización sea aprobada; el sistema valida la transición según el estado actual.

### Cotizaciones — fase F2 (local Android)

Decisiones aprobadas por el titular el 11/10/2026 para la ficha F2:

- Campos: `origen_material` opcional por línea `MATERIAL` con valores `CLIENTE`/`PROFESIONAL`; no se añade campo específico de motivo de rechazo (se reutilizan las observaciones); emitir exige al menos una línea, mientras que `BORRADOR` puede guardarse sin líneas.
- Vigencia: corre desde la creación, incluida la permanencia en `BORRADOR`; `fecha_vencimiento` es el último día válido y la Cotización pasa a `VENCIDA` cuando la fecha local del dispositivo es posterior a ese día. Alcance aclarado en F3 (§6): el vencimiento solo aplica a los estados no terminales `BORRADOR`, `EMITIDA` y `RECHAZADA`; `APROBADA` y `CANCELADA` son terminales y conservan su estado.
- Retenciones: se configuran globalmente por Usuario/Titular (no por Perfil); cada Cotización conserva el snapshot de las retenciones aplicadas.
- PDF: la exportación de PDF queda diferida a una fase posterior; no forma parte de F2.
- Transiciones de F2: `APROBADA`, `VENCIDA` y `CANCELADA` son terminales; `RECHAZADA` puede volver a `EMITIDA` antes de `APROBADA`; la edición no cambia el estado.

### Compras e Informes

- Al confirmar una Compra se incrementa el inventario por cada línea. Una Compra confirmada se puede anular mediante un movimiento inverso, editar y archivar.
- Al editar una Compra confirmada, el inventario se ajusta por la diferencia entre las cantidades anteriores y las nuevas: las líneas agregadas o aumentadas generan entradas por la diferencia; las líneas reducidas o eliminadas generan salidas por la diferencia. Se conservan los movimientos y la trazabilidad histórica.
- Archivar una Compra, o cualquier otro registro/documento generado, solo lo oculta de las vistas operativas habituales. Cualquier documento archivado se puede desarchivar para volver a mostrarlo. Archivar o desarchivar no elimina el dato ni revierte o modifica sus efectos en inventario, pagos, transacciones, relaciones o historial. La anulación es una acción distinta del archivo.
- Los ajustes iniciales o posteriores de inventario se registran como movimientos explicables. El consumo se descuenta únicamente cuando el profesional lo registra en un Trabajo; no se valida contra disponibilidad y el saldo puede ser negativo.
- La Compra y su comprobante pertenecen al flujo de Compras/Inventario. El **Informe de Trabajo** es otro documento: se genera desde un Trabajo y describe su ejecución. Aprobar una Compra no emite automáticamente un Informe de Trabajo.
- No se requiere almacenar foto del recibo/comprobante de compra en V1; la Compra conserva sus datos de referencia.
- Un Informe de Trabajo tiene estados `BORRADOR` y `EMITIDO`. Después de emitirse no se edita; una corrección crea una versión nueva relacionada con la anterior mediante un campo de referencia. La versión anterior se conserva como referencia.

### Pagos y Cuentas de Cobro

- El estado de Cuenta de Cobro se actualiza automáticamente a `PARCIAL` o `PAGADA` según pagos aplicados y saldo.
- Una Cuenta de Cobro con pagos aplicados no se puede cancelar.
- Para agrupar varias Cuentas del mismo Cliente se utiliza un **Consolidado de Cuentas de Cobro**. Este conserva intactas las Cuentas originales, usa sus valores históricos, agrupa los saldos y admite pagos contra el saldo consolidado. No hay un mecanismo de Pago directo sobre varias Cuentas.
- Un anticipo es un pago recibido como adelanto o pago parcial. Puede registrarse **sin que exista la Cuenta de Cobro** (pago no asignado, vinculado al Cliente) y asociarse posteriormente al documento correspondiente mediante operaciones auditadas (`assign`/`unassign`/`reassign`). No existe un módulo separado de anticipos.
- No se permite registrar un Pago por encima del saldo pendiente de la Cuenta o del Consolidado al que se aplica. Al asignar un Pago sin asignación, si su importe supera el saldo del destino se aplica solo lo necesario y el excedente permanece sin asignar (pertenece al mismo Pago; no es un pago nuevo). Ejemplo: pago $500.000 sobre Cuenta de $300.000 → se aplican $300.000, la Cuenta queda `PAGADA` y $200.000 quedan sin asignar.
- La corrección y anulación de Pagos están aprobadas para V1 con el mecanismo documentado `payment_adjustments` (su diseño detallado queda pendiente de confirmación final).
- Todo Pago aplicado a un documento se registra contra una Cuenta individual o un Consolidado; un Pago también puede existir sin documento asociado (recibido antes de la Cuenta) hasta su asociación posterior. Al anular un Pago, se conserva en el historial y deja de contar en el cálculo del documento asociado.
- Los pagos pueden ser parciales y no pueden superar el saldo del documento asociado. Los pagos al Consolidado reducen su saldo conjunto sin modificar ni distribuirse entre las Cuentas originales.
- Un Reembolso es un nuevo movimiento de dinero; no modifica ni elimina el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro utilizado y observación. V1 no añade un estado `REEMBOLSADA`.

### Cálculos y moneda

- En V1 no se ofrecen descuentos.
- El precio se ingresa manualmente como bruto. Las retenciones aprobadas se calculan sobre el total de conceptos `SERVICIO`; los `MATERIAL` quedan fuera de la base. La base de cada retención se define por su configuración `applies_to`; `OTRO` no está incluido ni excluido universalmente, se comporta según esa configuración.
- El tratamiento de impuestos, tipos, porcentajes, administrador, valores predeterminados y configuración por documento **no está aprobado para V1** y permanece pendiente. No es requisito funcional ni se deben inventar reglas fiscales.
- Las cantidades se redondean a dos decimales. Los importes se redondean por línea; el total es la suma de las líneas ya redondeadas.
- Los documentos históricos conservan los valores y resultados aprobados que correspondan al momento de emisión.
- Moneda V1: código ISO 4217, dos decimales, símbolo visible y pagos en la moneda principal del usuario. Si cambia la moneda principal, los documentos existentes conservan su moneda.
- El cálculo inverso permite determinar el bruto requerido para un neto objetivo usando las retenciones aplicables a `SERVICIO`; no incorpora impuestos no aprobados.

### Numeración documental

- Todo documento numerable recibe de inmediato un identificador provisional `TIPO-PEND-XXXX` (por ejemplo `COT-PEND-8F3A`), visible en la interfaz y utilizable en un PDF provisional, sin competir con la numeración oficial ni generar un segundo documento.
- El backend asigna el número oficial sobre el mismo documento/`entity_id` cuando existe conectividad (por ejemplo `COT-PEND-8F3A` → `COT-202610-023`).
- La numeración oficial es global por Usuario/Titular + tipo de documento + período, independiente del Perfil Profesional.

### Catálogo de servicios

- El catálogo de servicios se administra por Perfil Profesional.

### Protección de datos locales y entorno de desarrollo — planificación de F3

Decisiones aprobadas por el titular el 11/10/2026 para la planificación de F3 (sincronización Android ↔ backend):

- Durante el desarrollo y las pruebas de F3 solo se permiten datos sintéticos en Android, la base de datos local, la API y la base local de pruebas.
- No se usarán datos personales ni cuentas reales hasta definir, implementar y verificar la protección de datos locales en reposo.
- La tecnología concreta de cifrado/protección local permanece pendiente (§4.8); no se elige ninguna solución por inferencia.
- Esta decisión no añade cifrado local al alcance de F3 automáticamente; su inclusión requiere definir y aprobar la solución antes de usar datos reales.
- Recuperación de acceso: aprobada por correo antes de usar cuentas o datos reales; el proveedor, el envío y la configuración técnica quedan pendientes — el correo disponible en Hostinger puede considerarse, sin solicitar credenciales ni configurar el servicio todavía.
- No se requiere crear ni configurar la base MySQL de Hostinger durante la planificación ni el desarrollo local; el titular será avisado antes del hito de provisión o despliegue remoto.

### Sincronización, identidad y autenticación — planificación de F3

Decisiones de producto aprobadas por el titular el 11/10/2026 durante la planificación de F3. Los detalles técnicos (contrato de endpoints, esquema físico, estructura de `SyncOperation`/`AuditEvent` y mecanismos de servidor) permanecen pendientes y no se resuelven por inferencia.

**Identidad y vinculación**

- La identidad canónica es la cuenta del servidor, no el dispositivo.
- Al vincular una instalación con datos locales, se conservan los UUID de las entidades y se reasignan sus referencias al ID canónico de Usuario mediante una transacción local segura; las operaciones ya encoladas se regeneran con la identidad canónica (nuevos `operation_id`) conservando su orden.
- En una instalación limpia que inicia sesión se usa la identidad devuelta por el servidor (restauración por descarga).
- El servidor vincula cada `entity_id` al `user_id` en la primera escritura aceptada; el mismo `entity_id` usado desde otra cuenta se rechaza.
- Cambio de cuenta: exige reautenticación y confirmación explícita; se comprueba que no haya operaciones en cola ni entidades pendientes de sincronización. Solo entonces se permite limpiar la copia local y descargar la cuenta nueva. Si hay pendientes o no se puede confirmar el estado, el cambio se bloquea conservando los datos; no se transfieren ni mezclan datos automáticamente.

**Conflictos**

- La detección de concurrencia es explícita y los datos local y remoto se preservan hasta que el usuario resuelve el conflicto. El `updated_at` del dispositivo no se usa como ganador automático.
- Una transición de estado solicitada desde un estado obsoleto se rechaza con el estado actual del servidor y un aviso visible, conservando el contenido de la operación pendiente.
- Ediciones concurrentes de una Cotización: se conservan todas las versiones; el documento queda bloqueado para nuevas emisiones y aprobaciones mientras exista conflicto; la resolución por elección del usuario o por fusión se registra como una versión nueva, sin reescribir snapshots existentes. La secuencia autoritativa del servidor queda fuera del snapshot inmutable.
- Si la Cotización ya había sido emitida, el snapshot emitido permanece intacto, el número oficial se conserva sobre el mismo `entity_id` y `emittedVersion` pasa a apuntar a la versión de resolución.

**Reintentos e idempotencia**

- Las operaciones pendientes no se descartan. Los fallos transitorios se reintentan de forma persistente; los errores que requieren atención se pausan con aviso visible y reintento manual tras corregir la causa. El payload y el historial de la operación se conservan.
- La idempotencia usa `operation_id` único y payload inmutable como clave de reintento idéntico; reutilizar un `operation_id` con payload distinto está prohibido (`IDEMPOTENCY_KEY_REUSED`). Una operación corregida recibe `operation_id` nuevo y conserva la relación con la operación pausada. El control de concurrencia por entidad/versión es un mecanismo separado de la clave de idempotencia.
- La escritura local de la entidad y su operación pendiente se registran de forma segura en la misma transacción.

**Numeración oficial**

- El número oficial se asigna cuando el backend acepta por primera vez una Cotización en estado `EMITIDA` o `APROBADA`, sobre el mismo `entity_id`, y se conserva en transiciones posteriores.
- Una revalidación crea un documento nuevo que recibe su propio número oficial cuando se emite; los borradores conservan su provisional.

**Estados y vencimiento**

- Al terminar la vigencia pasan a `VENCIDA` únicamente los estados no terminales `BORRADOR`, `EMITIDA` y `RECHAZADA`. `APROBADA` y `CANCELADA` permanecen terminales y no cambian a `VENCIDA`; una Cotización `APROBADA` conserva su capacidad de originar un Trabajo.
- El vencimiento se materializa en la siguiente escritura local y se sincroniza como transición `→VENCIDA`, que el servidor acepta desde esos mismos tres estados no terminales. Un pull, push, reintento o uso desde otro dispositivo no puede convertir `APROBADA` o `CANCELADA` en `VENCIDA`, ni reactivar una cotización vencida cambiando sus fechas.

**Autenticación**

- Registro e inicio de sesión con email y contraseña (base de producto; contrato de endpoints pendiente).
- Recuperación de acceso por correo, aprobada antes de usar cuentas o datos reales; proveedor, envío y configuración pendientes (el correo de Hostinger disponible puede considerarse, sin configurarlo aún).
- HTTPS es obligatorio para credenciales o datos reales; HTTP solo es aceptable en desarrollo local `php -S`.

### Trabajos — fase F4 (local Android con sincronización)

Decisiones aprobadas por el titular el 12/10/2026 para planificar F4 (Trabajos locales en Android con sincronización). Esta ficha no autoriza la implementación: F4 requiere aprobación de etapa explícita según el protocolo. La implementación fue autorizada posteriormente por el titular el mismo 12/10/2026, verificada en local con datos sintéticos (elecciones técnicas en `05` §5.2; evidencia en `evidence/f4/`) e integrada en `main` por el merge `137de38` (PR #7) tras la revisión del titular. La verificación de Gradle Sync dentro de Android Studio con JDK 21 quedó pendiente para el titular — las verificaciones de F4 se ejecutaron por CLI.

**Estados y transiciones**

- `BORRADOR → PROGRAMADO | EN PROCESO | CANCELADO`
- `PROGRAMADO → BORRADOR | EN PROCESO | CANCELADO`
- `EN PROCESO → ENTREGADO | CANCELADO`
- `ENTREGADO` y `CANCELADO` son terminales.
- No existe transición ni estado de "Pausar".
- Un Trabajo `ENTREGADO` o `CANCELADO` no se edita; los demás estados permanecen editables según las reglas vigentes y la edición no cambia el estado.

**Dirección de ejecución**

- El Trabajo tiene su propia dirección de ejecución, que no se deduce necesariamente de la del Cliente (regla previa confirmada en §2.6).
- La dirección puede quedar pendiente mientras el Trabajo está en `BORRADOR`; es obligatoria al pasar a `PROGRAMADO`.

**Numeración**

- El Trabajo es numerable: identificador provisional `TR-PEND-XXXX` desde su creación.
- El backend asigna `TR-AAAAMM-NNN` sobre el mismo `entity_id`, con secuencia global por Usuario/Titular + tipo + período.
- El número oficial se asigna la primera vez que el backend acepta el Trabajo en `PROGRAMADO` o `EN PROCESO`, lo que ocurra primero; un Trabajo creado directamente en `EN PROCESO` recibe su número al aceptarse esa transición.
- No existe numeración por Perfil Profesional.

**Materiales del Trabajo**

- En F4 los materiales se registran de forma descriptiva: descripción, cantidad, unidad, origen `CLIENTE` o `PROFESIONAL` y observaciones.
- En F4 no se requiere catálogo ni `material_id`; estos registros no modifican inventario ni generan movimientos.
- La futura integración con inventario requiere la fase correspondiente y no debe reinterpretar retroactivamente los registros descriptivos.

**Actividades**

- Estados de actividad aprobados: `PENDIENTE`, `EN PROCESO`, `COMPLETADA`.
- Las actividades permanecen subordinadas al Trabajo y no añaden reglas de inventario.

**Sincronización y conflictos**

- Se extiende a las entidades nuevas de F4 (`Job`, `JobQuote`, `JobActivity`, `JobMaterial`) la política aprobada para las entidades de F3: detección de concurrencia por versión base, preservación de ambas copias y resolución explícita del usuario; no se elige ganador por reloj del dispositivo ni se sobrescribe silenciosamente.
- La consistencia de propiedad y referencias entre Trabajo, Cliente, Perfil, Cotizaciones y elementos hijos se conserva con los mecanismos ya aprobados, sin una política de producto distinta: cada entidad (padre o hija) registra su propio conflicto y preserva ambas copias hasta que el usuario resuelve; las referencias exigen propiedad de la misma cuenta y se rechazan en caso contrario; la resolución elegida se sincroniza como una operación nueva encadenada a la pausada, conservando la trazabilidad.
- Se reutilizan la idempotencia, la cola durable, los reintentos y los cursores de F3 donde corresponda.

### Informes — fase F5 (local Android con sincronización)

Decisiones aprobadas por el titular el 12/10/2026 para planificar F5 (Informes de Trabajo en Android con sincronización). Esta ficha registra las decisiones de producto; la etapa de implementación fue autorizada por el titular el mismo día y F5 quedó implementada en local (elecciones técnicas en `05` §5.3; evidencia en `evidence/f5/`) e integrada en `main` por el merge `b424c3f` (PR #9) tras la revisión del titular.

**Alcance y relación con el Trabajo**

- F5 cubre Informes de Trabajo en Android, como paso opcional de "Documentar" del flujo `Cotizar → Ejecutar → Documentar → Cobrar`.
- El Informe puede ser interno o para el Cliente; no es requisito para entregar un Trabajo ni para crear una Cuenta de Cobro (regla previa confirmada).
- Un Informe puede incluir uno o varios Trabajos del mismo Cliente **y del mismo Perfil Profesional**. La API debe validar las relaciones con los datos persistidos en el servidor, no con las relaciones declaradas por el cliente.

**Contenido ejecutado**

- El contenido inicial puede construirse desde los Trabajos asociados, pero solo las actividades en estado `COMPLETADA` se presentan como ejecución realizada. Las actividades `PENDIENTE` o `EN PROCESO` no deben presentarse como ejecutadas.
- El usuario puede revisar y editar el contenido mientras el Informe está en `BORRADOR`.

**Estados y correcciones**

- Estados: `BORRADOR` y `EMITIDO` (aprobados previamente en `04` §2.9 y `08` §3).
- Un Informe emitido no se edita. Corregirlo crea una versión nueva enlazada a la anterior mediante un campo de referencia; la versión emitida anterior y su snapshot se conservan.
- Las ediciones concurrentes requieren resolución explícita de conflictos (ver "Sincronización y conflictos").

**Numeración**

- Identificador provisional `INF-PEND-XXXX` desde la creación.
- El backend asigna `INF-AAAAMM-NNN` sobre el mismo `entity_id` cuando acepta por primera vez el Informe como `EMITIDO`. La secuencia oficial es global por Usuario/Titular + tipo + período, independiente del Perfil Profesional.

**Campos mínimos (F5)**

- `clientId` y `profileId` requeridos: el Informe identifica su Perfil Profesional, y todos los Trabajos asociados deben coincidir con el Informe tanto en Cliente como en Perfil.
- `título` requerido; contenido/observaciones editables en `BORRADOR`; `estado`; número provisional y oficial; destino `INTERNO` o `CLIENTE`; `archivado`; marcas de tiempo.

**Versionado y asociación**

- Cada versión emitida conserva un snapshot inmutable suficiente para reproducir el documento histórico (regla transversal de documentos históricos).
- Relación `Report`–`ReportVersion`: cada corrección crea una versión nueva enlazada por referencia a la anterior, conservando la versión emitida previa y su snapshot (mecanismo análogo al de versiones de Cotización, sin reescribir snapshots existentes).
- Relación `Report`–Trabajo mediante asociación (`ReportJob` conceptual): permite informes de un solo Trabajo o combinados y conserva la identidad y trazabilidad de cada Trabajo incluido.

**Sincronización y conflictos**

- Se extiende explícitamente a las entidades de F5 la política aprobada para las entidades de F3 y aplicada a F4: detección de concurrencia por versión base, preservación de ambas copias y resolución explícita del usuario; no se elige ganador por reloj del dispositivo ni se sobrescribe silenciosamente. La resolución elegida por el usuario crea una versión nueva del Informe.
- Se reutilizan la idempotencia, la cola durable, los reintentos, los cursores y la validación de propiedad de referencias donde corresponda.

**Exclusiones de F5**

- Fuera del alcance de F5: PDF y cualquier exportación, fotografías y archivos adjuntos, envío por correo o enlace al Cliente, Inventario, Compras, Cuenta de Cobro y Pagos.
- Las fotografías y archivos siguen pendientes conforme a §4.6; F5 no los requiere.

**Estado de implementación:** F5 quedó implementada en local el 12/10/2026 con datos sintéticos — entidades `report`/`report_job`/`report_version` sobre el protocolo de F3, Room v6, numeración `INF` del lado del servidor, versionado enlazado con snapshot inmutable, agrupación Cliente+Perfil validada en servidor, política de conflictos aplicada y UI de Informes en Android (elecciones técnicas en `05` §5.3; evidencia en `evidence/f5/`). F5 quedó integrada en `main` por el merge `b424c3f` (PR #9) tras la revisión del titular; el defecto de edición de Trabajo detectado durante F5 se corrigió en una rama independiente (`fix/job-form-loading`, PR #10, merge `0df9c87`). Sin despliegue remoto ni datos reales.

### Cuenta de Cobro, Métodos de Cobro y pagos esenciales — fase F6 (planificación aprobada)

Decisiones aprobadas por el titular el 13/10/2026 para planificar F6 — el paso "Cobrar" del flujo `Cotizar → Ejecutar → Documentar → Cobrar`. Esta ficha registra las decisiones de producto; **la implementación de F6 no está autorizada**. La hoja de ruta F1–F8 no asignaba módulos explícitos a F6–F8; esta división queda aprobada ahora.

**División de fases**

- F6: Cuenta de Cobro, Métodos de Cobro y pagos esenciales.
- F7: Consolidado de Cuentas, `payment_adjustments` (corrección/anulación de pagos) y Reembolsos.
- F6 no incluye Consolidado, ajuste/anulación de pagos ni Reembolsos.
- Limitación operacional registrada y aceptada: antes de F7, un error en un Pago no se resuelve editándolo ni anulándolo; solo se aplican las operaciones ya aprobadas de asignación/reasignación, sin falsear el historial. **F6 sin el mecanismo de corrección/anulación de pagos no es apta para datos reales**; continúa aplicando la regla global de solo datos sintéticos hasta definir, implementar y verificar la protección local en reposo (§4.8).

**Cuenta de Cobro**

- Campos mínimos aprobados: `clientId`, `profileId`, moneda, líneas, bruto/retenciones/neto/saldo, estado, número provisional/oficial, `issuedAt`, fecha de vencimiento opcional, condiciones opcionales, snapshots históricos de Cliente/Perfil/retenciones, `archivado` y marcas de tiempo.
- Ciclo de vida: una Cuenta empieza en `PENDIENTE`. Se puede editar mientras esté `PENDIENTE` y no tenga pagos aplicados; con algún pago aplicado, sus líneas, totales y snapshots no se editan. Se cancela solo si no tiene pagos aplicados. `PARCIAL` y `PAGADA` se derivan automáticamente de los pagos y el saldo.
- Líneas: entidad conceptual `CollectionAccountItem` — concepto, tipo `SERVICIO`/`MATERIAL`/`OTRO`, cantidad, precio unitario y valores históricos. Comparte el motor de cálculo de F2 con la Cotización; retenciones configurables con snapshot por documento; impuestos y descuentos permanecen fuera de V1.
- Numeración: `CC-PEND-XXXX` desde la creación; el backend asigna `CC-AAAAMM-NNN` sobre el mismo `entity_id` en el primer registro de la Cuenta aceptado por el servidor; secuencia global por Usuario/Titular + tipo + período.
- Asociaciones: una Cuenta puede incluir varios Trabajos **o** varias Cotizaciones, pero nunca mezclar ambos tipos en la misma Cuenta. Todos los asociados deben pertenecer al mismo Cliente y al mismo Perfil Profesional. Cada Trabajo o Cotización solo puede pertenecer a una Cuenta no cancelada; al cancelarse la Cuenta, la asociación deja de bloquear. Entidades conceptuales `CollectionAccountJob` (ya en `02` §23) y `CollectionAccountQuote`.
- **Pendiente bloqueante:** el estado requerido de una Cotización para ser elegible en una Cuenta (¿solo `APROBADA`?) no se decide por inferencia; crear una Cuenta desde Cotización queda condicionado a que el titular lo resuelva.
- El Consolidado no forma parte de F6; el Consolidado de F7 sigue siendo el único mecanismo para agrupar Cuentas y no crea pagos distribuidos entre las Cuentas originales.

**Métodos de Cobro y pagos esenciales**

- Métodos de Cobro globales por Usuario/Titular, sin valores predeterminados: mínimos aprobados `nombre` requerido, `detalle` opcional y `archivado`.
- F6 permite pagos contra una Cuenta individual o pagos sin asignar vinculados a un Cliente. No permite pago directo sobre varias Cuentas ni pagos contra Consolidado.
- Un pago aplicado directamente a una Cuenta no supera su saldo. Al asignar un pago sin asignación se aplica solo lo necesario y el excedente permanece `UNASSIGNED` dentro del mismo pago (ejemplo aprobado vigente).
- `assign`/`unassign`/`reassign` son operaciones auditadas, con motivo y con validación de saldo en el servidor.
- Los pagos y las asignaciones son eventos financieros **append-only** e idempotentes: no se editan ni se eliminan; los duplicados no se resuelven por sobrescritura ni por reloj local. Ajuste, anulación y Reembolso quedan para F7.

**Sincronización y conflictos**

- Por decisión del titular se extiende a F6 la política de conflictos de F3–F5 para las entidades con ciclo de vida documental: `collection_account` y `collection_method` usan detección por versión base, preservación de ambas copias y resolución explícita del usuario.
- `payment` y las operaciones de asignación son eventos financieros append-only: idempotencia por `operation_id` y validación atómica de saldo en el servidor; sin resolución por sobrescritura ni por reloj local.
- Requisitos registrados para la implementación: dependencias y propiedad de referencias (Cuenta → Cliente+Perfil; asociaciones → Cuenta+Trabajo/Cotización; Pago → Cliente+Método+documento destino) y orden de sincronización que impida aplicar un evento antes que sus referencias. Los contratos físicos no se deciden todavía.
- La política de conflictos para las entidades de F7 (Consolidado, ajustes, reembolsos) no se aprueba aquí y queda pendiente de decisión en su ficha.

**Exclusiones de F6**

- Fuera del alcance de F6: Consolidado, `payment_adjustments`, Reembolsos, PDF y exportación, envío por correo o enlace, fotografías/archivos, Inventario, Compras, Herramientas, Web y cualquier tratamiento fiscal. Las fotos/archivos siguen pendientes conforme a §4.6.

**Estado de planificación:** F6 documentada y aprobada para planificación el 13/10/2026; su implementación requiere autorización explícita posterior del titular.

## 7. Historial de cambios

| Versión | Fecha | Cambio | Motivo | Estado |
|---|---|---|---|---|
| 1.0 | 30/09/2026 | Anexo inicial de decisiones aprobadas y brechas. | Complementar v0.2 y revisión documental. | Aprobado. |
| 1.1 | 08/10/2026 | Base de retención solo `SERVICIO` con ejemplo aprobado; numeración provisional `TIPO-PEND-XXXX` y oficial global usuario+tipo+período; pago sin Cuenta previa (anticipo); corrección/anulación de Pagos en V1 vía `payment_adjustments`; catálogo por Perfil; pendientes actualizados (numeración y alcance de catálogo resueltos; nuevos pendientes de asignación de pagos y concepto `OTRO`). | Incorporación de decisiones aprobadas por el titular. | Aprobado. |
| 1.2 | 08/10/2026 | D13-B aprobada: asignación de pagos sin asignar con aplicación parcial — el excedente sobre el saldo del destino permanece `UNASSIGNED`; D14-C aprobada: base de retención determinada por `applies_to` configurable por regla (`OTRO` sin comportamiento hardcodeado); decisiones técnicas aprobadas: package Android `com.cotixgo.app` y JDK 21 LTS (registradas en `05`). Pendientes resueltos: mecánica de asignación de pagos y tratamiento de `OTRO`. | Aprobación de las decisiones pendientes de la etapa V1.1. | Aprobado. |
| 1.3 | 11/10/2026 | F1: mínimos aprobados de Cliente y Perfil Profesional (§2.6); pendiente §4.7 acotado a Trabajo, Cotización y Cuenta de Cobro. | Aprobación de las decisiones pendientes de la ficha F1. | Aprobado. |
| 1.4 | 11/10/2026 | Planificación de F3: solo datos sintéticos en desarrollo y pruebas hasta definir, implementar y verificar la protección de datos locales en reposo (§6); tecnología de cifrado local queda pendiente (§4.8) y no se añade a F3 automáticamente; recuperación de acceso por correo aprobada en principio con proveedor pendiente; no se requiere MySQL de Hostinger durante la planificación ni el desarrollo local. | Aprobación de la decisión de protección de datos locales de la ficha F3. | Aprobado. |
| 1.5 | 11/10/2026 | Planificación de F3 — pase documental completo (§6): identidad canónica del servidor y vinculación transaccional de datos locales; reglas de cambio de cuenta; política de conflictos aprobada (detección explícita, preservación, resolución del usuario, versión de resolución nueva en Cotizaciones, `emittedVersion` a la versión de resolución); rechazo de transiciones obsoletas con estado actual; reintentos persistentes sin descarte e idempotencia por `operation_id` + payload inmutable con operación corregida encadenada; momento del número oficial aprobado para Cotización (primera `EMITIDA`/`APROBADA` aceptada por el backend); auth email/contraseña como base; HTTPS obligatorio para datos reales. Pendiente §4.3 marcado parcialmente resuelto (estructura técnica pendiente). | Registro documental de las decisiones de producto de F3 ya aprobadas por el titular. | Aprobado. |
| 1.6 | 11/10/2026 | F3 — alcance de `VENCIDA` aclarado (§6): el vencimiento solo aplica a los estados no terminales `BORRADOR`, `EMITIDA` y `RECHAZADA`; `APROBADA` y `CANCELADA` permanecen terminales (la aprobada puede originar un Trabajo). El vencimiento materializado se sincroniza como transición `→VENCIDA` aceptada por el servidor desde los mismos estados. | Aclaración del alcance de la regla de vencimiento para alinear Android y API. | Aprobado. |
| 1.7 | 12/10/2026 | F3 integrada en `main` (merge `9e1a29c`). Planificación de F4 aprobada (§6): matriz de transiciones de Trabajo (`ENTREGADO`/`CANCELADO` terminales, sin "Pausar", edición solo fuera de terminales); dirección de ejecución propia obligatoria al `PROGRAMADO`; Trabajo numerable `TR-PEND-XXXX` → `TR-AAAAMM-NNN` en primera aceptación `PROGRAMADO`/`EN PROCESO`; materiales F4 descriptivos sin efecto en inventario ni reinterpretación retroactiva; actividades `PENDIENTE`/`EN PROCESO`/`COMPLETADA`; política de conflictos de F3 extendida a las entidades de F4. Brechas §3.2 y pendientes §4.1, §4.3 y §4.7 actualizados (estructura técnica de sincronización implementada en local para entidades F3; extensión de implementación a F4 y fases posteriores pendiente). | Registro de las decisiones de alcance de F4 aprobadas por el titular y corrección del estado posterior al merge de F3. | Aprobado. |
| 1.8 | 12/10/2026 | F4 implementada en local con datos sintéticos tras la aprobación de etapa del titular: Trabajos en Android (Room v5), sincronización de `Job`/`JobQuote`/`JobActivity`/`JobMaterial`, numeración `TR` del lado del servidor, matriz de transiciones, actividades y materiales descriptivos, archivado sin borrado y política de conflictos de F3 aplicada a las entidades nuevas (detalle técnico en `05` §5.2). Pendiente §4.3 actualizado: la política de conflictos quedó implementada para las entidades de F4; sigue pendiente solo la extensión a fases posteriores. La lista definitiva de campos obligatorios de Trabajo y Cuenta de Cobro (§4.7) permanece abierta. | Cierre de la implementación local de F4 autorizada por el titular; pendiente revisión vía PR. | Aprobado. |
| 1.9 | 12/10/2026 | Correcciones de la revisión independiente del PR de F4: validación en servidor del vínculo `job_quote` (cotización `APROBADA` y mismo Cliente, leído de las entidades persistidas), dependencias requeridas no vacías, "conservar remoto" que reaplica `official_number`/`has_version_conflict` (compartido con Cotización), orden de cola de `job_quote` tras las operaciones de la Cotización, invariante de Cliente en repositorio y prueba de migración real v4→v5 (detalle en `05` §5.2 y historial 1.10). | Corrección de los hallazgos de la revisión del PR de F4; pendiente revisión vía PR. | Aprobado. |
| 1.10 | 12/10/2026 | F4 integrada en `main` por el merge `137de38` (PR #7) tras la revisión del titular; Gradle Sync dentro de Android Studio con JDK 21 quedó pendiente de verificación manual. Nueva sección §6 para la planificación de F5 (Informes de Trabajo en Android): alcance opcional interno/Cliente, agrupación de Trabajos del mismo Cliente y Perfil Profesional validada con datos persistidos en la API, solo actividades `COMPLETADA` como ejecución presentada, estados `BORRADOR`/`EMITIDO` con corrección por versión nueva enlazada y snapshot conservado, numeración `INF-PEND-XXXX` → `INF-AAAAMM-NNN` en la primera aceptación `EMITIDO`, campos mínimos aprobados, política de conflictos de F3/F4 extendida a entidades de F5 y exclusiones (PDF, fotos/archivos, envío, Inventario/Compras/Cuenta de Cobro/Pagos). §2.3, §4.3, §4.6 y §4.7 actualizados en consecuencia. La implementación de F5 no está autorizada. | Aprobación de las decisiones de planificación de F5 por el titular y corrección del estado posterior al merge de F4. | Aprobado. |
| 1.11 | 12/10/2026 | F5 implementada en local con datos sintéticos tras la aprobación de etapa del titular: Informes en Android (Room v6), sincronización de `report`/`report_job`/`report_version`, numeración `INF` del lado del servidor, estados `BORRADOR`/`EMITIDO` con corrección por versión enlazada, snapshots inmutables, agrupación Cliente+Perfil validada en la API, solo actividades `COMPLETADA` como ejecución y política de conflictos aplicada a las entidades nuevas (detalle técnico en `05` §5.3; evidencia en `evidence/f5/`). §4.3 y la ficha §6 actualizados: la política de conflictos y la implementación quedan registradas para las entidades de F5; sigue pendiente solo la extensión a fases posteriores. | Cierre de la implementación local de F5 autorizada por el titular; pendiente revisión vía PR. | Aprobado. |
| 1.12 | 13/10/2026 | F5 integrada en `main` (merge `b424c3f`, PR #9) y correctivo de edición de Trabajo integrado (merge `0df9c87`, PR #10). Planificación de F6 aprobada (§6): división F6 (Cuenta de Cobro + Métodos de Cobro + pagos esenciales) / F7 (Consolidado + `payment_adjustments` + Reembolsos); campos mínimos y ciclo de vida de la Cuenta (editable solo `PENDIENTE` sin pagos; `PARCIAL`/`PAGADA` automáticos; cancelación solo sin pagos); líneas `SERVICIO`/`MATERIAL`/`OTRO` con motor de cálculo compartido; numeración `CC-PEND-XXXX` → `CC-AAAAMM-NNN` en el primer registro aceptado por el backend; asociaciones 0..N de un solo tipo (Trabajos o Cotizaciones) del mismo Cliente+Perfil y a lo sumo una Cuenta no cancelada por origen; elegibilidad por estado de Cotización registrada como pendiente bloqueante (§4.9); política de conflictos extendida a `collection_account`/`collection_method` y eventos financieros aprobados como append-only con validación de saldo en servidor; limitación registrada de que F6 no es apta para datos reales antes de F7. §3, §4.2–§4.5 y §4.7 actualizados; §4.9 nuevo. La implementación de F6 no está autorizada. | Registro de las decisiones de planificación de F6 aprobadas por el titular y corrección del estado posterior a los merges de F5 y del correctivo. | Aprobado. |

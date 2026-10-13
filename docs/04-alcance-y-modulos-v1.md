# CotixGo — Alcance y módulos de V1

**Versión:** 1.9  
**Estado:** Especificación de alcance basada en reglas aprobadas  
**Producto:** Herramienta profesional de campo para trabajadores independientes y pequeños proveedores de servicios.

## 1. Objetivo de V1

Permitir que el profesional prepare cotizaciones, gestione clientes y trabajos, registre la ejecución real, documente el servicio, emita Cuentas de Cobro y registre pagos. La experiencia debe apoyar el trabajo en campo, incluso sin conexión en Android.

CotixGo debe sentirse como una herramienta que ayuda al profesional a estar preparado; no como una aplicación contable ni como un mini-ERP.

Flujo orientador, no obligatorio:

```text
Cliente → Cotización (opcional) → Trabajo → Actividades / Materiales / Fotos
        → Informe (opcional, incluso interno) → Cuenta de Cobro → Pagos
```

Flujo de control interno:

```text
Compra → Inventario → Consumo propio registrado explícitamente en el Trabajo
```

Un Trabajo no requiere Cotización. El Informe es opcional; el Trabajo puede entregarse sin Informe y una Cuenta de Cobro puede generarse sin Informe. Los documentos opcionales no bloquean el flujo. Una Cuenta de Cobro también puede crearse directamente para un Cliente sin Cotización ni Trabajo.

## 2. Módulos incluidos

Los módulos siguientes forman parte del producto definido. Las funciones enumeradas reflejan decisiones aprobadas; los detalles no especificados aparecen como pendientes en `03-anexo-decisiones-aprobadas-y-brechas.md`.

### Regla transversal de archivo e historial

- Archivar cualquier registro o documento solo lo oculta de las vistas operativas habituales; cualquier documento archivado se puede desarchivar para volver a mostrarlo.
- Archivar o desarchivar no elimina datos, no anula operaciones ni revierte o modifica movimientos de inventario, pagos, transacciones, asignaciones, relaciones o historial.
- Ningún tipo de dato generado por el usuario se elimina físicamente. La anulación, cuando aplique, es una operación distinta y trazable.

### Regla transversal de numeración documental

- Todo documento numerable recibe de inmediato un identificador provisional `TIPO-PEND-XXXX` (por ejemplo `COT-PEND-8F3A`), visible en la interfaz y utilizable en un PDF provisional. No compite con la numeración oficial ni genera un segundo documento.
- El backend asigna el número oficial sobre el mismo documento cuando existe conectividad. La secuencia oficial es global por Usuario/Titular + tipo de documento + período, independiente del Perfil Profesional.

### 2.1 Dashboard

- Vista operacional orientada a acciones pendientes, no a analítica financiera.
- Acciones primarias: `+ Cotizar`, `+ Cuenta de Cobro`, `+ Trabajo`.
- Resumen accionable de cotizaciones pendientes/próximas a vencer, trabajos programados/en proceso, informes pendientes y Cuentas de Cobro pendientes/parciales.
- No saturar V1 con gráficos.

### 2.2 Clientes

- Mantener una cartera única de Clientes global al Usuario/Titular. Un Cliente no pertenece a un Perfil Profesional y puede utilizarse desde varios Perfiles del mismo usuario; no duplicarlo por Perfil.
- Datos contemplados: identificación, nombre, nombre comercial, teléfonos, correos, dirección principal/fiscal, ciudad, país, notas y estado activo/archivado.
- Mínimos aprobados para F1: `nombre` obligatorio; `tipo` persona/empresa; `teléfono`, `correo`, `dirección` y `notas` opcionales; `archivado`.
- Usar configuraciones comerciales del Cliente como valores predeterminados; cada documento conserva los valores efectivamente usados.
- La dirección de ejecución se registra en el Trabajo y no se deduce necesariamente del domicilio del Cliente.

### 2.3 Cotizaciones

- Crear cotizaciones para un Cliente y un Perfil Profesional.
- Incluir servicios, mano de obra y materiales, o únicamente servicio/mano de obra cuando el Cliente suministra materiales.
- Usar opcionalmente el Catálogo de Servicios del Perfil Profesional o escribir conceptos manualmente.
- Tratar precios de catálogo como sugerencias; guardar en cada documento la descripción, cantidades y valores utilizados.
- El profesional define la vigencia al crear cada Cotización; el sistema calcula su fecha de vencimiento.
- Modificar cotizaciones abiertas manteniendo versiones internas.
- Estados: `BORRADOR`, `EMITIDA`, `APROBADA`, `RECHAZADA`, `VENCIDA`, `CANCELADA`. El profesional registra manualmente la aprobación; puede editar `BORRADOR` y `EMITIDA` antes del vencimiento y cancelar cualquiera de esos dos estados. El vencimiento se determina automáticamente por fecha y solo alcanza a los estados no terminales (`BORRADOR`, `EMITIDA`, `RECHAZADA`); `APROBADA` y `CANCELADA` son terminales (`03` §6).
- Aprobar una Cotización habilita la creación manual de un Trabajo, pero no lo crea automáticamente. El sistema controla las transiciones de estado.
- No permitir editar/aprobar una cotización vencida bajo sus condiciones anteriores; permitir usarla como base de una nueva.
- Permitir revertir un rechazo mientras la Cotización no haya sido aprobada; el sistema valida las transiciones según el estado actual.
- No consultar ni exigir inventario como condición para cotizar.
- Mostrar y conservar los cálculos comerciales y la moneda histórica del documento.
- V1 usa código ISO 4217, dos decimales y símbolo visible. Los pagos usan la moneda principal del usuario; los documentos existentes conservan su moneda si cambia la moneda principal.

### 2.4 Trabajos

- Crear desde una Cotización o directamente desde un Cliente.
- Relacionar opcionalmente un Trabajo con una o varias Cotizaciones.
- Estados aprobados: `BORRADOR`, `PROGRAMADO`, `EN PROCESO`, `ENTREGADO`, `CANCELADO`.
- Transiciones aprobadas (F4, `03` §6): `BORRADOR → PROGRAMADO | EN PROCESO | CANCELADO`; `PROGRAMADO → BORRADOR | EN PROCESO | CANCELADO`; `EN PROCESO → ENTREGADO | CANCELADO`; `ENTREGADO` y `CANCELADO` terminales; sin transición ni estado de "Pausar". Un Trabajo entregado o cancelado no se edita; la edición no cambia el estado.
- La dirección de ejecución es propia del Trabajo: puede quedar pendiente en `BORRADOR` y es obligatoria al pasar a `PROGRAMADO` (aprobado en F4).
- El Trabajo es numerable: provisional `TR-PEND-XXXX` desde la creación; oficial `TR-AAAAMM-NNN` asignado por el backend en la primera aceptación del Trabajo en `PROGRAMADO` o `EN PROCESO`, sin numeración por Perfil Profesional (aprobado en F4).
- Actividades subordinadas al Trabajo con estados `PENDIENTE`, `EN PROCESO`, `COMPLETADA` (aprobados en F4).
- Registrar información general, lugar de ejecución, actividades, materiales utilizados, fotografías, observaciones y herramientas.
- Clasificar fotografías como `ANTES`, `DURANTE`, `DESPUÉS` u `OTRA`.
- Distinguir lo cotizado, lo ejecutado y lo consumido del inventario.
- Registrar explícitamente consumo propio; materiales suministrados por el Cliente no generan movimiento de inventario propio. En el alcance de F4 — sin módulo de inventario — el registro de materiales del Trabajo es descriptivo (descripción, cantidad, unidad, origen `CLIENTE`/`PROFESIONAL`, observaciones), sin catálogo ni `material_id`, sin generar movimientos y sin reinterpretación retroactiva cuando llegue la fase de inventario (aprobado en F4).
- Al entregar el Trabajo, registrar que la ejecución terminó. El Informe y la Cuenta de Cobro pueden generarse luego.

### 2.5 Inventario

- Control interno no restrictivo y global al Usuario/Titular, no asociado a Perfiles Profesionales.
- Estructura: `Inventario → Materiales / Herramientas`. El tipo determina el comportamiento funcional; la categoría solo organiza.
- Permitir al usuario crear libremente categorías separadas para Materiales y Herramientas. No cargar categorías predeterminadas; los ejemplos solo orientan.
- Consultar materiales y existencias; contemplar condición nueva/usada y origen del material.
- Mantener movimientos históricos explicables.
- Permitir ajustes para cargar el inventario existente en la bodega del profesional y para corregir existencias.
- Reflejar entradas por Compras confirmadas y salidas únicamente por consumo propio registrado explícitamente en un Trabajo.
- Cotizar materiales o incluirlos en un Informe no modifica el inventario.
- No validar el consumo contra la existencia disponible: el Trabajo registra la cantidad realmente utilizada y el inventario puede quedar negativo.
- Una falta de existencias o una discrepancia no bloquea ni altera el Trabajo, su Informe ni el cobro.

### 2.6 Compras

- Crear una Compra con una o varias líneas de materiales.
- Registrar fecha, proveedor/lugar, referencia de factura o recibo, material, cantidad, unidad, precio unitario, total y notas.
- Mantener Compras en borrador sin modificar inventario.
- Al confirmar una Compra, generar movimientos de entrada por cada elemento y cantidad.
- Una Compra confirmada puede anularse mediante un movimiento inverso, editarse o archivarse. Al editarla, el sistema aplica al inventario la diferencia entre las cantidades confirmadas antes y después: aumenta por cantidades agregadas/aumentadas y disminuye por cantidades reducidas/eliminadas, conservando la trazabilidad de movimientos. Archivar solo oculta la Compra y no cambia el inventario.
- El comprobante de compra pertenece al registro de Compra. El Informe de Trabajo es independiente, se genera desde el Trabajo y describe su ejecución; aprobar una Compra no genera automáticamente un Informe de Trabajo.
- No se requiere adjuntar fotografía del recibo en V1.

### 2.7 Cuenta de Cobro

- Crear desde Cotización, desde Trabajo o sin Cotización.
- Crear directamente para un Cliente sin Cotización ni Trabajo. En una atención de emergencia que se paga de inmediato, el flujo puede ser únicamente Cuenta de Cobro y Pago; no requiere Informe, fotografías ni registro de Trabajo.
- Asociaciones aprobadas en F6 (`03` §6): una Cuenta puede incluir varios Trabajos **o** varias Cotizaciones, pero nunca mezclar ambos tipos en la misma Cuenta. Todos los asociados deben ser del mismo Cliente y del mismo Perfil Profesional. Cada Trabajo o Cotización solo puede pertenecer a una Cuenta no cancelada; al cancelarse la Cuenta deja de bloquear esa asociación. Elegibilidad aprobada en F6 (`03` §6): solo se pueden asociar Cotizaciones en estado `APROBADA` — `BORRADOR`, `EMITIDA`, `RECHAZADA`, `VENCIDA` y `CANCELADA` no son elegibles — validado con el estado persistido por el backend.
- Estados aprobados: `PENDIENTE`, `PARCIAL`, `PAGADA`, `CANCELADA`. La Cuenta se edita mientras esté `PENDIENTE` y sin pagos aplicados; con algún pago aplicado, sus líneas, totales y snapshots no se editan (F6, `03` §6).
- Separar la creación de la Cuenta del evento de pago, incluso cuando el Cliente paga inmediatamente.
- Aplicar las reglas compartidas de retenciones, bruto/neto y moneda. Las retenciones de V1 se calculan sobre los conceptos clasificados como `SERVICIO`; los `MATERIAL` quedan fuera de la base. La base de cada retención se determina por su configuración `applies_to`; `OTRO` no está incluido ni excluido universalmente. La Cuenta conserva snapshot histórico de las retenciones aplicadas.
- En V1 no hay descuentos. El profesional ingresa el precio como bruto y se aplican las reglas aprobadas de retenciones. El tratamiento de impuestos y cualquier configuración fiscal permanecen pendientes; no son requisitos aprobados de V1.
- Los importes se redondean por línea y el total es la suma de las líneas redondeadas. Las cantidades se redondean a dos decimales.
- Campos mínimos aprobados en F6 (`03` §6): `clientId`, `profileId`, moneda, líneas, bruto/retenciones/neto/saldo, estado, número provisional/oficial, `issuedAt`, fecha de vencimiento opcional, condiciones opcionales, snapshots históricos de Cliente/Perfil/retenciones, `archivado` y marcas de tiempo. Las líneas (`CollectionAccountItem`) llevan concepto, tipo `SERVICIO`/`MATERIAL`/`OTRO`, cantidad, precio unitario y valores históricos (`02` §22).
- Numeración aprobada en F6 (`03` §6): `CC-PEND-XXXX` desde la creación; el backend asigna `CC-AAAAMM-NNN` sobre el mismo `entity_id` en el primer registro de la Cuenta aceptado por el servidor; secuencia global por Usuario/Titular + tipo + período.
- Conservar la información histórica de emisión. No asumir que cada cambio de estado congela automáticamente documentos relacionados.
- Gestionar Métodos de Cobro globales del Usuario/Titular, independientes de Perfiles Profesionales. El usuario los crea y administra; no hay métodos predeterminados. La interfaz puede mostrar ayuda y ejemplos sin insertar registros iniciales. Los métodos pueden utilizarse al registrar pagos recibidos y reembolsos. Mínimos aprobados en F6 (`03` §6): `nombre` requerido, `detalle` opcional y `archivado`.
- El Consolidado de Cuentas no forma parte de F6; queda asignado a F7 (`03` §6). Sigue siendo el único mecanismo para agrupar Cuentas; sus reglas de producto quedaron aprobadas en la ficha F7 (`03` §6) y su implementación sigue sin autorización.

### 2.8 Perfiles Profesionales

- Permitir varios perfiles comerciales por usuario.
- Cada perfil aporta identidad comercial, descripción/actividad, logo, información comercial, servicios, documentos y configuración comercial. No es propietario de Clientes, Inventario ni Métodos de Cobro.
- Mínimos aprobados para F1: `nombre_comercial` obligatorio; `descripción` e `información_comercial` opcionales; `logo` como referencia local opcional en F1.
- No asumir que un perfil equivale a una persona jurídica independiente.
- Conservar en cada documento histórico los datos del perfil usados al emitir, incluido logo.
- El catálogo de Servicios se administra por Perfil Profesional.

### 2.9 Informes

- Informe opcional: crear a partir de datos registrados durante la ejecución, sin volver a introducir desde cero toda la información. Puede entregarse al Cliente o ser exclusivamente para organización/histórico interno del profesional (destino `INTERNO` o `CLIENTE`, aprobado en F5).
- Estados: `BORRADOR` y `EMITIDO`. Un Informe emitido no se edita; una corrección crea una versión nueva enlazada por referencia, conservando la versión emitida anterior y su snapshot inmutable (aprobado en F5, `03` §6).
- Describir lo realmente ejecutado, no lo originalmente cotizado. Solo las actividades en estado `COMPLETADA` de los Trabajos asociados se presentan como ejecución realizada; las `PENDIENTE` o `EN PROCESO` no se presentan como ejecutadas (aprobado en F5, `03` §6).
- El contenido es editable mientras el Informe está en `BORRADOR`; revisar/completar el contenido y seleccionar evidencias antes de generar el documento.
- Permitir agrupar varios Trabajos del mismo Cliente **y del mismo Perfil Profesional** en un Informe, manteniendo identidad y trazabilidad individual por Trabajo; la API valida las relaciones con datos persistidos, no con las declaradas por el cliente (aprobado en F5, `03` §6).
- El Informe es numerable: provisional `INF-PEND-XXXX`; oficial `INF-AAAAMM-NNN` asignado por el backend sobre el mismo `entity_id` en la primera aceptación como `EMITIDO` (aprobado en F5, `03` §6).
- Campos mínimos aprobados para F5 (`03` §6): `clientId`, `profileId`, título requerido, contenido/observaciones editables en borrador, estado, número provisional/oficial, destino `INTERNO`/`CLIENTE`, archivado y marcas de tiempo.
- En F5 no se incluyen PDF/exportación, fotografías ni archivos adjuntos, ni envío al Cliente (`03` §6; las fotos/archivos siguen pendientes según `03` §4.6).
- No exigir un Informe para entregar un Trabajo o generar una Cuenta de Cobro.

### 2.10 Pagos

El registro de pagos es una capacidad del flujo de Cuenta de Cobro aunque no figure como módulo independiente en la lista de nueve módulos.

- Registrar el evento real de recepción de dinero por separado de la Cuenta de Cobro.
- Normalmente se registra un Pago contra una Cuenta de Cobro. Para agrupar Cuentas del mismo Cliente se utiliza el Consolidado de Cuentas de Cobro, que conserva intactas las Cuentas originales, usa sus valores históricos y permite gestionar el saldo consolidado y registrar pagos contra este. No existe un pago directo paralelo sobre varias Cuentas. En F6 solo se implementan el pago contra una Cuenta individual y el pago sin asignar; el pago contra Consolidado corresponde a F7 y sus reglas quedaron aprobadas en la ficha F7 (`03` §6): elegibilidad no `CANCELADA` con saldo > 0, mismo Cliente y moneda, Perfil libre; ciclo `PENDIENTE`/`PARCIAL`/`PAGADA`/`CANCELADA` derivado; anti-doble-vínculo; numeración `CONS-`.
- Actualizar automáticamente el estado a `PARCIAL` o `PAGADA` según pagos asignados. No cancelar una Cuenta con pagos aplicados.
- No permitir registrar un pago contra un documento por encima de su saldo ni dejar una parte de ese pago sin aplicar a ese documento (la asignación de pagos previamente sin asignar admite aplicación parcial con excedente sin asignar). La validación de saldo se realiza en el servidor de forma atómica (`03` §6, ficha F6).
- Los Pagos y las operaciones de asignación son eventos financieros append-only e idempotentes: no se editan ni se eliminan físicamente; los duplicados no se resuelven por sobrescritura ni por reloj local (`03` §6, ficha F6).
- Al cancelar/anular un Pago, conservarlo en el historial y excluirlo del cálculo del documento asociado. Diseño aprobado en la ficha F7 (`03` §6): `ANNULMENT` es terminal y de una sola vez por Pago; libera atómicamente las asignaciones vigentes y fija el monto efectivo en el total reembolsado (0 sin Reembolsos); tras aceptarse se rechazan nuevas asignaciones, reasignaciones, correcciones y reembolsos; reintento idempotente y segunda anulación distinta rechazada.
- Al corregir el importe de un Pago, aplicar la diferencia al mismo Pago y al documento asociado (una Cuenta o un Consolidado). Un pago contra Consolidado puede ser parcial; afecta su saldo conjunto y no altera las Cuentas originales. Diseño aprobado en la ficha F7 (`03` §6): `CORRECTION` al alza deja el incremento `UNASSIGNED` hasta asignación explícita; a la baja consume la parte libre y luego las asignaciones más recientes (LIFO) atómicamente; se rechaza si queda por debajo del total reembolsado.
- La corrección y anulación de Pagos son funcionalidad de V1 con trazabilidad: cada ajuste conserva valor anterior, valor nuevo, motivo, usuario, dispositivo, operación y fecha; el registro original no se modifica silenciosamente ni se elimina (mecanismo `payment_adjustments`, diseño confirmado en la ficha F7, `03` §6).
- Limitación aprobada de F6 (`03` §6): antes de F7, un error en un Pago no se resuelve editándolo ni anulándolo; solo se aplican las operaciones aprobadas de asignación/reasignación, sin falsear el historial. F6 no es apta para datos reales antes de que ese mecanismo esté implementado y verificado.
- Un Reembolso es un nuevo movimiento de dinero y no modifica ni elimina el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro utilizado y observación. V1 no tiene estado `REEMBOLSADA`. Los Reembolsos corresponden a F7 con alcance aprobado en la ficha F7 (`03` §6): solo hasta el remanente `efectivo − aplicado − reembolsado` compartido con las asignaciones; irreversible; reembolsar lo aplicado exige liberar antes con operación auditada.
- Un pago puede recibirse antes de que exista la Cuenta de Cobro correspondiente (anticipo): queda registrado sin asignación, vinculado al Cliente, y se asocia posteriormente mediante operaciones auditadas (`assign`/`unassign`/`reassign`) — en F6 a una Cuenta individual; la asociación a un Consolidado corresponde a F7 (`03` §6). No existe un módulo separado de anticipos.
- Al asignar un pago sin asignación a un documento, si su importe supera el saldo pendiente del destino se aplica únicamente lo necesario para cubrirlo y el excedente permanece sin asignar dentro del mismo pago, disponible para futuras asignaciones. Nunca existe dinero aplicado simultáneamente a dos documentos. Ejemplo: pago $500.000 sobre Cuenta de $300.000 → se aplican $300.000, la Cuenta queda `PAGADA` y $200.000 quedan sin asignar.
- Un pago registrado directamente contra un documento no puede superar su saldo ni quedar parcialmente sin asignar a ese documento.

### 2.11 Herramientas

El seguimiento de herramientas es opcional dentro del control operativo/inventario. V1 contempla registro y estados básicos: disponible, en uso, mantenimiento, dañada y fuera de servicio. El mantenimiento avanzado puede quedar para una fase posterior.

## 3. Restricciones y exclusiones explícitas de V1

- No se implementa una aplicación Desktop Java.
- No se convierte el producto en un sistema contable o mini-ERP.
- No se requieren analítica administrativa ni gráficos extensos.
- No se almacena fotografía de recibos de compra en V1.
- No se requiere catálogo de servicios para crear una Cotización.
- El inventario nunca es requisito para cotizar, trabajar, documentar, cobrar o registrar pagos.
- No se asume conversión automática entre monedas.
- No se asume tasa fija de retención.
- La colaboración multiusuario/permisos avanzados no está definida para V1.

## 4. Criterios de alcance

La operación se considera cerrada e histórica cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente está completamente pagada. Esta condición no implica congelar automáticamente cada documento relacionado en cada transición de estado. Una garantía posterior no reabre automáticamente Trabajo, Informe, Cotización o Cuenta de Cobro.

- Los nueve módulos enumerados son módulos funcionales aprobados, pero las reglas detalladas de este documento solo comprometen los comportamientos descritos.
- Si una pantalla o comportamiento del diseño visual existente parece contradecir una regla de negocio, prevalece la regla aprobada y se registra la diferencia para revisión.
- Las reglas de estados, cálculo, permisos, sincronización y documentos históricos no pueden inferirse solamente del diseño visual.
- La imagen de referencia existente [`CotixGo_ Gestión Inteligente de Servicios.png`](CotixGo_%20Gesti%C3%B3n%20Inteligente%20de%20Servicios.png) sirve como referencia visual; no sustituye este contrato funcional.

## 5. Historial de cambios

| Versión | Fecha | Cambio | Motivo | Estado |
|---|---|---|---|---|
| 1.0 | 30/09/2026 | Alcance y módulos de V1. | Especificación de alcance. | Aprobado. |
| 1.1 | 08/10/2026 | Regla transversal de numeración documental (provisional `TIPO-PEND-XXXX` + oficial global); retenciones solo sobre `SERVICIO`; catálogo por Perfil; pago sin Cuenta previa (anticipo); corrección/anulación de Pagos en V1. | Incorporación de decisiones aprobadas por el titular. | Aprobado. |
| 1.2 | 08/10/2026 | D13-B: asignación de pagos con aplicación parcial (excedente `UNASSIGNED`); D14-C: base de retención por `applies_to` configurable. | Aprobación de las decisiones pendientes de la etapa V1.1. | Aprobado. |
| 1.3 | 11/10/2026 | F1: campos mínimos aprobados de Cliente (§2.2) y Perfil Profesional (§2.8). | Aprobación de las decisiones pendientes de la ficha F1. | Aprobado. |
| 1.4 | 12/10/2026 | F4 (planificación aprobada): §2.4 registra la matriz de transiciones de Trabajo con `ENTREGADO`/`CANCELADO` terminales y sin "Pausar", dirección de ejecución propia obligatoria al `PROGRAMADO`, numeración `TR-PEND-XXXX` → `TR-AAAAMM-NNN`, estados de actividad `PENDIENTE`/`EN PROCESO`/`COMPLETADA` y registro de materiales F4 descriptivo sin efecto en inventario. | Aprobación de las decisiones de la ficha F4 por el titular. | Aprobado. |
| 1.5 | 12/10/2026 | F5 (planificación aprobada): §2.9 registra las decisiones de Informes — Trabajos asociados del mismo Cliente y Perfil Profesional validados con datos persistidos en la API, solo actividades `COMPLETADA` como ejecución presentada, edición en `BORRADOR`, corrección por versión nueva enlazada con snapshot conservado, numeración `INF-PEND-XXXX` → `INF-AAAAMM-NNN` en primera aceptación `EMITIDO`, destino `INTERNO`/`CLIENTE`, campos mínimos y exclusiones de F5 (sin PDF, fotos/archivos ni envío). | Aprobación de las decisiones de la ficha F5 por el titular. | Aprobado. |
| 1.6 | 13/10/2026 | F6 (planificación aprobada): §2.7 registra campos mínimos, líneas `SERVICIO`/`MATERIAL`/`OTRO` con motor compartido, edición solo `PENDIENTE` sin pagos, asociaciones de un solo tipo del mismo Cliente+Perfil con anti-doble-vínculo, numeración `CC-PEND-XXXX` → `CC-AAAAMM-NNN` en primer registro aceptado por el backend, y elegibilidad de Cotización pendiente; §2.10 registra pagos esenciales F6 (Cuenta individual o sin asignar), append-only/idempotencia, validación de saldo en servidor, `UNASSIGNED` en excedente, asignaciones auditadas con motivo, exclusión de Consolidado/ajustes/Reembolsos a F7 y la limitación de que F6 no es apta para datos reales antes de F7. | Aprobación de las decisiones de la ficha F6 por el titular. | Aprobado. |
| 1.7 | 13/10/2026 | Corrección de propagación de las decisiones F6 ya aprobadas (sin decisiones nuevas): §2.10 aclara que la asociación posterior de un anticipo en F6 es a una Cuenta individual; la asociación a un Consolidado corresponde a F7. | Coherencia del alcance F6/F7 tras la revisión del PR documental. | Aprobado. |
| 1.8 | 13/10/2026 | §2.7 registra la elegibilidad de Cotización aprobada por el titular: solo Cotizaciones `APROBADA` son elegibles como origen de Cuenta de Cobro, validado con el estado persistido por el backend. | Aprobación de la decisión pendiente `03` §4.9 por el titular. | Aprobado. |
| 1.9 | 13/10/2026 | §2.10 registra las reglas F7 aprobadas por el titular (sin funcionalidad nueva en F6): especificación del Consolidado (elegibilidad, composición, ciclo derivado, anti-doble-vínculo, numeración `CONS-`), `CORRECTION` con absorción LIFO y piso de reembolsados con alza `UNASSIGNED`, `ANNULMENT` terminal con piso y rechazo de operaciones posteriores, y Reembolsos limitados al remanente `efectivo − aplicado − reembolsado` compartido con asignaciones. La implementación de F7 sigue sin autorización. (Entrada renumerada desde 1.8 tras la revisión del PR documental #14.) | Registro de las decisiones de la ficha F7 por el titular. | Aprobado. |

# CotixGo — Tipos y contratos de datos

**Versión:** 1.2  
**Estado:** Convenciones conceptuales para alinear el modelo; no constituye DDL ni esquema de API final.

## 1. Propósito

Dar a Devin un vocabulario común para campos, tipos y relaciones sin fingir que ya se aprobaron todas las columnas físicas. Las entidades y relaciones base están en `02-modelo-de-datos.md`; las reglas prevalentes están en `01-reglas-de-negocio-v0.2.md` y `03-anexo-decisiones-aprobadas-y-brechas.md`.

## 2. Tipos lógicos

| Tipo lógico | Uso | Regla de representación |
|---|---|---|
| ID global | Identidad técnica de entidad u operación | Único globalmente y generable offline. No usar número consecutivo de pantalla como PK. Algoritmo exacto pendiente. |
| Texto | Nombres, descripciones, notas, direcciones, referencias | No asumir límites de longitud sin definirlos en el contrato de campo. Preservar contenido histórico de documentos. |
| Código/enum | Estados, tipo de concepto, origen, método o clasificación | Valores controlados y documentados; no depender de etiquetas traducidas de UI como valor persistido. Conjuntos aún no aprobados se marcan pendientes. |
| Decimal monetario | Precio, subtotal, retención, total, pago, reembolso y saldo | No usar punto flotante binario. En V1, dos decimales; importes redondeados por línea y total igual a la suma de líneas redondeadas. Moneda debe acompañar cada importe documental. Los impuestos no son requisito aprobado de V1. |
| Decimal de cantidad | Cantidad de material, mano de obra o unidad fraccionaria | No usar entero por defecto. En V1 las cantidades se redondean a dos decimales. |
| Fecha | Día comercial de emisión, vencimiento, compra o ejecución | Mantener separada de una marca de tiempo. Zona horaria/regla de fecha local pendiente. |
| Fecha-hora | Instante de creación, modificación, captura, recepción o sincronización | Usar formato interoperable y preservar el instante; estándar técnico y política de zona horaria pendientes. |
| Booleano | Indicadores binarios explícitos | No usar para reemplazar estados con más de dos condiciones. |
| Referencia/ID foráneo | Relación entre entidades | Validar pertenencia al usuario/perfil y preservar enlaces aunque un registro se archive; archivar no elimina datos ni relaciones. |
| Snapshot | Valores de Cliente, Perfil, líneas y cálculos de un documento emitido | Estructura y serialización pendientes; contenido debe bastar para reproducir el documento histórico. |
| Archivo/URI | Foto, logo o documento | La referencia debe sobrevivir al modo offline; permisos, ruta lógica, checksum, tamaño y retención están pendientes. |
| Moneda | Código de moneda elegida por el usuario/documento | Una moneda principal global en V1; país solo sugiere. Usar código ISO 4217, dos decimales y mostrar símbolo. |
| Método de Cobro | Forma registrada por el usuario para identificar cómo recibió dinero | Configuración global del Usuario/Titular, editable por este; no hay registros predeterminados. Puede utilizarse para pagos recibidos y reembolsos. |
| Categoría de inventario | Organización de Materiales o Herramientas | Categorías creadas por el usuario, separadas por tipo y globales al Usuario/Titular. Sin categorías iniciales; categoría organiza, tipo define comportamiento. |
| JSON/payload de sincronización | Operación pendiente entre dispositivo y API | Versionado, esquema, tamaño y reglas de evolución pendientes. No reemplaza campos consultables del dominio sin decisión. |

## 3. Convenciones de dominio

### 3.1 Identidad y propiedad

- Cada entidad sincronizable tiene ID técnico global, `user_id` o vínculo equivalente de propiedad y marcas de tiempo necesarias para sincronización.
- Clientes, Inventario, categorías de inventario y Métodos de Cobro pertenecen globalmente al Usuario/Titular. Las operaciones/documentos identifican el Perfil Profesional que les corresponde; el Cliente puede compartirse entre perfiles del mismo titular.
- `device_id` puede identificar el origen técnico de una operación, pero nunca reemplaza al propietario `user_id`.
- El número visible `COT-...`, `JOB-...`, `INF-...` o `CC-...` es distinto del ID técnico. Los documentos usan un número provisional `TIPO-PEND-XXXX` desde su creación hasta que el backend asigna el número oficial `TIPO-AAAAMM-NNN` sobre el mismo documento; la secuencia oficial es global por Usuario/Titular + tipo + período, independiente del Perfil Profesional.

### 3.2 Importes y cálculo

- Cada importe pertenece a una moneda explícita.
- En V1 se usa código ISO 4217, dos decimales y símbolo visible; los pagos usan la moneda principal del usuario.
- Subtotales y totales se derivan con el mismo motor de cálculo para Cotización y Cuenta de Cobro.
- En V1 las retenciones se calculan sobre el total de conceptos clasificados como `SERVICIO`; los `MATERIAL` quedan fuera de la base. La base de cada retención se determina por su configuración `applies_to`; `OTRO` no está incluido ni excluido universalmente y se comporta según esa configuración.
- No se aplican descuentos en V1. Se aplican las reglas aprobadas de retenciones. El tratamiento de impuestos no está aprobado para V1 y no debe incorporarse como requisito o cálculo. Los importes se redondean por línea y el total suma las líneas ya redondeadas.
- El documento conserva componentes del cálculo y el resultado usados al emitir; no se recalcula un documento histórico usando configuración actual.
- El profesional ingresa precios manualmente; el precio es bruto y el cálculo inverso devuelve un bruto único conforme a las reglas de retenciones aprobadas.
- Pendiente: si se incluirán impuestos en otra etapa y, en tal caso, reglas fiscales. No hay administrador de impuestos ni porcentajes predeterminados aprobados para V1.

### 3.3 Cantidades y unidades

- La cantidad cotizada, ejecutada y consumida son medidas distintas y no deben sobrescribirse entre sí.
- Material usado en un Trabajo puede proceder del Cliente o del profesional.
- El inventario es control interno: admite ajustes para reflejar existencias previas del profesional y entradas por cada línea de una Compra confirmada.
- Solo el consumo de material propio registrado explícitamente dentro de un Trabajo genera una salida. Cotizaciones e Informes no cambian existencias.
- El consumo registrado no se limita por el stock disponible; el saldo puede ser negativo y no bloquea el Trabajo ni su documentación.
- Materiales y Herramientas son los dos tipos de inventario; el usuario administra categorías separadas para ambos tipos, sin registros predeterminados.
- Las cantidades conservan la unidad seleccionada explícitamente; CotixGo no realiza conversiones automáticas entre unidades.
- Un Informe es opcional, puede ser interno o entregarse al Cliente y no es requisito para entregar un Trabajo o generar una Cuenta de Cobro. Una Cuenta de Cobro puede existir sin Cotización, Trabajo o Informe.
- El cierre histórico de la operación ocurre cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente está completamente pagada. No se infiere congelamiento automático de cada documento al cambiar de estado; una garantía posterior no reabre documentos automáticamente.
- La unidad se registra explícitamente y no se convierte automáticamente a otra unidad. Esta regla está cerrada.

### 3.4 Estados

Los siguientes conjuntos están aprobados:

```text
Trabajo: BORRADOR | PROGRAMADO | EN PROCESO | ENTREGADO | CANCELADO
Cuenta de Cobro: PENDIENTE | PARCIAL | PAGADA | CANCELADA
Cotización: BORRADOR | EMITIDA | APROBADA | RECHAZADA | VENCIDA | CANCELADA
Informe: BORRADOR | EMITIDO
Foto de Trabajo: ANTES | DURANTE | DESPUÉS | OTRA
Condición de material: NUEVO | USADO
Herramienta: DISPONIBLE | EN USO | MANTENIMIENTO | DAÑADA | FUERA DE SERVICIO
Pago (asignación): UNASSIGNED | ASSIGNED
Número documental: PROVISIONAL | OFFICIAL
Concepto de línea: SERVICIO | MATERIAL | OTRO
```

Estados de Compra y sincronización requieren especificación antes de cerrar modelos/API. El comportamiento funcional del archivo está aprobado: archivar oculta, desarchivar vuelve a mostrar y ninguna acción elimina datos o revierte efectos; su representación técnica y la matriz completa de transiciones siguen pendientes.

## 4. Entidades conceptuales

El modelo conceptual incluye `User`, `ProfessionalProfile`, `Client`, `Service`, `Material`, `Tool`, categorías de inventario separadas por tipo, Métodos de Cobro, `Quote`, `QuoteItem`, `Job`, `JobQuote`, `JobActivity`, `JobMaterial`, `Attachment`, `Purchase`, `PurchaseItem`, `InventoryMovement`, `Report`, `ReportJob`, `CollectionAccount`, `CollectionAccountJob`, `Payment`, `ConsolidatedCollection`, `PaymentAdjustment`, `SyncOperation` y `AuditEvent`. Esto no fija tablas físicas.

Relaciones aprobadas o necesarias:

- Usuario/Titular 1:N Clientes; el Cliente puede utilizarse en operaciones de varios Perfiles Profesionales del mismo usuario y no se duplica por Perfil.
- Cliente 1:N Cotizaciones, Trabajos, Informes y Cuentas de Cobro.
- Cotización 1:N líneas.
- Cotización N:M Trabajo, mediante asociación; la Cotización es opcional para crear un Trabajo.
- Trabajo 1:N actividades, materiales utilizados y evidencias.
- Trabajo N:M Informe.
- Trabajo N:M Cuenta de Cobro.
- Cuenta de Cobro puede relacionarse con cero o varias Cotizaciones y cero o varios Trabajos; si combinar ambos tipos en el mismo documento implica una regla adicional de prevención de doble cobro, permanece pendiente.
- Cuenta de Cobro puede no tener asociaciones ni a Cotización ni a Trabajo; en ese caso conserva su relación directa con Cliente y Perfil Profesional.
- Un Pago se asocia a una Cuenta individual o a un Consolidado, o permanece sin asignación cuando se recibe antes de existir el documento (vinculado al Cliente). La asignación posterior es una operación auditada; si el pago supera el saldo del destino se aplica solo lo necesario y el excedente permanece sin asignar dentro del mismo Pago. Para agrupar Cuentas del mismo Cliente se utiliza el Consolidado, que mantiene intactas las Cuentas originales, conserva sus valores históricos y administra el saldo conjunto. No se registra un Pago directamente sobre varias Cuentas. Un Pago anulado permanece en el historial y deja de contar en el saldo del documento asociado.
- `PaymentAdjustment` registra correcciones y anulaciones de un Pago (funcionalidad de V1): conserva valor anterior, valor nuevo, motivo, usuario, dispositivo, operación y fecha; el registro original no se modifica silenciosamente ni se elimina.
- Reembolso como nuevo movimiento de dinero; no modifica ni elimina el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro y observación. No hay estado `REEMBOLSADA` en V1.
- Compra 1:N líneas; Compra confirmada genera movimientos de entrada. Editarla ajusta el inventario por la diferencia entre cantidades anteriores y nuevas, con movimientos trazables. Archivar/desarchivar solo cambia visibilidad y no cambia inventario.
- Inventario, categorías de Materiales/Herramientas y Métodos de Cobro pertenecen al Usuario/Titular, no a Perfiles Profesionales. No hay categorías ni Métodos de Cobro predeterminados.
- Material 1:N movimientos; distinguir condición/origen de stock cuando corresponda.

No fijar todavía tablas, nombres físicos, índices ni claves compuestas a partir de este documento.

## 5. Integridad de relaciones y eventos

- Una operación repetida por sincronización debe ser idempotente.
- Un Pago contra una Cuenta o Consolidado puede ser parcial, pero no superar el saldo pendiente de ese documento.
- Confirmar Compra, registrar consumo y registrar Pago son eventos con efectos distintos; no inferirlos de la creación de sus formularios/documentos.
- Documentos emitidos conservan snapshots y moneda utilizados.
- El archivado solo cambia visibilidad, no elimina ni altera relaciones o efectos; la representación técnica del archivo sigue pendiente.
- Los ajustes de Pago (`payment_adjustments`) son eventos append-only: un Pago corregido o anulado conserva su registro original y el documento asociado recalcula su saldo con los valores efectivos.

## 6. Historial de cambios

| Versión | Fecha | Cambio | Motivo | Estado |
|---|---|---|---|---|
| 1.0 | 30/09/2026 | Convenciones de tipos y contratos de datos. | Alinear el modelo conceptual. | Aprobado. |
| 1.1 | 08/10/2026 | Base de retención solo `SERVICIO` (`OTRO` pendiente); estados `PROVISIONAL`/`OFFICIAL` y `UNASSIGNED`/`ASSIGNED`; numeración provisional y oficial global; `Payment` sin asignación (anticipo); nueva entidad `PaymentAdjustment`. | Incorporación de decisiones aprobadas por el titular. | Aprobado. |
| 1.2 | 08/10/2026 | D13-B: asignación de pagos con aplicación parcial (excedente `UNASSIGNED`); D14-C: enum de concepto `SERVICIO`/`MATERIAL`/`OTRO` y base de retención por `applies_to` configurable. | Aprobación de las decisiones pendientes de la etapa V1.1. | Aprobado. |

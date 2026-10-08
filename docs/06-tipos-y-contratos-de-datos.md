# CotixGo — Tipos y contratos de datos

**Versión:** 1.0  
**Estado:** Convenciones conceptuales para alinear el modelo; no constituye DDL ni esquema de API final.

## 1. Propósito

Dar a Devin un vocabulario común para campos, tipos y relaciones sin fingir que ya se aprobaron todas las columnas físicas. Las entidades y relaciones base están en `02-modelo-de-datos.md`; las reglas prevalentes están en `01-reglas-de-negocio-v0.2.md` y `03-anexo-decisiones-aprobadas-y-brechas.md`.

## 2. Tipos lógicos

| Tipo lógico | Uso | Regla de representación |
|---|---|---|
| ID global | Identidad técnica de entidad u operación | Único globalmente y generable offline. No usar número consecutivo de pantalla como PK. Algoritmo exacto pendiente. |
| Texto | Nombres, descripciones, notas, direcciones, referencias | No asumir límites de longitud sin definirlos en el contrato de campo. Preservar contenido histórico de documentos. |
| Código/enum | Estados, tipo de concepto, origen, método o clasificación | Valores controlados y documentados; no depender de etiquetas traducidas de UI como valor persistido. Conjuntos aún no aprobados se marcan pendientes. |
| Decimal monetario | Precio, subtotal, impuesto, retención, total, pago, saldo | No usar punto flotante binario. Precisión, escala y redondeo por moneda están pendientes; moneda debe acompañar cada importe documental. |
| Decimal de cantidad | Cantidad de material, mano de obra o unidad fraccionaria | No usar entero por defecto. En V1 las cantidades se redondean a dos decimales. |
| Fecha | Día comercial de emisión, vencimiento, compra o ejecución | Mantener separada de una marca de tiempo. Zona horaria/regla de fecha local pendiente. |
| Fecha-hora | Instante de creación, modificación, captura, recepción o sincronización | Usar formato interoperable y preservar el instante; estándar técnico y política de zona horaria pendientes. |
| Booleano | Indicadores binarios explícitos | No usar para reemplazar estados con más de dos condiciones. |
| Referencia/ID foráneo | Relación entre entidades | Validar pertenencia al usuario/perfil y preservar enlaces aunque un registro se archive; archivar no elimina datos ni relaciones. |
| Snapshot | Valores de Cliente, Perfil, líneas y cálculos de un documento emitido | Estructura y serialización pendientes; contenido debe bastar para reproducir el documento histórico. |
| Archivo/URI | Foto, logo o documento | La referencia debe sobrevivir al modo offline; permisos, ruta lógica, checksum, tamaño y retención están pendientes. |
| Moneda | Código de moneda elegida por el usuario/documento | Una moneda principal global en V1; país solo sugiere. Usar código ISO 4217, dos decimales y mostrar símbolo. |
| JSON/payload de sincronización | Operación pendiente entre dispositivo y API | Versionado, esquema, tamaño y reglas de evolución pendientes. No reemplaza campos consultables del dominio sin decisión. |

## 3. Convenciones de dominio

### 3.1 Identidad y propiedad

- Cada entidad sincronizable tiene ID técnico global, `user_id` o vínculo equivalente de propiedad y marcas de tiempo necesarias para sincronización.
- Los Clientes y datos comerciales se segmentan por Perfil Profesional conforme a las reglas aprobadas.
- `device_id` puede identificar el origen técnico de una operación, pero nunca reemplaza al propietario `user_id`.
- El número visible `COT-...`, `JOB-...`, `INF-...` o `CC-...` es distinto del ID técnico; asignación concurrente/offline está pendiente.

### 3.2 Importes y cálculo

- Cada importe pertenece a una moneda explícita.
- En V1 se usa código ISO 4217, dos decimales y símbolo visible; los pagos usan la moneda principal del usuario.
- Subtotales y totales se derivan con el mismo motor de cálculo para Cotización y Cuenta de Cobro.
- Retenciones se asocian a conceptos clasificados como Servicio en V1.
- No se aplican descuentos en V1. Impuestos se calculan antes que retenciones; estas se calculan sobre el bruto original. Los importes se redondean por línea y el total suma las líneas ya redondeadas.
- El documento conserva componentes del cálculo y el resultado usados al emitir; no se recalcula un documento histórico usando configuración actual.
- El profesional ingresa precios manualmente y el cálculo inverso devuelve un bruto único con configuración vigente.
- El precio ingresado manualmente es bruto. El administrador define valores predeterminados para impuestos; cada documento puede sobrescribirlos. Se calcula el impuesto sobre el subtotal agregado de conceptos `SERVICIO` y se muestra en el documento.
- Pendiente: alcance/operación del rol administrador.

### 3.3 Cantidades y unidades

- La cantidad cotizada, ejecutada y consumida son medidas distintas y no deben sobrescribirse entre sí.
- Material usado en un Trabajo puede proceder del Cliente o del profesional.
- El inventario es control interno: admite ajustes para reflejar existencias previas del profesional y entradas por cada línea de una Compra confirmada.
- Solo el consumo de material propio registrado explícitamente dentro de un Trabajo genera una salida. Cotizaciones e Informes no cambian existencias.
- El consumo registrado no se limita por el stock disponible; el saldo puede ser negativo y no bloquea el Trabajo ni su documentación.
- Catálogo de unidades y conversiones están **PENDIENTES DE DECISIÓN**.

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
```

Estados de Compra y sincronización requieren especificación antes de cerrar modelos/API. El comportamiento funcional del archivo está aprobado: archivar oculta, desarchivar vuelve a mostrar y ninguna acción elimina datos o revierte efectos; su representación técnica y la matriz completa de transiciones siguen pendientes.

## 4. Entidades conceptuales

El modelo v0.1 enumera estas entidades: `User`, `ProfessionalProfile`, `Client`, `Service`, `Material`, `Tool`, `Quote`, `QuoteItem`, `Job`, `JobQuote`, `JobActivity`, `JobMaterial`, `Attachment`, `Purchase`, `PurchaseItem`, `InventoryMovement`, `Report`, `ReportJob`, `CollectionAccount`, `CollectionAccountJob`, `Payment`, `PaymentAllocation`, `SyncOperation` y `AuditEvent`.

Relaciones aprobadas o necesarias:

- Perfil Profesional 1:N Clientes.
- Cliente 1:N Cotizaciones, Trabajos, Informes y Cuentas de Cobro.
- Cotización 1:N líneas.
- Cotización N:M Trabajo, mediante asociación; la Cotización es opcional para crear un Trabajo.
- Trabajo 1:N actividades, materiales utilizados y evidencias.
- Trabajo N:M Informe.
- Trabajo N:M Cuenta de Cobro.
- Cuenta de Cobro N:M Cotización, requerido por decisión aprobada posterior y aún ausente del borrador de datos.
- Cuenta de Cobro puede no tener asociaciones ni a Cotización ni a Trabajo; en ese caso conserva su relación directa con Cliente y Perfil Profesional.
- Pago asociado normalmente a una Cuenta de Cobro. Para varias Cuentas del mismo Cliente, se genera un documento global que consolida sus saldos; los pagos, incluso parciales, reducen un saldo residual global único y no se desglosan por Cuenta. Un Pago anulado permanece en el historial y deja de contar en saldos. Corregir su importe aplica la diferencia al mismo Pago y documento asociado.
- Compra 1:N líneas; Compra confirmada genera movimientos de entrada. Editarla ajusta el inventario por la diferencia entre cantidades anteriores y nuevas, con movimientos trazables. Archivar/desarchivar solo cambia visibilidad y no cambia inventario.
- Material 1:N movimientos; distinguir condición/origen de stock cuando corresponda.

No fijar todavía tablas, nombres físicos, índices ni claves compuestas a partir de este documento.

## 5. Integridad de relaciones y eventos

- Una operación repetida por sincronización debe ser idempotente.
- La suma asignada a un Pago no debe superar el monto recibido; no se permite dejar una parte del Pago sin asignar.
- No se puede asignar más de lo que permite el saldo de una Cuenta salvo que se apruebe manejo de anticipos/excedentes.
- Confirmar Compra, registrar consumo y registrar Pago son eventos con efectos distintos; no inferirlos de la creación de sus formularios/documentos.
- Documentos emitidos conservan snapshots y moneda utilizados.
- Archivado/eliminación y su efecto sobre relaciones aún están pendientes.

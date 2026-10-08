# CotixGo — Anexo de decisiones aprobadas y revisión de brechas

**Versión:** 1.0  
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

- Estados de Trabajo aprobados: `BORRADOR`, `PROGRAMADO`, `EN PROCESO`, `ENTREGADO` y `CANCELADO`.
- Un Trabajo puede crearse con una Cotización o directamente para un Cliente sin Cotización.
- Un Trabajo puede relacionarse con varias Cotizaciones. Son válidos tanto `Cliente → Cotización → Trabajo` como `Cliente → Trabajo`.
- El Trabajo puede registrar información general, actividades, materiales utilizados, fotografías, observaciones y herramientas.
- Las fotografías pueden clasificarse como `ANTES`, `DURANTE`, `DESPUÉS` u `OTRA`.
- Al entregar el Trabajo, la ejecución queda cerrada históricamente. El Informe y la Cuenta de Cobro pueden generarse después.

### 2.3 Informes

- El Informe describe lo realmente ejecutado; la Cotización describe lo previsto.
- Un Informe puede agrupar varios Trabajos del mismo Cliente. Cada Trabajo conserva su propio registro y trazabilidad.

### 2.4 Cuenta de Cobro y pagos

- El nombre funcional oficial es **Cuenta de Cobro**.
- Puede originarse desde una Cotización, desde un Trabajo o sin Cotización. Puede agrupar varias Cotizaciones y/o varios Trabajos.
- Puede emitirse directamente para un Cliente sin Cotización ni Trabajo relacionado. Caso aprobado: atención de emergencia que el Cliente paga de inmediato; basta con registrar la Cuenta de Cobro y el Pago. No se exige crear Cotización, Trabajo, Informe ni fotografías para este flujo.
- Estados aprobados: `PENDIENTE`, `PARCIAL`, `PAGADA` y `CANCELADA`.
- Crear una Cuenta de Cobro no registra un pago. Los pagos son eventos independientes.
- Normalmente se registra un Pago por Cuenta de Cobro. Como excepción, se pueden seleccionar dos o más Cuentas del mismo Cliente y registrar un solo Pago global para el saldo conjunto. Ese Pago genera un solo comprobante para el conjunto y no se desglosa por Cuenta; no se crean pagos ni importes individuales por cada Cuenta.
- Todo Pago debe estar asignado a una o varias Cuentas de Cobro; no existen pagos sueltos ni pagos para otros tipos de documentos.
- Al cancelar/anular un Pago, el sistema revierte su efecto en las Cuentas asignadas y recalcula sus saldos y estados. Si era el primer Pago, la Cuenta vuelve a `PENDIENTE` con el saldo total pendiente. El Pago cancelado se conserva en el historial y deja de contar para los saldos; cancelar no elimina el registro.
- Corregir el importe de un Pago aplica la diferencia al mismo Pago y a la misma Cuenta o conjunto de Cuentas seleccionado originalmente. Si aumenta, se suma la diferencia; si disminuye, se resta. El Pago y su historial se conservan; no se crea un Pago independiente ni se elimina el registro original. Un Pago global para varias Cuentas del mismo Cliente conserva un único importe y comprobante, sin desglose ni prorrateo por Cuenta; la diferencia se aplica al saldo conjunto.

### 2.5 Retenciones y cálculo

- Las retenciones son configurables. No se asume una tasa fija por país.
- En V1, las retenciones configuradas se aplican a conceptos clasificados como `SERVICIO`.
- El cálculo debe permitir hallar el valor bruto necesario para obtener un valor neto objetivo.
- Cotizaciones y Cuentas de Cobro deben compartir un motor de cálculo.
- Cuando corresponda, los documentos deben poder desglosar bruto, cada retención, total de retenciones y neto.

### 2.6 Clientes y perfiles profesionales

- Cada Cliente pertenece a un Perfil Profesional. El mismo Cliente real puede existir como registro independiente en perfiles distintos.
- Los datos comerciales del Cliente son valores predeterminados; cada operación conserva los valores que usó.
- El lugar de ejecución pertenece al Trabajo y puede diferir de la dirección fiscal o principal del Cliente.
- Un usuario puede administrar varios Perfiles Profesionales. Estos no representan automáticamente entidades jurídicas separadas; la identidad fiscal pertenece al titular/usuario.
- Los documentos históricos conservan la información del Perfil Profesional usada al emitirlos, incluido el logo.

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

## 3. Brechas y alineación requerida en el modelo de datos

| Tema | Estado del borrador actual | Alineación requerida |
|---|---|---|
| Trabajo sin Cotización | `Job` enumera cotización de origen; la sección de relaciones explica varias cotizaciones, pero no declara claramente que la relación es opcional. | Expresar que el Cliente basta para crear un Trabajo y que la asociación a Cotizaciones es opcional y muchos a muchos. |
| Estados de Trabajo | El borrador enumera estados conceptuales distintos y deja nombres/transiciones para después. | Reemplazar el conjunto conceptual por los cinco estados aprobados. Las transiciones siguen pendientes si no están definidas en reglas aprobadas. |
| Cuenta de Cobro ↔ Cotización | El borrador modela asociación con Trabajos, pero no una relación entre Cuenta de Cobro y Cotizaciones. | Añadir relación asociativa que permita cero o varias Cotizaciones, además de cero o varios Trabajos, conforme a las decisiones aprobadas. |
| Cuenta de Cobro independiente | El borrador enumera relaciones con Trabajos y no deja explícito el caso de cobro directo sin Cotización ni Trabajo. | Admitir una Cuenta de Cobro vinculada al Cliente y al Perfil Profesional sin Cotización ni Trabajo. El flujo de emergencia puede registrar el pago sin crear Informe o fotografías. |
| Retenciones e impuestos | El borrador inicial no reflejaba el alcance de impuestos/retenciones acordado. | La sección 6 fija motor compartido, impuesto sobre subtotal agregado de conceptos `SERVICIO`, valores predeterminados sobrescribibles por documento, retención sobre bruto original, orden, moneda, redondeo por línea y total de líneas redondeadas, y ausencia de descuentos en V1. |
| Perfil y Cliente | Las entidades tienen `user_id`/perfil en varios puntos, pero no queda formalizada la pertenencia obligatoria de cada Cliente a un Perfil Profesional. | Hacer explícita la relación Perfil 1:N Cliente y el alcance por perfil de sus datos y documentos. |
| Moneda | No se identifica claramente moneda global de usuario ni moneda capturada por documento. | Añadir moneda a configuración de usuario y snapshot del documento, sin conversión implícita. |
| Versiones de Cotización | El modelo tiene contenido y versiones potenciales, pero no especifica entidad o mecanismo para historial de modificaciones de cotizaciones abiertas. | Definir cómo se conserva cada versión antes de cerrar el modelo; cada versión debe preservar valores propios. |
| Compra en borrador | El modelo dice que una compra confirmada genera entradas, pero no enumera estados ni prohibición explícita para borrador. | Reflejar que el borrador no afecta inventario y que confirmar genera movimientos. |
| Condición de stock | El borrador menciona `Material` y movimientos, pero no representa claramente stock nuevo/usado coexistente y origen. | Modelar balances/movimientos por condición y origen sin convertir esa decisión en bloqueo de operación. |
| Adjuntos | El modelo registra adjuntos genéricos, pero no formaliza categorías fotográficas aprobadas. | Incluir `ANTES`, `DURANTE`, `DESPUÉS`, `OTRA` como clasificación de evidencia. |
| Cuenta de Cobro y snapshot | El modelo propone congelación al pago total, alineada con v0.2, pero debe convivir con estados aprobados y cambios permitidos con saldo pendiente. | Mantener separado estado comercial y condición de edición; no inferir reglas de edición adicionales. |

La tabla describe cambios de documentación requeridos, no decisiones de implementación de tablas ni esquema SQL.

## 4. PENDIENTE DE DECISIÓN

Estos asuntos no quedan resueltos por las reglas aprobadas disponibles. Las respuestas posteriores de la sección 6 cierran varias decisiones que antes aparecían como pendientes.

1. Condiciones y validaciones residuales de las transiciones gestionadas por el sistema.
2. Forma de registrar reembolsos y especificación visual/técnica del documento global de cobro. El documento global y su saldo residual conjunto están definidos funcionalmente en la sección 6. La corrección de un Pago aplicado a una Cuenta y la anulación de un Pago también están definidas en la sección 6.
3. Regla de emisión/numeración definitiva de documentos cuando la creación ocurre offline; reserva de rangos y colisiones.
4. Política de resolución de conflictos de sincronización por entidad y por campo, incluyendo cambios simultáneos.
5. Si una Cuenta de Cobro puede combinar Cotizaciones y Trabajos en el mismo documento y cómo evitar doble cobro de un concepto.
6. Catálogo y conversiones de unidades.
7. Retención y almacenamiento de fotografías/archivos, límites de tamaño, compresión, permisos y sincronización fallida.
8. Alcance de perfiles en catálogo de servicios, materiales, herramientas y numeración documental cuando un usuario tiene varios perfiles.
9. Campos definitivos y valores obligatorios de Cliente, Trabajo, Cotización y Cuenta de Cobro, aparte de los enumerados en reglas.

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
- Un Pago compartido puede asignarse a varias Cuentas del mismo Cliente y genera un solo comprobante; los anticipos se consideran pagos adelantados.
- Para registrar un anticipo, primero se crea la Cuenta de Cobro y el anticipo se asigna a esa Cuenta; no se permiten anticipos sin Cuenta o sin asignación.
- No se permite aplicar más que el saldo pendiente ni dejar parte de un Pago sin asignar.
- Un Pago se puede corregir o anular.
- Todo Pago está asignado a una o varias Cuentas de Cobro. Al anularlo, el sistema revierte su efecto en esas Cuentas y recalcula automáticamente saldos y estados; si era el primer Pago, la Cuenta vuelve a `PENDIENTE` con saldo total pendiente. El Pago anulado y sus asignaciones se conservan en el historial, pero ya no cuentan para el cálculo.
- Normalmente se registra un Pago por Cuenta de Cobro. Para consolidar varias Cuentas del mismo Cliente, CotixGo genera un documento global de cobro con el total conjunto y enlaces a las Cuentas seleccionadas. Los pagos pueden ser parciales y reducen un único saldo residual global de ese documento. No se distribuye el pago entre las Cuentas originales ni se calculan saldos residuales individuales para el cobro consolidado.
- Los reembolsos se tratan como pagos adicionales conforme al comportamiento definido por el sistema.

### Cálculos y moneda

- En V1 no se ofrecen descuentos.
- El profesional ingresa el precio manualmente como bruto; el motor compartido de Cotizaciones y Cuentas de Cobro aplica impuestos y retenciones configurados. El cálculo inverso de neto devuelve un valor bruto único con la configuración vigente.
- El administrador del sistema configura valores predeterminados para tipo y porcentaje de impuesto. Cada documento puede sobrescribir esa configuración; los impuestos se aplican al subtotal agregado de conceptos `SERVICIO` y se presentan en el documento.
- Se calculan impuestos antes que retenciones. Las retenciones se calculan sobre el bruto original y solo sobre conceptos `SERVICIO`.
- Las cantidades se redondean a dos decimales. Los importes se redondean por línea; el total es la suma de las líneas ya redondeadas.
- Los documentos históricos conservan tasas y resultados de emisión; nuevas Cotizaciones usan la configuración vigente.
- Moneda V1: código ISO 4217, dos decimales, símbolo visible y pagos en la moneda principal del usuario. Si cambia la moneda principal, los documentos existentes conservan su moneda.
- El precio ingresado manualmente es bruto; el cálculo inverso permite determinar el bruto requerido para un neto objetivo.

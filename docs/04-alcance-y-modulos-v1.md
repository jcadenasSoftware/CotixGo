# CotixGo — Alcance y módulos de V1

**Versión:** 1.0  
**Estado:** Especificación de alcance basada en reglas aprobadas  
**Producto:** Herramienta profesional de campo para trabajadores independientes y pequeños proveedores de servicios.

## 1. Objetivo de V1

Permitir que el profesional prepare cotizaciones, gestione clientes y trabajos, registre la ejecución real, documente el servicio, emita Cuentas de Cobro y registre pagos. La experiencia debe apoyar el trabajo en campo, incluso sin conexión en Android.

CotixGo debe sentirse como una herramienta que ayuda al profesional a estar preparado; no como una aplicación contable ni como un mini-ERP.

Flujo principal:

```text
Cliente → Cotización (opcional) → Trabajo → Actividades / Materiales / Fotos
        → Informe → Cuenta de Cobro → Pagos
```

Flujo de control interno:

```text
Compra → Inventario → Consumo propio registrado explícitamente en el Trabajo
```

El Informe y la Cuenta de Cobro pueden crearse después de entregar el Trabajo. Un Trabajo no requiere Cotización.

## 2. Módulos incluidos

Los módulos siguientes forman parte del producto definido. Las funciones enumeradas reflejan decisiones aprobadas; los detalles no especificados aparecen como pendientes en `03-anexo-decisiones-aprobadas-y-brechas.md`.

### Regla transversal de archivo e historial

- Archivar cualquier registro o documento solo lo oculta de las vistas operativas habituales; cualquier documento archivado se puede desarchivar para volver a mostrarlo.
- Archivar o desarchivar no elimina datos, no anula operaciones ni revierte o modifica movimientos de inventario, pagos, transacciones, asignaciones, relaciones o historial.
- Ningún tipo de dato generado por el usuario se elimina físicamente. La anulación, cuando aplique, es una operación distinta y trazable.

### 2.1 Dashboard

- Vista operacional orientada a acciones pendientes, no a analítica financiera.
- Acciones primarias: `+ Cotizar`, `+ Cuenta de Cobro`, `+ Trabajo`.
- Resumen accionable de cotizaciones pendientes/próximas a vencer, trabajos programados/en proceso, informes pendientes y Cuentas de Cobro pendientes/parciales.
- No saturar V1 con gráficos.

### 2.2 Clientes

- Crear y mantener registros de clientes por Perfil Profesional.
- Datos contemplados: identificación, nombre, nombre comercial, teléfonos, correos, dirección principal/fiscal, ciudad, país, notas y estado activo/archivado.
- Permitir que un mismo cliente real exista como registros separados en perfiles diferentes.
- Usar configuraciones comerciales del Cliente como valores predeterminados; cada documento conserva los valores efectivamente usados.
- La dirección de ejecución se registra en el Trabajo y no se deduce necesariamente del domicilio del Cliente.

### 2.3 Cotizaciones

- Crear cotizaciones para un Cliente y un Perfil Profesional.
- Incluir servicios, mano de obra y materiales, o únicamente servicio/mano de obra cuando el Cliente suministra materiales.
- Usar opcionalmente el Catálogo de Servicios o escribir conceptos manualmente.
- Tratar precios de catálogo como sugerencias; guardar en cada documento la descripción, cantidades y valores utilizados.
- El profesional define la vigencia al crear cada Cotización; el sistema calcula su fecha de vencimiento.
- Modificar cotizaciones abiertas manteniendo versiones internas.
- Estados: `BORRADOR`, `EMITIDA`, `APROBADA`, `RECHAZADA`, `VENCIDA`, `CANCELADA`. El profesional registra manualmente la aprobación; puede editar `BORRADOR` y `EMITIDA` antes del vencimiento y cancelar cualquiera de esos dos estados. El vencimiento se determina automáticamente por fecha.
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
- Registrar información general, lugar de ejecución, actividades, materiales utilizados, fotografías, observaciones y herramientas.
- Clasificar fotografías como `ANTES`, `DURANTE`, `DESPUÉS` u `OTRA`.
- Distinguir lo cotizado, lo ejecutado y lo consumido del inventario.
- Registrar explícitamente consumo propio; materiales suministrados por el Cliente no generan movimiento de inventario propio.
- Al entregar el Trabajo, cerrar históricamente la ejecución. El Informe y la Cuenta de Cobro pueden generarse luego.

### 2.5 Inventario

- Control interno no restrictivo.
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
- Permitir asociar varias Cotizaciones y/o Trabajos a una misma Cuenta de Cobro, sujeto a la pendiente sobre combinación y prevención de doble cobro.
- Estados aprobados: `PENDIENTE`, `PARCIAL`, `PAGADA`, `CANCELADA`.
- Separar la creación de la Cuenta del evento de pago, incluso cuando el Cliente paga inmediatamente.
- Aplicar las reglas compartidas de retenciones, conceptos de servicio, bruto/neto y moneda.
- En V1 no hay descuentos. El profesional ingresa el precio manualmente; impuestos se calculan antes que retenciones, y las retenciones se calculan sobre el bruto original de conceptos `SERVICIO`.
- El administrador del sistema configura valores predeterminados de tipo y porcentaje de impuesto, sobrescribibles por documento. Los impuestos se aplican al subtotal agregado de conceptos `SERVICIO` y se muestran en el documento.
- Los importes se redondean por línea y el total es la suma de las líneas redondeadas. Las cantidades se redondean a dos decimales. El precio ingresado manualmente es bruto.
- Conservar snapshot histórico al emitir; congelar según reglas aprobadas.

### 2.8 Perfiles Profesionales

- Permitir varios perfiles comerciales por usuario.
- Cada perfil aporta identidad comercial, descripción/actividad, logo, información comercial, clientes, servicios, documentos y configuración comercial.
- No asumir que un perfil equivale a una persona jurídica independiente.
- Conservar en cada documento histórico los datos del perfil usados al emitir, incluido logo.

### 2.9 Informes

- Crear a partir de datos registrados durante la ejecución; no volver a introducir desde cero toda la información.
- Estados: `BORRADOR` y `EMITIDO`. Un Informe emitido no se edita; una corrección crea una versión nueva.
- La nueva versión del Informe se enlaza a la anterior mediante un campo de referencia; la versión anterior se conserva como referencia.
- Describir lo realmente ejecutado, no lo originalmente cotizado.
- Revisar/completar el contenido y seleccionar evidencias antes de generar el documento.
- Permitir agrupar varios Trabajos del mismo Cliente en un Informe, manteniendo identidad y trazabilidad individual por Trabajo.

### 2.10 Pagos

El registro de pagos es una capacidad del flujo de Cuenta de Cobro aunque no figure como módulo independiente en la lista de nueve módulos.

- Registrar el evento real de recepción de dinero por separado de la Cuenta de Cobro.
- Normalmente se registra un Pago por Cuenta de Cobro. Como excepción, se puede registrar un Pago global para dos o más Cuentas del mismo Cliente; es un solo importe, genera un solo comprobante y no se desglosa por Cuenta.
- Actualizar automáticamente el estado a `PARCIAL` o `PAGADA` según pagos asignados. No cancelar una Cuenta con pagos aplicados.
- No permitir pagos mayores al saldo ni dejar una parte del Pago sin asignar.
- Al cancelar/anular un Pago, conservarlo junto con sus asignaciones en el historial, excluirlo de los cálculos, revertir su efecto y recalcular automáticamente saldos y estados. Si era el primer Pago, la Cuenta vuelve a `PENDIENTE` con el saldo total pendiente.
- Al corregir el importe de un Pago, aplicar la diferencia al mismo Pago y a la Cuenta de Cobro o documento global al que se registró. Un Pago global puede ser parcial y reduce el saldo residual global del documento consolidado; no se desglosa por Cuenta.
- Los pagos adelantados se tratan como anticipos y un Pago distribuido entre varias Cuentas se considera compartido. El sistema gestiona correcciones/anulaciones de pagos y asignaciones con trazabilidad; los reembolsos se tratan como pagos adicionales.
- Para registrar un anticipo, se crea primero la Cuenta de Cobro y luego se asigna el Pago a esa Cuenta; no se admiten pagos sin Cuenta o asignación.

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

- Los nueve módulos enumerados son módulos funcionales aprobados, pero las reglas detalladas de este documento solo comprometen los comportamientos descritos.
- Si una pantalla o comportamiento del diseño visual existente parece contradecir una regla de negocio, prevalece la regla aprobada y se registra la diferencia para revisión.
- Las reglas de estados, cálculo, permisos, sincronización y documentos históricos no pueden inferirse solamente del diseño visual.
- La imagen de referencia existente [`CotixGo_ Gestión Inteligente de Servicios.png`](CotixGo_%20Gesti%C3%B3n%20Inteligente%20de%20Servicios.png) sirve como referencia visual; no sustituye este contrato funcional.

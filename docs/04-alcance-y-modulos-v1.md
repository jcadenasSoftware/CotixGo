# CotixGo — Alcance y módulos de V1

**Versión:** 1.2  
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
- Usar configuraciones comerciales del Cliente como valores predeterminados; cada documento conserva los valores efectivamente usados.
- La dirección de ejecución se registra en el Trabajo y no se deduce necesariamente del domicilio del Cliente.

### 2.3 Cotizaciones

- Crear cotizaciones para un Cliente y un Perfil Profesional.
- Incluir servicios, mano de obra y materiales, o únicamente servicio/mano de obra cuando el Cliente suministra materiales.
- Usar opcionalmente el Catálogo de Servicios del Perfil Profesional o escribir conceptos manualmente.
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
- Permitir asociar varias Cotizaciones y/o Trabajos a una misma Cuenta de Cobro, sujeto a la pendiente sobre combinación y prevención de doble cobro.
- Estados aprobados: `PENDIENTE`, `PARCIAL`, `PAGADA`, `CANCELADA`.
- Separar la creación de la Cuenta del evento de pago, incluso cuando el Cliente paga inmediatamente.
- Aplicar las reglas compartidas de retenciones, bruto/neto y moneda. Las retenciones de V1 se calculan sobre los conceptos clasificados como `SERVICIO`; los `MATERIAL` quedan fuera de la base. La base de cada retención se determina por su configuración `applies_to`; `OTRO` no está incluido ni excluido universalmente.
- En V1 no hay descuentos. El profesional ingresa el precio como bruto y se aplican las reglas aprobadas de retenciones. El tratamiento de impuestos y cualquier configuración fiscal permanecen pendientes; no son requisitos aprobados de V1.
- Los importes se redondean por línea y el total es la suma de las líneas redondeadas. Las cantidades se redondean a dos decimales.
- Conservar la información histórica de emisión. No asumir que cada cambio de estado congela automáticamente documentos relacionados.
- Gestionar Métodos de Cobro globales del Usuario/Titular, independientes de Perfiles Profesionales. El usuario los crea y administra; no hay métodos predeterminados. La interfaz puede mostrar ayuda y ejemplos sin insertar registros iniciales. Los métodos pueden utilizarse al registrar pagos recibidos y reembolsos.

### 2.8 Perfiles Profesionales

- Permitir varios perfiles comerciales por usuario.
- Cada perfil aporta identidad comercial, descripción/actividad, logo, información comercial, servicios, documentos y configuración comercial. No es propietario de Clientes, Inventario ni Métodos de Cobro.
- No asumir que un perfil equivale a una persona jurídica independiente.
- Conservar en cada documento histórico los datos del perfil usados al emitir, incluido logo.
- El catálogo de Servicios se administra por Perfil Profesional.

### 2.9 Informes

- Informe opcional: crear a partir de datos registrados durante la ejecución, sin volver a introducir desde cero toda la información. Puede entregarse al Cliente o ser exclusivamente para organización/histórico interno del profesional.
- Estados: `BORRADOR` y `EMITIDO`. Un Informe emitido no se edita; una corrección crea una versión nueva.
- La nueva versión del Informe se enlaza a la anterior mediante un campo de referencia; la versión anterior se conserva como referencia.
- Describir lo realmente ejecutado, no lo originalmente cotizado.
- Revisar/completar el contenido y seleccionar evidencias antes de generar el documento.
- Permitir agrupar varios Trabajos del mismo Cliente en un Informe, manteniendo identidad y trazabilidad individual por Trabajo.
- No exigir un Informe para entregar un Trabajo o generar una Cuenta de Cobro.

### 2.10 Pagos

El registro de pagos es una capacidad del flujo de Cuenta de Cobro aunque no figure como módulo independiente en la lista de nueve módulos.

- Registrar el evento real de recepción de dinero por separado de la Cuenta de Cobro.
- Normalmente se registra un Pago contra una Cuenta de Cobro. Para agrupar Cuentas del mismo Cliente se utiliza el Consolidado de Cuentas de Cobro, que conserva intactas las Cuentas originales, usa sus valores históricos y permite gestionar el saldo consolidado y registrar pagos contra este. No existe un pago directo paralelo sobre varias Cuentas.
- Actualizar automáticamente el estado a `PARCIAL` o `PAGADA` según pagos asignados. No cancelar una Cuenta con pagos aplicados.
- No permitir registrar un pago contra un documento por encima de su saldo ni dejar una parte de ese pago sin aplicar a ese documento (la asignación de pagos previamente sin asignar admite aplicación parcial con excedente sin asignar).
- Al cancelar/anular un Pago, conservarlo en el historial y excluirlo del cálculo del documento asociado.
- Al corregir el importe de un Pago, aplicar la diferencia al mismo Pago y al documento asociado (una Cuenta o un Consolidado). Un pago contra Consolidado puede ser parcial; afecta su saldo conjunto y no altera las Cuentas originales.
- La corrección y anulación de Pagos son funcionalidad de V1 con trazabilidad: cada ajuste conserva valor anterior, valor nuevo, motivo, usuario, dispositivo, operación y fecha; el registro original no se modifica silenciosamente ni se elimina (mecanismo documentado `payment_adjustments`, diseño detallado pendiente de confirmación).
- Un Reembolso es un nuevo movimiento de dinero y no modifica ni elimina el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro utilizado y observación. V1 no tiene estado `REEMBOLSADA`.
- Un pago puede recibirse antes de que exista la Cuenta de Cobro correspondiente (anticipo): queda registrado sin asignación, vinculado al Cliente, y se asocia posteriormente a una Cuenta individual o a un Consolidado mediante operaciones auditadas (`assign`/`unassign`/`reassign`). No existe un módulo separado de anticipos.
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

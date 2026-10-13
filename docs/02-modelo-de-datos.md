# CotixGo — Modelo de Datos
## Documento de diseño v1.5

**Estado:** Borrador para revisión  
**Fuente funcional:** `01-reglas-de-negocio-v0.2.md`  
**Propósito:** Definir el modelo conceptual de datos de CotixGo antes de diseñar la base física, API, sincronización e implementación.

---

## 1. Propósito del modelo

Este documento traduce las reglas de negocio aprobadas a un modelo conceptual de datos.

No define todavía:

- tablas SQL definitivas;
- nombres finales de columnas;
- índices físicos completos;
- migraciones;
- endpoints de API;
- implementación Room;
- implementación MySQL.

Su objetivo es establecer **qué información existe, cómo se relaciona y qué invariantes deben preservarse**.

La implementación física deberá respetar este modelo y no introducir comportamientos que contradigan las reglas de negocio.

---

# 2. Principios del modelo

## 2.1 El usuario es el ancla de identidad

Los datos pertenecen a una cuenta de usuario.

El dispositivo Android no es la identidad del propietario de los datos.

Un usuario debe poder:

1. iniciar sesión en otro dispositivo;
2. descargar sus datos;
3. continuar trabajando;
4. conservar sus identificadores y documentos.

Por tanto, las entidades de negocio deben poder asociarse al `user_id`.

---

## 2.2 ID técnico ≠ número documental

Toda entidad debe tener un identificador técnico global y estable.

Ese identificador:

- debe poder generarse offline;
- no depende de un autoincremento local;
- no debe cambiar;
- no es mostrado necesariamente al usuario.

Los documentos que tengan numeración visible tendrán además un **número documental**.

Ejemplos:

- `COT-202609-01`
- `TR-202609-44`
- `INF-202609-87`
- `CC-202609-33`

El número documental no sustituye al ID técnico.

---

## 2.3 Los documentos históricos conservan su realidad

El modelo debe permitir conservar snapshots históricos.

Una modificación posterior de:

- cliente;
- perfil profesional;
- servicio;
- material;
- precio;
- descripción;
- condiciones;
- catálogo;

no debe modificar retroactivamente un documento ya emitido o una información histórica que deba permanecer intacta.

Principio transversal:

> **Lo que fue, fue.**

---

## 2.4 Catálogos no son documentos históricos

Los catálogos representan información reutilizable y actualizable.

Los documentos comerciales y operativos deben conservar sus propios valores históricos.

Ejemplo:

Un servicio del catálogo puede tener hoy un precio sugerido de $100.000 y mañana $120.000.

Una cotización antigua que utilizó $100.000 debe continuar mostrando $100.000.

---

## 2.5 El modelo debe soportar trabajo offline

Las entidades creadas desde Android pueden existir inicialmente solo en el dispositivo.

Por ello:

- IDs deben ser generables localmente;
- operaciones deben ser identificables;
- sincronización debe ser idempotente;
- los registros deben poder reconciliarse con el servidor;
- no se debe depender de un ID autoincremental del servidor para crear una entidad.

---

# 3. Entidades principales

El modelo conceptual inicial comprende:

### Identidad y configuración
`User`, `ProfessionalProfile`

### Maestros
`Client`, `Service`, `Material`, `Tool`, `InventoryCategory` (categorías creadas por el usuario, separadas por tipo) y `CollectionMethod` (métodos administrados por el Usuario/Titular)

### Comercial
7. `Quote`
8. `QuoteItem`

### Operación
9. `Job`
10. `JobActivity`
11. `JobMaterial`
12. `Attachment`

### Inventario y compras
13. `Purchase`
14. `PurchaseItem`
15. `InventoryMovement`

### Documentación
16. `Report`
17. `ReportJob`
18. `ReportVersion`

### Cobro
19. `CollectionAccount`
20. `CollectionAccountItem`
21. `CollectionAccountJob`
22. `CollectionAccountQuote`
23. `Payment`
24. `ConsolidatedCollection`
25. `Refund`
26. `PaymentAdjustment`

### Soporte de sincronización y trazabilidad
23. `SyncOperation`
24. `AuditEvent`

Estas entidades son conceptuales. Algunas podrán implementarse como tablas, otras como estructuras auxiliares según la arquitectura final.

---

# 4. User

Representa la cuenta propietaria de la información.

### Responsabilidad

Identificar al propietario lógico de los datos y permitir recuperación en otro dispositivo.

### Datos conceptuales

- `id`
- credenciales / identidad externa según autenticación
- datos mínimos de cuenta
- estado
- fechas de creación y actualización

### Relaciones

Un `User` puede tener:

- muchos perfiles profesionales;
- muchos clientes;
- muchas cotizaciones;
- muchos trabajos;
- muchos materiales;
- muchas herramientas;
- muchas compras;
- muchos informes;
- muchas Cuentas de Cobro;
- muchos pagos;
- métodos de cobro globales;
- categorías globales para Materiales y Herramientas.

---

# 5. ProfessionalProfile

Representa una identidad profesional con la que se emiten documentos.

Un usuario puede tener más de un perfil.

Ejemplos:

- perfil para servicios técnicos;
- perfil para servicios de software.

### Datos conceptuales

- `id`
- `user_id`
- nombre comercial
- nombre legal
- identificación
- teléfono
- correo
- dirección
- logo
- condiciones comerciales
- configuración documental
- estado activo/inactivo
- fechas

Mínimos aprobados para F1: `nombre_comercial` obligatorio; `descripción` e `información_comercial` opcionales; `logo` como referencia local opcional en F1.

### Regla histórica

Cuando un documento se emite, los datos relevantes del perfil deben quedar capturados en el snapshot documental correspondiente.

---

# 6. Client

Representa a la persona o empresa para quien se presta el servicio.

### Datos conceptuales

- `id`
- `user_id`
- tipo de cliente
- nombre / razón social
- identificación cuando corresponda
- teléfono
- correo
- dirección
- notas
- estado
- fechas

Mínimos aprobados para F1: `nombre` obligatorio; `tipo` persona/empresa; `teléfono`, `correo`, `dirección` y `notas` opcionales; `archivado`.

La cartera de Clientes pertenece globalmente al Usuario/Titular. El mismo Cliente puede utilizarse desde varios Perfiles Profesionales del usuario; cada operación/documento identifica el Perfil que corresponde. No se duplica el Cliente por Perfil.

### Relaciones

Un cliente puede tener:

- muchas cotizaciones;
- muchos trabajos;
- muchos informes;
- muchas Cuentas de Cobro.

No debe eliminarse físicamente un cliente si hacerlo rompe el historial documental.

Se recomienda manejar estado activo/inactivo.

---

# 7. Service

Catálogo reutilizable de servicios.

Es un maestro de productividad, no un documento histórico.

### Datos conceptuales

- `id`
- `user_id`
- nombre
- descripción
- categoría
- unidad
- precio sugerido
- duración estimada
- activo/inactivo
- fechas

### Reglas

- Un servicio puede reutilizarse en múltiples cotizaciones.
- Su precio es sugerido.
- El usuario puede modificar el precio al cotizar.
- Un usuario puede escribir un servicio manualmente aunque no exista en catálogo.
- Desde esa cotización puede optar por guardarlo como servicio del catálogo.
- Cambiar el catálogo no modifica cotizaciones existentes.

---

# 8. Material

Representa un material consumible administrado dentro del inventario global del Usuario/Titular, no por Perfil Profesional.

### Datos conceptuales

- `id`
- `user_id`
- nombre
- categoría de Material creada libremente por el usuario (la categoría organiza; no determina el comportamiento)
- unidad
- existencia actual
- existencia mínima
- costo de referencia
- proveedor
- ubicación
- notas
- activo/inactivo
- fechas

### Regla

El material del catálogo/inventario es información interna.

No debe confundirse con el material históricamente incluido en una cotización o utilizado en un trabajo.

---

# 9. Tool

Representa una herramienta o activo no consumible dentro del inventario global del Usuario/Titular, no por Perfil Profesional.

### Datos conceptuales

- `id`
- `user_id`
- categoría de Herramienta creada libremente por el usuario (la categoría organiza; no determina el comportamiento)
- nombre
- marca
- modelo
- serial
- fecha de adquisición
- costo
- estado
- ubicación
- notas
- fechas

### Estados conceptuales

- disponible
- en uso
- mantenimiento
- dañada
- fuera de servicio

La funcionalidad completa de mantenimiento queda fuera del alcance actual.

Las categorías de Materiales y de Herramientas son listas distintas creadas libremente por el Usuario/Titular. No existen categorías predeterminadas. El tipo Material/Herramienta define el comportamiento; la categoría solo organiza.

`CollectionMethod` representa un Método de Cobro global del Usuario/Titular. El usuario crea y administra sus métodos; al instalar CotixGo no se crean registros predeterminados. Puede utilizarse al registrar pagos recibidos y reembolsos. Los ejemplos y ayudas de la interfaz no se convierten en datos iniciales.

Campos mínimos aprobados en F6 (`03` §6): `nombre` requerido, `detalle` opcional y `archivado`.

---

# 10. Quote

Representa la propuesta comercial presentada al cliente.

### Datos conceptuales

- `id`
- `user_id`
- `client_id`
- `professional_profile_id`
- número documental
- fecha de emisión
- fecha de vencimiento
- período de vigencia
- estado comercial
- subtotal
- total
- condiciones
- observaciones
- snapshot del cliente
- snapshot del perfil profesional
- fecha de confirmación/emisión
- fechas de creación/actualización

### Estados conceptuales

Como mínimo, el modelo debe poder representar:

- borrador;
- enviada/emitida;
- aprobada;
- rechazada;
- vencida;
- cancelada.

Los nombres finales de enum/UI se definirán en el contrato funcional.

### Regla de vencimiento

Una cotización vencida:

- permanece como documento histórico;
- no puede aprobarse directamente bajo sus condiciones originales;
- puede utilizarse como base para una nueva cotización;
- no debe modificarse retroactivamente para convertirla en la nueva cotización.

### Revalidación

La revalidación debe generar una nueva cotización con:

- nuevo ID técnico;
- nueva fecha;
- nueva vigencia;
- sus propios precios;
- sus propias condiciones;
- nueva numeración cuando corresponda.

La cotización anterior permanece intacta.

---

# 11. QuoteItem

Representa una línea histórica de una cotización.

Puede corresponder a:

- servicio;
- mano de obra;
- material;
- otro concepto comercial permitido.

### Datos conceptuales

- `id`
- `quote_id`
- tipo de línea
- referencia opcional a catálogo
- descripción histórica
- cantidad
- unidad
- precio unitario
- subtotal
- orden
- origen del material cuando aplique
- fechas

### Regla fundamental

Aunque la línea tenga una referencia a `Service` o `Material`, debe conservar sus propios valores históricos.

No debe depender del catálogo actual para reconstruir una cotización antigua.

---

# 12. Job

Representa la ejecución real del trabajo.

No es una copia de la cotización.

### Datos conceptuales

- `id`
- `user_id`
- `client_id`
- número documental (numerable: provisional `TR-PEND-XXXX` desde la creación; oficial `TR-AAAAMM-NNN` asignado por el backend sobre el mismo `entity_id` en la primera aceptación del Trabajo en `PROGRAMADO` o `EN PROCESO` — aprobado en F4, `03` §6)
- estado
- fecha de inicio
- fecha estimada
- fecha de entrega
- dirección de ejecución propia del Trabajo (no se deduce necesariamente de la del Cliente; puede quedar pendiente en `BORRADOR` y es obligatoria al pasar a `PROGRAMADO` — aprobado en F4, `03` §6)
- observaciones
- información de cierre
- snapshot relevante del cliente
- perfil profesional cuando corresponda
- fechas

### Relación con cotizaciones

No se debe imponer una relación rígida 1:1.

Un trabajo puede estar relacionado con:

- una cotización original;
- una cotización posterior de alcance adicional;
- varias cotizaciones relacionadas.

La relación es opcional; el Cliente basta para crear el Trabajo.

Para preservar esta flexibilidad, la relación debe modelarse mediante una entidad asociativa conceptual `JobQuote`.

### Estado operativo

Estados aprobados: `BORRADOR`, `PROGRAMADO`, `EN PROCESO`, `ENTREGADO`, `CANCELADO`.

Transiciones aprobadas en la planificación de F4 (`03` §6):

- `BORRADOR → PROGRAMADO | EN PROCESO | CANCELADO`
- `PROGRAMADO → BORRADOR | EN PROCESO | CANCELADO`
- `EN PROCESO → ENTREGADO | CANCELADO`
- `ENTREGADO` y `CANCELADO` son terminales.
- No existe transición ni estado de "Pausar".

El trabajo permanece abierto y editable mientras no esté en un estado terminal; un Trabajo `ENTREGADO` o `CANCELADO` no se edita y la edición no cambia el estado.

### Regla de cierre histórico

No se asume que entregar el Trabajo congele automáticamente los documentos relacionados. La operación queda cerrada e histórica cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente está completamente pagada. Una garantía posterior no reabre automáticamente documentos.

---

# 13. JobQuote

Entidad asociativa entre `Job` y `Quote`.

Permite evitar el supuesto de que cada trabajo nace exclusivamente de una única cotización.

### Datos conceptuales

- `id`
- `job_id`
- `quote_id`
- tipo de relación
- fecha de asociación

Ejemplos de relación:

- cotización de origen;
- cotización adicional;
- revalidación relacionada.

---

# 14. JobActivity

Representa una actividad realizada durante un trabajo.

### Datos conceptuales

- `id`
- `job_id`
- descripción
- estado (`PENDIENTE`, `EN PROCESO`, `COMPLETADA` — aprobados en F4, `03` §6)
- fecha/hora de inicio cuando corresponda
- fecha/hora de finalización cuando corresponda
- observaciones
- orden
- usuario/dispositivo de captura
- fechas

### Regla

La actividad debe registrarse durante la ejecución y posteriormente poder reutilizarse para construir el Informe.

---

# 15. JobMaterial

Representa material relacionado con la ejecución real de un trabajo.

Es distinto de `QuoteItem`.

### Datos conceptuales

- `id`
- `job_id`
- `material_id` opcional
- descripción histórica
- origen: cliente / profesional
- cantidad utilizada
- unidad
- costo histórico cuando corresponda
- fecha de registro
- observaciones

### Regla crítica

La existencia de un `JobMaterial` no significa automáticamente que haya consumo de inventario.

Solo cuando:

- el material pertenece al profesional; y
- efectivamente se consume;

debe generarse el movimiento de inventario correspondiente.

El consumo se registra en el Trabajo según lo realmente utilizado, sin validarlo contra la existencia disponible. Si la cantidad supera el stock, el saldo puede quedar negativo; esto no bloquea ni modifica el Trabajo o su Informe. Cotizaciones e Informes no generan movimientos de inventario.

Alcance de F4 (`03` §6): el registro de materiales del Trabajo es descriptivo — descripción, cantidad, unidad, origen `CLIENTE`/`PROFESIONAL` y observaciones — sin catálogo ni `material_id`, sin modificar inventario ni generar movimientos. La futura fase de inventario no reinterpretará retroactivamente estos registros descriptivos.

---

# 16. Attachment

Representa una evidencia o archivo asociado a la operación.

Principalmente:

- fotografías;
- documentos;
- evidencias adicionales.

### Datos conceptuales

- `id`
- `user_id`
- `job_id` cuando corresponda
- tipo
- nombre
- ubicación lógica del archivo
- metadatos
- fecha de captura
- estado de sincronización
- fechas

### Regla offline

Las fotografías deben poder capturarse y conservarse localmente sin conexión.

---

# 17. Purchase

Representa una compra real de materiales.

### Datos conceptuales

- `id`
- `user_id`
- proveedor
- fecha
- estado
- observaciones
- total
- fechas

Una compra confirmada genera entradas de inventario.

---

# 18. PurchaseItem

Representa una línea de compra.

### Datos conceptuales

- `id`
- `purchase_id`
- `material_id`
- descripción histórica
- cantidad
- unidad
- costo unitario
- total
- fechas

### Regla histórica

Los costos de compras anteriores no deben sobrescribirse cuando se compre posteriormente el mismo material a otro precio.

---

# 19. InventoryMovement

Representa un movimiento histórico de inventario.

### Tipos conceptuales iniciales

- `PURCHASE_IN`
- `JOB_CONSUMPTION`
- `ADJUSTMENT_IN`
- `ADJUSTMENT_OUT`

La lista puede ampliarse posteriormente.

### Datos conceptuales

- `id`
- `user_id`
- `material_id`
- tipo
- cantidad
- unidad
- referencia de origen
- costo unitario histórico cuando corresponda
- fecha/hora
- observación
- operación de origen
- fechas

### Regla

Los movimientos son históricos.

No deben editarse silenciosamente para cambiar el pasado.

El stock actual puede materializarse para rendimiento, pero debe poder explicarse mediante el historial de movimientos.

Los ajustes permiten cargar las existencias que el profesional ya tenía en su bodega y corregir el saldo. Cada línea de una Compra confirmada genera una entrada. Si se edita una Compra confirmada, se registran movimientos por la diferencia entre cantidades anteriores y nuevas: entradas por aumentos o líneas agregadas, salidas por reducciones o líneas eliminadas. Los movimientos anteriores se conservan para trazabilidad. Solo el consumo propio registrado explícitamente en un Trabajo genera una salida por consumo; se admite que el saldo resultante sea negativo. Archivar una Compra solo la oculta; desarchivarla vuelve a mostrarla. Ninguna acción cambia sus movimientos.

### Inventario no restrictivo

El sistema no debe impedir:

- ejecutar un trabajo;
- finalizar un trabajo;
- generar un informe;
- emitir una Cuenta de Cobro;

porque el inventario no esté actualizado.

Puede existir un estado de ajuste pendiente.

---

# 20. Report

Representa un informe que puede entregarse al Cliente o utilizarse solo para organización/histórico interno. Es opcional.

### Datos conceptuales

- `id`
- `user_id`
- `client_id`
- `professional_profile_id`
- número documental
- tipo de informe
- título
- fecha
- estado
- destino del informe (`INTERNO`/`CLIENTE`)
- contenido complementario
- snapshot documental
- fecha de confirmación/finalización
- estado archivado
- fechas

### Regla

El informe puede construirse a partir de información ya registrada en los trabajos. No es requisito para entregar el Trabajo ni para generar una Cuenta de Cobro.

Puede incluir información de uno o varios trabajos.

Campos mínimos aprobados para F5 en `03` §6: `clientId`, `profileId`, título requerido, contenido/observaciones editables en `BORRADOR`, estado `BORRADOR`/`EMITIDO`, número provisional `INF-PEND-XXXX` y oficial `INF-AAAAMM-NNN` (asignado por el backend en la primera aceptación como `EMITIDO` sobre el mismo `entity_id`), destino `INTERNO`/`CLIENTE`, archivado y marcas de tiempo.

Todos los Trabajos asociados deben pertenecer al mismo Cliente y al mismo Perfil Profesional del Informe; la API valida las relaciones con datos persistidos, no con las declaradas por el cliente. Solo las actividades en estado `COMPLETADA` se presentan como ejecución realizada; el contenido es editable mientras el Informe está en `BORRADOR` (`03` §6).

Un Informe emitido no se edita: una corrección crea una versión nueva enlazada por referencia y la versión emitida anterior se conserva con su snapshot inmutable (`ReportVersion`, ver §36.3 resuelto para F5). En F5 no se incluyen PDF/exportación, fotografías ni archivos adjuntos ni envío al Cliente.

---

# 21. ReportJob

Entidad asociativa entre `Report` y `Job`.

Permite:

- un informe de un solo trabajo;
- un informe combinado de varios trabajos.

### Datos conceptuales

- `id`
- `report_id`
- `job_id`
- orden
- selección/contenido incluido
- fechas

Cada trabajo conserva su identidad y trazabilidad aunque aparezca dentro de un informe combinado. Los trabajos asociados comparten el Cliente y el Perfil Profesional del Informe (regla aprobada en F5, `03` §6).

---

# 22. CollectionAccount

Representa la **Cuenta de Cobro**.

El nombre técnico puede ser `CollectionAccount`, pero la interfaz utilizará siempre **Cuenta de Cobro**.

### Datos conceptuales

- `id`
- `user_id`
- `client_id`
- `professional_profile_id`
- moneda (ISO 4217, dos decimales; la Cuenta conserva la moneda de su creación)
- líneas (`CollectionAccountItem`)
- bruto / retenciones / neto / saldo pendiente
- número documental provisional `CC-PEND-XXXX` y oficial `CC-AAAAMM-NNN` sobre el mismo `entity_id` (asignado en el primer registro aceptado por el backend; secuencia global por Usuario/Titular + tipo + período) — aprobado en F6 (`03` §6)
- fecha de emisión (`issuedAt`)
- fecha de vencimiento (opcional)
- estado
- condiciones (opcional)
- snapshot del cliente
- snapshot del perfil profesional
- snapshot de las retenciones aplicadas
- contenido histórico
- `archivado`
- fecha de confirmación
- fechas

Campos mínimos aprobados en F6 (`03` §6): `clientId`, `profileId`, moneda, líneas, bruto/retenciones/neto/saldo, estado, número provisional/oficial, `issuedAt`, vencimiento opcional, condiciones opcionales, snapshots de Cliente/Perfil/retenciones, `archivado` y marcas de tiempo.

### Ciclo de vida (aprobado en F6, `03` §6)

- La Cuenta empieza en `PENDIENTE` y se edita solo en ese estado y sin pagos aplicados; con algún pago aplicado, sus líneas, totales y snapshots no se editan.
- `PARCIAL` y `PAGADA` se derivan automáticamente de los pagos aplicados y el saldo.
- Se cancela solo si no tiene pagos aplicados.

Puede crearse directamente para un Cliente sin Cotización, Trabajo o Informe. Los Métodos de Cobro se pueden seleccionar al registrar pagos recibidos o reembolsos; no existen métodos predeterminados y los ejemplos de interfaz no crean registros.

### Relación con trabajos y cotizaciones (aprobado en F6, `03` §6)

Una Cuenta de Cobro puede incluir uno o varios trabajos **o** una o varias cotizaciones, pero nunca mezclar ambos tipos en la misma Cuenta. Todos los asociados deben pertenecer al mismo Cliente y al mismo Perfil Profesional que la Cuenta. Cada Trabajo o Cotización solo puede pertenecer a una Cuenta no cancelada; al cancelarse la Cuenta deja de bloquear esa asociación.

Por tanto, no se debe asumir `collection_account.job_id` como única relación.

Deben existir las asociaciones conceptuales `CollectionAccountJob` y `CollectionAccountQuote`. En F6 solo son elegibles Cotizaciones en estado `APROBADA`, validado con el estado persistido por el backend (`03` §6).

---

# 23. CollectionAccountJob

Entidad asociativa entre Cuenta de Cobro y Trabajo.

### Datos conceptuales

- `id`
- `collection_account_id`
- `job_id`
- monto o participación cuando corresponda
- descripción
- orden
- fechas

Permite:

- una Cuenta de Cobro para un trabajo;
- una Cuenta de Cobro para varios trabajos.

Restricción aprobada en F6 (`03` §6): un mismo trabajo pertenece a lo sumo a una Cuenta no cancelada; cancelar la Cuenta libera la asociación. Las Cuentas que asocian trabajos no mezclan cotizaciones en el mismo documento.

---

## CollectionAccountItem

Entidad conceptual de línea de la Cuenta de Cobro, aprobada en F6 (`03` §6). Comparte con `QuoteItem` el motor de cálculo de V1 (bruto/neto, retenciones `applies_to`, redondeo por línea).

### Datos conceptuales

- `id`
- `collection_account_id`
- concepto / descripción
- tipo: `SERVICIO` | `MATERIAL` | `OTRO`
- cantidad
- precio unitario
- valores históricos (importe de línea y base de retención calculados al emitir)
- orden
- fechas

Las líneas y los totales de la Cuenta no se editan una vez aplicado un pago; los valores calculados se conservan como históricos.

---

## CollectionAccountQuote

Entidad asociativa entre Cuenta de Cobro y Cotización, aprobada en F6 (`03` §6). Resuelve la brecha previa del modelo (solo existía la asociación con Trabajos).

### Datos conceptuales

- `id`
- `collection_account_id`
- `quote_id`
- monto o participación cuando corresponda
- descripción
- orden
- fechas

Restricción aprobada en F6 (`03` §6): una misma cotización pertenece a lo sumo a una Cuenta no cancelada; cancelar la Cuenta libera la asociación. Las Cuentas que asocian cotizaciones no mezclan trabajos en el mismo documento. Elegibilidad aprobada: solo Cotizaciones en estado `APROBADA`, validado con el estado persistido por el backend.

---

# 24. Payment

Representa el evento real de recepción de dinero.

### Datos conceptuales

- `id`
- `user_id`
- `client_id`
- fecha
- monto total
- Método de Cobro del Usuario/Titular
- Cuenta de Cobro individual o Consolidado asociado cuando corresponda (opcional)
- estado de asignación (`UNASSIGNED`/`PARTIAL_ASSIGNED`/`ASSIGNED` — implementado así en F6; alineación documental)
- referencia
- observaciones
- fechas

### Regla crítica

Un Pago puede existir sin documento asociado: cuando el dinero se recibe antes de que exista la Cuenta de Cobro, el Pago queda registrado como no asignado, vinculado al Cliente, y no afecta saldos de documentos hasta su asociación posterior. La asociación posterior se realiza mediante operaciones propias y auditadas (`assign`/`unassign`/`reassign`, con motivo y validación de saldo en el servidor). Si el importe del pago supera el saldo pendiente del destino, se aplica únicamente lo necesario para cubrirlo y el excedente permanece sin asignar, perteneciendo al mismo Pago; nunca existe dinero aplicado simultáneamente a dos documentos.

Normalmente un Pago corresponde a una Cuenta de Cobro individual. Si se consolida el cobro de varias Cuentas del mismo Cliente, el Pago se registra contra el documento global y reduce su saldo residual conjunto; no se reparte entre las Cuentas originales.

### Alcance aprobado por fase (`03` §6)

- En F6: pago contra una Cuenta individual (sin superar su saldo) y pago sin asignar vinculado al Cliente. Los pagos y las asignaciones son eventos append-only e idempotentes: no se editan ni se eliminan; los duplicados no se resuelven por sobrescritura ni por reloj local.
- En F7 (reglas aprobadas en la ficha F7, `03` §6): pago contra Consolidado con el mismo mecanismo de asignación auditada, corrección/anulación mediante `PaymentAdjustment` y Reembolsos. Conceptualmente el Pago se dirige a una Cuenta individual, a un Consolidado o permanece sin asignación; el nombre del campo y su representación física quedan para el contrato de API y la implementación.
- Reserva de fondos aprobada (ficha F7): asignaciones y Reembolsos consumen la misma parte libre del Pago; una asignación nueva se valida contra `montoEfectivo − aplicadoVigente − reembolsadoAcumulado`. Invariante: `aplicadoVigente + reembolsadoAcumulado ≤ montoEfectivo`.
- Limitación registrada de F6: un error en un Pago solo se resuelve con las operaciones de asignación ya aprobadas; F6 no es apta para datos reales antes de F7.

---

# 25. ConsolidatedCollection

Representa conceptualmente un documento global para cobrar el saldo conjunto de dos o más Cuentas de Cobro del mismo Cliente. Especificación aprobada en la ficha F7 (`03` §6); asignado a F7 por la división de fases aprobada; no forma parte de F6.

### Datos conceptuales

- `id`
- `user_id`
- `client_id`
- lista de Cuentas de Cobro incluidas
- total conjunto
- saldo residual global
- moneda
- fecha
- estado (`PENDIENTE`/`PARCIAL`/`PAGADA`/`CANCELADA`)
- número provisional `CONS-PEND-XXXX` y oficial `CONS-AAAAMM-NNN`
- `archivado`
- fechas

### Reglas

El Consolidado conserva intactas las Cuentas originales y utiliza sus valores históricos. Los Pagos se registran contra el Consolidado y reducen su saldo conjunto; no se registran directamente sobre varias Cuentas originales.

Reglas aprobadas en la ficha F7 (`03` §6):

- Elegibilidad y composición: incluye Cuentas no `CANCELADA` con saldo pendiente mayor que cero, del mismo Cliente y de la misma moneda; se permite combinar Perfiles porque cada Cuenta conserva su propio snapshot.
- Ciclo de vida: `PENDIENTE` al crear; `PARCIAL` y `PAGADA` los deriva el servidor desde las asignaciones; `CANCELADA` solo sin pagos aplicados y al cancelarse libera sus Cuentas; la composición de miembros solo se edita en `PENDIENTE` sin pagos aplicados.
- Doble cobro: una Cuenta dentro de un Consolidado no cancelado no admite asignación directa ni pertenencia a otro Consolidado no cancelado; cancelar el Consolidado libera sus Cuentas.
- Numeración: `CONS-PEND-XXXX` → `CONS-AAAAMM-NNN` sobre el mismo `entity_id` en la primera aceptación del servidor, idempotente.
- Las asignaciones reutilizan `assign`/`unassign`/`reassign` con motivo, auditoría y validación atómica de saldo, incluido el excedente `UNASSIGNED`.

---

## Reembolso

Representa un Reembolso como un nuevo movimiento de dinero asociado al Pago que devuelve, sin modificar ni eliminar el registro original. Asignado a F7 por la división de fases aprobada (`03` §6); no forma parte de F6. Trazabilidad y alcance aprobados en la ficha F7 (`03` §6).

### Datos conceptuales

- fecha;
- valor;
- Método de Cobro utilizado;
- observación;
- referencia al Pago original.

El Reembolso puede ser parcial o total. V1 no agrega un estado `REEMBOLSADA`. El Pago conserva sus propios fecha, valor, método y observaciones.

Reglas aprobadas en la ficha F7 (`03` §6):

- Un Reembolso solo puede cubrirse con el remanente disponible del Pago: `disponible = montoEfectivo − aplicadoVigente − reembolsadoAcumulado`. Asignaciones y Reembolsos consumen la misma reserva.
- Para reembolsar fondos ya aplicados a un documento, primero se liberan mediante una operación auditada (`unassign`/`reassign`) y después se registra el Reembolso.
- El Reembolso es irreversible (no existe capacidad aprobada de revertirlo) y por ello actúa como piso del monto efectivo en correcciones y anulaciones.

---

## PaymentAdjustment

Representa la corrección o anulación de un `Payment` ya registrado. Es el mecanismo aprobado para la capacidad de V1 de corregir/anular pagos sin destruir el historial. Asignado a F7 por la división de fases aprobada (`03` §6); diseño confirmado en la ficha F7 (`03` §6).

### Datos conceptuales

- `id`
- `payment_id`
- tipo: `CORRECTION` | `ANNULMENT`
- valor anterior
- valor nuevo (cuando corresponda)
- motivo
- usuario que realizó la operación
- dispositivo
- operación de origen
- fecha/hora

### Reglas

- Toda corrección o anulación se registra como un nuevo evento append-only, idempotente y auditado; el registro original del Pago no se modifica silenciosamente ni se elimina.
- `CORRECTION` conserva valor anterior y nuevo; el monto efectivo del Pago pasa a ser el nuevo valor y el saldo del documento asociado se recalcula (reglas de la ficha F7):
  - Al alza: el incremento queda `UNASSIGNED` hasta una asignación explícita; nunca se distribuye automáticamente.
  - A la baja: consume primero la parte libre (`efectivo − aplicado − reembolsado`) y luego libera las asignaciones más recientes (LIFO), atómicamente.
  - Se rechaza si el nuevo monto efectivo queda por debajo del total reembolsado (un Reembolso no se puede revertir).
- `ANNULMENT` excluye el Pago del cálculo del documento asociado; el Pago permanece en el historial (anular no elimina). Reglas de la ficha F7:
  - Es terminal y solo puede registrarse una vez por Pago; libera atómicamente sus asignaciones vigentes y fija el monto efectivo en el total reembolsado (0 sin Reembolsos).
  - Tras aceptarse, se rechazan nuevas asignaciones, reasignaciones, correcciones y reembolsos sobre ese Pago; el reintento de la misma operación devuelve el resultado memorizado y una segunda anulación distinta se rechaza.
- Un Reembolso es un caso distinto: es un nuevo movimiento de dinero sobre un Pago correcto y no utiliza `PaymentAdjustment`.
- Invariante vigente: `aplicadoVigente + reembolsadoAcumulado ≤ montoEfectivo`.

---

# 26. Relación general del modelo

La estructura conceptual principal queda:

```text
USER
 │
 ├── ProfessionalProfile
 │
 ├── Client
 │    │
 │    ├── Quote
 │    │    └── QuoteItem
 │    │
 │    ├── Job
 │    │    ├── JobActivity
 │    │    ├── JobMaterial
 │    │    └── Attachment
 │    │
 │    ├── Report
 │    │    └── ReportJob ─── Job
 │    │
 │    └── CollectionAccount
 │         ├── CollectionAccountItem
 │         ├── CollectionAccountJob ─── Job
 │         └── CollectionAccountQuote ─── Quote
 │
 ├── Service
 ├── Material ─── InventoryCategory
 │    ├── Purchase
 │    │    └── PurchaseItem
 │    └── InventoryMovement
 │
 ├── Tool
 │
 ├── Tool ─────── InventoryCategory
 ├── CollectionMethod
 ├── Payment
 ├── ConsolidatedCollection
 ├── Refund
 └── PaymentAdjustment
 │
 ├── SyncOperation
 └── AuditEvent
```

---

# 27. Relaciones cardinales críticas

## Cliente

```text
Client 1 ─── N Quote
Client 1 ─── N Job
Client 1 ─── N Report
Client 1 ─── N CollectionAccount
```

## Cotización

```text
Quote 1 ─── N QuoteItem
Quote N ─── N Job
Quote N ─── N CollectionAccount   (a lo sumo una Cuenta no cancelada por Cotización)
```

La segunda relación se realiza mediante `JobQuote` y la tercera mediante `CollectionAccountQuote` (F6, `03` §6).

## Trabajo

```text
Job 1 ─── N JobActivity
Job 1 ─── N JobMaterial
Job 1 ─── N Attachment

Job N ─── N Report
Job N ─── N CollectionAccount   (a lo sumo una Cuenta no cancelada por Trabajo)
```

Las relaciones de Trabajo con Informe y con Cuenta se realizan mediante `ReportJob` y `CollectionAccountJob` (F6, `03` §6). Una Cuenta no mezcla Trabajos y Cotizaciones en el mismo documento.

## Cobro

```text
CollectionAccount 1 ─── N CollectionAccountItem
Payment ─── CollectionAccount (individual)
Payment ─── ConsolidatedCollection ─── N CollectionAccount   (F7)
Payment ─── (sin asignación) ─── Client   (pago recibido antes del documento)
Payment 1 ─── N PaymentAdjustment   (F7)
Refund ─── Payment (referencia al original)   (F7)
```

## Inventario

```text
Material 1 ─── N PurchaseItem
Material 1 ─── N InventoryMovement
Job 1 ─── N JobMaterial
```

---

# 28. Snapshots históricos

Los snapshots no deben considerarse una copia indiscriminada de todas las entidades.

Su objetivo es conservar la información necesaria para que un documento histórico pueda reproducirse correctamente.

## Como mínimo, cuando corresponda:

### Cliente

- nombre/razón social;
- identificación;
- dirección;
- contacto;
- demás datos mostrados en el documento.

### Perfil profesional

- nombre comercial;
- nombre legal;
- identificación;
- contacto;
- dirección;
- logo;
- Método de Cobro seleccionado cuando corresponda;
- condiciones comerciales;
- demás información documental.

### Líneas comerciales

- descripción;
- cantidad;
- unidad;
- precio;
- retenciones aprobadas;
- totales;
- condiciones relevantes.

### Materiales

- descripción;
- cantidad;
- unidad;
- origen;
- costo histórico cuando corresponda.

El mecanismo físico del snapshot se decidirá en el diseño técnico.

---

# 29. Documentos y cierre histórico

El congelamiento representa el cierre comercial e histórico de la operación. No se infiere automáticamente de cada cambio de estado. La operación queda cerrada cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente está completamente pagada. No se ha definido una regla general de edición por documento a partir de este cierre. Una garantía posterior no reabre automáticamente documentos históricos.

---

# 30. Numeración documental

La numeración visible utiliza:

```text
TIPO-AAAAMM-NNN
```

Tipos iniciales:

```text
COT
TR
INF
CC
```

### Características

- consecutivo independiente por tipo;
- período basado en año y mes;
- número visible independiente del ID técnico;
- debe ser seguro frente a concurrencia y sincronización;
- debe contemplar creación offline.

### Numeración provisional offline — decisión aprobada

Todo documento numerable recibe de inmediato un identificador provisional con formato:

```text
TIPO-PEND-XXXX
```

Por ejemplo `COT-PEND-8F3A`. El provisional:

- aparece en la interfaz;
- puede usarse en un PDF provisional;
- identifica al mismo documento (no genera un segundo documento);
- no compite con la numeración oficial.

Cuando exista conectividad, el backend asigna el número oficial sobre el mismo `entity_id` (`COT-PEND-8F3A` → `COT-202610-023`). El modelo representa el estado del número (provisional/oficial) por documento.

### Secuencia oficial — decisión aprobada

La numeración oficial es global por Usuario/Titular + tipo de documento + período, independiente del Perfil Profesional. No existen secuencias por perfil. Los detalles de endpoint y campos quedan para el contrato de API.

---

# 31. Creación offline y sincronización

Toda entidad sincronizable debe contemplar:

- `id` técnico global;
- `user_id`;
- timestamps;
- versión o mecanismo equivalente de actualización;
- identificación de la operación que originó el cambio;
- estado de sincronización local cuando aplique.

No se debe utilizar:

```text
AUTO_INCREMENT local
```

como identidad de negocio.

---

# 32. SyncOperation

Representa una operación de sincronización.

### Objetivo

Permitir que una misma operación pueda reintentarse sin crear duplicados.

### Datos conceptuales

- `operation_id`
- `user_id`
- `device_id` como dato técnico, no como identidad del propietario
- entidad afectada
- ID de entidad
- tipo de operación
- payload o referencia
- fecha de creación
- estado
- fecha de procesamiento
- resultado
- error cuando corresponda

### Principio

La operación debe ser **idempotente**.

Si llega dos veces al servidor, el resultado final debe ser equivalente a haberla procesado una sola vez.

---

# 33. AuditEvent

Representa trazabilidad básica.

### Debe permitir conocer

- quién;
- cuándo;
- qué entidad;
- qué operación;
- desde qué dispositivo;
- qué operación de sincronización la originó cuando corresponda.

No pretende ser todavía un sistema completo de auditoría empresarial.

Su objetivo inicial es permitir diagnosticar cambios y problemas de sincronización.

---

# 34. Reglas de integridad que el modelo debe proteger

## Cotizaciones

- Una cotización vencida no puede aprobarse directamente.
- Revalidar crea una nueva cotización.
- La cotización anterior permanece intacta.
- Los precios históricos no dependen del catálogo actual.

## Trabajos

- Un trabajo no es una copia inmutable de la cotización.
- Puede incorporar ejecución real.
- Puede relacionarse con varias cotizaciones.
- Puede permanecer abierto y editable.

## Materiales

- Material del cliente no afecta inventario.
- Material propio efectivamente consumido sí puede generar movimiento.
- Material utilizado y consumo de inventario son conceptos diferentes.

## Inventario

- No debe bloquear la operación.
- Los movimientos históricos se conservan.
- Los costos históricos no se sobrescriben.

## Informes

- Se construyen a partir de trabajos.
- Un informe puede incluir varios trabajos del mismo Cliente y del mismo Perfil Profesional (regla aprobada en F5, `03` §6).
- Cada trabajo mantiene su propia trazabilidad.

## Cuentas de Cobro

- Crear una Cuenta de Cobro no significa recibir dinero.
- Puede relacionarse con varios trabajos.
- Puede quedar pendiente, parcial o pagada.
- El cierre histórico ocurre cuando el Trabajo se entregó y la Cuenta correspondiente está completamente pagada; no se infiere congelamiento automático de cada documento por estado.

## Pagos

- Un pago es un evento independiente.
- Para varias Cuentas del mismo Cliente se genera un documento global; el pago reduce su saldo residual conjunto sin distribución entre las Cuentas originales.

## Historial

- Los cambios de maestros no modifican documentos históricos.
- Una garantía posterior no reabre automáticamente documentos históricos.
- Los IDs técnicos no cambian.

---

# 35. Qué NO debe convertirse todavía en entidad

Para evitar sobre-modelar el sistema, no se crearán entidades independientes para:

- Dashboard;
- estados;
- indicadores;
- filtros;
- pestañas de navegación;
- acciones rápidas;
- estados visuales de sincronización.

Estas son vistas, enums, configuraciones o comportamientos, salvo que una regla posterior justifique una entidad propia.

---

# 36. Elementos pendientes de definición

Este documento establece la estructura conceptual, pero todavía deben cerrarse:

1. transiciones completas permitidas de cada entidad y sus actores (Trabajo: resueltas para F4 en `03` §6; Cuenta de Cobro: reglas de ciclo de vida aprobadas para F6 en `03` §6);
2. transiciones/estados restantes de Compras, archivos y sincronización;
3. relación de versiones de Informe — resuelta para F5: relación `Report`–`ReportVersion` con snapshot inmutable por versión emitida; una corrección crea una versión nueva enlazada por campo de referencia y la versión anterior se conserva (`03` §6); la corrección/anulación de Pagos está aprobada para V1 con el mecanismo `PaymentAdjustment` y su diseño detallado quedó confirmado en la ficha F7 (`03` §6): `CORRECTION` con absorción LIFO y piso de reembolsados, `ANNULMENT` terminal con piso de reembolsados e invariante `aplicado + reembolsado ≤ efectivo`;
4. tratamiento de impuestos (no es funcionalidad aprobada ni requisito de V1);
5. instante exacto de vencimiento de Cotización — resuelto para F2 y aclarado en F3: `fecha_vencimiento` es el último día válido y pasa a `VENCIDA` cuando la fecha local del dispositivo es posterior, solo desde los estados no terminales `BORRADOR`, `EMITIDA` y `RECHAZADA` (`03` §6);
6. momento exacto, por tipo de documento, en que el backend confirma el número oficial tras el provisional — resuelto para Cotización en la planificación de F3: al aceptar el backend la primera versión `EMITIDA`/`APROBADA` sobre el mismo `entity_id` (`03` §6); resuelto para Trabajo en la planificación de F4: al aceptar el backend por primera vez el Trabajo en `PROGRAMADO` o `EN PROCESO` (`03` §6); resuelto para Informe en la planificación de F5: al aceptar el backend por primera vez el Informe como `EMITIDO` sobre el mismo `entity_id` (`03` §6); resuelto para Cuenta de Cobro en la planificación de F6: `CC-PEND-XXXX` → `CC-AAAAMM-NNN` en el primer registro de la Cuenta aceptado por el backend sobre el mismo `entity_id` (`03` §6); resuelto para Consolidado en la ficha F7: `CONS-PEND-XXXX` → `CONS-AAAAMM-NNN` en la primera aceptación del servidor sobre el mismo `entity_id`, idempotente (`03` §6); otros tipos de documento siguen pendientes;
7. estructura física de snapshots;
8. representación técnica del estado archivado/desarchivado; los datos generados no se eliminan;
9. timestamps y control de versiones;
10. resolución de conflictos — política de producto aprobada para las entidades de F3 (detección explícita, preservación, resolución del usuario; ver `03` §6) e implementada en local para esas entidades (`05` §5.1); extendida por decisión a las entidades de F4 en la ficha F4 (`03` §6) e implementada en local para ellas (`05` §5.2); extendida por decisión a las entidades de F5 (Informes) en la ficha F5 (`03` §6) — la resolución del usuario crea una versión nueva del Informe — e implementada en local para ellas (`05` §5.3); extendida por decisión a las entidades documentales de F6 (`collection_account`, `collection_method`) en la ficha F6 (`03` §6), con los eventos financieros (`payment`, asignaciones) aprobados como append-only con validación atómica de saldo en el servidor; extendida por decisión a las entidades de F7 en la ficha F7 (`03` §6): `consolidated_collection` como entidad documental y `payment_adjustment`/`refund` como eventos financieros append-only — implementada en local para ellas (`05` §5.5); la extensión a fases posteriores sigue pendiente;
11. estructura exacta de `SyncOperation`;
12. estructura exacta de `AuditEvent`;
13. campos finales de cada entidad;
14. precisión de cantidades;
15. almacenamiento físico y política de retención de fotografías/archivos;
16. campos y reglas de relación pendientes entre perfil profesional y documentos, sin afectar la propiedad global del inventario y Métodos de Cobro;
17. reglas de permisos futuras si se incorpora colaboración multiusuario;
18. relación y trazabilidad del Reembolso con el Pago original — resuelta en la ficha F7 (`03` §6): el Reembolso referencia `payment_id`, es irreversible, solo puede cubrirse con el remanente `efectivo − aplicado − reembolsado` compartido con las asignaciones, y no implica modificar el Pago.

Estos puntos no deben inventarse en la implementación: deberán resolverse en los documentos correspondientes.

---

# 37. Principio de evolución

El modelo debe permitir crecimiento sin introducir relaciones rígidas que contradigan el negocio.

Especialmente:

- no asumir 1 cotización = 1 trabajo;
- no asumir 1 trabajo = 1 informe;
- no asumir 1 trabajo = 1 Cuenta de Cobro;
- permitir pago por una Cuenta, por un Consolidado de Cuentas de Cobro del mismo Cliente, o un pago sin documento asociado registrado antes de la Cuenta; los pagos al Consolidado no se distribuyen entre las Cuentas originales;
- no asumir que material utilizado = inventario consumido;
- no asumir que catálogo actual = información histórica.

---

# 38. Estado del documento

## Aclaración aprobada posterior: Cuenta de Cobro independiente

Una Cuenta de Cobro puede existir para un Cliente sin Cotización y sin Trabajo relacionado. Caso de uso aprobado: atención de emergencia que el Cliente paga de inmediato. En ese flujo solo se requiere la Cuenta de Cobro y el registro independiente del Pago; no se exige Cotización, Trabajo, Informe ni fotografías.

Por tanto, las asociaciones de `CollectionAccount` con Cotizaciones y Trabajos son opcionales. La Cuenta debe conservar su Cliente y Perfil Profesional conforme al snapshot histórico. Esta aclaración complementa el modelo v0.1; no resuelve las demás decisiones pendientes de este documento.

**CotixGo — Modelo de Datos v0.2**

**Estado:** BORRADOR PARA REVISIÓN

Este documento será revisado contra las reglas de negocio v0.2 antes de convertirse en contrato técnico.

No iniciar todavía la creación de tablas definitivas, migraciones o código de persistencia hasta aprobar este modelo y resolver los puntos pendientes que afecten arquitectura, historial u offline/sincronización.

## Anexo posterior: estados, pagos y cálculos aprobados

Este anexo complementa el borrador v0.1 y prevalece cuando corrige una propuesta conceptual anterior:

- `Quote` usa estados `BORRADOR`, `EMITIDA`, `APROBADA`, `RECHAZADA`, `VENCIDA`, `CANCELADA`. El profesional registra manualmente la aprobación; se puede editar `BORRADOR` y `EMITIDA` antes del vencimiento, cada modificación conserva una versión; el profesional puede cancelar esos dos estados. El profesional define la vigencia al crearla y el sistema calcula el vencimiento. El rechazo es reversible hasta aprobar.
- Aprobar una Cotización solo habilita crear un Trabajo manualmente. El sistema valida transiciones según el estado actual.
- `Report` representa el Informe de Trabajo, con estados `BORRADOR` y `EMITIDO`. Después de emitirse no se edita; una corrección crea una nueva versión enlazada por referencia y la anterior se conserva con su snapshot inmutable (`03` §6, F5).
- Al aprobar una Compra, el sistema incrementa inventario. Una Compra aprobada puede anularse mediante movimiento inverso, editarse o archivarse. La Compra y su comprobante pertenecen a Compras/Inventario; aprobarla no emite un Informe de Trabajo.
- El estado de `CollectionAccount` cambia automáticamente a `PARCIAL` o `PAGADA` según pagos aplicados. No se cancela si tiene pagos aplicados. No se permiten pagos mayores al saldo ni pagos parcialmente sin asignar.
- Un Pago puede existir sin Cuenta de Cobro asociada: un pago recibido antes de la Cuenta (anticipo) queda registrado sin asignación, vinculado al Cliente, y se asocia posteriormente mediante operaciones auditadas (`assign`/`unassign`/`reassign`). Si el pago supera el saldo del destino se aplica solo lo necesario y el excedente permanece sin asignar dentro del mismo Pago.
- V1 no maneja descuentos. El precio se ingresa manualmente como bruto. Las retenciones de V1 se calculan sobre los conceptos clasificados como `SERVICIO`; los conceptos `MATERIAL` quedan fuera de la base. La base de cada retención se determina por su configuración `applies_to`; `OTRO` no está incluido ni excluido universalmente. El tratamiento de impuestos, administrador, porcentajes y configuración fiscal no está aprobado para V1 y permanece pendiente.
- Normalmente hay un Pago contra una Cuenta. Para agrupar varias Cuentas del mismo Cliente se utiliza el Consolidado, que conserva intactas las originales y sus valores históricos y gestiona el saldo conjunto. Los Pagos contra el Consolidado pueden ser parciales; no se registran directamente sobre varias Cuentas.
- Al anular un Pago, se conserva en el historial y deja de contar en el cálculo del documento asociado. Corregir un Pago se registra mediante un evento `PaymentAdjustment` con valor anterior, valor nuevo, motivo, usuario, dispositivo, operación y fecha; no crea un Reembolso ni modifica silenciosamente el registro original.
- Un Reembolso es un nuevo movimiento de dinero; no modifica ni elimina el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro y observación. V1 no agrega un estado `REEMBOLSADA`.
- Al corregir el importe de un Pago, el saldo de la Cuenta de Cobro o del Consolidado asociado se recalcula con el nuevo valor efectivo.
- La moneda usa código ISO 4217, dos decimales y símbolo visible. Pagos en moneda principal del usuario; documentos existentes conservan la moneda original.
- Todo documento numerable usa un identificador provisional `TIPO-PEND-XXXX` visible desde su creación hasta que el backend le asigna el número oficial sobre el mismo `entity_id`. La numeración oficial es global por Usuario/Titular + tipo + período, independiente del Perfil Profesional.
- El catálogo de servicios se administra por Perfil Profesional.
- La corrección y anulación de Pagos forman parte de V1 mediante el mecanismo documentado `PaymentAdjustment`.

Pendientes del modelo: validaciones residuales de transiciones; precisión de cantidades. Las entidades `ConsolidatedCollection`, `PaymentAdjustment` y `Refund` quedaron implementadas en local para F7 con datos sintéticos (`05` §5.5): el Consolidado materializa `consolidated_collections` + membresía N:M `consolidated_collection_accounts`; el destino de asignación se decidió como discriminador `targetType`/`assignedTargetType` (wire `account`/`consolidated`) con `accountId`/`consolidatedId` y `fromAccountId`/`fromConsolidatedId`; los ajustes (`payment_adjustments`) y los Reembolsos (`payment_refunds`) son eventos append-only con trazabilidad (motivo, importes antes/después, lo liberado y su origen). Las unidades se registran explícitamente y no se convierten automáticamente. Los pendientes no deben resolverse por inferencia. Archivar solo oculta y desarchivar vuelve a mostrar; ningún dato generado se elimina y ninguna de estas acciones modifica efectos o relaciones.

---

# 39. Historial de cambios

| Versión | Fecha | Cambio | Motivo | Estado |
|---|---|---|---|---|
| v0.1 | 30/09/2026 | Modelo conceptual inicial. | Documento de diseño base. | Borrador para revisión. |
| v0.2 | 08/10/2026 | Numeración provisional `TIPO-PEND-XXXX` y oficial global por usuario+tipo+período; `Payment` admite existir sin Cuenta de Cobro (anticipo) con asociación posterior; nueva entidad `PaymentAdjustment` para corrección/anulación de Pagos en V1; catálogo de servicios por Perfil Profesional; aclarada base de retención sobre conceptos `SERVICIO`; actualizados los pendientes. | Incorporación de decisiones aprobadas por el titular. | Cambio aprobado; el documento sigue en borrador. |
| v0.3 | 08/10/2026 | D13-B: asignación de pagos sin asignar con aplicación parcial — el excedente sobre el saldo del destino permanece `UNASSIGNED` en el mismo Pago; D14-C: base de retención determinada por `applies_to` configurable (`OTRO` sin comportamiento hardcodeado). | Aprobación de las decisiones pendientes de la etapa V1.1. | Cambio aprobado; el documento sigue en borrador. |
| v0.4 | 11/10/2026 | F1: mínimos aprobados registrados en `ProfessionalProfile` (§5) y `Client` (§6). | Aprobación de las decisiones pendientes de la ficha F1. | Cambio aprobado; el documento sigue en borrador. |
| v0.5 | 11/10/2026 | Pendientes §36 actualizados: vencimiento de Cotización resuelto en F2; momento del número oficial resuelto para Cotización en F3; política de conflicto aprobada para entidades de F3 (referencia a `03` §6). | Registro de decisiones de F2/F3 que cierran pendientes del modelo. | Cambio aprobado; el documento sigue en borrador. |
| v0.6 | 12/10/2026 | F4: `Job` §12 registra los cinco estados aprobados y la matriz de transiciones (`ENTREGADO`/`CANCELADO` terminales, sin "Pausar", sin edición en terminal), dirección de ejecución propia obligatoria al `PROGRAMADO` y numeración `TR-PEND-XXXX` → `TR-AAAAMM-NNN`; `JobActivity` §14 con estados `PENDIENTE`/`EN PROCESO`/`COMPLETADA`; `JobMaterial` §15 con alcance F4 descriptivo sin efecto en inventario ni reinterpretación retroactiva. Ejemplos y tipos iniciales de numeración alineados al tipo `TR` (§2.2 y §28). Pendientes §36 actualizados (transiciones de Trabajo, momento de numeración, política de conflictos extendida a F4). | Aprobación de las decisiones de la ficha F4 por el titular. | Cambio aprobado; el documento sigue en borrador. |
| v0.7 | 12/10/2026 | F4 implementada en local: pendiente §36.10 actualizado — la política de conflictos quedó implementada en local para `Job`, `JobQuote`, `JobActivity` y `JobMaterial` (`05` §5.2); sigue pendiente solo para fases posteriores. Sin cambios en el modelo conceptual. | Registro del estado de implementación local de F4; pendiente revisión vía PR. | Cambio aprobado; el documento sigue en borrador. |
| v0.8 | 12/10/2026 | F5 (planificación aprobada): §20 `Report` registra los campos mínimos aprobados (`clientId`, `profileId`, título, contenido/observaciones, estado `BORRADOR`/`EMITIDO`, `INF-PEND-XXXX`/`INF-AAAAMM-NNN`, destino `INTERNO`/`CLIENTE`, archivado), la regla de mismo Cliente y Perfil Profesional para los Trabajos asociados con validación API sobre datos persistidos, la presentación de solo actividades `COMPLETADA` como ejecutadas y las exclusiones de F5; §21 `ReportJob` y sección "Informes" alineados; pendientes §36.3 (versiones de Informe), §36.6 (número oficial INF) y §36.10 (conflictos extendidos a F5) resueltos para F5. | Aprobación de las decisiones de la ficha F5 por el titular. | Cambio aprobado; el documento sigue en borrador. |
| v0.9 | 12/10/2026 | F5 implementada en local: pendiente §36.10 actualizado — la política de conflictos quedó implementada en local para `report`, `report_job` y `report_version` (`05` §5.3); sigue pendiente solo para fases posteriores. Sin cambios en el modelo conceptual. | Registro del estado de implementación local de F5; pendiente revisión vía PR. | Cambio aprobado; el documento sigue en borrador. |
| v1.0 | 13/10/2026 | F6 (planificación aprobada): `CollectionAccount` §22 registra campos mínimos, ciclo de vida (edición solo `PENDIENTE` sin pagos; `PARCIAL`/`PAGADA` automáticos; cancelación solo sin pagos), numeración `CC-PEND-XXXX` → `CC-AAAAMM-NNN` en el primer registro aceptado por el backend y asociaciones de un solo tipo (Trabajos o Cotizaciones, sin mezclar) del mismo Cliente+Perfil con a lo sumo una Cuenta no cancelada por origen. Nuevas entidades conceptuales `CollectionAccountItem` (líneas con `SERVICIO`/`MATERIAL`/`OTRO` y motor de cálculo compartido) y `CollectionAccountQuote` (resuelve la brecha previa del modelo); §23 ajusta la cardinalidad de `CollectionAccountJob`. `Payment` §24 registra el alcance F6 (append-only, validación atómica de saldo en servidor) y la asignación a F7 de Consolidado/ajustes/Reembolso; `ConsolidatedCollection`, Reembolso y `PaymentAdjustment` marcados como F7. Pendientes §36.6 y §36.10 actualizados; §38 anota la elegibilidad de Cotización pendiente. La implementación de F6 no está autorizada. | Aprobación de las decisiones de la ficha F6 por el titular. | Cambio aprobado; el documento sigue en borrador. |
| v1.1 | 13/10/2026 | Corrección de propagación de las decisiones F6 ya aprobadas (sin decisiones nuevas): `CollectionMethod` registra sus mínimos aprobados (`nombre` requerido, `detalle` opcional, `archivado`) y §36.1 anota que las reglas de ciclo de vida de la Cuenta de Cobro quedaron aprobadas para F6 en `03` §6. | Coherencia del modelo con el anexo canónico tras la revisión del PR documental. | Cambio aprobado; el documento sigue en borrador. |
| v1.2 | 13/10/2026 | Elegibilidad de Cotización para Cuenta de Cobro aprobada por el titular: solo Cotizaciones `APROBADA` son elegibles como origen, validado con el estado persistido por el backend. §22 y la sección de `CollectionAccountQuote` actualizados; §38 retira el pendiente correspondiente. | Aprobación de la decisión pendiente `03` §4.9 por el titular. | Cambio aprobado; el documento sigue en borrador. |
| v1.3 | 13/10/2026 | F6 implementada en local con datos sintéticos tras la autorización de etapa del titular: las entidades conceptuales `CollectionAccount`, `CollectionAccountItem`, `CollectionAccountJob`, `CollectionAccountQuote`, `CollectionMethod`, `Payment` y los eventos de asignación quedaron materializados como siete tablas Room (v7) y como tipos del almacenamiento genérico `entities` del servidor, sobre el protocolo de sincronización F3 (detalle técnico en `05` §5.4; evidencia en `evidence/f6/`). Sin cambios en el modelo conceptual aprobado. | Registro del estado de implementación local de F6; pendiente revisión vía PR. | Cambio aprobado; el documento sigue en borrador. |
| v1.4 | 13/10/2026 | F7 (planificación aprobada): `ConsolidatedCollection` §25 registra la especificación aprobada (elegibilidad no `CANCELADA` con saldo > 0, mismo Cliente y moneda, Perfil libre; ciclo `PENDIENTE`/`PARCIAL`/`PAGADA`/`CANCELADA` derivado con liberación al cancelar; anti-doble-vínculo; numeración `CONS-PEND-XXXX` → `CONS-AAAAMM-NNN` idempotente; asignaciones `assign`/`unassign`/`reassign` con validación atómica); `PaymentAdjustment` registra la semántica confirmada (`CORRECTION` alza `UNASSIGNED`/baja LIFO/piso de reembolsados; `ANNULMENT` terminal con piso y rechazo de operaciones posteriores); Reembolso registra la fórmula de disponible `efectivo − aplicado − reembolsado`, reserva compartida con asignaciones e irreversibilidad; `Payment` §24 registra `targetType` y la reserva de fondos. Alineación documental: el enum de estado del Pago incluye `PARTIAL_ASSIGNED` (ya implementado en F6). Pendientes §36.3, §36.6, §36.10 y §36.18 resueltos/anotados para F7. La implementación de F7 no está autorizada. | Aprobación de las decisiones de la ficha F7 por el titular. | Cambio aprobado; el documento sigue en borrador. |
| v1.5 | 13/10/2026 | Corrección de la revisión del PR documental #14 (sin decisiones nuevas): §24 retira el nombre de campo `targetType`, que se había registrado como si fuera parte del contrato; queda la regla conceptual aprobada — el Pago se dirige a una Cuenta individual, a un Consolidado o permanece sin asignación — y el nombre del campo y su representación física quedan para el contrato de API y la implementación. | Coherencia con la regla de no resolver por inferencia los detalles de contrato. | Cambio aprobado; el documento sigue en borrador. |
| v1.6 | 13/10/2026 | F7 implementada en local con datos sintéticos tras la autorización de etapa del titular: `ConsolidatedCollection`, la membresía `consolidated_collection_accounts`, `PaymentAdjustment` y `Refund` quedaron materializados como tablas Room (v8) y como tipos del almacenamiento genérico `entities` del servidor, sobre el protocolo de sincronización F3–F6; el discriminador físico de destino quedó implementado como `targetType`/`assignedTargetType` con `accountId`/`consolidatedId` y `fromAccountId`/`fromConsolidatedId` (detalle técnico en `05` §5.5; evidencia en `evidence/f7/`). §38 actualizado: los pendientes físicos de F7 quedan resueltos por la implementación. Sin cambios en el modelo conceptual aprobado. | Registro del estado de implementación local de F7; pendiente revisión vía PR. | Cambio aprobado; el documento sigue en borrador. |

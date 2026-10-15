# CotixGo — Instrucción global y planificación inicial para Devin

**Versión:** 4.0  
**Estado:** Planificación Android-first (fases F1–F8) aprobada por el titular. La implementación avanza por fases; cada fase requiere aprobación explícita del titular antes de implementarse. F1–F5 están implementadas e integradas en `main` (F5 por el merge `b424c3f`, PR #9 — Informes en Android con sincronización, solo datos sintéticos; ver `03` §6 y `05` §5.3), junto con el correctivo de edición de Trabajo (merge `0df9c87`, PR #10). El titular confirmó el 15/10/2026 que CotixGo compila desde Android Studio en su estado actual; la verificación de Gradle Sync y compilación en el IDE tras integrar dependencias nuevas (p. ej. la librería nativa de SQLCipher) queda como criterio de aceptación de la implementación correspondiente, no como pendiente general. Las decisiones de alcance de F6 (Cuenta de Cobro, Métodos de Cobro y pagos esenciales) fueron aprobadas para planificación el 13/10/2026 (`03` §6) y su implementación local fue autorizada y completada el mismo día con datos sintéticos (elecciones técnicas en `05` §5.4, evidencia en `evidence/f6/`); F6 quedó integrada en `main` por el merge `22da7b0` (PR #13). F7 (Consolidado de Cuentas, `payment_adjustments` y Reembolsos) fue implementada en local el 13/10/2026 con datos sintéticos tras la autorización de etapa del titular — reglas aprobadas en la ficha F7 (`03` §6), elecciones técnicas en `05` §5.5, evidencia en `evidence/f7/` — e integrada en `main` por el merge `7d5e167` (PR #15) tras las revisiones del titular. La planificación de F8 (Inventario y Compras) quedó aprobada por el titular el 14/10/2026 (ficha F8, `03` §6) y su implementación local fue autorizada y completada el mismo día con datos sintéticos — reglas aprobadas en la ficha F8 (`03` §6), elecciones técnicas en `05` §5.6, evidencia en `evidence/f8/` — F8 quedó integrada en `main` por el merge `fd56941` (PR #18). El 15/10/2026 el titular aprobó la base de planificación de la protección de datos locales en reposo (ficha en `03` §6): SQLCipher para la base Room con clave aleatoria envuelta por Android Keystore, bloqueo de interfaz opcional sin ligar la clave a la autenticación (barrera de interfaz, no cifrado ni autenticación del almacén), token migrado fuera de `security-crypto` deprecada y migración fail-closed con limpieza bloqueante de archivos claros; la implementación quedó autorizada por el titular y completada en la rama `impl/proteccion-local-reposo` con `sqlcipher-android:4.17.0` + `androidx.sqlite:sqlite:2.6.2` sobre Room 2.6.1 sin elevar `compileSdk`/AGP/Gradle (`05` §5.7, `evidence/reposo/`), integrada en `main` por PR #20 (`51fe0cab`) y el fix M1 PR #21 (`7a68205`); el titular confirmó Gradle Sync y ejecución desde Android Studio en `main` sobre un Xiaomi físico. La verificación no autoriza datos reales ni servicios externos; se conserva el uso exclusivo de datos sintéticos hasta autorización específica. Herramientas queda pospuesto para planificación posterior, sin número de fase ni alcance asignado. Sin despliegue remoto ni datos reales.
**Responsabilidad:** El usuario define y aprueba el producto; Devin prepara el plan y, después de su aprobación, implementa; el usuario y el equipo revisan cada entrega.

## 1. Encargo de esta etapa

Lee la documentación completa y la referencia visual existente. Prepara un plan de implementación por etapas para CotixGo, empezando por **Android** y dejando la aplicación web para una fase posterior. El backend PHP/MySQL en Hostinger debe considerarse desde el inicio para que Android sincronice datos y la futura web pueda usar la misma API.

En esta primera etapa entrega **planificación solamente**. No escribas código, no crees tablas ni migraciones, no despliegues servicios y no cambies decisiones de producto. El usuario revisará y aprobará el plan antes de autorizar la implementación.

## 2. Fuentes que debes leer

Lee estos archivos en orden. Las rutas son relativas a la raíz del repositorio:

1. `docs/README.md` — índice, estado y autoridad documental.
2. `docs/01-reglas-de-negocio-v0.2.md` — reglas base aprobadas.
3. `docs/03-anexo-decisiones-aprobadas-y-brechas.md` — decisiones posteriores y pendientes actuales.
4. `docs/04-alcance-y-modulos-v1.md` — módulos y alcance de V1.
5. `docs/05-arquitectura-y-tecnologias.md` — plataformas y tecnologías aprobadas.
6. `docs/02-modelo-de-datos.md` y `docs/06-tipos-y-contratos-de-datos.md` — modelo conceptual; no son todavía un esquema físico aprobado.
7. `docs/08-matriz-decision-estados-y-calculos.md` — decisiones consolidadas sobre estados, pagos y cálculos.
8. `docs/CotixGo_ Gestión Inteligente de Servicios.png` — referencia visual existente.

`01-reglas-de-negocio.md` es un antecedente v0.1 y no prevalece sobre v0.2 ni sobre decisiones posteriores explícitas.

Si hay contradicciones, aplica este orden: decisión aprobada más reciente en `03` y el consolidado de `08`; reglas aprobadas de `01-reglas-de-negocio-v0.2.md`; alcance y arquitectura; borrador conceptual del modelo. No conviertas una idea visual o un campo tentativo en una regla de negocio.

## 3. Producto y reglas globales

CotixGo es una herramienta de campo para profesionales independientes y pequeños proveedores de servicios. Debe ayudarles a sentirse preparados para cotizar, gestionar trabajos y cobrar. No debe convertirse en una aplicación contable, un ERP ni un sistema más complicado de lo necesario.

Flujo principal:

```text
Cliente → Cotización (opcional) → Trabajo → Informe (opcional)
        → Cuenta de Cobro → Pagos
```

Reglas que deben mantenerse en todos los módulos:

- No cambiar, omitir ni reinterpretar decisiones aprobadas. Si una decisión pendiente afecta cobros, inventario, impuestos, historial, datos personales u operación offline, señálala y presenta alternativas concretas antes de planificar esa parte.
- Mantener simple la experiencia: resolver el flujo real con los pasos mínimos, reutilizar componentes y evitar módulos, automatizaciones, dependencias o patrones que no tengan una necesidad documentada.
- El inventario es control interno y no bloquea Cotizaciones, Trabajos, Informes ni cobros. Solo el consumo propio registrado en un Trabajo descuenta inventario; el saldo puede ser negativo.
- Mantener una sola cartera de Clientes global al Usuario/Titular. Un Cliente no pertenece a un Perfil Profesional, se puede usar desde varios Perfiles y no se duplica por Perfil. Cada operación/documento identifica su Perfil Profesional.
- El Inventario pertenece globalmente al Usuario/Titular, no a Perfiles Profesionales. Se organiza en Materiales y Herramientas; el usuario crea categorías separadas para cada tipo. No crear categorías predeterminadas; el tipo define comportamiento y la categoría solo organiza.
- Los Métodos de Cobro son globales al Usuario/Titular. El usuario los crea y administra; no insertar métodos predeterminados. Ayudas o ejemplos de interfaz no son datos iniciales. Se usan para pagos recibidos y reembolsos.
- El Consolidado de Cuentas de Cobro es el único mecanismo para agrupar varias Cuentas del mismo Cliente. Conserva intactas las originales y sus valores históricos y gestiona el saldo conjunto. No registrar un Pago directamente sobre varias Cuentas como mecanismo paralelo.
- Un Reembolso es un nuevo movimiento; no modificar ni eliminar el Pago original. Puede ser parcial o total y conserva fecha, valor, Método de Cobro y observación. No crear un estado `REEMBOLSADA` en V1.
- La corrección y anulación de Pagos son funcionalidad V1 con trazabilidad, mediante el mecanismo `payment_adjustments` (valor anterior, valor nuevo, motivo, usuario, dispositivo, operación, fecha; el registro original no se modifica ni elimina) cuyo diseño quedó aprobado en la ficha F7 (`03` §6): `CORRECTION` con absorción `UNASSIGNED`→LIFO y piso de reembolsados; `ANNULMENT` terminal con piso de reembolsados y rechazo de operaciones posteriores. No confundir con el Reembolso, que es un nuevo movimiento de dinero limitado al remanente `efectivo − aplicado − reembolsado`.
- El Informe es opcional y puede ser interno o entregarse al Cliente. No exigirlo para entregar un Trabajo o generar una Cuenta de Cobro. Cotización, Trabajo, Informe y Cuenta de Cobro no deben formar una secuencia rígida.
- El cierre comercial/histórico de la operación ocurre cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente está completamente pagada. No asumir que cada transición congela automáticamente documentos; una garantía posterior no los reabre automáticamente.
- Archivar solo oculta; cualquier documento archivado se puede desarchivar. Nunca eliminar datos generados ni cambiar su historial. Anular una operación es distinto de archivarla.
- Los documentos históricos conservan los valores usados al emitirlos. No recalcularlos silenciosamente con la configuración actual.
- Cotizar, ejecutar y consumir inventario son cantidades/realidades distintas. No generar movimientos por una Cotización o un Informe.
- Un Pago se registra contra una Cuenta individual, un Consolidado, o queda sin asignación cuando el dinero se recibe antes de que exista el documento (vinculado al Cliente). La asociación posterior usa operaciones auditadas (`assign`/`unassign`/`reassign`); si el pago supera el saldo del destino se aplica solo lo necesario y el excedente permanece `UNASSIGNED` dentro del mismo pago. Los pagos registrados contra un documento no pueden superar su saldo ni quedar parcialmente sin aplicar a ese documento; no distribuirlos directamente entre Cuentas originales.
- Todo documento numerable usa un identificador provisional `TIPO-PEND-XXXX` (ej. `COT-PEND-8F3A`) visible en interfaz y apto para PDF provisional; el backend asigna el número oficial `TIPO-AAAAMM-NNN` sobre el mismo documento/`entity_id` al sincronizar. La secuencia oficial es global por Usuario/Titular + tipo + período, independiente del Perfil Profesional. El catálogo de servicios se administra por Perfil Profesional.
- Las retenciones de V1 se calculan sobre los conceptos `SERVICIO`; los `MATERIAL` quedan fuera de la base. La base de cada retención se determina por su configuración `applies_to`; `OTRO` no está incluido ni excluido universalmente — se comporta según esa configuración. Las tasas son configurables; no usar una tasa fija. Los impuestos y cualquier administrador, tipo, porcentaje o valor predeterminado de impuestos no están aprobados como funcionalidad V1; no convertirlos en requisitos ni inventar reglas fiscales.
- Mantener `Cotizar → Ejecutar → Documentar → Cobrar` como principio orientador flexible. Las funciones adicionales deben simplificar el flujo principal y no convertir el producto en un ERP rígido.
- Las decisiones que todavía figuren como **PENDIENTE DE DECISIÓN** no se resuelven mediante suposiciones de implementación.

## 4. Plataformas y tecnologías aprobadas

- **Primera plataforma:** Android, Kotlin, Jetpack Compose y Room, con funcionamiento offline-first. `applicationId` y namespace: `com.cotixgo.app`. Builds con JDK 21 LTS.
- **Backend:** API REST en PHP y base de datos MySQL en Hostinger.
- **Archivos:** almacenamiento en Hostinger, sujeto a los límites de almacenamiento y sincronización que se definan.
- **Fase posterior:** web con React, TypeScript y Next.js, usando el mismo backend/API.

Android debe comunicarse con la API; no conectarse directamente a MySQL ni incluir credenciales de base de datos en la aplicación. Mantén una sola fuente de reglas y cálculos compartidos, con validación en backend y comportamiento consistente en Android y web.

No propongas otra tecnología, proveedor o dependencia importante sin explicar qué problema concreto resuelve, su costo de mantenimiento y por qué el stack aprobado no basta.

## 5. Cómo usar la referencia visual

La imagen PNG guía la identidad visual, jerarquía, navegación y orientación de las pantallas. Usa sus vistas Android como referencia inicial y deja la vista Web para la fase posterior. El diseño no sustituye los contratos funcionales.

Antes de proponer las pantallas, compara la imagen con las reglas escritas y registra las discrepancias. En particular:

- La imagen muestra descuentos en Cotizaciones y Cuentas de Cobro; **V1 no incluye descuentos**.
- La pantalla de pago compartido muestra valores asignados por Cuenta; la decisión aprobada es un documento global con saldo residual conjunto y **sin desglose del pago entre Cuentas originales**.
- Si una pantalla o etiqueta contradice una regla aprobada, conserva la regla aprobada y propone una adaptación visual sencilla. No implementes la contradicción.

## 6. Entregables de planificación

Entrega un plan breve, entendible por el usuario y suficientemente concreto para revisión, con:

1. Fases recomendadas para Android primero, backend Hostinger desde el inicio y web después.
2. Primer flujo vertical de Android recomendado, con módulos, dependencias y criterio de aceptación de cada etapa.
3. Mapa entre pantallas de la referencia visual, reglas de negocio y datos que se necesitan.
4. Responsabilidades de Android, API PHP y MySQL; qué debe funcionar offline y qué se sincroniza.
5. Riesgos o dependencias reales, en especial sincronización, numeración offline, transiciones y documento global de cobro.
6. Lista corta y priorizada de decisiones pendientes que bloquean la primera etapa, con alternativas y recomendación simple. No pedir al usuario que resuelva ahora decisiones que no sean necesarias para empezar.
7. Criterios de aceptación por etapa y forma de revisión de cada entrega.

Separa claramente lo aprobado de lo pendiente. No presentes como decisión del usuario una recomendación tuya.

## 7. Reglas para el trabajo posterior

Después de entregar el plan, espera la aprobación del usuario antes de escribir código o modificar contratos. Cuando la implementación sea autorizada:

- Trabaja en entregas pequeñas y revisables por flujo, no en una construcción masiva de toda la aplicación.
- Antes de cada entrega, indica qué reglas cubre, qué queda pendiente y cómo se comprobará el resultado.
- Prioriza la solución más sencilla que respete los documentos; no agregues funciones “por si acaso”.
- Conserva trazabilidad de cambios de inventario, pagos, anulaciones, archivos y sincronización. No sobrescribas ni borres historial.
- Si encuentras una contradicción o una brecha nueva, detén únicamente el comportamiento afectado, explica el caso en lenguaje claro y ofrece opciones concretas; continúa las partes independientes.

**Resultado esperado ahora:** la hoja de ruta F1–F8 ya está aprobada; el resultado de cada etapa es una entrega implementada, probada y evidenciada en PR sin merge, a la espera de la revisión del titular conforme al protocolo de §8. La hoja de ruta original no asignaba módulos explícitos a F6–F8; la división aprobada el 13/10/2026 (`03` §6) asigna a F6 la Cuenta de Cobro, los Métodos de Cobro y los pagos esenciales, y a F7 el Consolidado de Cuentas, `payment_adjustments` y los Reembolsos. F6 quedó integrada en `main` por el merge `22da7b0` (PR #13); las reglas de producto de F7 quedaron aprobadas el 13/10/2026 en la ficha F7 (`03` §6) y su implementación local quedó completada el mismo día con datos sintéticos (`05` §5.5, `evidence/f7/`); F7 quedó integrada en `main` por el merge `7d5e167` (PR #15); las reglas de producto de F8 (Inventario y Compras) quedaron aprobadas el 14/10/2026 en la ficha F8 (`03` §6) — catálogo de Materiales global por titular, `job_material` históricos intactos con vínculo solo en consumos nuevos, numeración `COM-` en el primer registro aceptado, `ANULADA` terminal con archivado separado, movimientos append-only como fuente de verdad del stock — y su implementación local quedó completada el mismo día con datos sintéticos (`05` §5.6, `evidence/f8/`); F8 quedó integrada en `main` por el merge `fd56941` (PR #18). Además, el 15/10/2026 se aprobó la base de planificación de la protección local en reposo (`03` §6) — SQLCipher + Android Keystore, migración fail-closed — y su implementación quedó autorizada, completada e integrada en `main` por PR #20 (`51fe0cab`) y PR #21 (`7a68205`) (`05` §5.7, `evidence/reposo/`). El titular confirmó Gradle Sync y ejecución desde Android Studio en `main`; se mantiene el uso de datos sintéticos y no se autorizan datos reales ni servicios externos. Herramientas queda pospuesto sin fase ni alcance.

## 8. Metodología permanente de documentación de decisiones

Cuando el usuario apruebe una decisión nueva o modifique una existente:

1. La decisión queda definida y aprobada por el usuario.
2. Devin identifica qué documentación resulta afectada.
3. Actualiza el texto vigente de los documentos correspondientes — no solo agrega notas de prevalencia.
4. Si el cambio es significativo, incrementa la versión del documento.
5. Registra el cambio en el historial del documento: versión, fecha, cambio realizado, motivo y estado de aprobación.
6. Actualiza modelo de datos, arquitectura, contratos, matrices o pruebas si la decisión los afecta.
7. Verifica que no queden contradicciones conocidas entre los documentos vigentes.
8. Solo después la decisión puede utilizarse como referencia para implementar.

La documentación vigente del repositorio es la fuente de referencia para las siguientes etapas; el proyecto no debe depender de recordar conversaciones. No crear un archivo nuevo por cada modificación pequeña: usar documentación vigente + versionado/historial cuando el cambio sea significativo.

### Protocolo permanente de trabajo

```text
Definición → aprobación → documentación → planificación
→ aprobación de etapa → implementación → pruebas
→ evidencia → revisión → merge
```

La autorización para implementar una etapa debe ser explícita y la implementación se limita al alcance aprobado. Si aparece algo fuera del plan: STOP → consultar → aprobar/modificar → documentar → continuar. Si durante una etapa aparece una contradicción entre documentos, una decisión no documentada, una regla ambigua o una decisión técnica que pueda cambiar el comportamiento funcional, detente y consulta antes de implementarla.

## 9. Historial de cambios

| Versión | Fecha | Cambio | Motivo | Estado |
|---|---|---|---|---|
| 2.0 | 30/09/2026 | Guía de implementación para Devin. | Instrucción global para planificación Android-first. | Aprobado. |
| 2.1 | 08/10/2026 | Reglas de §3 actualizadas (retenciones solo `SERVICIO`, numeración provisional/oficial, pago sin Cuenta, corrección de pagos V1, catálogo por Perfil); nueva §8 con metodología permanente de documentación de decisiones y protocolo de trabajo; historial de cambios. | Incorporación de decisiones aprobadas y metodología documental por el titular. | Aprobado. |
| 2.2 | 08/10/2026 | D13-B: aplicación parcial en asignación de pagos con excedente `UNASSIGNED`; D14-C: base de retención por `applies_to`; D15: package `com.cotixgo.app`; D16: JDK 21 LTS para builds Android/Gradle (ambos en §4). | Aprobación de las decisiones pendientes de la etapa V1.1. | Aprobado. |
| 2.3 | 11/10/2026 | Estado y resultado esperado actualizados: la hoja de ruta Android-first F1–F8 ya fue aprobada y la implementación avanza por fases con aprobación explícita por fase (F1 implementada y en revisión). El protocolo permanente de §7–§8 se mantiene sin cambios. | Estado documental alineado con la aprobación del plan y la fase F1. | Aprobado. |
| 2.4 | 11/10/2026 | Estado actualizado: F1 y F2 implementadas e integradas en `main`; F3 en planificación. Registrada la regla aprobada de solo datos sintéticos en desarrollo local hasta verificar la protección de datos en reposo (detalle en `03` §6 y `05` §4). | Estado documental alineado con el avance de fases y la decisión de protección de datos de la ficha F3. | Aprobado. |
| 2.5 | 12/10/2026 | Estado actualizado: F3 implementada y verificada en local (sincronización de las siete entidades, auth, cola durable, conflictos, numeración oficial; ver `05` §5.1) y en revisión del titular vía PR. Sin despliegue remoto ni datos reales. | Estado documental alineado con el cierre de la implementación local de F3 autorizada por el titular. | Aprobado. |
| 2.6 | 12/10/2026 | Estado actualizado: F3 integrada en `main` mediante el merge `9e1a29c` (PR #5). Decisiones de alcance de F4 aprobadas para planificación (Trabajos locales en Android con sincronización; detalle en `03` §6): matriz de transiciones, dirección de ejecución propia, numeración `TR`, materiales descriptivos, actividades `PENDIENTE`/`EN PROCESO`/`COMPLETADA` y extensión de la política de conflictos. F4 pendiente de aprobación de etapa para implementar. | Corrección del estado posterior al merge de F3 y registro de la planificación aprobada de F4. | Aprobado. |
| 2.7 | 12/10/2026 | Estado actualizado: F4 implementada en local tras la aprobación de etapa del titular — Trabajos en Android (Room v5), entidades `job`/`job_quote`/`job_activity`/`job_material` sobre el protocolo de F3, numeración `TR` del lado del servidor, matriz de transiciones y política de conflictos aplicada a las entidades nuevas; verificada con datos sintéticos (e2e backend 58/58, instrumentadas 39/39, unitarias JVM 21/21, `assembleDebug`/`assembleRelease` con JDK 21; evidencia en `evidence/f4/`). F4 en revisión del titular vía PR. Sin despliegue remoto ni datos reales. | Cierre de la implementación local de F4 autorizada por el titular. | Aprobado. |
| 2.8 | 12/10/2026 | Estado actualizado: F4 integrada en `main` por el merge `137de38` (PR #7) tras la revisión del titular (verificación corregida: e2e 65/65, instrumentadas 43/43, JVM 21/21). Gradle Sync dentro de Android Studio con JDK 21 quedó pendiente de verificación manual. Decisiones de alcance de F5 aprobadas para planificación (Informes de Trabajo en Android con sincronización; detalle en `03` §6): agrupación de Trabajos del mismo Cliente y Perfil, solo actividades `COMPLETADA` como ejecución, estados `BORRADOR`/`EMITIDO` con corrección por versión enlazada, numeración `INF`, política de conflictos extendida a entidades de F5 y exclusiones (sin PDF, fotos/archivos ni envío). F5 pendiente de aprobación de etapa para implementar. | Corrección del estado posterior al merge de F4 y registro de la planificación aprobada de F5. | Aprobado. |
| 2.9 | 12/10/2026 | Estado actualizado: F5 implementada en local tras la aprobación de etapa del titular — Informes de Trabajo en Android (Room v6), entidades `report`/`report_job`/`report_version` sobre el protocolo de F3, numeración `INF` del lado del servidor, estados `BORRADOR`/`EMITIDO` con corrección por versión enlazada y snapshot inmutable, agrupación Cliente+Perfil validada en la API, solo actividades `COMPLETADA` como ejecución y política de conflictos aplicada a las entidades nuevas; verificada con datos sintéticos (e2e backend 90/90, instrumentadas 59/59, unitarias JVM 23/23, `assembleDebug`/`assembleRelease` con JDK 21; evidencia en `evidence/f5/`, detalle técnico en `05` §5.3). F5 en revisión del titular vía PR. Sin despliegue remoto ni datos reales. | Cierre de la implementación local de F5 autorizada por el titular. | Aprobado. |
| 3.0 | 13/10/2026 | Estado actualizado: F5 integrada en `main` (merge `b424c3f`, PR #9) y correctivo de edición de Trabajo integrado (merge `0df9c87`, PR #10). Decisiones de alcance de F6 aprobadas para planificación (detalle en `03` §6): Cuenta de Cobro + Métodos de Cobro + pagos esenciales; F7 reservada para Consolidado, `payment_adjustments` y Reembolsos. La implementación de F6 no está autorizada. | Corrección del estado posterior a los merges de F5 y del correctivo, y registro de la planificación aprobada de F6. | Aprobado. |
| 3.1 | 13/10/2026 | Estado actualizado: F6 implementada en local con datos sintéticos tras la autorización de etapa del titular — Cuentas de Cobro (Room v7, numeración `CC` del servidor, asociaciones Trabajos/XOR Cotizaciones del mismo Cliente+Perfil con elegibilidad `APROBADA` validada en servidor, ciclo `PENDIENTE`/`PARCIAL`/`PAGADA`/`CANCELADA` con inmutabilidad tras pagos), Métodos de Cobro por titular y Pagos append-only con eventos de asignación auditados, validación atómica de saldo y excedente `UNASSIGNED`; verificada con e2e backend 121/121, instrumentadas 70/70, JVM 29/29, migración v6→v7 real y `assembleDebug`/`assembleRelease` con JDK 21 (elecciones técnicas en `05` §5.4, evidencia en `evidence/f6/`). F6 en revisión del titular vía PR. F7 sigue sin autorización de implementación. Gradle Sync dentro de Android Studio con JDK 21 sigue pendiente de verificación manual. | Cierre de la implementación local de F6 autorizada por el titular. | Aprobado. |
| 3.2 | 13/10/2026 | Estado actualizado: F6 integrada en `main` (merge `22da7b0`, PR #13). Planificación de F7 aprobada por el titular a partir de `docs/f7-plan-propuesta.md` — Consolidado, `payment_adjustments` (`CORRECTION`/`ANNULMENT`) y Reembolsos con reglas de producto confirmadas (`03` §6, ficha F7); la implementación de F7 no está autorizada y requiere aprobación explícita. La regla de solo datos sintéticos sigue vigente (F7 no habilita datos reales). | Corrección del estado posterior al merge de F6 y registro de la planificación aprobada de F7. | Aprobado. |
| 3.3 | 13/10/2026 | Estado actualizado: F7 implementada en local con datos sintéticos tras la autorización de etapa del titular — Consolidado de Cuentas (membresía N:M histórica, elegibilidad/doble-vínculo validados con datos persistidos, numeración `CONS-` idempotente), `payment_adjustments` (`CORRECTION` LIFO con piso de reembolsados, `ANNULMENT` terminal) y Reembolsos sobre la reserva compartida, Room v8 con migración v7→v8, destino genérico de asignación y UI de Consolidados/ajustes/Reembolsos (elecciones técnicas en `05` §5.5, evidencia en `evidence/f7/`). F7 en revisión del titular vía PR. Sin despliegue remoto ni datos reales; Gradle Sync dentro de Android Studio con JDK 21 sigue pendiente de verificación manual. | Cierre de la implementación local de F7 autorizada por el titular. | Aprobado. |
| 3.4 | 14/10/2026 | Estado actualizado: F7 integrada en `main` por el merge `7d5e167` (PR #15) tras las dos revisiones independientes del titular y sus correcciones (serialización de membresías con `READ COMMITTED` y orden canónico de bloqueos, identidad canónica `id`/`userId`/`createdAt`, errores visibles en acciones F7, barrera de concurrencia real y evidencia precisa — e2e 195/195, race 27/27, instrumentadas 81/81 por `am instrument`; detalle en `05` §5.5 y `evidence/f7/`). El encabezado y §7 actualizados al estado posterior al merge. F8 sigue sin alcance aprobado ni implementación iniciada; la regla de solo datos sintéticos permanece vigente (la protección local en reposo sigue pendiente); Gradle Sync dentro de Android Studio con JDK 21 sigue pendiente de verificación manual. | Corrección del estado posterior al merge de F7. | Aprobado. |
| 3.5 | 14/10/2026 | Estado actualizado: la planificación de F8 (Inventario y Compras) quedó aprobada por el titular — nueva ficha F8 en `03` §6 con mínimos de Material/Compra, numeración `COM-`, `ANULADA` terminal, conflictos (documental para `Material`/`Compra`, append-only para `inventory_movement`), `SOBRANTE` manual, moneda global y saldo proyectado atómico; Herramientas pospuesto sin fase ni alcance. El encabezado y §7 actualizados; `02`, `04`, `05` y `06` registran las mismas decisiones. La implementación de F8 no está autorizada ni iniciada; la regla de solo datos sintéticos permanece vigente. | Registro de la planificación de F8 aprobada por el titular. | Aprobado. |
| 3.6 | 14/10/2026 | Estado actualizado: F8 implementada en local con datos sintéticos tras la autorización de etapa del titular — Inventario y Compras en Android (Room v9) y API PHP/MariaDB local: catálogo global de Materiales, Compras `BORRADOR`/`CONFIRMADA`/`ANULADA` con numeración `COM-` idempotente y total/moneda derivados por el servidor, movimientos append-only como fuente de verdad con saldo proyectado atómico por material+condición (negativo permitido), movimientos de Compra derivados por el servidor dentro de su transacción, ajustes/sobrantes manuales con nota y consumo explícito vinculado en `job_material` nuevos (detalle técnico en `05` §5.6, evidencia en `evidence/f8/`). F8 en revisión del titular vía PR. Herramientas sigue pospuesto sin fase ni alcance; la regla de solo datos sintéticos permanece vigente; Gradle Sync dentro de Android Studio con JDK 21 sigue pendiente de verificación manual. | Cierre de la implementación local de F8 autorizada por el titular. | Aprobado. |
| 3.7 | 15/10/2026 | Estado actualizado: F8 integrada en `main` por el merge `fd56941` (PR #18); encabezado y §7 corregidos al estado posterior al merge. El titular confirmó que CotixGo compila desde Android Studio en su estado actual — la verificación IDE tras integrar dependencias nuevas (p. ej. SQLCipher) queda como criterio de aceptación de implementación. Registrada la base de planificación aprobada de protección local en reposo (`03` §6): SQLCipher + Android Keystore, bloqueo de interfaz sin binding de clave, token fuera de `security-crypto`, migración fail-closed con limpieza bloqueante de archivos claros; versión exacta pendiente de spike; **implementación no autorizada**; regla de solo datos sintéticos vigente. | Corrección del estado posterior al merge de F8 y registro de la planificación de protección en reposo aprobada. | Aprobado. |
| 3.8 | 15/10/2026 | Estado actualizado: protección local en reposo implementada en la rama `impl/proteccion-local-reposo` tras autorización del titular (spike previo fijó `sqlcipher-android:4.17.0` + `androidx.sqlite:2.6.2` compatibles con Room 2.6.1/`compileSdk 35`; Gradle Sync/Assemble del spike confirmados por el titular en Android Studio). Encabezado y §7 actualizados; la verificación IDE sobre la rama de implementación sigue pendiente como criterio de aceptación y la regla de solo datos sintéticos permanece vigente. | Corrección del estado tras la implementación autorizada de protección en reposo. | Aprobado. |

|| 3.9 | 16/10/2026 | Corrección de seguridad M1 sobre protección en reposo: la app deja de regenerar `dbkey.bin` cuando existe una base cifrada ya migrada y falta el blob; el flujo de producción bloquea con `key_unavailable` y la acción informada del usuario es la única vía de limpieza/resync. | Evitar cierres en bucle y preservar el fail-closed informado ante pérdida de clave. | Aprobado. |
| 4.0 | 15/10/2026 | Estado actualizado: protección local en reposo integrada en `main` (PR #20, merge `51fe0cab`, y corrección M1 PR #21, merge `7a68205`). El titular confirmó Gradle Sync y ejecución en Android Studio sobre `main` en Xiaomi físico. Se conserva el límite de datos sintéticos; no se autoriza uso de datos reales ni servicios externos. | Registro factual de la integración y verificación manual del titular, sin decisiones de producto nuevas. | Aprobado. |

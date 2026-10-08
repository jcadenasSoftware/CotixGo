# CotixGo — Instrucción global y planificación inicial para Devin

**Versión:** 2.0  
**Estado:** Preparado para iniciar la planificación; no autoriza todavía la implementación de código.  
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
- Las reglas funcionales expresamente aprobadas para corregir o anular Pagos se mantienen; cualquier mecanismo técnico adicional o detalle no aprobado queda pendiente y no debe convertirse en alcance V1 por inferencia.
- El Informe es opcional y puede ser interno o entregarse al Cliente. No exigirlo para entregar un Trabajo o generar una Cuenta de Cobro. Cotización, Trabajo, Informe y Cuenta de Cobro no deben formar una secuencia rígida.
- El cierre comercial/histórico de la operación ocurre cuando el Trabajo fue entregado y la Cuenta de Cobro correspondiente está completamente pagada. No asumir que cada transición congela automáticamente documentos; una garantía posterior no los reabre automáticamente.
- Archivar solo oculta; cualquier documento archivado se puede desarchivar. Nunca eliminar datos generados ni cambiar su historial. Anular una operación es distinto de archivarla.
- Los documentos históricos conservan los valores usados al emitirlos. No recalcularlos silenciosamente con la configuración actual.
- Cotizar, ejecutar y consumir inventario son cantidades/realidades distintas. No generar movimientos por una Cotización o un Informe.
- Un Pago se registra contra una Cuenta individual o un Consolidado. Los pagos pueden ser parciales, pero no superar el saldo del documento asociado; no distribuirlos directamente entre Cuentas originales.
- Las retenciones son las reglas aprobadas de V1. Los impuestos y cualquier administrador, tipo, porcentaje o valor predeterminado de impuestos no están aprobados como funcionalidad V1; no convertirlos en requisitos ni inventar reglas fiscales.
- Mantener `Cotizar → Ejecutar → Documentar → Cobrar` como principio orientador flexible. Las funciones adicionales deben simplificar el flujo principal y no convertir el producto en un ERP rígido.
- Las decisiones que todavía figuren como **PENDIENTE DE DECISIÓN** no se resuelven mediante suposiciones de implementación.

## 4. Plataformas y tecnologías aprobadas

- **Primera plataforma:** Android, Kotlin, Jetpack Compose y Room, con funcionamiento offline-first.
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

**Resultado esperado ahora:** un plan Android-first de CotixGo que el usuario pueda aprobar o corregir antes de iniciar la programación.

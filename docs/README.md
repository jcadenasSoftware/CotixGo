# Documentación de CotixGo

Esta carpeta contiene la fuente funcional para definir el producto antes de iniciar su implementación.

## Orden de lectura y autoridad

1. [`01-reglas-de-negocio-v0.2.md`](01-reglas-de-negocio-v0.2.md) — base de reglas aprobada.
2. [`03-anexo-decisiones-aprobadas-y-brechas.md`](03-anexo-decisiones-aprobadas-y-brechas.md) — decisiones aprobadas después de v0.2 y revisión de consistencia. Si una regla de v0.2 contradice una decisión explícita del anexo, prevalece el anexo.
3. [`04-alcance-y-modulos-v1.md`](04-alcance-y-modulos-v1.md) — alcance funcional inicial y responsabilidades de los módulos.
4. [`05-arquitectura-y-tecnologias.md`](05-arquitectura-y-tecnologias.md) — plataformas, stack aprobado y requisitos de arquitectura.
5. [`02-modelo-de-datos.md`](02-modelo-de-datos.md) y [`06-tipos-y-contratos-de-datos.md`](06-tipos-y-contratos-de-datos.md) — modelo conceptual y convenciones de tipos; aún requieren cierre de pendientes antes del contrato físico.
6. [`07-guia-de-implementacion-para-devin.md`](07-guia-de-implementacion-para-devin.md) — instrucciones de lectura, precedencia, límites y entregables para la etapa de implementación.
7. [`08-matriz-decision-estados-y-calculos.md`](08-matriz-decision-estados-y-calculos.md) — registro de decisiones por cerrar para estados, transiciones y cálculo monetario.

[`01-reglas-de-negocio.md`](01-reglas-de-negocio.md) es la versión fundacional v0.1 y se conserva como antecedente. No reemplaza a v0.2.

## Estado

La especificación funcional tiene una base aprobada y un anexo de decisiones aprobadas posteriores. El alcance y la arquitectura están documentados en sus respectivos archivos. El modelo y los tipos de datos siguen en nivel conceptual. Las decisiones que todavía no están tomadas se marcan explícitamente como **PENDIENTE DE DECISIÓN**; no deben resolverse por inferencia durante la implementación.

Todavía no se autoriza aquí la implementación de código, migraciones ni creación de tablas definitivas.

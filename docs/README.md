# Documentación de CotixGo

Esta carpeta contiene la fuente de verdad funcional y técnica de CotixGo. El paquete está listo para que Devin prepare la planificación inicial; la implementación de código comienza después de que el usuario revise y apruebe ese plan.

## Orden de lectura y autoridad

1. [`01-reglas-de-negocio-v0.2.md`](01-reglas-de-negocio-v0.2.md) — base de reglas aprobada.
2. [`03-anexo-decisiones-aprobadas-y-brechas.md`](03-anexo-decisiones-aprobadas-y-brechas.md) — decisiones aprobadas después de v0.2 y pendientes actuales. Si una regla de v0.2 contradice una decisión explícita del anexo, prevalece el anexo.
3. [`04-alcance-y-modulos-v1.md`](04-alcance-y-modulos-v1.md) — alcance funcional y responsabilidades de los módulos.
4. [`05-arquitectura-y-tecnologias.md`](05-arquitectura-y-tecnologias.md) — plataformas, stack aprobado y requisitos de arquitectura.
5. [`08-matriz-decision-estados-y-calculos.md`](08-matriz-decision-estados-y-calculos.md) — decisiones consolidadas sobre estados, pagos y cálculos.
6. [`02-modelo-de-datos.md`](02-modelo-de-datos.md) y [`06-tipos-y-contratos-de-datos.md`](06-tipos-y-contratos-de-datos.md) — modelo conceptual y convenciones de tipos; no son un esquema físico aprobado.
7. [`07-guia-de-implementacion-para-devin.md`](07-guia-de-implementacion-para-devin.md) — instrucción global para planificar Android primero y preparar el trabajo posterior.
8. [`CotixGo_ Gestión Inteligente de Servicios.png`](CotixGo_%20Gesti%C3%B3n%20Inteligente%20de%20Servicios.png) — referencia visual para la identidad y las pantallas. Si contradice una regla aprobada, prevalece la regla escrita.

[`01-reglas-de-negocio.md`](01-reglas-de-negocio.md) es la versión fundacional v0.1 y se conserva como antecedente. No reemplaza a v0.2.

## Estado

La especificación funcional tiene una base aprobada y un anexo de decisiones posteriores. Android se planifica como primera plataforma, con backend PHP/MySQL en Hostinger desde el inicio y web en una fase posterior. El modelo y los tipos de datos siguen en nivel conceptual. Las decisiones que todavía no están tomadas se marcan explícitamente como **PENDIENTE DE DECISIÓN**; no deben resolverse por inferencia.

La tarea inicial de Devin es presentar un plan revisable; no crear código, migraciones ni tablas definitivas hasta que el usuario apruebe el plan y autorice la implementación.

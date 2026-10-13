# Ficha de planificación F7 — PROPUESTA para revisión del titular

**Estado: PROPUESTA — PENDIENTE DE APROBACIÓN.** Nada de lo aquí propuesto es decisión aprobada salvo lo citado expresamente como regla vigente (`03` §6, `02`, `01` v0.2, `04`, `08`). Los puntos marcados **[PROPUESTA]** requieren aprobación explícita antes de documentarse como decisión o implementarse. Preparada el 13/10/2026 sobre `main` @ `22da7b007195427d25c22ecff3f4bf85867c8c5d` (merge PR #13).

---

## 1. Estado actual y dependencias

**F6 integrada en `main`** (merge `22da7b0`, PR #13):

- `collection_account`: ciclo `PENDIENTE`/`PARCIAL`/`PAGADA`/`CANCELADA`, líneas `SERVICIO`/`MATERIAL`/`OTRO` con motor de cálculo F2, snapshots, numeración `CC`, XOR Trabajos/Cotizaciones del mismo Cliente+Perfil, elegibilidad `APROBADA`, inmutabilidad tras pagos (salvo `archived`), `archived` como única mutación permitida.
- `collection_method`: por titular, `nombre`/`detalle`/`archivado`.
- `payment` + `payment_assignments`: append-only, eventos `assign`/`unassign`/`reassign` con motivo y auditoría, `UNASSIGNED`/`PARTIAL_ASSIGNED`/`ASSIGNED`, saldo atómico `FOR UPDATE` en servidor, excedente `UNASSIGNED`, `IMMUTABLE_FINANCIAL`, dedupe por `entity_id` + `operation_id`.
- Sincronización F3–F6: cola durable con dependencias, reintentos, `STALE_BASE_VERSION`, `sync_conflicts` con resolución explícita, pull por cursor `server_seq`, `processed_operations`, `audit_events`.
- Backend: `entities`/`entities_meta`/`official_numbers`/`audit_events`/`processed_operations` (modelo genérico); handlers por tipo en `sync.php`/`domain.php`.
- Android: Room v7, pestaña Cobros, política de conflictos implementada para entidades documentales.

**Dependencias de F7 sobre F6:** los ajustes/anulaciones y reembolsos operan sobre `payment` y sus asignaciones existentes; el Consolidado referencia `collection_account` existentes y recibe asignaciones de `payment` existentes. F7 no redefine nada de F6: lo extiende con nuevos tipos de destino y nuevos eventos.

**Limitación vigente aprobada:** F6 no es apta para datos reales antes de F7 (`03` §6, `04` §2.10, `08` §consolidado). F7 resolvería la **limitación funcional** que motivó esa restricción — la ausencia del mecanismo de corrección/anulación de pagos y reembolsos — pero **no autoriza datos personales ni cuentas reales**: la regla de solo datos sintéticos permanece vigente hasta que la protección local en reposo esté definida, implementada y verificada (`03` §4.8, pendiente independiente fuera del alcance de F7).

## 2. Alcance

### Incluido (asignado a F7 por la división aprobada, `03` §6)

1. **Consolidado de Cuentas de Cobro** (`ConsolidatedCollection`, `02` §25): documento global que agrupa ≥2 Cuentas del mismo Cliente, conserva intactas las originales con valores históricos, administra saldo conjunto y admite pagos contra ese saldo. Único mecanismo de agrupación; no existe pago directo sobre varias Cuentas.
2. **`payment_adjustments`**: corrección (`CORRECTION`) y anulación (`ANNULMENT`) de pagos con trazabilidad completa (valor anterior, valor nuevo, motivo, usuario, dispositivo, operación de origen, fecha). El Pago original no se modifica ni elimina.
3. **Reembolsos** (`Refund`, `02` §25): nuevo movimiento de dinero ligado al Pago que devuelve; parcial o total; fecha, valor, Método de Cobro, observación. Sin estado `REEMBOLSADA` en V1.
4. Interacción con F6: asignación de pagos contra Consolidado reutilizando `assign`/`unassign`/`reassign` con motivo, auditoría y validación atómica de saldo; recálculo de documentos ante ajustes; prevención de doble cobro/doble contabilización.
5. Política de sincronización/conflictos para las nuevas entidades (pendiente de aprobación — propuesta en §11).

### Excluido (reglas vigentes, no se amplía)

- Impuestos, descuentos, tratamiento fiscal (`03` §6, `06` §3).
- PDF/exportación, envío por correo o enlace, fotografías/archivos (`03` §4.6 sigue pendiente — no es de F7).
- Inventario, Compras, Herramientas, cliente Web, pagos directos sobre varias Cuentas originales.
- Distribución automática de un pago de Consolidado entre las Cuentas originales (aprobado: el pago reduce el saldo conjunto, no se reparte).
- Protección de datos en reposo y cualquier uso de datos reales (`03` §4.8 — pendiente independiente).
- Hostinger, MySQL remota, correo real, despliegues (ver §12).

## 3. Flujos principales y reglas de negocio

### 3.1 Consolidado

- Crear Consolidado: elegir Cliente → seleccionar Cuentas elegibles → confirmar. **[PROPUESTA §11-A: elegibilidad = no `CANCELADA`, con `balance > 0`, misma moneda.]**
- El Consolidado nace con `total conjunto` = suma de saldos pendientes de las Cuentas incluidas (valores históricos) y `saldo residual` igual al total. **[PROPUESTA §11-B: ciclo `PENDIENTE`/`PARCIAL`/`PAGADA`/`CANCELADA` espejo de Cuenta, derivado por servidor.]**
- Registrar pago contra Consolidado: igual que contra Cuenta individual — tope en saldo, excedente `UNASSIGNED`, `assign`/`unassign`/`reassign` con motivo (regla aprobada §2.4: "los Pagos contra el Consolidado reducen su saldo conjunto sin modificar ni distribuirse entre las Cuentas originales").
- Doble cobro: una Cuenta dentro de un Consolidado no cancelado no puede recibir asignaciones directas ni pertenecer a otro Consolidado activo; cancelar el Consolidado libera sus Cuentas. **[PROPUESTA §11-C — pendiente residual de `03` §4.4.]**
- Anular un Pago o unassign aplicado a Consolidado: el saldo del Consolidado se recalcula; las Cuentas originales no cambian (regla aprobada).

### 3.2 `payment_adjustments` (corrección / anulación)

- Corregir importe (`CORRECTION`): evento con valor anterior → nuevo; el monto efectivo del Pago cambia y el documento asociado recalcula (regla aprobada `02` §25, `04` §2.10). **[PROPUESTA §11-D, refinada:**
  - **Alza:** el incremento queda íntegro en la parte libre (`UNASSIGNED`) del Pago hasta una asignación explícita posterior — nunca se distribuye automáticamente (coherente con la regla aprobada de no reparto entre Cuentas).
  - **Baja:** la reducción consume primero la parte libre `efectivo − aplicado − reembolsado` y luego revierte las asignaciones más recientes (LIFO), atómicamente `FOR UPDATE`.
  - **Piso:** se rechaza una corrección que deje el monto efectivo por debajo del total ya reembolsado (`nuevoEfectivo < reembolsadoAcumulado` → `REJECTED`), porque el reembolso es un movimiento de dinero irreversible y ninguna corrección puede descontar dinero ya devuelto. Alternativa documentada en §11-D.]**
- Anular (`ANNULMENT`): el Pago queda en historial pero deja de contar; sus importes aplicados dejan de afectar los documentos asociados (regla aprobada). **[PROPUESTA §11-E, refinada:**
  - **Efecto e invariante:** la anulación libera atómicamente las asignaciones vigentes y fija el monto efectivo en el total ya reembolsado (`efectivo := reembolsadoAcumulado`; 0 sin reembolsos) — no puede quedar por debajo porque un reembolso no se puede revertir (capacidad no aprobada). La invariante `aplicadoVigente + reembolsadoAcumulado ≤ montoEfectivo` se conserva siempre (tras la anulación: 0 aplicado + reembolsado = efectivo).
  - **Terminalidad:** `ANNULMENT` es terminal y solo puede registrarse **una vez** por Pago. Tras una anulación aceptada, el Pago queda cerrado: se rechazan nuevas asignaciones, reasignaciones, correcciones y reembolsos sobre él.
  - **Idempotencia:** el reintento de la misma operación devuelve el resultado memorizado (`processed_operations`); una segunda operación de anulación **distinta** sobre el mismo Pago se rechaza.
  - *Ejemplo sintético:* pago 500 → 300 aplicado a C1 + `Refund` 200 aceptado (parte libre 0). `ANNULMENT` aceptado → libera los 300 de C1 (vuelve `PENDIENTE`, balance completo) y fija efectivo 200 (200 reembolsado = 200 efectivo ✓). Intentos posteriores: `CORRECTION` a 600 → REJECTED; `reassign` sobre el Pago → REJECTED; `Refund` 50 → REJECTED; segunda `ANNULMENT` distinta → REJECTED; reintento del mismo `ANNULMENT` → resultado memorizado.
  - Alternativa: rechazar la anulación cuando existen reembolsos — ver §11-E.]**
- Todo ajuste requiere motivo; append-only, idempotente, auditado — igual que los eventos F6.

### 3.3 Reembolso

- Reembolsar un Pago: nuevo movimiento de dinero ligado al Pago; parcial o total; fecha, valor, Método de Cobro, observación; el Pago original intacto (regla aprobada).
- **[PROPUESTA §11-F, refinada — disponible para reembolso:**
  `disponibleReembolso(pago) = montoEfectivo − aplicadoVigente − reembolsadoAcumulado` (≥ 0). Es la misma parte libre que alimenta nuevas asignaciones: **una sola reserva, dos consumidores** — asignar o reembolsar consumen del mismo remanente, por lo que una asignación nueva también se valida contra `efectivo − aplicado − reembolsado` (extensión propuesta de la regla F6 de tope de saldo; requiere aprobación).
  - Un Reembolso ≤ `disponibleReembolso`. Para reembolsar dinero aplicado se exige `unassign`/`reassign` auditado previo que lo devuelva al remanente, y después el Reembolso. Así nunca hay dinero contado dos veces ni saldos negativos.
  - Un Reembolso es irreversible (no hay capacidad aprobada de revertirlo); por eso es piso del monto efectivo en correcciones y anulaciones (§11-D/E).]** Alternativa documentada en §11-F.
- Trazabilidad: el Reembolso referencia `payment_id`; el detalle del Pago muestra su historial de asignaciones, ajustes y reembolsos.

## 4. Modelo conceptual y relaciones (sin contratos físicos)

```
ConsolidatedCollection 1 ─── N CollectionAccount   (pertenencia con orden; solo mismo Cliente)
Payment ─── 0..1 documento destino: CollectionAccount | ConsolidatedCollection   (XOR)
PaymentAssignment:  assign/unassign/reassign sobre (Payment → Cuenta | Consolidado)
Payment 1 ─── N PaymentAdjustment (CORRECTION | ANNULMENT)
Payment 1 ─── N Refund
```

- `ConsolidatedCollection` (conceptual `02` §25 + propuestas): `id`, `userId`, `clientId`, lista ordenada de Cuentas, `total`, `balance` (saldo residual conjunto), moneda, estado derivado, `issuedAt`, número provisional/oficial **[PROPUESTA §11-G]**, `archived`, timestamps, `userId`.
- `PaymentAdjustment`: `id`, `paymentId`, `type` (`CORRECTION`|`ANNULMENT`), `previousAmount`, `newAmount`, `reason`, `userId`, `deviceId`, `operationId`, `createdAt`.
- `Refund`: `id`, `paymentId`, `clientId`, `amount`, `methodId`, `observation`, `createdAt`, `userId`.
- En `Payment`, el destino gana un discriminador conceptual `targetType`: `ACCOUNT` | `CONSOLIDATED` | (sin asignar) — extensión del modelo ya aprobado en `02` §24 ("Cuenta individual o Consolidado").

## 5. Reglas de saldo y doble contabilización

- Unidad de verdad: los importes aplicados viven en eventos de asignación; los documentos (`collection_account`, `consolidated_collection`) muestran `appliedAmount`/`balance` derivados que **solo el servidor escribe** (mismo patrón F6, `FOR UPDATE`).
- Nunca dinero aplicado a dos documentos a la vez (regla aprobada) → se extiende: una Cuenta en Consolidado activo no acepta asignación directa; un evento a Consolidado no toca saldos de las Cuentas originales.
- `ANNULMENT`/`CORRECTION` recomputan atómicamente: importe efectivo del Pago → aplicaciones vigentes → `appliedAmount`/`balance`/estado de cada documento afectado → `unassignedAmount`/`status` del Pago.
- **[PROPUESTA §11-F/D] Invariante financiera única:** `aplicadoVigente + reembolsadoAcumulado ≤ montoEfectivo`, equivalentemente `parteLibre = efectivo − aplicado − reembolsado ≥ 0`. Asignaciones nuevas, reembolsos, correcciones a la baja y anulaciones se validan contra esa invariante de forma atómica; el reembolso es irreversible y por tanto piso del efectivo.
- Ejemplos numéricos coherentes **[PROPUESTA §11-D/E/F]**: pago 500 → 300 aplicado a C1, 200 libres. `Refund` 200 → OK (libre 0). `Refund` 50 → REJECTED (libre 0). `CORRECTION` a 450 → consume libre 0 y libera 50 de la asignación más reciente → C1 applied 250, balance recalcula (250+200=450=efectivo ✓). `CORRECTION` a 150 → REJECTED (`150 < reembolsado 200`). `CORRECTION` a 600 → +100 queda `UNASSIGNED` hasta asignación explícita. `ANNULMENT` sobre ese pago (200 reembolsados acumulados) → libera las asignaciones vigentes (250) y fija el efectivo en 200; documentos recomputan (200 reembolsado = 200 efectivo ✓, nada aplicado). Tras la anulación: `CORRECTION`/`assign`/`reassign`/`Refund` adicionales → REJECTED (pago cerrado); reintento del mismo `ANNULMENT` → resultado memorizado; segunda anulación distinta → REJECTED.
- Todos los montos en centavos/`DECIMAL`, redondeo por línea, moneda acompañando cada importe (`06` vigente).

## 6. Idempotencia, auditoría, sincronización, conflictos y offline

- **Idempotencia:** igual que F6 — dedupe por `operation_id` en `processed_operations` (resultado memorizado) y por `entity_id` con `IMMUTABLE_FINANCIAL` en eventos financieros; payload inmutable por operación; replay seguro. **[PROPUESTA §11-E]** `ANNULMENT` es terminal: una sola anulación por Pago — reintento de la misma operación → resultado memorizado; segunda anulación distinta → rechazo; y cualquier operación financiera posterior sobre un Pago anulado (`assign`/`reassign`/`CORRECTION`/`Refund`) → rechazo.
- **Auditoría:** `audit_events` con `CONSOLIDATED_*`, `PAYMENT_ADJUST`, `REFUND` (+ `assign`/`unassign`/`reassign` con `targetType`); cada evento guarda motivo, usuario, dispositivo y operación de origen (trazabilidad aprobada).
- **Sync:** mismos endpoints `/sync/push` y `/sync/pull?cursor=`; nuevas entidades `consolidated_collection`, `payment_adjustment`, `refund`; la cola retiene eventos hasta resolver dependencias (mismo mecanismo `pushableNow`); pull entrega documentos completos.
- **Conflictos — [PROPUESTA §11-H]:** `consolidated_collection` = entidad documental → política F3–F6 (detección por versión base, ambas copias, resolución del usuario). `payment_adjustment` y `refund` = eventos financieros append-only → sin resolución por sobrescritura ni reloj local; validación atómica en servidor. Es la extensión directa de la política aprobada para F6; queda pendiente de aprobación (`03` §4.3).
- **Offline:** creación local de Consolidado/ajustes/reembolsos con numeración provisional y encolado durable; validaciones espejo en Android y autoritativas en servidor; reintentos sin descarte.

## 7. Secuencia sugerida (Android-first) y primer flujo vertical

1. **Vertical 1 — Consolidado extremo a extremo:** modelo Room (migración v7→v8) + repositorio + UI mínima (crear desde lista de Cuentas del Cliente, detalle con saldo conjunto) + handler servidor + `assign` con `targetType=CONSOLIDATED` + pull/upsert + pruebas. *Primer flujo vertical recomendado:* seleccionar 2 Cuentas de un Cliente → crear Consolidado offline → sincronizar → asignar pago `UNASSIGNED` contra su saldo → ver `PARCIAL`/`PAGADA` derivado y remanente.
2. **Vertical 2 — `payment_adjustments`:** eventos `CORRECTION`/`ANNULMENT` con motivo, recálculo atómico de documentos, historial en detalle del Pago.
3. **Vertical 3 — Reembolsos:** registro ligado al Pago, restricción a remanente [§11-F], historial consolidado del Pago.
4. Cierre: resolución de conflictos del Consolidado, casos borde (anulación con asignaciones, corrección por debajo de lo aplicado, consolidado con pago y cancelación), E2E completo, migración con datos+cola, evidencia.

## 8. Responsabilidades

- **Android (Kotlin/Room/Compose):** entidades v8, DAOs, repositorios, validaciones espejo, cola/payloads/conflictos, UI (Crear/detalle Consolidado, ajustar/anular Pago con motivo, reembolsar, historial del Pago).
- **API PHP:** handlers por tipo en `sync.php`/`domain.php`; validación autoritativa con datos persistidos (elegibilidad de Cuentas, mismo Cliente, anti-doble-vínculo Consolidado, topes de saldo); saldo atómico `FOR UPDATE`; derivación de estados; numeración oficial idempotente del Consolidado [§11-G]; dedupe/`IMMUTABLE_FINANCIAL`; `audit_events`.
- **MySQL/MariaDB local:** `entities`/`entities_meta`/`official_numbers`/`audit_events`/`processed_operations` (modelo genérico vigente — sin nuevas tablas de dominio en servidor, igual que F6).

## 9. Criterios de aceptación y revisión

- E2E backend nuevos verdes (crear Consolidado, doble-vínculo rechazado, asignación con tope+excedente, corrección al alza → `UNASSIGNED`, corrección a la baja LIFO con recompuesto, corrección por debajo de lo reembolsado → REJECTED, anulación con liberación de asignaciones y piso de reembolsos, terminalidad de la anulación (segunda anulación distinta → REJECTED; reintento → resultado memorizado; `assign`/`reassign`/`CORRECTION`/`Refund` post-anulación → REJECTED), reembolso dentro/fuera del remanente `efectivo − aplicado − reembolsado`, asignación validada contra el mismo remanente, idempotencia, aislamiento por titular) + regresión F3–F6 completa.
- Instrumentadas + JVM verdes; migración v7→v8 con datos y cola; `assembleDebug`/`assembleRelease` JDK 21.
- Evidencia en `docs/evidence/f7/`; docs e historiales conforme a `07` §8 separando decisiones aprobadas de elecciones técnicas.
- Revisión por etapa: PR abierto sin fusionar → revisión independiente → correcciones → nueva revisión → merge autorizado. **Nada se implementa sin aprobación previa de esta ficha y de la etapa.**

## 10. Riesgos

- **R1 (alto):** recálculo ante `ANNULMENT`/`CORRECTION` con múltiples asignaciones — mitigado por validación atómica servidor + LIFO propuesto + E2E de casos borde. La terminalidad de `ANNULMENT` (rechazo de operaciones financieras posteriores y de una segunda anulación distinta, con reintento memorizado) cierra el hueco de mutaciones post-cierre [PROPUESTA §11-E].
- **R2 (medio):** deadlocks ABBA en reasignaciones inversas concurrentes (observación previa) — reintento de cola; monitorizar en E2E.
- **R3 (medio):** doble vínculo Cuenta↔Consolidado bajo carrera multi-dispositivo — anti-doble-vínculo validado en servidor con datos persistidos + FOR UPDATE.
- **R4 (bajo):** deriva doc `02` §24 enum de estado del Pago (omite `PARTIAL_ASSIGNED` implementado en F6) — corregir en la pasada documental de F7.
- **R5:** migración v7→v8 debe preservar cola + datos — cubierta por `MigrationTest` con datos reales sintéticos.

## 11. Decisiones pendientes — alternativas y recomendación (todo **[PROPUESTA]**)

- **§11-A Elegibilidad de Cuentas para Consolidado.** A1: no `CANCELADA` con `balance > 0` (permite `PENDIENTE` y `PARCIAL`). A2: solo `PENDIENTE`. **Recomendada A1** — permite consolidar saldos vivos incluidos los parcialmente pagados; el Consolidado cubre solo el pendiente.
- **§11-B Ciclo del Consolidado.** B1: espejo `PENDIENTE`/`PARCIAL`/`PAGADA`/`CANCELADA` derivado por servidor, edición solo `PENDIENTE` sin pagos, cancelación solo sin pagos aplicados (libera Cuentas). B2: sin ciclo (solo estado informativo). **Recomendada B1** — consistencia con Cuenta y con la liberación de vínculos aprobada.
- **§11-C Doble cobro.** C1: Cuenta en Consolidado activo bloquea asignación directa y segundo Consolidado; cancelar libera. C2: permitir ambos y confiar en topes (riesgo de doble contabilización). **Recomendada C1.**
- **§11-D Absorción y límites en `CORRECTION`.** Al alza: el incremento queda `UNASSIGNED` hasta asignación explícita (sin reparto automático). A la baja — D1: resta la parte libre primero, luego asignaciones más recientes (LIFO) con recompuesto de documentos; se **rechaza** si `nuevoEfectivo < reembolsadoAcumulado` (un reembolso no se puede revertir). D2: proporcional sobre asignaciones. D3: rechazar si reduce por debajo de lo aplicado (exige unassign manual). **Recomendada D1 con el piso de reembolsados** — automática, determinista y preserva la invariante; D3 sigue disponible como alternativa más conservadora para el tramo aplicado.
- **§11-E `ANNULMENT`.** E1: libera atómicamente sus asignaciones vigentes y fija el monto efectivo en `reembolsadoAcumulado` (0 si no hay reembolsos) — un pago con reembolsos sí puede anularse, pero su efectivo no puede bajar del dinero ya devuelto, que es irreversible. `ANNULMENT` es **terminal** (una sola vez por Pago): tras aceptarse se rechazan `assign`/`reassign`/`CORRECTION`/`Refund` posteriores y una segunda anulación distinta; el reintento de la misma operación devuelve el resultado memorizado. E2: exige unassign previo a la anulación. E3: rechazar la anulación cuando existen reembolsos (deja esos pagos sin anulación para siempre). **Recomendada E1 con terminalidad** — preserva la invariante `aplicado + reembolsado ≤ efectivo` sin inventar reversión de reembolsos y coincide con "deja de contar en el cálculo" ya aprobado; E3 es la alternativa más simple si se prefiere prohibir el caso.
- **§11-F Alcance y disponible del Reembolso.** F1: Reembolso ≤ `efectivo − aplicado − reembolsado`; una sola reserva libre alimenta asignaciones y reembolsos (asignar también valida contra ese remanente — extensión propuesta de la regla F6); reembolsar lo aplicado exige liberar antes con evento auditado. F2: puede cubrir también lo aplicado, liberándolo automáticamente. **Recomendada F1** — cero doble contabilización, reutiliza operaciones aprobadas; F2 añade un segundo mecanismo implícito de liberación.
- **§11-G Numeración del Consolidado.** G1: provisional `CONS-PEND-XXXX` → oficial `CONS-AAAAMM-NNN` mismo `entity_id` idempotente en primera aceptación (espejo `CC`). G2: sin numeración propia. **Recomendada G1** — documento cobrable con identidad visible consistente.
- **§11-H Conflicto para F7.** H1 (recomendada): `consolidated_collection` como documental (versión base, ambas copias, resolución del usuario); `payment_adjustment`/`refund` append-only sin resolución. H2: todo append-only incluido el Consolidado (pierde resolución de edición concurrente). **Recomendada H1.**
- **§11-I Composición del Consolidado.** I1: mismo Cliente; Perfil libre (cada Cuenta conserva su snapshot) y misma moneda obligatoria. I2: además mismo Perfil. **Recomendada I1** — el cobro es al Cliente; los datos históricos quedan en cada Cuenta.
- **§11-J `userId`/`id`/`createdAt` en payloads.** (Observación sistémica de revisión: payloads verbatim en create/edición permiten inyectar identidad.) J1: normalizar identidad desde lo persistido en todos los tipos nuevos de F7 + `collection_account` en create/edición `PENDIENTE`. J2: solo documentar como limitación. **Recomendada J1** al menos para entidades F7 nuevas.

## 12. Hito Hostinger / despliegue

F7 se implementa íntegramente en local (Android + PHP dev server + MariaDB local), igual que F1–F6. **El hito de provisión remota no pertenece a esta ficha:** será necesario únicamente al alcanzar el hito de despliegue/provisión (Hostinger + MySQL remota + correo), que la hoja de ruta sitúa después de las fases funcionales. **Aviso previo obligatorio:** antes de ese hito se detendrá para que el titular decida provisionar base de datos, credenciales, correo y HTTPS. Nada de ello se crea ni configura en F7.

## 13. Datos

Continúa vigente la regla aprobada: **solo datos sintéticos** en Android, backend y bases locales de prueba, hasta que la protección local en reposo esté definida, implementada y verificada (`03` §4.8). Esta ficha no selecciona tecnología de cifrado ni resuelve ese pendiente por inferencia.

---

## 14. Tabla de decisiones pendientes para aprobación del titular

Todas son **[PROPUESTA] de Devin — ninguna es decisión aprobada**. Cada una indica el pendiente documental vigente que cierra, la regla concreta con ejemplo, alternativas, recomendación y si bloquea F7.

| § | Pendiente vigente | Regla propuesta (ejemplo) | Alternativas y consecuencias | Recomendación Devin `[PROPUESTA]` | ¿Bloquea F7? |
|---|---|---|---|---|---|
| **11-A** Elegibilidad de Cuentas | `03` §4.2 (especificación Consolidado); `02` §25 | Cuenta elegible = no `CANCELADA` **y** `balance > 0`, del mismo Cliente y misma moneda. *Ej.: una `PARCIAL` con 114 aplicados y 36 pendientes puede entrar; su pendiente suma al Consolidado.* | A2: solo `PENDIENTE` — excluye cuentas parcialmente cobradas; impediría consolidar saldos reales vivos. | **A1** — el Consolidado cubre solo el saldo pendiente; más fiel a "agrupa saldos" ya aprobado. | **Sí** — define la selección de UI y la validación de composición en servidor. |
| **11-B** Ciclo/estados del Consolidado | `03` §4.2; `02` §25 (campo `estado` sin definir) | Espejo de Cuenta: `PENDIENTE` al crear; `PARCIAL`/`PAGADA` derivadas por servidor desde asignaciones; `CANCELADA` solo sin pagos aplicados (libera sus Cuentas); miembros editables solo `PENDIENTE`. *Ej.: Consolidado 300 recibe assign 200 → `PARCIAL` saldo 100.* | B2: sin ciclo propio — no habría cancelación formal ni liberación de vínculos; estados inconsistentes con Cuenta. | **B1** — consistencia con el ciclo aprobado de Cuenta y con la regla "cancelar libera". | **Sí** — determina transiciones, liberación de Cuentas y qué muestra Cobros. |
| **11-C** Doble cobro / doble vínculo | `03` §4.4 residual; `02` §25; `04` §2.10 | Una Cuenta dentro de un Consolidado no cancelado **no acepta asignación directa ni un segundo Consolidado**; cancelar el Consolidado libera sus Cuentas. *Ej.: `assign` directo a C1 estando en Consolidado activo → `REJECTED VALIDATION_ERROR`.* | C2: permitir ambos — el mismo saldo podría contarse dos veces (consolidado + cuenta) → doble cobro posible. | **C1** — extiende la regla ya aprobada "un origen, una Cuenta no cancelada" al nivel Consolidado. | **Sí** — es la regla central de integridad financiera; sin ella hay doble contabilización. |
| **11-D** `CORRECTION` | `03` §4.5 (diseño pendiente); `02` §25; `04` §2.10 | **Alza:** el incremento queda `UNASSIGNED` hasta asignación explícita (sin reparto). **Baja:** consume primero la parte libre (`efectivo − aplicado − reembolsado`), luego revierte las asignaciones más recientes **(LIFO)** atómicamente `FOR UPDATE`; `nuevoEfectivo < reembolsadoAcumulado` → `REJECTED`. *Ej.: pago 500 (300→C1, 200 libres) con reembolso 200 → corregir a 450 libera 50 del assign más reciente (250+200=450 ✓); corregir a 150 → REJECTED (150<200); corregir a 600 → +100 UNASSIGNED.* | D2: proporcional entre asignaciones — reparte el ajuste pero es menos explicable al usuario. D3: rechazar si baja de lo aplicado — exige `unassign` manual previo (más conservadora). | **D1 con piso de reembolsados** — automática, determinista, preserva la invariante `aplicado + reembolsado ≤ efectivo`. | **Sí** — es la semántica de recálculo monetario del mecanismo. |
| **11-E** `ANNULMENT` | `03` §4.5; `02` §25 ("deja de contar en el cálculo"); `04` §2.10 | Evento append-only **terminal, una sola vez por Pago**, que libera atómicamente todas las asignaciones vigentes y fija el monto efectivo en `reembolsadoAcumulado` (0 sin reembolsos). Tras aceptarse: `assign`/`reassign`/`CORRECTION`/`Refund` posteriores → REJECTED; reintento de la misma op → resultado memorizado; segunda anulación distinta → REJECTED. Invariante conservada: `aplicado + reembolsado ≤ efectivo`. *Ej.: pago 500 (300→C1, refund 200) → `ANNULMENT` libera los 300 y fija efectivo 200; después `CORRECTION` a 600 → REJECTED, `Refund` 50 → REJECTED, reassign → REJECTED.* | E2: exigir `unassign` manual antes de anular — mismo efecto con paso extra. E3: rechazar la anulación si existen reembolsos — más simple, pero deja esos pagos sin anulación para siempre. | **E1 con piso de reembolsados + terminalidad** — preserva la invariante sin inventar reversión de reembolsos; coincide con "deja de contar" ya aprobado. | **Sí** — su efecto sobre asignaciones es el núcleo del mecanismo; la terminalidad cierra mutaciones post-cierre. |
| **11-F** Alcance y disponible del Reembolso | `02` §36.18 (trazabilidad pendiente); `02` §25; `04` §2.10 | `disponibleReembolso = montoEfectivo − aplicadoVigente − reembolsadoAcumulado`. Una sola reserva libre alimenta asignaciones **y** reembolsos (asignar también valida contra ese remanente — extensión propuesta de la regla F6). Reembolsar dinero aplicado exige `unassign`/`reassign` auditado previo y luego el Reembolso; el Reembolso es irreversible y piso del efectivo. *Ej.: pago 500 (300→C1, 200 libres) → `Refund` 200 OK; `Refund` 50 más → REJECTED; para reembolsar lo de C1 hay que liberarlo antes.* | F2: el Reembolso libera automáticamente lo aplicado — segundo mecanismo implícito de liberación, más casos borde y menos trazabilidad explícita. | **F1** — cero doble contabilización y reutiliza las operaciones auditadas ya aprobadas. | **Sí** — define si puede coexistir dinero reembolsado y aplicado (integridad financiera). |
| **11-G** Numeración del Consolidado | `03` §4.2; regla general de numeración aprobada | Provisional `CONS-PEND-XXXX` → oficial `CONS-AAAAMM-NNN` sobre el mismo `entity_id`, idempotente en la primera aceptación del backend (espejo `CC`). *Ej.: creado offline `CONS-PEND-0007` → al sincronizar `CONS-202610-003`.* | G2: sin número propio — el usuario carece de referencia documental visible para el cobro consolidado. | **G1** — identidad documental consistente con Cuenta/Trabajo/Informe. | **Parcial** — la UI funciona con provisional, pero el contrato documental queda incompleto sin decisión. |
| **11-H** Conflictos para F7 | `03` §4.3 residual; `03` §6 F6 ("pendiente para F7") | `consolidated_collection` = **entidad documental** (detección por versión base, ambas copias, resolución del usuario). `payment_adjustment`/`refund` = **eventos financieros append-only** (idempotencia por `operation_id`/`entity_id`, validación atómica en servidor, sin resolución por sobrescritura ni reloj). *Ej.: edición concurrente de miembros del Consolidado → `sync_conflicts`; ajuste replicado → dedupe.* | H2: todo append-only — las ediciones concurrentes del Consolidado no tendrían resolución posible. | **H1** — extensión directa de la política F6 ya aprobada. | **Sí** — fija el comportamiento de sincronización de las tres entidades nuevas. |
| **11-I** Composición del Consolidado | `03` §4.2; `02` §25 (solo `client_id`) | Mismo **Cliente** obligatorio; **Perfil libre** (cada Cuenta conserva su snapshot propio); **misma moneda** obligatoria. *Ej.: C1 (Perfil A) + C2 (Perfil B) del mismo Cliente en COP → consolidado válido.* | I2: exigir mismo Perfil — más restrictivo; impediría consolidar cobros multi-servicio reales del mismo Cliente. | **I1** — el cobro es al Cliente; los datos históricos permanecen en cada Cuenta. | **Sí** — define la validación de composición al crear. |
| **11-J** Endurecimiento `id`/`userId`/`createdAt` | Observación sistémica de las revisiones del PR #13 (sin § formal) | Normalizar `id`/`userId`/`createdAt` desde lo persistido en **todas las entidades nuevas de F7** y en las ramas `CREATE`/edición `PENDIENTE` de `collection_account`. *Ej.: op fabricada con `userId='otro'` sobre una Cuenta `PENDIENTE` → se guarda el titular canónico, sin corrupción ni cambio de titular.* | J2: solo documentar la limitación — el vector fabricado queda abierto en todos los tipos y en Cuentas `PENDIENTE`. | **J1** — coherente con lo ya corregido en la rama inmutable; costo mínimo al implementar tipos nuevos. | **No bloquea** el diseño, pero conviene decidirlo ahora para incluirlo en F7. |

### Corrección documental propuesta (no es decisión de producto)

**Deriva `PARTIAL_ASSIGNED`:** `docs/02-modelo-de-datos.md` §24 (línea 911) muestra el enum conceptual del estado de asignación del Pago como `UNASSIGNED`/`ASSIGNED`, pero la implementación vigente y aprobada en F6 usa `UNASSIGNED`/`PARTIAL_ASSIGNED`/`ASSIGNED` (`backend/src/domain.php:81`). **Propuesta:** corregir el enum en `02` §24 durante la pasada documental de F7 — es alinear el documento con la regla ya implementada, no una decisión nueva.

# CotixGo — Arquitectura y tecnologías

**Versión:** 1.4  
**Estado:** Stack y principios aprobados; detalles técnicos pendientes donde se indica.

## 1. Plataformas y stack

| Componente | Tecnología aprobada | Responsabilidad principal |
|---|---|---|
| Android | Kotlin, Jetpack Compose, Room | Aplicación móvil offline-first y persistencia local. |
| Web | React, TypeScript, Next.js | Aplicación web para los flujos de CotixGo definidos. |
| Backend | PHP, API REST | Autenticación/autorización, validación de reglas compartidas, persistencia y sincronización. |
| Base de datos | MySQL | Persistencia central de datos del usuario y operaciones sincronizadas. |
| Servidor | Hostinger | Hospedaje de backend y servicios definidos para V1. |
| Archivos | Hostinger | Almacenamiento de fotografías y documentos, sujeto a diseño técnico. |
| Control de versiones | Git/GitHub | Historial y colaboración sobre el código cuando comience la implementación. |

No hay versiones de lenguaje, framework, runtime, servidor o base de datos fijadas en la documentación disponible, salvo las excepciones siguientes. No escoger versiones incompatibles u obsoletas: validar compatibilidad al iniciar implementación y documentar las versiones seleccionadas.

Decisiones técnicas aprobadas:

- **Package Android:** `com.cotixgo.app` como `applicationId` y namespace del proyecto.
- **JDK de build:** JDK 21 LTS para los builds Android/Gradle (configuración vía `gradle.properties`/`org.gradle.java.home` o toolchain del proyecto). No se usa JBR 25 ni se migra a Gradle/AGP 9.x solo por tener JDK 25 instalado.

## 2. Principios de arquitectura

### 2.1 Android offline-first

- La aplicación Android debe poder consultar y registrar información operativa sin conexión.
- Room es la fuente local de trabajo en el dispositivo; la sincronización con el backend ocurre posteriormente.
- La cuenta del usuario, no el dispositivo, es la identidad propietaria de los datos.
- Cambiar o perder un dispositivo no debe hacer perder los datos que ya se sincronizaron.

### 2.2 Separación de responsabilidades

```text
Android UI (Jetpack Compose)
        ↓
Casos de uso / reglas de aplicación
        ↓
Repositorios
        ↓
Room local ↔ Cola de sincronización ↔ API REST PHP
                                      ↓
                                   MySQL
                                      ↔
                            Archivos en Hostinger
```

El diagrama expresa responsabilidades conceptuales; no aprueba nombres de paquetes, librerías adicionales ni estructura de carpetas.

- La interfaz presenta información y captura acciones; no debe duplicar cálculos comerciales en múltiples pantallas.
- Los cálculos comunes a Cotizaciones y Cuentas de Cobro deben tener una única regla funcional compartida y resultados consistentes en móvil, web y servidor.
- El backend valida identidad, permisos y consistencia de operaciones sincronizadas. La estrategia exacta de autoridad para conflictos queda pendiente.
- MySQL no debe usar claves locales autoincrementales como identidad global de entidades creadas offline.
- Las fotografías/documentos deben poder asociarse localmente a su Trabajo/entidad antes de sincronizar.

### 2.3 Sincronización e integridad

Requisitos aprobados:

- IDs globalmente únicos generables offline.
- Cola persistente de operaciones pendientes, reintentos e idempotencia.
- Repetir una operación no puede duplicar un documento, un pago ni un movimiento de inventario.
- Trazabilidad suficiente para diagnosticar origen, usuario, dispositivo y operación.
- Recuperación de información sincronizada al iniciar sesión en otro dispositivo.
- Gestión explícita de cambios simultáneos y conflictos; no resolverlos mediante sobrescritura silenciosa.

**PENDIENTE DE DECISIÓN (técnica):** protocolo/orden de sincronización, cursores, estructuras físicas de `SyncOperation`/`AuditEvent`, límites concretos de reintento y contrato de endpoints. Aprobado para F3 en `03` §6: política de conflicto por entidad (detección explícita, preservación de ambas copias, resolución del usuario; versión de resolución nueva para Cotizaciones), operaciones nunca descartadas (reintentos persistentes; errores de atención pausados con reintento manual), resultados parciales por operación, recuperación por descarga completa en instalación limpia y reglas de cambio de cuenta. Algoritmo de ID aprobado: UUIDv4 aleatorio generado en el dispositivo (F1). Regla aprobada: cualquier documento archivado se puede desarchivar; los datos generados no se eliminan y archivar/desarchivar solo afecta su visibilidad, no sus efectos.

### 2.4 Documentos históricos

- Al emitir o congelar un documento, guardar los valores necesarios para reproducir su representación histórica: Cliente, Perfil Profesional, logo, conceptos, cantidades, precios, moneda, retenciones y totales aplicables.
- Cambios posteriores en entidades maestras no alteran documentos históricos.
- Las versiones de Cotización y los snapshots de documentos deben poder distinguirse de los datos maestros actuales.
- El mecanismo físico (snapshot estructurado, versión inmutable u otro) queda para diseño técnico y debe cumplir la regla histórica.

## 3. API y backend

Está aprobado que el backend sea una API REST en PHP y use MySQL. Todavía no está aprobado un contrato de endpoints.

El contrato de API deberá especificar, antes o durante implementación controlada:

- autenticación y renovación de sesión (base de producto aprobada: registro e inicio de sesión con email y contraseña; recuperación por correo aprobada en principio — proveedor pendiente);
- autorización por usuario y Perfil Profesional;
- respetar el alcance de propiedad: Clientes, Inventario y Métodos de Cobro pertenecen al Usuario/Titular; cada operación/documento identifica el Perfil Profesional que le corresponde;
- formato común de error y validación;
- operaciones idempotentes y clave de operación;
- sincronización incremental, cursores y versiones;
- cargas de archivos y sus estados;
- paginación/filtrado de colecciones;
- numeración de documentos (regla y momento de asignación aprobados para Cotización: cuando el backend acepta la primera versión `EMITIDA`/`APROBADA` sobre el mismo `entity_id`; detalles de endpoint pendientes);
- compatibilidad/versionado de API;
- límites, seguridad y registro de auditoría.

Cada punto no definido es **PENDIENTE DE DECISIÓN**; esta lista es el índice del futuro contrato, no una especificación inventada.

El tratamiento de impuestos, incluidos tipos, porcentajes, valores predeterminados y cualquier administrador/configuración, **no está aprobado para V1**. Si se considera en otra etapa, sus reglas deben permanecer **PENDIENTE DE DECISIÓN**; no constituye requisito funcional actual.

## 4. Seguridad, privacidad y disponibilidad

La documentación funcional confirma identidad asociada a cuenta y propiedad de datos del usuario, pero no define todavía:

- detalles del flujo de autenticación (registro e inicio de sesión con email y contraseña están aprobados como base de producto para F3; el contrato concreto permanece pendiente);
- recuperación de acceso: aprobada por correo antes de usar cuentas o datos reales; proveedor, envío y configuración pendientes — el correo disponible en Hostinger puede considerarse sin configurarlo aún. Cierre de sesión en dispositivos perdidos sigue pendiente;
- cifrado de transporte: HTTPS es obligatorio para credenciales o datos reales (HTTP solo es aceptable en desarrollo local `php -S`);
- cifrado/protección de datos locales en reposo: pendiente de definición tecnológica. Decisión aprobada en la ficha F3 (`03` §6): solo datos sintéticos en desarrollo y pruebas hasta definir, implementar y verificar la protección antes de usar datos reales; no se añade al alcance de F3 automáticamente;
- política de retención y exportación; no se eliminan datos generados y archivar solo oculta;
- permisos multiusuario;
- copias de seguridad y recuperación del servidor;
- límites de almacenamiento de archivos;
- requisitos de privacidad por país.

Estos asuntos deben documentarse antes de exponer datos reales a usuarios. Devin no debe asumir una política de producto en su lugar.

## 5. Decisiones técnicas que deben registrarse al implementar

Cuando se autorice la implementación, registrar versiones soportadas, librerías seleccionadas, arquitectura de módulos, ambientes, configuración de despliegue, estrategia de migraciones, observabilidad, backups y procedimiento de publicación. Esas elecciones deben respetar el stack y las reglas ya aprobadas.

## 6. Historial de cambios

| Versión | Fecha | Cambio | Motivo | Estado |
|---|---|---|---|---|
| 1.0 | 30/09/2026 | Arquitectura y tecnologías aprobadas. | Definir stack y principios. | Aprobado. |
| 1.1 | 08/10/2026 | Decisiones técnicas aprobadas: package Android `com.cotixgo.app` (D15) y JDK 21 LTS para builds Android/Gradle (D16). | Aprobación de las decisiones pendientes de la etapa V1.1. | Aprobado. |
| 1.2 | 11/10/2026 | F1: algoritmo de ID global aprobado (UUIDv4). | Aprobación de la decisión pendiente de la ficha F1. | Aprobado. |
| 1.3 | 11/10/2026 | Planificación de F3: base de auth email/contraseña registrada en §4; recuperación de acceso por correo aprobada en principio (proveedor pendiente); HTTPS obligatorio para credenciales/datos reales; protección de datos locales en reposo pendiente con regla de solo datos sintéticos hasta su verificación (ver `03` §6). | Aprobación de las decisiones de protección de datos y entorno de la ficha F3. | Aprobado. |
| 1.4 | 11/10/2026 | Planificación de F3 — pase documental completo: §2.3 actualizado separando pendientes técnicos (protocolo, cursores, estructuras físicas) de políticas de producto ya aprobadas (conflictos, reintentos, operaciones parciales, recuperación, cambio de cuenta — ver `03` §6); §3 anota base de auth aprobada y momento del número oficial para Cotización. | Registro de las decisiones de producto de F3 aprobadas por el titular. | Aprobado. |

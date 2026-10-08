**CotixGo — Reglas de Negocio**  
**Documento fundacional v0.1**  
**Estado:** Aprobado  
   
 **Propósito:** Documento base para producto, arquitectura y desarrollo.  
   
 **Audiencia principal:** Joel, diseño, arquitectura y Devin.  
**Nota de vigencia:** Este documento v0.1 se conserva únicamente como antecedente. Para reglas vigentes se deben usar `01-reglas-de-negocio-v0.2.md`, `03-anexo-decisiones-aprobadas-y-brechas.md` y su consolidado en `08-matriz-decision-estados-y-calculos.md`; cualquier diferencia con estos documentos posteriores queda supersedida.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OMQ2AABAAsSPBCj5fFgpQwYwEZiywEZJWQZeZ2ao9AAD+4lyruzq+ngAA8Nr1AMTRBeEgNK9YAAAAAElFTkSuQmCC)  
**1. Propósito de CotixGo**  
CotixGo es una herramienta profesional de campo para trabajadores independientes y pequeños proveedores de servicios.  
Su propósito es permitir que el usuario pueda:  
- cotizar desde el móvil;  
- trabajar incluso sin conexión a Internet;  
- gestionar clientes y trabajos;  
- controlar materiales y herramientas;  
- documentar lo realizado durante el trabajo;  
- generar informes profesionales;  
- emitir Cuentas de Cobro;  
- registrar pagos.  
CotixGo **no debe sentirse como una aplicación contable, financiera o administrativa**, sino como una herramienta profesional de campo.  
La idea central es:  
***“Estoy preparado.”***  
Y el flujo operativo fundamental es:  
***Cotizar → Ejecutar → Documentar → Cobrar***  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OMQ2AABAAsSNhRAF6EPYDLhGADSywEZJWQZeZ2aszAAD+4l6rrTq+ngAA8Nr1AIWsBDYDm5cLAAAAAElFTkSuQmCC)  
**2. Flujo oficial del producto**  
El flujo principal aprobado es:  
CLIENTE  
    │  
    ▼  
 COTIZACIÓN  
    │  
    │ aprobada  
    ▼  
 TRABAJO  
    │  
    ├── Actividades  
    ├── Materiales  
    ├── Fotos  
    └── Observaciones  
    │  
    ▼  
 INFORME  
    │  
    ▼  
 CUENTA DE COBRO  
    │  
    ▼  
 PAGOS  
   
Existe además un flujo paralelo de inventario:  
COMPRA  
    │  
    ▼  
 INVENTARIO  
    │  
    ▼  
 CONSUMO REAL EN TRABAJO  
   
El inventario es soporte de la operación; no es el centro de la experiencia del usuario.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANElEQVR4nO3OQQmAUBBAwSd8bOHVnBvBkAaxgjcRZhLMNjNHdQUAwF/cq9qr8+sJAACvrQctgQNH4A++9QAAAABJRU5ErkJggg==)  
**3. Módulos aprobados**  
Los módulos funcionales definidos hasta este momento son:  
1. **Dashboard**  
2. **Clientes**  
3. **Cotizaciones**  
4. **Trabajos**  
5. **Inventario**  
6. **Compras**  
7. **Cuenta de Cobro**  
8. **Perfiles Profesionales**  
9. **Informes**  
Los módulos de analítica interna o reportes administrativos pueden considerarse posteriormente, pero no forman parte del núcleo actual.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OQQmAABRAsSfYxZo/jVEMYQLPJrCCNxG2BFtmZquOAAD4i3Ot7mr/egIAwGvXA4rLBc059ysnAAAAAElFTkSuQmCC)  
**4. Clientes**  
Un cliente representa a la persona o empresa para quien se presta el servicio.  
Un cliente puede estar relacionado con:  
- cotizaciones;  
- trabajos;  
- informes;  
- Cuentas de Cobro.  
La información del cliente debe mantenerse como entidad propia y reutilizarse en los documentos y procesos que correspondan.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OMQ2AABAAsSNBCUrfDqrYGVDAgAU2QtIq6DIzW7UHAMBfHGt1V+fXEwAAXrseHCQGBEuErVgAAAAASUVORK5CYII=)  
**5. Perfiles Profesionales**  
CotixGo debe permitir más de un perfil profesional.  
Ejemplos:  
- una identidad para servicios técnicos;  
- otra identidad para servicios de software.  
Un perfil profesional puede aportar a los documentos:  
- nombre comercial;  
- nombre legal;  
- identificación;  
- teléfono;  
- correo;  
- dirección;  
- logo;  
- datos de pago;  
- condiciones comerciales;  
- configuración documental.  
Una cotización, informe o Cuenta de Cobro debe poder identificar el perfil profesional con el que fue emitido.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OMQ2AABAAsSNBACPiUML0NpGACyywEZJWQZeZ2aszAAD+4l6rrTq+ngAA8Nr1AL/SBEZwuCSwAAAAAElFTkSuQmCC)  
**6. Cotizaciones**  
Una cotización representa la **propuesta comercial** presentada al cliente.  
Puede contener:  
- servicios;  
- mano de obra;  
- materiales;  
- cantidades;  
- precios;  
- descuentos o impuestos cuando correspondan;  
- totales;  
- condiciones;  
- estado.  
**6.1 La cotización representa la necesidad del cliente**  
La cotización debe mostrar lo que el trabajo requiere, independientemente de cuánto material exista actualmente en el inventario.  
Ejemplo:  
*El trabajo requiere 100 metros de cable.*  
Aunque el inventario tenga solamente 20 metros, la cotización continúa representando los **100 metros requeridos**.  
No debe reducirse la cantidad cotizada a la existencia disponible.  
**6.2 El inventario es información interna**  
La información de inventario es interna.  
El cliente no debe ver automáticamente:  
- existencias;  
- faltantes;  
- costos internos;  
- proveedores;  
- movimientos de inventario.  
La consulta del inventario puede ayudar al profesional a prepararse para el trabajo, pero no modifica por sí misma la cotización.  
**6.3 Aprobar una cotización no consume inventario**  
La aprobación de una cotización **no genera automáticamente una salida de inventario**.  
Puede utilizarse el inventario para determinar si habrá que comprar material, pero el consumo real ocurre durante la ejecución del trabajo.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OQQmAABRAsSfYxZo/kSGMYQLPJrCCNxG2BFtmZquOAAD4i3Ot7mr/egIAwGvXA4qrBdGuSdJuAAAAAElFTkSuQmCC)  
**7. Materiales: origen del material**  
Un trabajo puede involucrar materiales de diferentes orígenes.  
Cada material debe poder distinguirse conceptualmente entre:  
- **Material suministrado por el cliente**  
- **Material suministrado por el profesional**  
Esto permite cubrir varios escenarios.  
**Caso A — Solo mano de obra**  
El cliente ya tiene todos los materiales.  
Cotización  
 └── Mano de obra  
   
Durante el trabajo:  
- se registra la ejecución;  
- pueden documentarse los materiales proporcionados por el cliente;  
- **no se modifica el inventario interno**.  
**Caso B — Mano de obra + materiales propios**  
Cotización  
 ├── Mano de obra  
 └── Materiales propios  
   
Los materiales propios pueden estar relacionados con el inventario.  
Durante el trabajo, el consumo real de esos materiales puede generar movimientos de salida.  
**Caso C — Materiales mezclados**  
Puede existir un trabajo en el que:  
- algunos materiales sean proporcionados por el cliente;  
- otros sean proporcionados por el profesional.  
Solo los materiales propios efectivamente consumidos afectan el inventario interno.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OQQmAABRAsSfYxZo/jzlMYQLPJrCCNxG2BFtmZquOAAD4i3Ot7mr/egIAwGvXA4q7Bc870TqdAAAAAElFTkSuQmCC)  
**8. Regla fundamental: material utilizado no equivale a consumo de inventario**  
Esta es una de las reglas de negocio más importantes de CotixGo.  
***“Material utilizado en el trabajo” y “material consumido del inventario de CotixGo” son conceptos diferentes.***  
Un material puede aparecer en el trabajo y en el informe porque fue utilizado, pero haber sido suministrado por el cliente.  
Por tanto:  
MATERIAL UTILIZADO  
        │  
        ├── Cliente  
        │     └── No afecta inventario  
        │  
        └── Profesional  
              └── Si se consume realmente  
                     ↓  
                  Inventario  
   
La regla definitiva es:  
***Solo el material propio efectivamente consumido en el trabajo genera movimientos sobre el inventario interno.***  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OMQ2AABAAsSNhYMMAKlD4OzrxgQU2QtIq6DIzR3UFAMBf3Gu1VefXEwAAXtsfSqADWz4G/HUAAAAASUVORK5CYII=)  
**9. Diferencia entre cotizado, ejecutado y consumido**  
CotixGo debe mantener conceptualmente separadas estas tres realidades:  
**Cotizado**  
Lo que se propuso al cliente.  
Ejemplo:  
*100 m de cable.*  
**Ejecutado**  
Lo que realmente se hizo o utilizó durante el trabajo.  
Ejemplo:  
*Se utilizaron 92 m.*  
**Consumido del inventario**  
La parte que salió realmente del inventario propio.  
Ejemplo:  
*70 m salieron del inventario del profesional y 22 m fueron suministrados por el cliente.*  
Estas diferencias son importantes para evitar inconsistencias.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANElEQVR4nO3OQQmAABRAsSdYxKa/i8WMIR7ECt5E2BJsmZmt2gMA4C+Otbqr8+sJAACvXQ85PAYartXEogAAAABJRU5ErkJggg==)  
**10. Trabajo**  
El Trabajo representa la **ejecución real** de una cotización aprobada.  
Normalmente se origina a partir de una cotización aprobada, pero no debe considerarse una simple copia de la cotización.  
Debe permitir registrar información real de ejecución, incluyendo:  
- cliente;  
- cotización de origen;  
- estado;  
- fechas;  
- actividades;  
- materiales utilizados;  
- fotos;  
- observaciones;  
- información de cierre.  
El Trabajo es el punto central que conecta:  
**Cotización + ejecución + inventario + informe + cobro.**  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OMQ2AABAAsSNBACP6MMH6NpGACyywEZJWQZeZ2aszAAD+4l6rrTq+ngAA8Nr1AL+6BElk4wV6AAAAAElFTkSuQmCC)  
**11. Actividades**  
Durante la ejecución el usuario debe poder registrar las actividades realizadas.  
Ejemplos:  
- desmontaje;  
- instalación;  
- reparación;  
- pruebas;  
- ajustes;  
- limpieza;  
- verificaciones.  
Estas actividades deben poder reutilizarse posteriormente para construir el Informe.  
La aplicación debe evitar que el usuario tenga que reconstruir desde memoria todo lo realizado después de terminar la jornada.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OMQ2AABAAsSNBCUpfDq4wwIAABiywEZJWQZeZ2ao9AAD+4liruzq/ngAA8Nr1ABweBgdur/QFAAAAAElFTkSuQmCC)  
**12. Fotos y evidencias**  
Las fotografías forman parte de la documentación del Trabajo.  
Conceptualmente pueden organizarse como:  
- antes;  
- durante;  
- después.  
El usuario debe poder seleccionar posteriormente cuáles fotografías forman parte del Informe.  
Las fotografías y evidencias deben estar vinculadas al Trabajo y no depender de que el usuario recuerde todo al finalizar.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OMQ2AABAAsSNhYMMAKlD4OzrxgQU2QtIq6DIzR3UFAMBf3Gu1VefXEwAAXtsfSqADWz4G/HUAAAAASUVORK5CYII=)  
**13. Informes**  
El Informe es un documento profesional que documenta lo realizado en un Trabajo.  
El Informe **no debe ser un formulario independiente que obligue al usuario a volver a introducir toda la información**.  
Debe construirse a partir de los datos ya registrados durante la ejecución:  
TRABAJO  
  ├── Actividades  
  ├── Materiales  
  ├── Fotos  
  └── Observaciones  
         │  
         ▼  
      INFORME  
         │  
         ├── Revisar  
         ├── Completar  
         └── Generar PDF  
   
El usuario puede revisar y completar el contenido antes de generar el documento final.  
Esto es especialmente importante para trabajos en los que el cliente exige un informe posterior.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OQQmAABRAsSeYxKS/kJkED6bwYAVvImwJtszMVu0BAPAXx1rd1fn1BACA164HHDwF+DpPyKwAAAAASUVORK5CYII=)  
**14. Inventario**  
El inventario es de uso interno.  
Tiene dos grandes conceptos:  
**Materiales**  
Son consumibles.  
Pueden tener información como:  
- categoría;  
- unidad;  
- existencia;  
- existencia mínima;  
- costo;  
- proveedor;  
- ubicación;  
- notas.  
**Herramientas**  
Son activos y no consumibles.  
Pueden tener información como:  
- categoría;  
- marca;  
- modelo;  
- serial;  
- fecha de adquisición;  
- costo;  
- estado;  
- ubicación;  
- notas.  
Los estados de herramientas contemplados son:  
- disponible;  
- en uso;  
- mantenimiento;  
- dañada;  
- fuera de servicio.  
La funcionalidad completa de mantenimiento de herramientas puede incorporarse posteriormente; el modelo debe poder crecer sin obligar a implementarla ahora.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANElEQVR4nO3OUQmAABBAsSdYxKbXxlpGEAOIFfwTYUuwZWa2ag8AgL841uquzq8nAAC8dj05VAYO3phhoQAAAABJRU5ErkJggg==)  
**15. Compras**  
Una Compra representa la adquisición real de materiales.  
Puede contener:  
- proveedor;  
- fecha;  
- material;  
- cantidad;  
- unidad;  
- costo unitario;  
- total;  
- observaciones.  
Una compra confirmada genera una entrada de inventario.  
Los costos históricos deben conservarse.  
Si el mismo material se compra posteriormente a un precio diferente, la compra anterior no debe ser sobrescrita.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OQQmAABRAsSd49m4v6wg/pwmMYQVvImwJtszMXp0BAPAX91pt1fH1BACA164Hoq8EQMMPmF8AAAAASUVORK5CYII=)  
**16. Consumo de inventario**  
El consumo de inventario ocurre por la **ejecución real del Trabajo**, no por la creación ni por la aprobación de la cotización.  
Por ejemplo:  
Cotizado:       100 m  
 Ejecutado:       92 m  
 Propio:          70 m  
 Cliente:         22 m  
   
El inventario solamente debe disminuir en:  
70 m  
   
No en 100 m ni en 92 m.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OQQmAABRAsSeYxKS/kJkED6bwYAVvImwJtszMVu0BAPAXx1rd1fn1BACA164HHDwF+DpPyKwAAAAASUVORK5CYII=)  
**17. Cuenta de Cobro**  
En CotixGo se utilizará oficialmente el término colombiano:  
***Cuenta de Cobro***  
La Cuenta de Cobro representa una obligación de pago derivada del trabajo realizado.  
Debe poder manejar conceptos como:  
- cliente;  
- trabajo asociado;  
- monto;  
- fecha;  
- fecha de vencimiento;  
- saldo;  
- estado;  
- pagos asociados.  
**17.1 Crear una Cuenta de Cobro no significa recibir dinero**  
Son eventos diferentes:  
TRABAJO  
    │  
    ▼  
 CUENTA DE COBRO  
    │  
    ▼  
 PAGO  
   
La creación de la Cuenta de Cobro no modifica por sí misma el dinero recibido.  
Un pago registrado es el evento que reduce el saldo pendiente.  
La Cuenta de Cobro debe poder representar situaciones como:  
- pendiente;  
- parcialmente pagada;  
- pagada;  
- cancelada.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OMQ2AABAAsSPBCUbfEm6YmFDBhAU2QtIq6DIzW7UHAMBfnGt1V8fXEwAAXrse/w8F7pbTa1oAAAAASUVORK5CYII=)  
**18. Dashboard**  
El Dashboard es una vista operacional.  
Su objetivo principal es responder:  
***“¿Qué requiere mi atención hoy?”***  
No debe convertirse en un dashboard financiero.  
Puede reunir información de:  
- cotizaciones pendientes;  
- trabajos activos;  
- Cuentas de Cobro pendientes;  
- inventario bajo;  
- informes pendientes;  
- actividad reciente;  
- estado de sincronización;  
- acciones rápidas.  
El Dashboard es una vista sobre las entidades del sistema, no una entidad de negocio independiente.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OMQ2AABAAsSNhRAF6EPYDLhGADSywEZJWQZeZ2aszAAD+4l6rrTq+ngAA8Nr1AIWsBDYDm5cLAAAAAElFTkSuQmCC)  
**19. Principio offline-first**  
Android debe funcionar aunque el usuario no tenga conexión a Internet.  
Esto es una necesidad real de campo.  
El usuario debe poder, cuando sea técnicamente posible:  
- consultar información;  
- crear y editar clientes;  
- crear y editar cotizaciones;  
- trabajar con trabajos;  
- registrar actividades;  
- registrar materiales;  
- tomar fotografías;  
- registrar observaciones;  
- preparar documentos.  
Las operaciones deben poder quedar pendientes de sincronización.  
Cuando vuelva la conexión, CotixGo sincronizará los cambios con el servidor.  
La ausencia de Internet **no debe sentirse como un error de la aplicación**.  
Principio:  
***CotixGo funciona. La sincronización puede esperar.***  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANElEQVR4nO3OQQmAABRAsSdYxKa/i8WMIR7ECt5E2BJsmZmt2gMA4C+Otbqr8+sJAACvXQ85PAYartXEogAAAABJRU5ErkJggg==)  
**20. Sincronización**  
La sincronización debe diseñarse desde el principio.  
No debe dejarse como una solución posterior.  
Se requieren:  
- identificadores únicos que puedan generarse offline;  
- operaciones trazables;  
- sincronización idempotente;  
- estados claros;  
- estrategia explícita para conflictos;  
- servidor como fuente central de verdad;  
- datos locales suficientes para trabajar sin conexión.  
La experiencia visual debe diferenciar estados como:  
- sincronizado;  
- sincronizando;  
- cambios pendientes;  
- sin conexión;  
- atención requerida.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OQQmAABRAsScYxpg/h5VMYARvRrCCNxG2BFtmZquOAAD4i3Ot7mr/egIAwGvXA224BcUMk6pDAAAAAElFTkSuQmCC)  
**21. Plataformas**  
La estrategia tecnológica aprobada conceptualmente es:  
**Android**  
- Kotlin  
- Jetpack Compose  
- Room  
- arquitectura offline-first  
**Web**  
- React  
- TypeScript  
- Next.js  
**Backend**  
- PHP  
- API REST  
- Hostinger  
**Base de datos**  
- MySQL  
**Archivos**  
- almacenamiento en Hostinger  
**Control de versiones**  
- Git  
- GitHub  
Android y Web utilizarán el mismo backend/API.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OQQ2AQBAAsSHhiQI0IWp9ngBsYIEfIWkVdJuZs5oAAPiLe6+O6vp6AgDAa+sBhYwEOqBD7p8AAAAASUVORK5CYII=)  
**22. Reglas de alcance y desarrollo**  
CotixGo se desarrollará de manera planificada.  
No se debe comenzar a programar una funcionalidad importante antes de cerrar sus reglas y contratos correspondientes.  
Las nuevas ideas que aparezcan durante el desarrollo deben clasificarse como:  
- **Bug**  
- **Corrección necesaria**  
- **Mejora**  
- **Nueva funcionalidad**  
- **Funcionalidad futura**  
No toda idea nueva debe incorporarse inmediatamente.  
Esto protege el alcance y evita repetir problemas del desarrollo anterior de Xpendz.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OMQ2AABAAsSNBCkJfE1pYGfHAiAU2QtIq6DIzW7UHAMBfnGt1V8fXEwAAXrse4dwF6o2O55YAAAAASUVORK5CYII=)  
**23. Principio de no reconstrucción**  
Una de las reglas de producto más importantes es:  
***CotixGo no debe pedir al trabajador que reconstruya después lo que hizo durante el día.***  
La información debe capturarse progresivamente durante el Trabajo y reutilizarse para:  
- el Informe;  
- el control de materiales;  
- el consumo de inventario;  
- el cierre del Trabajo;  
- la Cuenta de Cobro cuando corresponda.  
El objetivo es que el usuario termine el trabajo y tenga la mayor parte de la documentación ya preparada.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANElEQVR4nO3OQQmAUBBAwSfIb+HdmNvAkgaxgjcRZhLMNjNHdQUAwF/ce7Wq8+sJAACvrQctewNKtdojwQAAAABJRU5ErkJggg==)  
**24. Regla para futuras decisiones**  
Este documento representa la base de las reglas de negocio aprobadas.  
Cuando aparezca una nueva situación no contemplada:  
1. se identifica la situación;  
2. se determina si corresponde a una regla existente o requiere una nueva;  
3. se documenta la decisión;  
4. se actualiza este documento;  
5. solamente después se implementa si corresponde.  
**No se debe asumir una regla de negocio no documentada cuando esta pueda afectar datos, inventario, cobros, sincronización o documentos.**  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OQQmAABRAsSfYxZo/jVEMYQLPJrCCNxG2BFtmZquOAAD4i3Ot7mr/egIAwGvXA4rLBc059ysnAAAAAElFTkSuQmCC)  
**25. Estado del documento**  
**CotixGo — Reglas de Negocio v0.1**  
Estado: **APROBADO**  
Este documento constituye la referencia funcional inicial para las siguientes etapas:  
1. Modelo de Datos  
2. Contrato de API  
3. Estrategia de sincronización  
4. Especificación UX  
5. Estrategia de pruebas  
6. Implementación  
No constituye todavía el modelo de datos, el contrato técnico ni la implementación de CotixGo.  

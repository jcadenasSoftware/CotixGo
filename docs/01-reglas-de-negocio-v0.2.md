**CotixGo — Reglas de Negocio**  
**Documento fundacional v0.2**  
**Estado:** Aprobado  
   
 **Propósito:** Documento base para producto, arquitectura y desarrollo.  
   
 **Audiencia principal:** Joel, diseño, arquitectura y Devin.  
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
**2. Flujo principal del producto**  
El flujo orientador del producto es:  
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
 INFORME (opcional)  
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
   
El flujo expresa el recorrido habitual, no una secuencia obligatoria: Cotización y Trabajo pueden existir sin depender uno del otro; el Informe es opcional y puede ser interno o entregarse al Cliente; la Cuenta de Cobro puede generarse sin Cotización, Trabajo o Informe. El inventario es soporte de la operación; no es el centro de la experiencia del usuario.  
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
Existe una sola cartera de Clientes global al Usuario/Titular. El Cliente no pertenece a un Perfil Profesional y puede utilizarse desde distintos Perfiles del mismo usuario; el Perfil se selecciona en cada operación o documento. No se debe duplicar un Cliente por cambiar de Perfil.  
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
- información comercial y documental;  
- condiciones comerciales;  
- configuración documental.  
Una cotización, informe o Cuenta de Cobro debe poder identificar el perfil profesional con el que fue emitido. El inventario y los Métodos de Cobro pertenecen globalmente al Usuario/Titular y no a los Perfiles Profesionales.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANklEQVR4nO3OMQ2AABAAsSNBACPiUML0NpGACyywEZJWQZeZ2aszAAD+4l6rrTq+ngAA8Nr1AL/SBEZwuCSwAAAAAElFTkSuQmCC)  
**6. Cotizaciones**  
Una cotización representa la **propuesta comercial** presentada al cliente.  
Puede contener:  
- servicios;  
- mano de obra;  
- materiales;  
- cantidades;  
- precios;  
- retenciones aprobadas (V1 no ofrece descuentos). El tratamiento de impuestos no está aprobado para V1 y permanece pendiente;  
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
El Trabajo representa la **ejecución real** de un servicio.  
Puede originarse a partir de una cotización aprobada o crearse directamente para un Cliente, sin Cotización. No debe considerarse una simple copia de la cotización.  
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
**Cotización opcional + ejecución + inventario + documentación opcional + cobro.**  
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
El Informe documenta lo realizado en uno o varios Trabajos y puede servir para entregar al Cliente o para organización e histórico interno del profesional. Es opcional: el Trabajo puede entregarse y la Cuenta de Cobro puede generarse sin Informe.  
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
El usuario decide si lo genera y si lo entrega al Cliente.  
![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAnEAAAACCAYAAAA3pIp+AAAABmJLR0QA/wD/AP+gvaeTAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAANUlEQVR4nO3OQQmAABRAsSeYxKS/kJkED6bwYAVvImwJtszMVu0BAPAXx1rd1fn1BACA164HHDwF+DpPyKwAAAAASUVORK5CYII=)  
**14. Inventario**  
El inventario es de uso interno y pertenece globalmente al Usuario/Titular, no a sus Perfiles Profesionales. Se divide estructuralmente en Materiales y Herramientas.  
**Materiales**  
Son consumibles y el usuario puede crear libremente sus categorías; CotixGo no impone categorías predeterminadas.  
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
Son activos y no consumibles; el usuario puede crear libremente sus categorías, sin categorías predeterminadas impuestas por CotixGo.  
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
La Cuenta de Cobro representa una obligación de pago. Puede vincularse a una Cotización o Trabajo, o crearse directamente para un Cliente sin esos documentos (por ejemplo, una atención de emergencia).  
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
Los Métodos de Cobro pertenecen globalmente al Usuario/Titular. El usuario los crea y administra; CotixGo no incluye métodos predeterminados al instalarse. La interfaz puede orientar mediante textos de ayuda y ejemplos, pero esos ejemplos no se guardan como registros iniciales. Los métodos pueden usarse al registrar pagos recibidos y reembolsos.  
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

---

**25. Catálogo reutilizable de Servicios**

CotixGo debe contar con un **Catálogo de Servicios reutilizable** que se vaya construyendo progresivamente con el uso real de la aplicación.

El catálogo permite que, una vez que un tipo de trabajo o servicio ya haya sido utilizado, el usuario pueda seleccionarlo en futuras cotizaciones sin tener que escribirlo nuevamente.

**25.1 El catálogo no es obligatorio para cotizar**

El usuario debe poder escribir manualmente un servicio que todavía no exista en el catálogo.

Durante una cotización:

- puede seleccionar un servicio existente;
- puede escribir un servicio nuevo;
- puede continuar con la cotización aunque el servicio no exista previamente.

Después de escribir un servicio nuevo, CotixGo debe ofrecer la posibilidad de:

> **Guardar este servicio en el catálogo**

Guardar el servicio es opcional.

**25.2 El catálogo es una referencia, no una obligación**

El precio sugerido, descripción u otros datos del catálogo sirven como referencia para nuevas cotizaciones.

El usuario debe poder modificar esos datos para una cotización concreta.

Modificar el catálogo posteriormente **no modifica las cotizaciones históricas**.

---

**26. Vigencia de las Cotizaciones**

Las Cotizaciones deben manejar:

- fecha de emisión;
- período de validez;
- fecha de vencimiento.

La vigencia debe ser **flexible y configurable**. No existe una duración universal obligatoria para todas las cotizaciones.

El Perfil Profesional puede tener una vigencia predeterminada, pero el usuario debe poder establecer una vigencia diferente para una cotización concreta cuando corresponda.

**26.1 Una Cotización vencida**

Cuando llega la fecha de vencimiento, la cotización pasa a estar vencida comercialmente.

Una Cotización vencida:

- no se elimina;
- conserva su contenido histórico;
- no puede aprobarse directamente bajo sus condiciones originales;
- puede utilizarse como base para una nueva cotización.

La fecha de vencimiento significa que terminó la vigencia comercial de las condiciones originales. **No significa que la cotización haya perdido utilidad como antecedente.**

**26.2 Revalidación de una Cotización vencida**

Si el cliente regresa después del vencimiento, el profesional puede revisar:

- precios;
- cantidades;
- servicios;
- materiales;
- alcance;
- condiciones.

Si las condiciones siguen siendo válidas, puede generar una nueva cotización basada en la anterior.

La nueva cotización tendrá:

- su propia fecha;
- su propia vigencia;
- sus propios precios;
- sus propias condiciones;
- su propio número documental.

La cotización anterior permanece intacta.

CotixGo puede mostrar diferencias entre la información anterior y la información actualmente disponible, pero **la decisión de revalidar las condiciones pertenece al profesional**.

---

**27. Regla transversal: “Lo que fue, fue”**

> **Una vez que un documento o registro histórico ha sido emitido o registrado, conserva la información que correspondía en ese momento. Los cambios posteriores en catálogos, precios, clientes, materiales, servicios o perfiles profesionales no modifican retroactivamente ese registro.**

Esta regla aplica, entre otros, a:

- Cotizaciones;
- Trabajos y su documentación histórica;
- Informes emitidos;
- Cuentas de Cobro;
- Pagos;
- movimientos de inventario.

Los datos maestros y catálogos sí pueden evolucionar.

Los documentos históricos deben conservar su propia representación de la información relevante en el momento correspondiente.

---

**28. Numeración de documentos**

Los documentos visibles para el usuario utilizarán el formato:

> **TIPO-AAAAMM-NÚMERO**

Ejemplos:

- `COT-202609-01`
- `JOB-202609-44`
- `INF-202609-87`
- `CC-202609-33`

Tipos definidos:

- `COT` — Cotización
- `JOB` — Trabajo
- `INF` — Informe
- `CC` — Cuenta de Cobro

El consecutivo corresponde al tipo de documento y al período correspondiente. No existe un único contador global para todos los documentos.

**28.1 Número documental e identificador técnico son conceptos diferentes**

El número visible del documento no debe utilizarse como identificador técnico principal.

Cada entidad debe tener un identificador técnico globalmente único, apto para funcionar offline.

Por tanto:

```text
ID técnico único
        +
Número documental visible
```

El ID técnico identifica la entidad dentro del sistema y la sincronización.

El número documental identifica al documento para el usuario.

**28.2 Fecha de creación y fecha de emisión**

La fecha en que se comienza a crear un documento y la fecha en que se confirma o emite no necesariamente son iguales.

Un documento puede permanecer como borrador antes de recibir su número documental definitivo, según las reglas de emisión de cada tipo de documento.

Esto permite, por ejemplo, que un Trabajo iniciado a finales de un mes pueda quedar confirmado y numerado en el mes siguiente.

---

**29. Estados, edición y transiciones**

Los documentos y procesos de CotixGo tienen ciclos de vida.

El modelo debe distinguir entre:

- estado operativo;
- estado documental;
- posibilidad de edición;
- congelación histórica.

**29.1 Regla general**

Mientras un proceso permanezca abierto, el usuario debe poder continuar registrando o modificando información cuando las reglas del estado lo permitan.

CotixGo no debe congelar prematuramente la información.

**29.2 Trabajo**

Un Trabajo puede encontrarse, conceptualmente, en estados como:

- en proceso;
- activo;
- entregado;
- cancelado.

Los nombres definitivos de los estados y sus transiciones se formalizarán posteriormente.

Mientras el Trabajo esté abierto puede recibir:

- actividades;
- materiales;
- fotografías;
- observaciones;
- cambios;
- adicionales.

**29.3 Adicionales y cambios de alcance**

Un cliente puede solicitar cambios o actividades adicionales durante la ejecución.

CotixGo debe permitir diferentes formas de gestión según el caso, incluyendo:

- modificar la cotización correspondiente;
- crear una nueva cotización para el adicional.

No debe existir una regla rígida de que una única cotización pueda originar obligatoriamente un único Trabajo.

Un Trabajo puede relacionarse con más de una Cotización cuando el negocio lo requiera.

---

**30. Informes de uno o varios Trabajos**

Un Informe puede documentar:

- un solo Trabajo; o
- varios Trabajos relacionados con el mismo cliente cuando resulte conveniente generar un informe global.

El Informe no sustituye a los Trabajos individuales.

Cada Trabajo debe conservar su propio registro de:

- actividades;
- materiales;
- fotografías;
- observaciones;
- fechas;
- información de ejecución.

El Informe puede funcionar como documento agregado que reúne la información de varios Trabajos.

---

**31. Cuentas de Cobro múltiples**

No debe existir una relación rígida de:

> un Trabajo = una Cuenta de Cobro.

CotixGo debe permitir:

- varias Cuentas de Cobro asociadas a un mismo Trabajo cuando corresponda;
- una Cuenta de Cobro que incluya uno o varios Trabajos, cuando el negocio lo requiera.

Esto permite manejar trabajos cobrados por partes o agrupaciones de trabajos.

**31.1 Consolidado de Cuentas de Cobro**

Normalmente se registra un Pago contra una Cuenta de Cobro. Para agrupar varias Cuentas del mismo Cliente, se utiliza un **Consolidado de Cuentas de Cobro**: conserva intactas las Cuentas originales, toma sus valores históricos, agrupa sus saldos y permite registrar pagos contra el saldo consolidado. No existe un mecanismo paralelo para registrar un Pago directamente sobre varias Cuentas.

**31.2 Reembolsos**

Un Reembolso es un nuevo movimiento de dinero y conserva intacto el Pago original. Puede ser parcial o total y registra fecha, valor, Método de Cobro utilizado y observación. No se cambia ni elimina el Pago original y V1 no crea un estado `REEMBOLSADA`.

---

**32. Inventario como control interno, no como restricción operativa**

El inventario es una herramienta de control interno.

**No debe impedir la operación del negocio.**

La falta de existencia registrada no debe bloquear:

- la ejecución de un Trabajo;
- el registro de materiales;
- la generación de un Informe;
- la generación de una Cuenta de Cobro;
- la entrega del Trabajo.

Puede ocurrir que el profesional:

- compre material adicional y todavía no registre la entrada;
- utilice material disponible físicamente que aún no aparece en el inventario registrado;
- tenga que realizar posteriormente un ajuste de inventario.

En esos casos CotixGo puede señalar:

> **Inventario pendiente de ajuste**

pero no debe impedir continuar con la documentación o el cobro.

**32.1 Inventario reservado**

La reserva de inventario puede utilizarse como herramienta de planificación.

Sin embargo, una reserva no debe convertirse en una restricción rígida que impida realizar un Trabajo.

El profesional debe poder continuar cuando la realidad operativa lo requiera.

CotixGo informa y ayuda a controlar; **no bloquea el trabajo por una inconsistencia de inventario interno**.

---

**33. Snapshot histórico de documentos**

Cuando un documento deba conservar su representación histórica, debe guardar o congelar la información relevante que correspondía al momento de emisión, confirmación o congelación.

Como mínimo, según corresponda al documento:

**Cliente**

- nombre;
- identificación;
- datos de contacto;
- dirección.

**Perfil Profesional**

- nombre;
- identificación;
- contacto;
- dirección;
- logo;
- información comercial o de pago relevante.

**Conceptos económicos**

- descripción;
- cantidad;
- unidad;
- precio unitario;
- componentes y resultados de retenciones aprobadas; cualquier tratamiento de impuestos permanece pendiente y no es requisito de V1;
- totales;
- información relevante sobre el origen de materiales.

**Fechas**

- creación;
- emisión o confirmación;
- ejecución;
- entrega;
- vencimiento cuando corresponda.

El documento histórico no debe depender de que los datos maestros actuales continúen iguales.

---

**34. Cierre histórico de la operación**

El congelamiento representa el cierre comercial e histórico de la operación. No se debe asumir que cada cambio de estado congela automáticamente todos los documentos relacionados.

**Trabajo**

El Trabajo puede permanecer editable mientras esté en ejecución.

Cuando el Trabajo haya sido entregado y la Cuenta de Cobro correspondiente esté completamente pagada, la operación queda cerrada e histórica. Los documentos históricos no deben modificarse como si la operación siguiera abierta.

**Cuenta de Cobro**

El Trabajo, Informe, Cotización y Cuenta de Cobro no se congelan automáticamente de manera individual por cada cambio de estado. Una garantía posterior no reabre automáticamente ninguno de esos documentos. Los datos históricos conservan la información de su momento de emisión o registro.

---

**35. Documentos offline**

La ausencia de Internet no debe impedir la operación de campo ni obligar al usuario a esperar conexión para generar documentación.

Android debe poder trabajar offline y, cuando sea técnicamente posible:

- registrar actividades;
- registrar materiales;
- tomar fotografías;
- preparar Informes;
- generar documentos;
- trabajar con Cotizaciones;
- trabajar con Trabajos;
- preparar Cuentas de Cobro.

Los archivos y documentos creados offline deben quedar asociados a sus entidades y pendientes de sincronización cuando corresponda.

La conexión se requiere para sincronizar con el servidor, no para que el usuario pueda continuar trabajando.

---

**36. Cuenta de usuario y recuperación en cambio de dispositivo**

La cuenta del usuario es el ancla de identidad de sus datos.

El teléfono es un dispositivo de trabajo, no el propietario lógico de la información.

Conceptualmente:

```text
CUENTA DE USUARIO
       │
   ┌───┴────┐
   │        │
Teléfono A  Teléfono B
   │        │
   └───SYNC─┘
       │
       ▼
SERVIDOR CENTRAL
```

Si el usuario pierde, cambia o reemplaza su teléfono, debe poder:

1. instalar CotixGo en el nuevo dispositivo;
2. iniciar sesión con la misma cuenta;
3. sincronizar sus datos;
4. recuperar su información.

El diseño no debe depender de identificadores exclusivos del dispositivo.

---

**37. Identificadores offline**

Las entidades deben utilizar identificadores técnicos que puedan generarse de manera segura sin conexión.

No se deben utilizar consecutivos simples como identificadores técnicos internos.

Dos dispositivos pueden crear registros offline simultáneamente y sus identificadores deben seguir siendo inequívocamente diferentes.

El número documental visible (`COT-202609-01`, etc.) es independiente del identificador técnico.

---

**38. Idempotencia y trazabilidad**

Las operaciones de sincronización deben ser idempotentes.

Si una misma operación es enviada más de una vez debido a una interrupción de red, el servidor no debe aplicar el efecto de la operación varias veces.

Ejemplo:

```text
Consumo real: 20 m
```

Si el dispositivo intenta sincronizar esa misma operación dos veces, CotixGo debe reconocer que se trata de la misma operación y evitar duplicar el consumo.

Todas las operaciones relevantes deben poder rastrearse para facilitar:

- sincronización;
- diagnóstico;
- recuperación;
- resolución de conflictos.

---

**39. Auditoría básica**

CotixGo debe mantener una trazabilidad básica de operaciones relevantes.

Como mínimo, el sistema debe poder determinar:

- quién realizó el cambio;
- cuándo ocurrió;
- sobre qué entidad ocurrió;
- qué operación se realizó;
- desde qué dispositivo cuando sea relevante;
- qué identificador de operación se utilizó para sincronización.

La auditoría debe servir especialmente para diagnosticar problemas de sincronización y mantener trazabilidad de cambios importantes.

---

**40. Historial de cambios**

### v0.2 — 30/09/2026

Se incorporan las decisiones aprobadas posteriormente a la versión fundacional v0.1:

- Catálogo reutilizable de Servicios.
- Creación manual de servicios desde una Cotización.
- Opción de guardar servicios nuevos en el catálogo.
- Vigencia configurable de Cotizaciones.
- Fecha de emisión y vencimiento.
- Tratamiento de Cotizaciones vencidas y revalidación.
- Regla transversal **“Lo que fue, fue.”**
- Numeración documental por tipo y período.
- Separación entre ID técnico y número documental.
- Ciclos de vida y edición de documentos.
- Adicionales y relación flexible entre Cotizaciones y Trabajos.
- Informes asociados a uno o varios Trabajos.
- Múltiples Cuentas de Cobro.
- Pagos aplicables a múltiples Cuentas de Cobro.
- Inventario como control interno no restrictivo.
- Inventario reservado como mecanismo flexible.
- Snapshot histórico de documentos.
- Reglas de congelación documental.
- Generación y trabajo offline con documentos y fotografías.
- Recuperación de datos mediante la cuenta del usuario al cambiar de dispositivo.
- Identificadores generables offline.
- Idempotencia de operaciones.
- Auditoría básica.

---

**41. Estado del documento**

**CotixGo — Reglas de Negocio v0.2**

Estado: **APROBADO**

Este documento constituye la referencia funcional actual para las siguientes etapas:

1. Modelo de Datos
2. Contrato de API
3. Estrategia de sincronización
4. Especificación UX
5. Estrategia de pruebas
6. Implementación

Los documentos técnicos posteriores deben implementar estas reglas y no contradecirlas.

Este documento no constituye todavía el modelo físico de base de datos, el contrato técnico de API ni la implementación de CotixGo.

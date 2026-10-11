# CotixGo Android — abrir, sincronizar, compilar y ejecutar desde Android Studio

## Prerrequisitos

- Android Studio (versión reciente; verificado en **2026.2.1**).
- Android SDK instalado (`local.properties` apunta a `sdk.dir`; ese archivo es
  local de cada máquina y no se versiona — Android Studio lo genera).
- **JDK 21 LTS** instalado (decisión D16): cualquier distribución vale
  (Oracle OpenJDK, Temurin, Microsoft, JetBrains JDK 21, etc.).
- Toolchain fijado: Gradle **8.11.1** (wrapper) + AGP **8.9.1** + Kotlin
  **2.0.21**. **No** migrar a Gradle/AGP 9.x ni ejecutar Gradle con
  JDK 25 / JBR 25.

## Abrir el proyecto

Abrir el **subdirectorio `android/`** del repositorio clonado, no la raíz:

```text
File → Open → <ruta-donde-clonaste-el-repo>\android
```

La raíz del repo no contiene `settings.gradle.kts`; abrirla impide el
Gradle Sync.

## Gradle JDK — fijarlo en JDK 21 (problema conocido)

Gradle 8.11.1 solo puede ejecutarse sobre JVM 17–23. Android Studio reciente
incluye **JBR 25** y puede seleccionar `jbr-25` como Gradle JVM por defecto,
lo que rompe el Gradle Sync / build (forma del error: `* What went wrong:
25.0.x`).

Corrección (solo configuración local del IDE; no se versiona ninguna ruta):

```text
Settings → Build, Execution, Deployment → Build Tools → Gradle
→ Gradle JDK: elegir la entrada "21" (un JDK 21 instalado en la máquina)
→ Apply → File → Sync Project with Gradle Files
```

- Si la lista no muestra un JDK 21: instala uno y usa **Add JDK…** en ese mismo
  desplegable para apuntar a tu directorio de instalación de JDK 21 (la ruta
  depende de cada máquina y no se versiona), o vuelve a abrir el desplegable
  para que el IDE lo detecte.
- Las entradas `jbr-*` resuelven al runtime empaquetado de Android Studio
  (`$APPLICATION_HOME_DIR$/jbr`); en versiones recientes eso es JBR 25 —
  **no seleccionarlas** como Gradle JDK.

El ajuste se guarda en `android/.idea/gradle.xml` (excluido por `.gitignore`);
cada máquina lo configura una vez.

## Compilar y ejecutar

- IDE: `Build → Make Project`; luego `Run 'app'` contra un emulador o
  dispositivo conectado.
- CLI (verificación equivalente; `JAVA_HOME` debe apuntar a tu JDK 21):

```bat
cd android
set JAVA_HOME=<ruta-de-tu-JDK-21>
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

APK resultante: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Notas

- `org.gradle.tooling.parallel=true` en `android/gradle.properties` fue
  añadido localmente por Android Studio (sync paralela de Gradle 9.4+);
  Gradle 8.11.1 la ignora. Es un cambio local, no se versiona.
- `org.gradle.java.home` **no** debe escribirse en archivos versionados
  (la ruta del JDK depende de cada máquina); usar el ajuste del IDE descrito
  arriba.

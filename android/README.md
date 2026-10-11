# CotixGo Android — abrir, sincronizar, compilar y ejecutar desde Android Studio

## Prerrequisitos

- Android Studio (versión reciente; verificado en **2026.2.1**).
- Android SDK instalado (`local.properties` apunta a `sdk.dir`; ese archivo es local y no se versiona).
- **JDK 21 LTS** (decisión D16). En esta máquina: `C:\Program Files\Java\jdk-21`.
- Toolchain fijado: Gradle **8.11.1** (wrapper) + AGP **8.9.1** + Kotlin **2.0.21**.
  **No** migrar a Gradle/AGP 9.x ni ejecutar Gradle con JDK 25 / JBR 25.

## Abrir el proyecto

Abrir el **subdirectorio `android/`**, no la raíz del repositorio:

```text
File → Open → D:\1 Proyectos 2026\CotixGo\android
```

La raíz del repo no contiene `settings.gradle.kts`; abrirla impide el Gradle Sync.

## Gradle JDK — fijarlo en JDK 21 (problema conocido)

Gradle 8.11.1 solo puede ejecutarse sobre JVM 17–23. Android Studio reciente
incluye **JBR 25** y puede seleccionar `jbr-25` como Gradle JVM por defecto,
lo que rompe el Gradle Sync / build (forma del error: `* What went wrong: 25.0.x`).

Corrección (solo configuración local del IDE; no se versiona ninguna ruta):

```text
Settings → Build, Execution, Deployment → Build Tools → Gradle
→ Gradle JDK: elegir la entrada "21" (C:\Program Files\Java\jdk-21)
→ Apply → File → Sync Project with Gradle Files
```

Ojo: la entrada llamada `jbr-21` apunta a `$APPLICATION_HOME_DIR$/jbr`, que en
AS 2026.2.1 resuelve al JBR 25 empaquetado — **no seleccionarla**. Elegir la
entrada `"21"` que resuelve a `C:\Program Files\Java\jdk-21`.

El ajuste se guarda en `android/.idea/gradle.xml` (excluido por `.gitignore`);
cada máquina lo configura una vez.

## Compilar y ejecutar

- IDE: `Build → Make Project`; luego `Run 'app'` contra un emulador o
  dispositivo conectado.
- CLI (verificación equivalente):

```bat
cd android
set JAVA_HOME=C:\Program Files\Java\jdk-21
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

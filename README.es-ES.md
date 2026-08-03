

[English](README.md) | [中文](README_zh.md)

# jd-mcp-duo

Un servidor y toolkit de CLI para MCP (Model Context Protocol) para descompilación de Java, impulsado por [transformer-api](https://github.com/nbauma109/transformer-api).

Construido sobre el conjunto de motores de descompilación de [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo), este proyecto expone las mismas capacidades de descompilación multi-motor y análisis estático a través de los modos MCP y CLI: modo MCP para agentes de IA, y modo CLI para uso directo en terminal o integración en scripts.

## Escenarios de Análisis de Seguridad

jd-mcp-duo envuelve descompilación, cadenas de llamadas estáticas, búsqueda de texto completo entre archivos y extracción de recursos en herramientas MCP, equipando a los agentes de IA de ingenieros de seguridad con las capacidades centrales necesarias para auditoría de código, análisis de dependencias y descubrimiento de vulnerabilidades: los JAR, WAR y APK de código cerrado ya no están fuera de alcance.

Ejemplos de escenarios de análisis de seguridad que este servicio MCP puede apoyar al conectarse a un agente de IA:

- **Auditoría de código cerrado** — El agente usa `save_all_sources` para descompilar un JAR/WAR/APK completo conservando la estructura de directorios del proyecto, y luego revisa la lógica comercial clase por clase

  ```bash
  save_all_sources --path=target.war --output=./target-src
  ```

- **Rastreo de taint (propagación de datos)** — El agente aprovecha el grafo de llamadas estáticas resueltas con CHA de `call_chain`, combinado con su propio conocimiento de fuentes de entrada y sumideros peligrosos, para rastrear el flujo de datos desde una entrada externa hasta operaciones sensibles como la ejecución de comandos

  ```bash
  call_chain --path=app.jar --className=com.example.Controller --methodName=upload --direction=callees --depth=5
  ```

- **Descubrimiento de secretos codificados** — El agente usa `search_in_jar` para buscar patrones de cadenas clave en los archivos, localizando claves API, tokens, cadenas de conexión a base de datos, claves privadas y otra información sensible codificados

  ```bash
  search_in_jar --path=app.jar --query='password' --type=string
  ```

- **Identificación de riesgos de dependencias** — El agente usa `list_dependencies` para extraer todas las coordenadas Maven incrustadas de un archivo, permitiendo cruzar con inteligencia de vulnerabilidades para identificar dependencias con vulnerabilidades conocidas

  ```bash
  list_dependencies --path=app.jar --format=text --output=deps.txt
  ```

- **Resolución de trazas de pila** — El agente usa `resolve_stacktrace` para resolver cada cuadro de una traza de excepción a una posición a nivel de línea en el código fuente descompilado, localizando rápidamente el código exacto que desencadenó la excepción

  ```bash
  resolve_stacktrace --path=app.jar --textPath=crash.log
  ```

## Arquitectura

```
jd-mcp-duo (este proyecto)
├── Capa de protocolo MCP — JSON-RPC 2.0 sobre stdio
├── Modo CLI — acceso por línea de comandos sin MCP
├── 34 herramientas — descompilar, buscar, analizar, comparar
├── Índice SQLite — grafo de llamadas y jerarquía de tipos entre archivos
└── Abstracción de archivos — entrada JAR/WAR/DEX/APK/directorio
```

A diferencia del de escritorio [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo) que tiene una interfaz Swing GUI y una arquitectura SPI multi-módulo, jd-mcp-duo es un servidor de módulo único optimizado para acceso programático vía MCP.

## Motores de descompilación

Todos los motores se acceden a través de `transformer-api`. El conjunto de motores incluye:

| Motor | Descripción |
|---|---|
| auto | Fallback multi-motor (predeterminado) — Vineflower → CFR → JD-Core v1+v0 patch → JADX |
| JD-Core v1 | Descompilador analítico con parcheo de métodos v0 en caso de falla |
| JD-Core v0 | Descompilador basado en patrones (fuente de parches de métodos para JD-Core v1) |
| CFR | Ampliamente compatible, salida estable |
| Procyon | Salida legible con opciones de números de línea |
| Fernflower | Descompilador analítico clásico |
| Vineflower | Descompilador moderno enfocado en Java con mayor precisión |
| JADX | Orientado a JVM + Android/DEX |

## Descarga

Cada [versión](https://github.com/AgeloVito/jd-mcp-duo/releases) incluye:

| Archivo | Para |
|---|---|
| `jd-mcp-duo.jar` | JAR sin dependencias — requiere JDK 25+ |
| `jd-mcp-duo-macos-arm64.tar.xz` | macOS Apple Silicon (M1/M2/M3) |
| `jd-mcp-duo-macos-x64.tar.xz` | macOS Intel |
| `jd-mcp-duo-linux-x64.tar.xz` | Linux x64 |
| `jd-mcp-duo-windows-x64.zip` | Windows x64 |

Los archivos de plataforma incluyen un JRE empaquetado (jlink) — no se requiere instalación de Java.

## Instalación

Descarga el archivo para tu plataforma, extrae y ejecuta:

```bash
# macOS / Linux
# macOS / Linux (elige el archivo correcto para tu plataforma)
tar xf jd-mcp-duo-macos-arm64.tar.xz
./jd-mcp-duo/bin/jd-mcp-duo --help

# Windows
# Extrae el zip, luego:
jd-mcp-duo\bin\jd-mcp-duo.bat --help
```

## Uso

### Modo servidor MCP

Configura tu cliente MCP. Elige una opción:

```bash
# Opción A: claude mcp add (recomendada)
claude mcp add --transport stdio --scope user jd-mcp-duo -- /path/to/jd-mcp-duo/bin/jd-mcp-duo

# Verifica que se haya añadido
claude mcp list
claude mcp get jd-mcp-duo
```

```json
// Opción B: directamente en ~/.claude.json
{
  "mcpServers": {
    "jd-mcp-duo": {
      "type": "stdio",
      "command": "/path/to/jd-mcp-duo/bin/jd-mcp-duo",
      "args": [],
      "env": {}
    }
  }
}
```

El servidor se comunica mediante JSON-RPC 2.0 sobre stdio. Soporta las versiones del protocolo MCP 2025-11-25, 2025-06-18, 2025-03-26 y 2024-11-05.

### Modo CLI

```bash
./bin/jd-mcp-duo <tool-name> [options]
```

## Herramientas

Todas las herramientas se pueden invocar vía CLI (`./bin/jd-mcp-duo <tool-name> [options]`) o a través de llamadas de herramientas MCP.

### Descompilación principal

**`decompile_class`** — Descompila una clase individual con metadatos estructurados.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta a un archivo .class, archivo o directorio |
| `className` | no | Nombre de la clase cuando path es un archivo o directorio (ej. `com.example.Main`) |

Si `path` apunta a un único archivo `.class`, `className` se infiere automáticamente. Para archivos y directorios, `className` selecciona qué clase descompilar.

```bash
./bin/jd-mcp-duo decompile_class --path=app.jar --className=com.example.Main
./bin/jd-mcp-duo decompile_class --path=com/example/Main.class
```

**`decompile_advanced`** — Descompila con resolución de tipos asistida por classpath. Resuelve dependencias desde archivos hermanos y módulos JDK para una salida más precisa.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada |
| `className` | no | Nombre de la clase cuando path es un archivo o directorio |
| `advancedLookup` | no | Habilitar resolución de dependencias de archivos hermanos y módulos JDK (predeterminado: false) |
| `classpath` | no | Entradas adicionales de classpath |

```bash
./bin/jd-mcp-duo decompile_advanced --path=app.jar --className=com.example.Main --advancedLookup=true
```

**`decompile_jar`** — Descompila todas las clases en un archivo y genera un resumen con estadísticas por motor para cada clase.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta a un archivo compatible |

```bash
./bin/jd-mcp-duo decompile_jar --path=app.jar --engine=vineflower
```

### Procesamiento por lotes

**`save_all_sources`** — Descompila cada clase en un archivo o directorio y escribe los resultados en un directorio o sources JAR. Los recursos no clase (XML, propiedades, imágenes, plantillas, etc.) se conservan junto con las fuentes descompiladas. La estructura de salida refleja la estructura del archivo de entrada.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Archivo o directorio de entrada |
| `output` | sí | Ruta del directorio de salida o sources JAR |
| `format` | no | `directory` (predeterminado) o `sources-jar` |
| `engine` | no | Motor de descompilación (predeterminado: auto) |

```bash
./bin/jd-mcp-duo save_all_sources --path=app.jar --output=./app-src
./bin/jd-mcp-duo save_all_sources --path=app.jar --output=sources.jar --format=sources-jar
```

**`decompile_directory`** — Escanea recursivamente un directorio en busca de archivos `.class` y archivos compatibles, los descompila en un directorio de destino conservando las rutas relativas. Los archivos no clase se copian tal cual.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Directorio de entrada |
| `outputDir` | sí | Directorio de salida |
| `recursive` | no | Escanear subdirectorios recursivamente (predeterminado: false) |

```bash
./bin/jd-mcp-duo decompile_directory --path=./input --outputDir=./output --engine=jadx
```

**`analyze_directory`** — Escanea un directorio en busca de archivos compatibles e informa el conteo y tamaño de clases sin descompilar. Soporta paginación `--offset`/`--limit`.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Directorio a analizar |
| `recursive` | no | Escanear subdirectorios recursivamente (predeterminado: false) |
| `limit` | no | Máximo de archivos por página (predeterminado: 200) |
| `offset` | no | Número de archivos a saltar para paginación (predeterminado: 0) |

```bash
./bin/jd-mcp-duo analyze_directory --path=./libs --limit=10 --offset=0
```

**`batch_decompile`** — Descompila clases desde una raíz de directorio, con `limit` y `outputDir` opcionales.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Raíz de directorio que contiene archivos de clase |
| `limit` | no | Máximo de clases a descompilar (predeterminado: 0 = todas) |

```bash
./bin/jd-mcp-duo batch_decompile --path=./classes --limit=200
```

**`batch_decompile_jars`** — Descompila clases a través de múltiples archivos en un directorio.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Directorio que contiene archivos |
| `recursive` | no | Escanear subdirectorios (predeterminado: false) |
| `pattern` | no | Filtro de patrón glob |

```bash
./bin/jd-mcp-duo batch_decompile_jars --path=./libs --recursive=true --pattern="*.jar"
```

### Inspección

**`index_scope`** — Construye o actualiza el índice SQLite entre archivos. Ejecútalo antes de `search_in_jar`, `call_chain`, etc. para evitar bloqueos durante las consultas.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Archivo principal o directorio |
| `scopePath` | no | Directorio adicional de archivos para indexar |
| `scopeRecursive` | no | Escanear scopePath recursivamente (predeterminado: false) |
| `indexPath` | no | Ruta personalizada del índice SQLite (predeterminado: `~/.jd-mcp-duo/index.sqlite`) |

```bash
./bin/jd-mcp-duo index_scope --path=./app.jar --scopePath=./lib --scopeRecursive=true
```

**`list_classes`** — Lista clases en un archivo o directorio con nombres normalizados y estadísticas de paquetes. Soporta paginación `--offset`/`--limit`.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta a un archivo o directorio |
| `limit` | no | Máximo de clases por página (predeterminado: 200) |
| `offset` | no | Número de clases a saltar para paginación (predeterminado: 0) |
| `package` | no | Filtro opcional de prefijo de paquete |
| `detailed` | no | Incluir estadísticas de paquetes (predeterminado: false) |

```bash
./bin/jd-mcp-duo list_classes --path=app.jar --limit=50 --offset=0
```

**`class_metadata`** — Inspecciona metadatos a nivel de clase: banderas de acceso, versión de bytecode, superclase, interfaces, nombre de módulo, métodos, campos, anotaciones y estadísticas del pool de constantes.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada |
| `className` | no | Nombre de la clase cuando path es un archivo o directorio |

```bash
./bin/jd-mcp-duo class_metadata --path=app.jar --className=com.example.Main
```

**`list_engines`** — Lista todos los motores de descompilación disponibles y sus alias.

```bash
./bin/jd-mcp-duo list_engines
```

**`describe_engine_options`** — Describe las opciones configurables para un motor de descompilación específico.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `engine` | sí | Nombre del motor (ej. `cfr`, `jadx`, `fernflower`) |

```bash
./bin/jd-mcp-duo describe_engine_options --engine=cfr
```

### Búsqueda y análisis

**`search_in_jar`** — Búsqueda basada en índice a través de clases, métodos, constructores, campos, constantes de cadena y archivos de recursos. Soporta comodines `*` y `?`. Los resultados incluyen rutas de archivos para búsquedas con ámbito. Soporta paginación `--offset`/`--limit` y `--output` para escribir resultados completos en un archivo.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `query` | sí | Término de búsqueda con soporte de comodines |
| `type` | no | Filtro: `type`, `class`, `constructor`, `method`, `field`, `string`, `module`, `resource`, `xml`, `properties`, `service`, `manifest`, `yaml`, `json`, `all` |
| `queryMode` | no | `plain` (predeterminado), `wildcard` o `regex` |
| `limit` | no | Máximo de resultados por página (predeterminado: 50) |
| `offset` | no | Número de resultados a saltar para paginación (predeterminado: 0) |
| `output` | no | Escribir resultados completos en un archivo (omite paginación) |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo search_in_jar --path=app.jar --query=getUser --type=method --limit=20 --offset=0
./bin/jd-mcp-duo search_in_jar --path=app.jar --query='password' --type=string --output=/tmp/keys.txt
```

**`type_lookup`** — Busca declaraciones de tipo por nombre exacto, comodín o regex en la entrada actual y ámbito opcional. Soporta paginación `--offset`/`--limit`.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `query` | sí | Nombre de tipo (exacto, `*patrón` o regex) |
| `queryMode` | no | `plain` (predeterminado), `wildcard` o `regex` |
| `limit` | no | Máximo de resultados por página (predeterminado: 50) |
| `offset` | no | Número de resultados a saltar para paginación (predeterminado: 0) |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo type_lookup --path=app.jar --query='*Service' --scopePath=./libs --limit=20 --offset=0
```

**`type_hierarchy`** — Construye árboles de jerarquía de supertipo y subtipo para una clase. Busca en todos los archivos del ámbito usando el índice SQLite.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `className` | no | Nombre de la clase |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo type_hierarchy --path=app.jar --className=com.example.BaseService --scopePath=./libs
```

**`find_references`** — Encuentra todas las referencias a un tipo, campo o método a través del ámbito indexado. Resuelve referencias de propietario de método, propietario de campo y uso de tipo desde instrucciones de bytecode.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `className` | sí | Nombre de la clase objetivo |
| `kind` | no | `type`, `method` o `field` |
| `methodName` o `fieldName` | no | Nombre de método o campo |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo find_references --path=app.jar --className=com.example.Util --kind=method --methodName=format
```

**`method_overrides`** — Encuentra relaciones de anulación e implementación para un método. Muestra qué métodos anulan al objetivo y qué métodos anula el objetivo.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `className` | sí | Nombre de la clase declarante |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo method_overrides --path=app.jar --className=com.example.BaseDao --methodName=save
```

**`resolve_symbol`** — Resuelve un símbolo de tipo, campo o método a nombres internos, descriptores JVM y declaraciones coincidentes. Maneja métodos sobrecargados listando todos los descriptores candidatos.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `className` | sí | Nombre de la clase declarante |
| `className` | no | Nombre de la clase |
| `methodName` o `fieldName` | no | Nombre de método o campo |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo resolve_symbol --path=app.jar --className=com.example.Service --methodName=process
```

**`resolve_stacktrace`** (alias: `analyze_log`) — Analiza texto de traza de pila Java o log, resuelve cada cuadro a su clase declarante, método, descriptor, archivos de origen candidatos y mapeos de número de línea descompilados.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `text` o `textPath` | sí | Texto de traza de pila o ruta de archivo .log |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo resolve_stacktrace --path=app.jar --textPath=stacktrace.log
./bin/jd-mcp-duo analyze_log --path=app.jar --textPath=stacktrace.log
```

**`source_lookup`** — Busca código fuente original desde un sources JAR local, `-sources.jar` hermano o Maven Central usando coordenadas SHA-1.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada principal |
| `className` | no | Nombre de la clase a buscar |

```bash
./bin/jd-mcp-duo source_lookup --path=app.jar --className=com.example.Main
```

**`call_chain`** — Construye una cadena estática de llamadores/llamados con recorrido BFS. Usa Análisis de Jerarquía de Clases (CHA) para resolver invocaciones de métodos virtuales (`invokevirtual`/`invokeinterface`) a implementaciones concretas dentro del ámbito indexado.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta a un archivo o directorio compatible |
| `className` | sí | Nombre de la clase declarante |
| `direction` | no | `callers`, `callees` o `both` (predeterminado: both) |
| `depth` | no | Profundidad de recursión (predeterminado: 3) |
| `maxNodes` | no | Máximo de nodos retornados (predeterminado: 128) |
| `scopePath` | no | Ruta de ámbito multi-archivo |

```bash
./bin/jd-mcp-duo call_chain --path=app.jar --className=com.example.Service --methodName=handle --direction=callees --depth=5
```

**`source_quality_report`** — Informa métricas de calidad de descompilación a través de clases en un archivo: tasa de éxito, motor usado, conteo de parches, tasa de fallback y advertencias.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Archivo o directorio de entrada |
| `engine` | no | Motor de descompilación a evaluar (predeterminado: auto) |
| `classLimit` | no | Máximo de clases a analizar (predeterminado: 100, 0 = ilimitado) |

```bash
./bin/jd-mcp-duo source_quality_report --path=app.jar
```

### Bytecode y flujo de control

**`show_bytecode`** — Muestra bytecode estilo javap para un método desde un archivo `.class`, directorio o archivo.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada |
| `className` | no | Nombre de la clase |

```bash
./bin/jd-mcp-duo show_bytecode --path=app.jar --className=com.example.Main --methodName=main --descriptor='([Ljava/lang/String;)V'
```

**`show_cfg`** — Genera un grafo de flujo de control de método como marcado Mermaid y datos de bordes estructurados.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada |
| `className` | no | Nombre de la clase |

```bash
./bin/jd-mcp-duo show_cfg --path=app.jar --className=com.example.Main --methodName=process
```

### Comparación

**`compare_jars`** — Compara dos archivos por tamaño de entrada y CRC-32, informando entradas agregadas, eliminadas y modificadas. Maneja JARs anidados dentro de WAR/EAR.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `jar1` | sí | Ruta del primer archivo |
| `jar2` | sí | Ruta del segundo archivo |

```bash
./bin/jd-mcp-duo compare_jars --jar1=v1/app.jar --jar2=v2/app.jar
```

**`compare_class`** — Compara código fuente descompilado para la misma clase bajo dos configuraciones de motor diferentes, o la misma clase a través de dos rutas de entrada diferentes.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `leftPath` | sí | Ruta de entrada izquierda |
| `rightPath` | no | Ruta derecha (por defecto leftPath) |
| `className` | no | Nombre de la clase |
| `leftEngine` | no | Motor para el lado izquierdo (predeterminado: auto) |
| `rightEngine` | no | Motor para el lado derecho (predeterminado: auto) |

```bash
./bin/jd-mcp-duo compare_class --leftPath=app.jar --className=com.example.Main --leftEngine=cfr --rightEngine=vineflower
```

**`compare_jd_core`** — Comparación lado a lado de la salida descompilada de JD-Core v0 y JD-Core v1 para la misma clase. Útil para investigar diferencias de descompilación y entender por qué fue necesario el parcheo de métodos.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta de entrada |
| `className` | no | Nombre de la clase |

```bash
./bin/jd-mcp-duo compare_jd_core --path=app.jar --className=com.example.Complex
```

### Generación de código y diagnósticos

**`build_skeleton`** — Genera un esqueleto de compilación Maven/Gradle desde uno o más archivos. Resuelve coordenadas de dependencias (groupId:artifactId:version) desde metadatos META-INF/maven, búsqueda SHA-1 en Maven Central y atributos de manifiesto.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Archivo principal o directorio |
| `files` | no | Rutas de archivos adicionales |
| `outputDir` | no | Directorio para escribir pom.xml, build.gradle y mvn_deploy.bat generados |

```bash
./bin/jd-mcp-duo build_skeleton --path=./libs --outputDir=./skeleton
```

**`list_dependencies`** — Escanea un archivo y lista dependencias Maven incrustadas encontradas bajo META-INF/maven/. Salida en formato JSON o texto GAV. Soporta paginación `--offset`/`--limit`.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta a un archivo compatible |
| `format` | no | `json` (predeterminado) o `text` (GAV por línea) |
| `output` | no | Ruta de archivo para escribir la lista de dependencias |
| `limit` | no | Máximo de dependencias por página (predeterminado: 500) |
| `offset` | no | Número de dependencias a saltar para paginación (predeterminado: 0) |

```bash
./bin/jd-mcp-duo list_dependencies --path=app.jar --format=text --output=deps.txt
```

**`compiler_diagnostics`** — Ejecuta el compilador Eclipse JDT en un archivo fuente Java o salida descompilada, informando errores, advertencias e indicadores de información. Útil para validar la corrección de la salida descompilada.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta a un archivo fuente .java |

```bash
./bin/jd-mcp-duo compiler_diagnostics --path=./src/com/example/Main.java
```

**`remove_unnecessary_casts`** — Elimina casts de tipo innecesarios desde código fuente Java o salida descompilada usando `CleanUpRefactoring` de Eclipse JDT. Informa qué casts fueron eliminados y el código fuente limpio.

| Parámetro | Requerido | Descripción |
|---|---|---|
| `path` | sí | Ruta a un archivo fuente .java |

```bash
./bin/jd-mcp-duo remove_unnecessary_casts --path=./src/com/example/Main.java
```

### Meta

**`help`** — Verificación de actividad MCP. Lista todas las herramientas disponibles con sus descripciones. En modo MCP, úsalo para verificar que el servidor está ejecutándose y descubrir herramientas disponibles.

| Parámetro | Requerido | Descripción |
|---|---|---|
| *(ninguno)* | — | Sin parámetros; retorna lista de herramientas |

```bash
./bin/jd-mcp-duo help
```

### Parámetros comunes

| Parámetro | Herramientas aplicables | Descripción |
|---|---|---|
| `engine` | herramientas de descompilación | Motor de descompilación: `auto`, `jd-core-v1`, `jd-core-v0`, `cfr`, `procyon`, `fernflower`, `vineflower`, `jadx` |
| `scopePath` | herramientas de búsqueda/análisis | Directorio o archivo para indexación de ámbito multi-archivo |
| `indexPath` | herramientas de búsqueda/análisis | Ruta personalizada del índice SQLite (predeterminado: `~/.jd-mcp-duo/index.sqlite`) |
| `verbose` | herramientas de descompilación por lotes | Incluir detalles por archivo en el resultado estructurado (predeterminado: false) |
| `releaseVersion` | herramientas de descompilación | Versión de clase multi-release objetivo (por defecto la versión Java actual) |
| `descriptor` | herramientas a nivel de método | Descriptor de método JVM para desambiguación de sobrecarga, ej. `(Ljava/lang/String;)V` |

## Formatos soportados

- `.class` — Archivos de clase Java
- `.jar` / `.war` / `.ear` / `.kar` — Archivos Java (incluyendo Spring Boot fat JARs)
- `.zip` — Archivos ZIP genéricos
- `.jmod` — Módulos Java
- `.aar` — Archivos Android
- `.apk` / `.dex` — Paquetes Android y archivos DEX

## Características principales

### Fallback multi-motor

Cuando el motor preferido falla (StackOverflowError, salida vacía, marcadores de descompilación), el motor auto intenta motores de fallback en un orden configurado. Los tiempos de espera por motor evitan descompilaciones colgadas.

### Parcheo de métodos JD-Core v1 + v0

Cuando JD-Core v1 produce salida con fallas de descompilación, los métodos individuales fallidos se parchean automáticamente desde la salida de JD-Core v0 usando fusión AST de Eclipse JDT.

### Índice SQLite multi-archivo

Un índice SQLite persistente almacena datos precalculados de clase, método, campo, cadena, referencia de tipo, grafo de llamadas y recursos a través de todos los archivos del ámbito. Esto permite búsqueda rápida, análisis de cadenas de llamadas y resolución de jerarquías de tipo sin reescanear bytecode.

El índice se actualiza incrementalmente mediante huellas de contenido — solo los archivos cambiados se reindexan.

### Preservación de recursos

Al descompilar archivos a directorios, los recursos no clase (configuraciones XML, propiedades, imágenes, MANIFEST.MF, plantillas, etc.) se conservan junto con los archivos `.java` descompilados, produciendo un esqueleto de proyecto completo.

### Cadena de llamadas estática con CHA

La herramienta `call_chain` realiza análisis de grafo de llamadas estáticas usando Análisis de Jerarquía de Clases (CHA) para resolver invocaciones de métodos virtuales (`invokevirtual`, `invokeinterface`) a implementaciones concretas dentro del ámbito indexado, y usa recorrido BFS para exploración balanceada del grafo bajo límites de nodos.

## Configuración

La ruta del índice SQLite por defecto es `~/.jd-mcp-duo/index.sqlite`. Cámbiala por herramienta vía `--indexPath`:

```bash
./bin/jd-mcp-duo search_in_jar --path=./lib --query=MyClass --indexPath=/d/projects/my-index.sqlite
```

O globalmente vía el script de lanzamiento o `JAVA_TOOL_OPTIONS`:

```bash
JAVA_TOOL_OPTIONS="-Djd.mcp.sqlite.index=/path/to/custom/index.sqlite" ./bin/jd-mcp-duo <tool-name> [options]
```

### Tamaño de pila (Stack size)

Para archivos grandes, aumenta el tamaño de pila del hilo:

```bash
java -Xss10m -jar jd-mcp-duo.jar <tool-name> [options]
```

## Créditos

Este proyecto se construye sobre las capacidades de descompilación de [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo) y comparte la misma dependencia principal, [transformer-api](https://github.com/nbauma109/transformer-api).

| Componente | Autor | Enlace | Licencia |
|---|---|---|---|
| transformer-api | nbauma109 | https://github.com/nbauma109/transformer-api | MIT |
| CFR | Lee Benfield | https://github.com/leibnitz27/cfr | MIT |
| Procyon | Mike Strobel | https://github.com/mstrobel/procyon | Apache v2 |
| Fernflower | JetBrains | https://github.com/JetBrains/intellij-community | Apache v2 |
| Vineflower | Vineflower | https://github.com/Vineflower/vineflower | Apache v2 |
| JADX | Skylot | https://github.com/skylot/jadx | Apache v2 |
| JD-GUI | Emmanuel Dupuy | https://github.com/java-decompiler/jd-gui | GPL v3 |

## Licencia

MIT

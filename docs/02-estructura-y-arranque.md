# 02 — Estructura del proyecto y arranque

---

## 1. El árbol de carpetas

```
api/
├── pom.xml                      ← el "composer.json" + configuración de build
├── mvnw / mvnw.cmd              ← Maven Wrapper: ejecuta Maven sin instalarlo
├── .mvn/wrapper/                ← configuración del wrapper
├── target/                      ← salida de la compilación (NO se versiona)
│
└── src/
    ├── main/
    │   ├── java/com/gestion/eventos/api/     ← TODO el código
    │   │   ├── ApiApplication.java           ← punto de entrada
    │   │   ├── controller/                   ← capa web (3 clases)
    │   │   ├── service/                      ← lógica de negocio (6 archivos)
    │   │   ├── repository/                   ← acceso a datos (5 interfaces)
    │   │   ├── domain/                       ← entidades JPA (5 clases)
    │   │   ├── dto/                          ← objetos de transporte (5 clases)
    │   │   ├── mapper/                       ← conversión entidad⇄DTO (5 interfaces)
    │   │   ├── exception/                    ← manejo de errores (2 clases)
    │   │   ├── data/                         ← datos iniciales (1 clase)
    │   │   └── security/                     ← todo lo de autenticación
    │   │       ├── config/SecurityConfig.java
    │   │       ├── controller/AuthController.java
    │   │       ├── dto/                      ← LoginDto, RegisterDto, JwtAuthResponseDto
    │   │       ├── jwt/                      ← JwtGenerator, filtro, entry point
    │   │       └── service/UserDetailsServiceImpl.java
    │   │
    │   └── resources/
    │       └── application.properties        ← configuración (el ".env" + "config/packages")
    │
    └── test/
        └── java/com/gestion/eventos/api/
            └── ApiApplicationTests.java
```

### La estructura `src/main/java` no es opcional

Maven impone esta estructura ("convención sobre configuración"):

| Carpeta | Qué va ahí |
|---|---|
| `src/main/java` | Código fuente que se empaqueta en el `.jar` |
| `src/main/resources` | Ficheros no-Java que van al `.jar` (properties, YAML, SQL, plantillas) |
| `src/test/java` | Tests. **No** se empaquetan |
| `target/` | Todo lo generado: `.class`, el `.jar` final, el código generado por MapStruct |

Comparación con Symfony:

| Symfony | Este proyecto |
|---|---|
| `src/` | `src/main/java/com/gestion/eventos/api/` |
| `config/packages/*.yaml` | `src/main/resources/application.properties` |
| `public/index.php` | No existe — el servidor va embebido en `ApiApplication` |
| `tests/` | `src/test/java/...` |
| `var/`, `vendor/` | `target/`, `~/.m2/repository/` |

### El paquete base importa mucho

Todo el código cuelga de `com.gestion.eventos.api`. **Esto no es decorativo**: la clase
`ApiApplication` está en ese paquete raíz, y Spring escanea *hacia abajo* desde ahí buscando
componentes. Si crearas una clase en `com.otracosa.Servicio`, Spring **no la encontraría** y no
sería un bean. Lo veremos en la sección 4.

---

## 2. `pom.xml` — el corazón de Maven

Vamos por partes el archivo real: [pom.xml](../pom.xml).

### 2.1 El `<parent>`

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
</parent>
```

Este proyecto usa **Spring Boot 4.1.0**, que es una versión reciente. El `parent` hace dos cosas
enormes:

1. **Gestión de versiones (BOM).** Fíjate en que casi ninguna dependencia lleva `<version>`:

   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-data-jpa</artifactId>
   </dependency>   <!-- ¿versión? La decide el parent -->
   ```

   El parent sabe qué versión de Hibernate, Jackson, Tomcat, etc. son compatibles entre sí en Boot
   4.1.0. Esto te ahorra el infierno de incompatibilidades. En Composer tendrías que ir fijando
   versiones tú con `^` y `~`.

2. **Configuración de plugins por defecto** (compilador, empaquetado, tests).

### 2.2 Las `<properties>`

```xml
<java.version>21</java.version>
<org.mapstruct.version>1.6.3</org.mapstruct.version>
<lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
```

Variables reutilizables. `java.version=21` le dice al compilador que use Java 21.

### 2.3 Las dependencias, una a una

| Dependencia | Para qué sirve en ESTE proyecto |
|---|---|
| `spring-boot-starter-webmvc` | Servidor Tomcat embebido + Spring MVC + Jackson (JSON). Sin esto no hay API REST. **Nota:** en Spring Boot 2 y 3 este starter se llamaba `spring-boot-starter-web`; en Boot 4 se renombró a `-webmvc`. Si sigues tutoriales antiguos, verás el nombre viejo. |
| `spring-boot-starter-data-jpa` | Hibernate + Spring Data JPA + gestión de transacciones + pool de conexiones HikariCP. Es lo que hace funcionar `domain/`, `repository/` y `@Transactional`. |
| `spring-boot-starter-security` | Spring Security: la cadena de filtros, `BCryptPasswordEncoder`, `@PreAuthorize`… |
| `spring-boot-starter-validation` | Hibernate Validator: hace funcionar `@NotBlank`, `@Email`, `@Size` y `@Valid`. |
| `spring-boot-h2console` | Habilita la consola web de H2 en `/h2-console`. (En Boot 3 esto venía dentro del starter web; en Boot 4 es un artefacto aparte.) |
| `com.h2database:h2` (`runtime`) | La base de datos H2. `runtime` = se necesita al ejecutar, pero no al compilar (tu código nunca importa clases de H2). |
| `org.postgresql:postgresql` (`runtime`) | Driver de PostgreSQL. **Está declarado pero no se usa**: `application.properties` apunta a H2. Está preparado para el día que cambies de base de datos. |
| `org.mapstruct:mapstruct` | Las anotaciones `@Mapper`, `@Mapping`… (ver doc 08) |
| `org.projectlombok:lombok` (`optional`) | `@Data`, `@RequiredArgsConstructor`… `optional=true` significa que quien use tu jar no la hereda: Lombok solo hace falta al compilar. |
| `io.jsonwebtoken:jjwt-api` / `-impl` / `-jackson` (0.13.0) | Creación y validación de JWT. `api` es la interfaz pública, `impl` la implementación y `jackson` el serializador JSON de los claims. |
| `spring-boot-starter-data-jpa-test` (`test`) | Utilidades de test para JPA. |
| `spring-boot-starter-webmvc-test` (`test`) | JUnit 5, Mockito, AssertJ, `MockMvc`… (En Boot 3 todo esto era `spring-boot-starter-test`.) |

**El concepto de *scope*** (equivalente a `require` vs `require-dev` de Composer, pero con más
matices):

| Scope | Disponible al compilar | Disponible al ejecutar | En el jar final |
|---|---|---|---|
| `compile` (por defecto) | ✅ | ✅ | ✅ |
| `runtime` | ❌ | ✅ | ✅ |
| `test` | solo en tests | solo en tests | ❌ |
| `provided` | ✅ | ❌ (lo aporta el servidor) | ❌ |

### 2.4 Qué es un *starter* exactamente

Un *starter* de Spring Boot **no contiene código**. Es un `pom.xml` que:

1. Declara un grupo coherente de dependencias.
2. Activa **autoconfiguración**: clases que dicen "si veo `HikariDataSource` en el classpath y no
   hay un `DataSource` definido por el usuario, creo uno automáticamente".

Ejemplo real de lo que hace `spring-boot-starter-data-jpa` sin que tú escribas nada:
- crea un `DataSource` con pool HikariCP leyendo `spring.datasource.*`
- crea un `EntityManagerFactory` de Hibernate
- crea un `JpaTransactionManager` (lo que hace funcionar `@Transactional`)
- activa el escaneo de `@Entity` y de repositorios

Esto es el equivalente a un **bundle** de Symfony con su `Extension` y su `Configuration`, pero
mucho más agresivo: Symfony te pide activar el bundle en `bundles.php` y configurarlo en
`config/packages/doctrine.yaml`; Spring Boot detecta el classpath y decide solo.

> **Regla de oro de la autoconfiguración:** *"Si tú no lo defines, yo lo defino por ti. Si tú lo
> defines, me aparto."* Técnicamente esto se implementa con `@ConditionalOnMissingBean`.

### 2.5 El bloque `<build>` — aquí pasa algo importante

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    ...
    <annotationProcessorPaths>
        <path>...lombok...</path>
        <path>...lombok-mapstruct-binding...</path>
        <path>...mapstruct-processor...</path>
    </annotationProcessorPaths>
</plugin>
```

Esto configura los **procesadores de anotaciones**: programas que se ejecutan *durante la
compilación* y **generan código Java nuevo**.

- **Lombok** lee `@Data` y añade getters/setters al bytecode.
- **MapStruct** lee `@Mapper` y **escribe archivos `.java` nuevos** (por ejemplo
  `EventMapperImpl.java`) en `target/generated-sources/annotations/`.
- **`lombok-mapstruct-binding`** existe porque los dos anteriores se pisan: MapStruct necesita ver
  los getters que Lombok genera. Este artefacto coordina el orden. Sin él, MapStruct generaría
  mappers vacíos.

> **No hay nada equivalente en PHP.** PHP no tiene una fase de compilación donde se pueda generar
> código. Lo más parecido sería el compilador de contenedor de Symfony, que sí genera PHP en
> `var/cache/`.

**Ejercicio muy recomendable:** después de compilar, abre
`target/generated-sources/annotations/com/gestion/eventos/api/mapper/EventMapperImpl.java`. Vas a
ver, escrito en Java normal y corriente, todo el `new EventResponseDto(); dto.setName(event.getName()); ...`
que MapStruct escribió por ti. Es la mejor forma de perderle el miedo a la "magia".

---

## 3. `application.properties` — la configuración

Archivo real: [application.properties](../src/main/resources/application.properties)

```properties
spring.application.name=api
spring.jpa.show-sql=true
spring.jpa.hibernate.ddl-auto=update
jwt.secret=UnaClaveMuySecretaParaFirmarTusJWTTokens...
jwt.expiration=600000

spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

spring.datasource.url=jdbc:h2:mem:gestioneventosdb
spring.datasource.username=sa
spring.datasource.password=admin1234
spring.datasource.driverClassName=org.h2.Driver
spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
```

Línea por línea:

| Propiedad | Qué hace |
|---|---|
| `spring.application.name=api` | Nombre lógico de la app (aparece en logs y en herramientas de monitorización). |
| `spring.jpa.show-sql=true` | **Muy útil para aprender:** imprime en consola cada SQL que Hibernate ejecuta. Es como el profiler de Doctrine, en versión pobre. |
| `spring.jpa.hibernate.ddl-auto=update` | Hibernate **crea y modifica las tablas solo**, a partir de tus `@Entity`. Ver aviso abajo. |
| `jwt.secret` / `jwt.expiration` | Propiedades **tuyas**, no de Spring. Las lee `JwtGenerator` con `@Value`. `600000` ms = 10 minutos. |
| `spring.h2.console.enabled/path` | Activa la consola web de H2 en `/h2-console`. |
| `spring.datasource.url=jdbc:h2:mem:gestioneventosdb` | ⚠️ **`mem:`** = base de datos **en memoria**. Se destruye al parar la aplicación. |
| `spring.datasource.username/password` | Credenciales de H2 (necesarias para entrar en la consola). |
| `spring.jpa.hibernate.naming.physical-strategy=...Standard...` | Desactiva la conversión automática camelCase→snake_case de los nombres de columna. Ver abajo. |

### ⚠️ Tres avisos importantes sobre esta configuración

**a) La base de datos se borra en cada reinicio.** `jdbc:h2:mem:` significa que todo vive en RAM.
Por eso existe [DataLoader.java](../src/main/java/com/gestion/eventos/api/data/DataLoader.java):
recrea los roles, usuarios, categorías, ponentes y 60 eventos en cada arranque. Es perfecto para
aprender, y esa es exactamente su función aquí.

**b) `ddl-auto=update` no se usa en producción.** Los valores posibles:

| Valor | Comportamiento |
|---|---|
| `none` | No toca el esquema |
| `validate` | Comprueba que las tablas coinciden con las entidades, y falla si no |
| `update` | Añade tablas/columnas que falten. **Nunca borra ni modifica lo existente** |
| `create` | Borra y recrea el esquema al arrancar |
| `create-drop` | Como `create`, y además borra al parar |

`update` es traicionero: si renombras un campo, te crea una columna nueva y deja la vieja con los
datos. En producción se usa **Flyway** o **Liquibase**, que son el equivalente a
`doctrine:migrations` de Symfony.

**c) La estrategia de nombres.** Por defecto, Hibernate en Spring Boot convierte `categoryId` en
`category_id`. La línea `PhysicalNamingStrategyStandardImpl` **desactiva** eso: las columnas se
llaman exactamente como el campo Java (`name`, `date`, `location`, `categoryId`…). Es una decisión
válida, pero conviene saberla: si escribes SQL a mano en la consola de H2, los nombres de columna
son camelCase, no snake_case.

### La otra opción: `application.yml`

Exactamente lo mismo se puede escribir en YAML (`application.yml`), que se parece más a lo que
conoces de Symfony:

```yaml
spring:
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: update
  datasource:
    url: jdbc:h2:mem:gestioneventosdb
jwt:
  secret: ...
  expiration: 600000
```

Es cuestión de gustos. `.properties` es plano; `.yml` es jerárquico y evita repetir prefijos.

### Perfiles: el equivalente a `APP_ENV`

En Symfony tienes `config/packages/dev/`, `config/packages/prod/`. En Spring:

- `application.properties` → siempre se carga
- `application-dev.properties` → solo si el perfil `dev` está activo
- `application-prod.properties` → solo si el perfil `prod` está activo

Se activa con `spring.profiles.active=dev` o `java -jar api.jar --spring.profiles.active=prod`.

Este proyecto **no usa perfiles todavía**; sería la evolución natural (H2 en `dev`, PostgreSQL en
`prod`, que para eso está el driver de Postgres ya declarado).

### 🔐 Sobre el secreto JWT

`jwt.secret` está escrito en claro y versionado en Git. Para aprender, no pasa nada. Pero que sepas
que en un proyecto real ese valor iría en una variable de entorno:

```properties
jwt.secret=${JWT_SECRET}
```

Spring resuelve `${JWT_SECRET}` desde las variables de entorno del sistema, igual que
`%env(JWT_SECRET)%` en Symfony.

---

## 4. El arranque: qué pasa exactamente al ejecutar la app

### 4.1 La clase de arranque

[ApiApplication.java](../src/main/java/com/gestion/eventos/api/ApiApplication.java) — 13 líneas que
lo mueven todo:

```java
@SpringBootApplication
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
```

`public static void main` es el punto de entrada estándar de cualquier programa Java (equivalente
conceptual a `public/index.php`).

### 4.2 `@SpringBootApplication` = tres anotaciones en una

```java
@SpringBootApplication
// ≡
@Configuration          // esta clase puede declarar beans con @Bean
@EnableAutoConfiguration // activa la autoconfiguración basada en el classpath
@ComponentScan          // escanea ESTE paquete y sus subpaquetes buscando componentes
```

**`@ComponentScan` es la razón por la que tu código tiene que colgar de
`com.gestion.eventos.api`.** Spring toma el paquete de la clase anotada como raíz y busca hacia
abajo. Todo lo que encuentre con `@Component`, `@Service`, `@Repository`, `@Controller`,
`@RestController` o `@Configuration` se convierte en un bean.

> Equivalencia Symfony: es el bloque de `services.yaml` que dice
> ```yaml
> App\:
>     resource: '../src/'
> ```
> Solo que en Spring no hace falta escribirlo: lo hace `@SpringBootApplication`.

### 4.3 La secuencia completa del arranque

```
1.  SpringApplication.run(...)
      │
2.  ├─ Detecta el tipo de aplicación → "web servlet" (porque hay spring-boot-starter-webmvc)
      │
3.  ├─ Crea el ApplicationContext (el contenedor)
      │
4.  ├─ @ComponentScan: escanea com.gestion.eventos.api.**
      │    └─ encuentra EventController, EventService, CategoryServiceImpl, SpeakerServiceImpl,
      │       UserDetailsServiceImpl, JwtGenerator, JwtAuthenticationFilter, JwtAuthEntryPoint,
      │       DataLoader, GlobalExceptionHandler, SecurityConfig,
      │       EventMapperImpl/CategoryMapperImpl/... (los que generó MapStruct)
      │
5.  ├─ @EnableAutoConfiguration: aplica ~150 autoconfiguraciones según el classpath
      │    ├─ DataSourceAutoConfiguration    → crea el DataSource (pool Hikari) desde spring.datasource.*
      │    ├─ HibernateJpaAutoConfiguration  → crea el EntityManagerFactory
      │    │                                   → Hibernate lee las @Entity y aplica ddl-auto=update
      │    │                                     (¡AQUÍ SE CREAN LAS TABLAS!)
      │    ├─ TransactionAutoConfiguration   → crea el JpaTransactionManager (hace vivir a @Transactional)
      │    ├─ JpaRepositoriesAutoConfiguration → genera las implementaciones de los 5 repositorios
      │    ├─ SecurityAutoConfiguration      → arranca Spring Security con tu SecurityConfig
      │    ├─ JacksonAutoConfiguration       → configura la serialización JSON
      │    ├─ ValidationAutoConfiguration    → arranca Hibernate Validator
      │    └─ H2ConsoleAutoConfiguration     → publica /h2-console
      │
6.  ├─ Instancia TODOS los beans singleton, resolviendo el grafo de dependencias:
      │    EventController necesita IEventService y EventMapper
      │      → EventService necesita EventRepository, EventMapper, CategoryService, SpeakerService
      │        → CategoryServiceImpl necesita CategoryRepository
      │          → CategoryRepository ya está creado (paso 5)
      │    Spring resuelve el orden solo. Si hay un ciclo, FALLA AL ARRANCAR (no en runtime).
      │
7.  ├─ Envuelve en PROXIES los beans que llevan @Transactional / @PreAuthorize  ← ver doc 04
      │
8.  ├─ Arranca Tomcat embebido en el puerto 8080
      │    └─ registra el DispatcherServlet en "/"
      │    └─ registra la cadena de filtros de Spring Security
      │
9.  ├─ Ejecuta todos los CommandLineRunner  → ¡AQUÍ CORRE DataLoader.run()!
      │    └─ crea roles, admin, user, 3 categorías, 2 ponentes, 60 eventos
      │
10. └─ "Started ApiApplication in 3.8 seconds" → la app queda escuchando
```

Los pasos 5, 6 y 7 son lo que Symfony hace al compilar el contenedor en `var/cache/`. La diferencia
es que Spring lo hace **en memoria, en cada arranque**, y con muchísima más introspección.

### 4.4 `DataLoader`: los fixtures de este proyecto

[DataLoader.java](../src/main/java/com/gestion/eventos/api/data/DataLoader.java) implementa
`CommandLineRunner`, una interfaz de Spring Boot con un solo método:

```java
@Component
@RequiredArgsConstructor
public class DataLoader implements CommandLineRunner {
    @Override
    @Transactional
    public void run(String... args) throws Exception { ... }
}
```

**Spring Boot busca todos los beans que implementen `CommandLineRunner` y ejecuta su `run()` justo
después de terminar el arranque.** No hay que registrarlo en ningún sitio: basta con `@Component`.

Lo que hace, en orden:

1. Crea (si no existen) los roles `ROLE_ADMIN` y `ROLE_USER`.
2. Crea el usuario `admin` (contraseña `admin1234`, con ambos roles) y `user` (contraseña `123456`,
   solo `ROLE_USER`). **Las contraseñas se guardan hasheadas con BCrypt**, nunca en claro.
3. Crea 3 categorías: Conferencia, Taller, Webinar.
4. Crea 2 ponentes: John Doe y Jane Smith.
5. Si la tabla de eventos está vacía, genera **60 eventos** con fechas escalonadas, salas rotativas,
   categoría asignada por `i % 3` y ponentes por `i % 2`. Los 60 eventos existen para que la
   **paginación** tenga algo que paginar.

Fíjate en el patrón que se repite:

```java
Role adminRole = roleRepository.findByName("ROLE_ADMIN")
        .orElseGet(() -> {
            Role newRole = new Role();
            newRole.setName("ROLE_ADMIN");
            return roleRepository.save(newRole);
        });
```

"Búscalo; si no existe, créalo y devuélvemelo." Es **idempotente**: puedes ejecutarlo mil veces sin
duplicar datos. Buena práctica que conviene copiar.

> Equivalente Symfony: `DoctrineFixturesBundle` + `bin/console doctrine:fixtures:load`. La
> diferencia es que aquí se ejecuta **automáticamente en cada arranque**, no bajo demanda.

---

## 5. Comandos útiles

```powershell
# Arrancar la aplicación en modo desarrollo
.\mvnw.cmd spring-boot:run

# Compilar sin ejecutar (útil para ver el código generado por MapStruct/Lombok)
.\mvnw.cmd clean compile

# Ejecutar los tests
.\mvnw.cmd test

# Construir el .jar ejecutable (queda en target/api-0.0.1-SNAPSHOT.jar)
.\mvnw.cmd clean package

# Ejecutar el jar ya construido
java -jar target\api-0.0.1-SNAPSHOT.jar

# Ver el árbol de dependencias (equivalente a composer show --tree)
.\mvnw.cmd dependency:tree
```

Para entrar en la consola de H2 mientras la app corre:
`http://localhost:8080/h2-console`, con JDBC URL `jdbc:h2:mem:gestioneventosdb`, usuario `sa`,
contraseña `admin1234`.

---

← [01 — Spring Boot vs Symfony](01-springboot-vs-symfony.md) | **Siguiente:** [03 — El contenedor de Spring: Beans e Inyección](03-contenedor-beans-inyeccion.md)

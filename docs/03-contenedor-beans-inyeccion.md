# 03 — El contenedor de Spring: Beans e Inyección de Dependencias

> **Aquí se responde tu pregunta sobre `@Bean`.**
> Este es el concepto central de Spring. Si entiendes esto, entiendes el 60% del framework.

---

## 1. ¿Qué es un *bean*?

Un **bean** es simplemente **un objeto que crea y gestiona Spring en lugar de crearlo tú con `new`**.

Eso es todo. No hay más misterio. Es exactamente el mismo concepto que un **servicio** en Symfony.

```java
// SIN Spring — tú lo controlas todo:
CategoryRepository repo = new CategoryRepositoryImpl(entityManager);
CategoryService service = new CategoryServiceImpl(repo);
CategoryMapper mapper = new CategoryMapperImpl();
CategoryController controller = new CategoryController(service, mapper);
// ... y así con las 36 clases, en el orden correcto, sin equivocarte

// CON Spring — tú declaras qué necesitas, Spring lo construye:
@RestController
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;   // "necesito esto"
    private final CategoryMapper categoryMapper;     // "y esto"
}
```

## 2. Inversión de Control (IoC): el nombre elegante

**Control tradicional:** tu código decide cuándo y cómo se crean sus dependencias (`new Servicio()`).

**Control invertido:** el framework decide. Tu código solo *declara* qué necesita.

El objeto que hace ese trabajo se llama **contenedor IoC**, y en Spring su nombre técnico es
**`ApplicationContext`**. Es el equivalente exacto al `Container` de Symfony.

```
                    ApplicationContext
     ┌────────────────────────────────────────────────┐
     │  "eventController"      → EventController@1a2b │
     │  "eventService"         → EventService$$Proxy  │
     │  "categoryServiceImpl"  → CategoryServiceImpl  │
     │  "eventRepository"      → SimpleJpaRepository  │
     │  "eventMapperImpl"      → EventMapperImpl@9f3  │
     │  "passwordEncoder"      → BCryptPasswordEncoder│
     │  "filterChain"          → DefaultSecurityFilte…│
     │  "dataSource"           → HikariDataSource     │
     │  ... (~200 beans en total, contando los de Boot)│
     └────────────────────────────────────────────────┘
```

Cada bean tiene un **nombre** (por defecto, el nombre de la clase en camelCase) y un **tipo**.
Spring inyecta **por tipo** en primer lugar; si hay ambigüedad, desempata por nombre.

### ¿Por qué molestarse? Tres razones concretas

1. **No repites el cableado.** `CategoryRepository` se construye una vez y se comparte con quien lo
   pida. En este proyecto `CategoryService` lo usan `CategoryController` **y** `EventService`, y
   solo existe una instancia.
2. **Puedes cambiar implementaciones sin tocar el código que las usa.** Si mañana
   `CategoryServiceImpl` se sustituye por `CategoryServiceCacheadoImpl`, `CategoryController` no
   cambia ni una línea.
3. **Puedes testear.** En un test inyectas un doble (mock) en lugar del objeto real. Con `new`
   dentro del código eso es imposible.

---

## 3. Cómo se declara un bean: las dos vías

Spring tiene **dos formas** de registrar beans, y este proyecto usa las dos. Entender cuándo se usa
cada una es exactamente tu pregunta.

### VÍA A — Estereotipos: anotar la clase

Anotas **tu propia clase** y Spring la registra al escanear.

| Anotación | Dónde se usa en este proyecto | Significado |
|---|---|---|
| `@Component` | `JwtGenerator`, `JwtAuthenticationFilter`, `JwtAuthEntryPoint`, `DataLoader` | Genérica: "esto es un bean". |
| `@Service` | `EventService`, `CategoryServiceImpl`, `SpeakerServiceImpl`, `UserDetailsServiceImpl` | "Esto es lógica de negocio". |
| `@Repository` | *(ninguna clase; los repositorios son interfaces — ver doc 06)* | "Esto es acceso a datos". Además **traduce las excepciones** propias de la BD a las excepciones estándar de Spring (`DataAccessException`). |
| `@Controller` / `@RestController` | `EventController`, `CategoryController`, `SpeakerController`, `AuthController` | "Esto atiende peticiones HTTP". |
| `@Configuration` | `SecurityConfig` | "Esta clase declara beans con métodos `@Bean`". |
| `@ControllerAdvice` | `GlobalExceptionHandler` | "Esto intercepta excepciones de todos los controladores". |

**Dato importante:** `@Service`, `@Repository`, `@Controller` y `@Configuration` **son todas
`@Component` por dentro**. Míralo así:

```java
@Target(ElementType.TYPE)
@Component            // ← @Service ES un @Component
public @interface Service { ... }
```

Entonces, ¿para qué diferenciarlas si técnicamente hacen lo mismo?

1. **Documentan la intención.** Ver `@Service` te dice "aquí hay reglas de negocio" sin leer el
   código.
2. **Algunas añaden comportamiento real.** `@Repository` traduce excepciones. `@Configuration`
   activa el proxy CGLIB que hace que los métodos `@Bean` devuelvan siempre la misma instancia.
3. **Permiten filtrar.** Puedes aplicar aspectos (AOP) a "todos los `@Service`".

> **Equivalente Symfony:** en Symfony todas tus clases de `src/` son servicios automáticamente por
> el `autoconfigure`/`resource` de `services.yaml`. No hay estereotipos. Spring te obliga a marcar
> explícitamente qué es un bean, lo cual es más verboso pero también más explícito.

---

### VÍA B — `@Bean`: un método que fabrica el objeto

**Y aquí está la respuesta a tu pregunta.**

```java
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

Esto significa: *"Spring, cuando alguien pida un `PasswordEncoder`, ejecuta este método y usa lo que
devuelva. Ejecútalo **una sola vez** y guarda el resultado."*

El bean resultante:
- se llama `passwordEncoder` (el nombre del método)
- es de tipo `PasswordEncoder` (el tipo de retorno)
- es un singleton

### 🎯 ¿Cuándo `@Bean` y cuándo `@Service`/`@Component`?

**Regla clarísima:**

| Situación | Usa |
|---|---|
| La clase es **tuya** y puedes anotarla | `@Component` / `@Service` / `@Repository` |
| La clase **no es tuya** (viene de una librería) y no puedes ponerle anotaciones | **`@Bean`** |
| Necesitas **lógica** para construir el objeto (leer config, elegir implementación, encadenar llamadas) | **`@Bean`** |
| Quieres **varios beans del mismo tipo** con distinta configuración | **`@Bean`** (varios métodos) |

Miremos los **cuatro `@Bean` reales** de este proyecto, en
[SecurityConfig.java](../src/main/java/com/gestion/eventos/api/security/config/SecurityConfig.java),
y por qué cada uno **tiene** que ser `@Bean`:

#### `@Bean` nº 1 — `passwordEncoder()`

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

**Por qué es `@Bean`:** `BCryptPasswordEncoder` es una clase de **Spring Security**, no tuya. No
puedes abrirla y ponerle `@Component`. La única forma de meterla en el contenedor es fabricarla en
un método.

Además fíjate en un detalle de diseño: el método devuelve la **interfaz** `PasswordEncoder`, no la
clase concreta. Así, quien la inyecta (`AuthController`, `DataLoader`) depende de la abstracción. Si
mañana cambias a `Argon2PasswordEncoder`, solo tocas esta línea.

> **Equivalente Symfony:** esto es exactamente `password_hashers: auto` en `security.yaml`, o
> registrar un servicio de una clase de vendor en `services.yaml`.

#### `@Bean` nº 2 — `authenticationManager(...)`

```java
@Bean
public AuthenticationManager authenticationManager(
        AuthenticationConfiguration authenticationConfiguration) throws Exception {
    return authenticationConfiguration.getAuthenticationManager();
}
```

Dos cosas nuevas y muy importantes aquí:

**a) Un método `@Bean` puede recibir parámetros, y Spring los inyecta.**
`AuthenticationConfiguration` es un bean que ya creó Spring Security. Al ponerlo como parámetro,
Spring lo busca en el contenedor y te lo pasa. **Los parámetros de un método `@Bean` son inyección
de dependencias.**

**b) El objeto no se crea con `new`, se obtiene llamando a otro objeto.** Aquí queda claro por qué
`@Component` no serviría: el `AuthenticationManager` lo construye internamente Spring Security con
una lógica compleja (buscar todos los `AuthenticationProvider`, encadenarlos...). Tú solo pides el
resultado y lo publicas como bean para poder inyectarlo en
[AuthController](../src/main/java/com/gestion/eventos/api/security/controller/AuthController.java).

#### `@Bean` nº 3 — `filterChain(HttpSecurity http)`

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .exceptionHandling(e -> e.authenticationEntryPoint(jwtAuthEntryPoint))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/auth/**").permitAll()
            .requestMatchers("/h2-console/**").permitAll()
            .anyRequest().authenticated())
        .headers(AbstractHttpConfigurer::disable);
    http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

Este es el ejemplo perfecto de **"necesito lógica para construir el objeto"**. El
`SecurityFilterChain` no se puede crear con un `new`: se construye con un *builder* al que le vas
encadenando decisiones. **Este método `@Bean` es, literalmente, el `security.yaml` de Symfony
escrito en Java.** Lo desmenuzamos entero en el [doc 10](10-seguridad-jwt.md).

#### `@Bean` nº 4 — `corsConfigurationSource()`

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Collections.singletonList("http://localhost:4200"));
    configuration.setAllowedMethods(Arrays.asList("GET","POST","PUT","DELETE","OPTIONS","PATCH"));
    configuration.setAllowedHeaders(Collections.singletonList("*"));
    configuration.setExposedHeaders(Arrays.asList("Authorization"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

Otro caso de "clase de librería + lógica de construcción": creas el objeto, le llamas a siete
setters, lo envuelves en otro objeto y lo devuelves. Nada de eso cabe en una anotación.

> ⚠️ **Ojo con este bean concreto:** declararlo **no basta** para que el CORS funcione. La cadena de
> filtros tiene que activar el soporte CORS explícitamente con `.cors(Customizer.withDefaults())`.
> Se explica en el [doc 12, observación nº 2](12-observaciones-y-glosario.md).

### Un matiz muy elegante de `@Configuration`

```java
@Configuration
public class MiConfig {
    @Bean public A a() { return new A(); }
    @Bean public B b() { return new B(a()); }   // ← llama a a() directamente
    @Bean public C c() { return new C(a()); }   // ← y otra vez
}
```

En Java normal, `a()` se ejecutaría **tres veces** y habría tres objetos `A` distintos. Pero como la
clase lleva `@Configuration`, Spring la envuelve en un **proxy CGLIB** que intercepta las llamadas
a los métodos `@Bean`: la segunda y tercera vez, en lugar de ejecutar el método, devuelve el bean ya
creado del contenedor. **Resultado: un único `A`, compartido.**

Esto es una demostración perfecta de que Spring no es solo "anotaciones": está reescribiendo el
comportamiento de tus clases en tiempo de ejecución. De eso trata el [doc 04](04-por-que-interfaces.md).

---

## 4. Las tres formas de inyectar dependencias

### Forma 1 — Por constructor ✅ (la que usa este proyecto, y la correcta)

```java
@Service
public class CategoryServiceImpl implements CategoryService {
    private final CategoryRepository categoryRepository;

    // Este constructor lo genera Lombok con @RequiredArgsConstructor
    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }
}
```

Como la clase tiene **un solo constructor**, Spring lo usa automáticamente: ni siquiera hace falta
`@Autowired` (desde Spring 4.3).

**Por qué es la mejor forma:**

| Ventaja | Explicación |
|---|---|
| Los campos pueden ser `final` | Una vez construido el objeto, esas referencias **no pueden cambiar**. Inmutabilidad = seguridad en entornos con muchos hilos. |
| El objeto **nunca existe a medias** | Si falta una dependencia, la aplicación **no arranca**. Con inyección por campo, el objeto se crea con un `null` dentro y explota más tarde con un `NullPointerException`. |
| Se ve el acoplamiento | Si un constructor pide 9 dependencias, la clase hace demasiado. Es un olor a código visible. |
| Testeable sin Spring | `new CategoryServiceImpl(repoFalso)` en un test unitario. Sin contenedor, sin magia. |

### El truco de `@RequiredArgsConstructor`

Esta anotación de Lombok aparece en **13 clases** del proyecto. Genera un constructor con **todos
los campos `final`** (y los `@NonNull`):

```java
@Service
@RequiredArgsConstructor          // ← Lombok
public class EventService implements IEventService {
    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final CategoryService categoryService;
    private final SpeakerService speakerService;
}

// Lombok genera al compilar:
// public EventService(EventRepository eventRepository, EventMapper eventMapper,
//                     CategoryService categoryService, SpeakerService speakerService) {
//     this.eventRepository = eventRepository;
//     ...
// }
```

Y como es el único constructor, Spring lo usa para inyectar. **La combinación
`@Service` + `@RequiredArgsConstructor` + campos `final` es el patrón estándar de Spring moderno.**
Cuando lo veas, lee: "esto es un servicio con estas dependencias".

### Forma 2 — Por campo con `@Autowired` ❌ (evitar)

```java
@Service
public class MalServicio {
    @Autowired private CategoryRepository repo;   // NO hagas esto
}
```

Funciona, pero: el campo no puede ser `final`, el objeto puede quedar a medio construir, y no puedes
instanciarlo en un test sin usar reflexión.

**En este proyecto solo hay un `@Autowired` por campo**, en
[UserMapper.java](../src/main/java/com/gestion/eventos/api/mapper/UserMapper.java):

```java
@Mapper(componentModel = "spring")
public abstract class UserMapper {
    @Autowired
    protected RoleRepository roleRepository;
    ...
}
```

Y aquí **está justificado**: `UserMapperImpl` la genera MapStruct, así que no controlas su
constructor. La inyección por campo es la única vía. Es el caso típico de "la regla tiene una
excepción bien fundada".

### Forma 3 — Por setter

```java
@Autowired
public void setRepo(CategoryRepository repo) { this.repo = repo; }
```

Solo tiene sentido para dependencias genuinamente **opcionales**. No se usa en este proyecto.

---

## 5. Los ámbitos (*scopes*) de un bean

| Scope | Cuántas instancias | Equivalente Symfony |
|---|---|---|
| `singleton` (**por defecto**) | Una para toda la aplicación | Servicio normal (aunque en Symfony "toda la aplicación" = una petición) |
| `prototype` | Una nueva cada vez que se pide | `shared: false` |
| `request` | Una por petición HTTP | Servicio con scope `request` |
| `session` | Una por sesión HTTP | Servicio con scope de sesión |

**Todos los beans de este proyecto son singleton.** Y de ahí sale la regla más importante:

> ### ⚠️ Un bean singleton NO puede tener estado mutable
>
> `EventService` es **una sola instancia** atendiendo peticiones de muchos usuarios **al mismo
> tiempo, en hilos distintos**. Si tuviera un campo `private Event eventoActual`, dos peticiones
> simultáneas se pisarían los datos.
>
> Por eso todos los campos de los servicios son `final` y solo contienen otras dependencias
> (que también son *stateless*). El estado de una petición viaja **en los parámetros y variables
> locales de los métodos**, que sí son propias de cada hilo.
>
> **Esto es MUCHO más crítico en Spring que en Symfony**, donde cada petición es un proceso PHP
> aislado y un servicio con estado como mucho te ensucia una petición.

---

## 6. Preguntas frecuentes

### ¿Cómo sabe Spring qué inyectar si hay dos beans del mismo tipo?

Falla al arrancar con `NoUniqueBeanDefinitionException`. Se resuelve así:

```java
@Bean @Primary                     // "en caso de duda, éste"
public PasswordEncoder bcrypt() { ... }

@Bean @Qualifier("argon")          // se pide con @Qualifier("argon")
public PasswordEncoder argon() { ... }
```

En este proyecto ocurre algo relacionado y elegante:
[SecurityConfig](../src/main/java/com/gestion/eventos/api/security/config/SecurityConfig.java)
inyecta `UserDetailsService` (la **interfaz** de Spring Security) y Spring encuentra que el único
bean de ese tipo es **tu** `UserDetailsServiceImpl`. Por eso funciona sin configurar nada más:
Spring Security dice "necesito un `UserDetailsService`" y tú has puesto uno en el contenedor.

### ¿Y si la dependencia es opcional?

`private final Optional<MiBean> x;` o `@Autowired(required = false)`.

### ¿Puedo ver todos los beans registrados?

Sí, y es un ejercicio muy instructivo. Añade esto temporalmente a `ApiApplication`:

```java
@Bean
public CommandLineRunner listarBeans(ApplicationContext ctx) {
    return args -> Arrays.stream(ctx.getBeanDefinitionNames())
            .sorted()
            .forEach(System.out::println);
}
```

Verás unos 200 nombres: los tuyos y los que crearon las autoconfiguraciones.
Es el equivalente a `bin/console debug:container`.

---

## 7. Resumen del capítulo

- Un **bean** = un objeto gestionado por Spring = un **servicio** de Symfony.
- El **`ApplicationContext`** es el contenedor. Se construye al arrancar y vive todo el proceso.
- **Dos formas de declarar un bean:**
  - `@Component`/`@Service`/`@Repository`/`@Controller` → **la clase es tuya**
  - **`@Bean`** en un método de una clase `@Configuration` → **la clase es de una librería, o hace
    falta lógica para construirla**
- Este proyecto tiene exactamente **4 beans declarados con `@Bean`**, todos en `SecurityConfig`, y
  los cuatro por el mismo motivo: son objetos de Spring Security que hay que fabricar a mano.
- **Inyecta siempre por constructor**, con campos `final` y `@RequiredArgsConstructor`.
- Los beans son **singleton**: nunca les pongas estado mutable.

---

← [02 — Estructura y arranque](02-estructura-y-arranque.md) | **Siguiente:** [04 — Por qué tantas interfaces](04-por-que-interfaces.md)

# 12 — Observaciones, mejoras y glosario

---

## Parte 1 — Observaciones sobre el código actual

Esta lista recoge lo que he encontrado al revisar el proyecto. **No es una crítica**: es un proyecto
de aprendizaje y la estructura general está bien planteada. Muchas de estas cosas son exactamente lo
siguiente que se aprende en un curso.

Están ordenadas por importancia.

---

### 🔴 1. `RegisterDto` permite auto-asignarse `ROLE_ADMIN`

**Dónde:** [RegisterDto.java:32](../src/main/java/com/gestion/eventos/api/security/dto/RegisterDto.java#L32)
+ [AuthController.java:51](../src/main/java/com/gestion/eventos/api/security/controller/AuthController.java#L51)

`RegisterDto` tiene `private Set<String> roles;` y el endpoint `/api/v1/auth/register` es
`permitAll()`. Cualquiera puede hacer:

```json
POST /api/v1/auth/register
{"username":"intruso","password":"123456","email":"a@b.c","name":"X","roles":["ROLE_ADMIN"]}
```

y quedar registrado como administrador.

**Solución:** quitar `roles` del DTO de registro público y dejar que `UserMapper` asigne siempre
`ROLE_USER` (ya lo hace cuando el campo viene vacío). Para crear administradores, un endpoint aparte
protegido con `@PreAuthorize("hasRole('ADMIN')")`.

---

### 🟠 2. El bean de CORS está definido pero no se aplica

**Dónde:** [SecurityConfig.java:76](../src/main/java/com/gestion/eventos/api/security/config/SecurityConfig.java#L76)

El `@Bean corsConfigurationSource()` está bien escrito, pero la cadena de filtros nunca activa el
soporte CORS. Declarar el bean no basta.

**Solución:** añadir una línea a la cadena:

```java
import org.springframework.security.config.Customizer;

http
    .cors(Customizer.withDefaults())      // ← añadir esto
    .csrf(AbstractHttpConfigurer::disable)
    ...
```

Con eso, Spring Security busca el bean `corsConfigurationSource` y lo usa. *(Este bean es el cambio
que tienes sin commitear, así que probablemente ya estabas en ello.)*

---

### 🟠 3. Falta `@Valid` en el registro y el login

**Dónde:** [AuthController.java:39 y :52](../src/main/java/com/gestion/eventos/api/security/controller/AuthController.java#L39)

`RegisterDto` tiene validaciones muy completas y **ninguna se aplica**. Ahora mismo se acepta una
contraseña de un carácter y un email inválido.

```java
public ResponseEntity<String> registerUser(@Valid @RequestBody RegisterDto registerDto) {
//                                          ↑ añadir
```

---

### 🟠 4. `.headers(disable)` desactiva todas las cabeceras de seguridad

**Dónde:** [SecurityConfig.java:54](../src/main/java/com/gestion/eventos/api/security/config/SecurityConfig.java#L54)

La intención (que funcione el iframe de la consola H2) es correcta, pero desactiva también
`X-Content-Type-Options`, `Strict-Transport-Security`, `Cache-Control`…

```java
.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
```

Desactiva solo lo que estorba y conserva el resto.

---

### 🟡 5. El handler genérico puede convertir un 403 en un 500

**Dónde:** [GlobalExceptionHandler.java:57](../src/main/java/com/gestion/eventos/api/exception/GlobalExceptionHandler.java#L57)

`@ExceptionHandler(Exception.class)` captura todo lo que no encaje antes, incluida la
`AccessDeniedException` que lanza `@PreAuthorize`. Resultado: un fallo de permisos podría
responder 500 en lugar de 403.

**Compruébalo:** haz login como `user` e intenta `POST /api/v1/events`. Si sale 500, añade este
handler:

```java
@ExceptionHandler(AccessDeniedException.class)   // org.springframework.security.access
public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
    Map<String, Object> body = new HashMap<>();
    body.put("status", HttpStatus.FORBIDDEN.value());
    body.put("error", "Forbidden");
    body.put("message", "No tienes permisos para realizar esta operación.");
    return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
}
```

---

### 🟡 6. El problema N+1 en `EventService.findAll()`

**Dónde:** [EventService.java:36](../src/main/java/com/gestion/eventos/api/service/EventService.java#L36)

Con `spring.jpa.show-sql=true` puedes verlo: una página de 10 eventos genera hasta 21 consultas,
porque `category` y `speakers` son LAZY y el mapper los toca uno a uno.

**Solución:**

```java
public interface EventRepository extends JpaRepository<Event, Long> {

    @EntityGraph(attributePaths = {"category", "speakers"})
    Page<Event> findByNameContainingIgnoreCase(String name, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"category", "speakers"})
    Page<Event> findAll(Pageable pageable);
}
```

*(Ojo: con `JOIN FETCH` de colecciones y paginación, Hibernate puede avisar de que pagina en
memoria. `@EntityGraph` sobre un `@ManyToMany` tiene ese matiz. Una alternativa robusta es cargar
primero los ids paginados y luego los datos completos con `WHERE id IN (...)`. Es un tema avanzado,
pero merece la pena saber que existe.)*

---

### 🟡 7. `N` consultas para cargar los ponentes

**Dónde:** [EventService.java:62-64](../src/main/java/com/gestion/eventos/api/service/EventService.java#L62-L64) y [:95-97](../src/main/java/com/gestion/eventos/api/service/EventService.java#L95-L97)

```java
Set<Speaker> speakers = requestDto.getSpeakersIds().stream()
        .map(speakerService::findById)      // una consulta POR CADA id
        .collect(Collectors.toSet());
```

Con 5 ponentes son 5 consultas. `findAllById` hace una sola:

```java
List<Speaker> encontrados = speakerRepository.findAllById(requestDto.getSpeakersIds());
if (encontrados.size() != requestDto.getSpeakersIds().size()) {
    throw new ResourceNotFoundException("Alguno de los ponentes indicados no existe");
}
Set<Speaker> speakers = new HashSet<>(encontrados);
```

*(La versión actual tiene una ventaja: dice **qué** id concreto falla. La optimizada requiere la
comprobación manual del tamaño para conservar ese comportamiento.)*

---

### 🟡 8. `CategoryDto` no tiene validaciones

**Dónde:** [CategoryDto.java](../src/main/java/com/gestion/eventos/api/dto/CategoryDto.java)

Los controladores la reciben con `@Valid`, pero el DTO no tiene ninguna restricción: se puede crear
una categoría con `{"name": ""}`.

```java
@NotBlank(message = "El nombre de la categoría no puede estar vacío.")
@Size(max = 100, message = "El nombre no puede exceder los 100 caracteres.")
private String name;

@Size(max = 500, message = "La descripción no puede exceder los 500 caracteres.")
private String description;
```

---

### 🟡 9. La entidad `User` no tiene restricciones de columna

**Dónde:** [User.java:29-32](../src/main/java/com/gestion/eventos/api/domain/User.java#L29-L32)

```java
private String name;
private String username;    // sin @Column → nullable, sin UNIQUE
private String email;
private String password;
```

La unicidad de `username`/`email` solo se comprueba en el controlador con `existsByUsername`. Dos
registros simultáneos con el mismo usuario podrían pasar ambos (*race condition*). La restricción
`UNIQUE` en la base de datos es lo que lo impide de verdad — y además, si salta, el
`GlobalExceptionHandler` ya sabe convertir la `DataIntegrityViolationException` en un 409.

```java
@Column(nullable = false, unique = true, length = 50)
private String username;

@Column(nullable = false, unique = true, length = 100)
private String email;

@Column(nullable = false)
private String password;
```

---

### 🟢 10. `@Data` en entidades JPA

**Dónde:** las 5 entidades de [domain/](../src/main/java/com/gestion/eventos/api/domain/)

Ya está bien mitigado con `@ToString.Exclude` y `@EqualsAndHashCode.Exclude` en las colecciones —
eso demuestra que conoces el problema. Quedan dos flecos:

- `Event.category` **no** está excluido del `equals`, y al ser LAZY puede disparar consultas al
  comparar eventos.
- El `hashCode` cambia cuando se asigna el id, lo que rompe los `HashSet` con entidades nuevas.

La receta segura está en el [doc 05, sección 6](05-dominio-entidades-jpa.md): `@Getter @Setter` en
lugar de `@Data`, y `equals`/`hashCode` a mano basados en el id.

---

### 🟢 11. `System.out.println` y `printStackTrace` en lugar de logs

**Dónde:** [JwtGenerator.java:63-71](../src/main/java/com/gestion/eventos/api/security/jwt/JwtGenerator.java#L63-L71),
[GlobalExceptionHandler.java:59-60](../src/main/java/com/gestion/eventos/api/exception/GlobalExceptionHandler.java#L59-L60),
[DataLoader.java](../src/main/java/com/gestion/eventos/api/data/DataLoader.java)

Spring Boot trae SLF4J + Logback configurado. Lombok lo hace trivial:

```java
@Slf4j              // ← Lombok crea el campo 'log'
@Component
public class JwtGenerator {
    ...
    catch (ExpiredJwtException e) {
        log.warn("JWT expirado: {}", e.getMessage());
    }
}
```

Ventajas: niveles (`debug`/`info`/`warn`/`error`), formato consistente, se puede desactivar por
configuración, y va a los ficheros de log en producción. `System.out` se pierde.

---

### 🟢 12. El mensaje de error de la BD se expone al cliente

**Dónde:** [GlobalExceptionHandler.java:50-52](../src/main/java/com/gestion/eventos/api/exception/GlobalExceptionHandler.java#L50-L52)

```java
errorDetails.put("message", "La operación no se pudo completar...");
errorDetails.put("message", ex.getRootCause() != null ? ex.getRootCause().getMessage() : "...");
//                ↑ el segundo put PISA al primero
```

El propio comentario dice "no recomendado". El mensaje raíz puede revelar nombres de tablas y
restricciones. Basta con borrar la segunda línea.

---

### 🟢 13. Inconsistencias de estilo (menores)

| Qué | Dónde |
|---|---|
| `IEventService` usa prefijo `I`; `CategoryService`/`SpeakerService` usan sufijo `Impl` | `service/` |
| `EventService` **es** una implementación pero su nombre parece de interfaz | `EventService.java` |
| `findAll` devuelve DTOs pero el resto de métodos devuelven entidades | `EventService` |
| `CategoryController` usa `stream().map().collect()`; `SpeakerController` usa `mapper.toResponseDtoList()` | `controller/` |
| El comentario dice "Lazy loading" y el código es `FetchType.EAGER` | [User.java:34](../src/main/java/com/gestion/eventos/api/domain/User.java#L34) |
| `JwtAuthResponseDto.tokenType = "Bearer "` con espacio final | `JwtAuthResponseDto.java` |
| El `save()` de los métodos `update()` es redundante (dirty checking) | los 3 servicios |
| `new PageImpl<>(...)` en vez de `page.map(...)` | `EventService.findAll` |
| `userDetailsService` inyectado en `SecurityConfig` pero sin usar | `SecurityConfig.java:34` |

Ninguna rompe nada. Unificar criterios facilita mucho volver al código meses después.

---

### 🟢 14. Falta de tests

Solo existe `ApiApplicationTests.contextLoads()`, que verifica que el contexto arranca. Es más útil
de lo que parece (detecta beans mal cableados), pero es el mínimo.

El siguiente paso natural, con las dependencias que **ya tienes** en el `pom.xml`:

```java
// Test de controlador, sin base de datos
@WebMvcTest(CategoryController.class)
class CategoryControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean CategoryService categoryService;
    @MockitoBean CategoryMapper categoryMapper;

    @Test
    void devuelve404SiNoExiste() throws Exception {
        when(categoryService.findById(99L)).thenThrow(new ResourceNotFoundException("no existe"));
        mockMvc.perform(get("/api/v1/categories/99"))
               .andExpect(status().isNotFound());
    }
}

// Test de repositorio, con base de datos en memoria
@DataJpaTest
class CategoryRepositoryTest {
    @Autowired CategoryRepository repo;

    @Test
    void encuentraPorNombre() {
        repo.save(new Category(null, "Taller", "desc"));
        assertThat(repo.findByName("Taller")).isPresent();
    }
}
```

---

## Parte 2 — Por dónde seguir aprendiendo

Ordenado por lo que más te va a aportar ahora mismo:

**1. Arregla las observaciones 1-4.** Son cambios de pocas líneas y consolidan lo aprendido en
seguridad y validación.

**2. Activa el log de SQL y estudia el N+1.** Ya tienes `show-sql=true`. Haz un
`GET /api/v1/events`, cuenta las consultas, añade `@EntityGraph` y vuelve a contarlas. Es la lección
de rendimiento más importante de JPA.

**3. Escribe tests.** `@WebMvcTest` y `@DataJpaTest` te obligarán a entender de verdad qué depende
de qué. Es donde se ve el valor de las interfaces y la inyección por constructor.

**4. Añade Swagger/OpenAPI.** Una dependencia y tienes documentación interactiva de toda la API:
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.7.0</version>
</dependency>
```
→ `http://localhost:8080/swagger-ui.html`. Es el equivalente a NelmioApiDocBundle.

**5. Perfiles + PostgreSQL.** Ya tienes el driver. Crea `application-dev.properties` (H2) y
`application-prod.properties` (PostgreSQL) y aprende a cambiar de entorno.

**6. Flyway o Liquibase.** Sustituye `ddl-auto=update` por migraciones versionadas. Es el
equivalente a `doctrine:migrations` y es imprescindible en producción.

**7. Refresh tokens.** Con 10 minutos de expiración, la experiencia de usuario pide un mecanismo de
renovación. Es el siguiente paso natural en la parte de JWT.

**8. Spring Boot Actuator.** Endpoints de salud, métricas y estado de la aplicación.

---

## Parte 3 — Glosario

### Spring y su ecosistema

| Término | Significado |
|---|---|
| **Bean** | Objeto gestionado por el contenedor de Spring. ≈ *servicio* en Symfony |
| **ApplicationContext** | El contenedor IoC. ≈ el `Container` de Symfony |
| **IoC** (Inversión de Control) | El framework crea tus objetos, no tú con `new` |
| **DI** (Inyección de Dependencias) | Recibir las dependencias en lugar de construirlas |
| **Autoconfiguración** | Spring Boot configura cosas solas según lo que ve en el classpath |
| **Starter** | Dependencia de Maven que agrupa librerías + autoconfiguración. ≈ *bundle* |
| **Estereotipo** | `@Component`, `@Service`, `@Repository`, `@Controller` |
| **Proxy** | Objeto que envuelve al tuyo para añadirle comportamiento (transacciones, seguridad) |
| **AOP** | Programación orientada a aspectos: separar preocupaciones transversales |
| **CGLIB** | Librería que genera subclases en memoria; el mecanismo de proxy por defecto |
| **Singleton** | Ámbito por defecto de un bean: una instancia para toda la aplicación |
| **DispatcherServlet** | El *front controller* de Spring MVC. ≈ `public/index.php` |
| **ThreadLocal** | Variable cuyo valor es propio de cada hilo. Base del `SecurityContextHolder` |
| **Classpath** | El conjunto de clases y librerías disponibles al ejecutar |

### JPA e Hibernate

| Término | Significado |
|---|---|
| **JPA** | La especificación estándar de persistencia (`jakarta.persistence`) |
| **Hibernate** | La implementación de JPA que genera el SQL |
| **Entidad** | Clase `@Entity` que se mapea a una tabla |
| **EntityManager** | El gestor del contexto de persistencia. ≈ `EntityManagerInterface` de Doctrine |
| **Contexto de persistencia** | Mapa en memoria de las entidades gestionadas durante una transacción |
| **Caché de primer nivel** | Otro nombre para lo anterior: evita repetir consultas dentro de una transacción |
| **Dirty checking** | Hibernate detecta los cambios y genera los `UPDATE` **solo** |
| **Entidad gestionada** | La que está en el contexto de persistencia; sus cambios se guardan solos |
| **Entidad detached** | La que estuvo gestionada pero ya no (fuera de la transacción) |
| **Flush** | Volcar los cambios pendientes al SQL. En JPA suele ser automático al hacer commit |
| **LAZY** | La relación se carga cuando la tocas |
| **EAGER** | La relación se carga siempre |
| **Proxy de Hibernate** | Objeto vacío que sustituye a una relación LAZY hasta que la usas |
| **N+1** | 1 consulta para la lista + N consultas para las relaciones de cada elemento |
| **Lado propietario** | En una relación, el que tiene `@JoinTable`/`@JoinColumn`. Es el que JPA mira al guardar |
| **`mappedBy`** | Marca el lado **inverso**: JPA lo ignora al escribir |
| **JPQL** | Lenguaje de consultas sobre entidades. ≈ DQL |
| **`ddl-auto`** | Genera o actualiza el esquema desde las entidades |
| **`LazyInitializationException`** | Tocar una relación LAZY fuera de la transacción |

### Web y seguridad

| Término | Significado |
|---|---|
| **DTO** | Objeto plano para transportar datos entre capas |
| **Jackson** | La librería que convierte JSON ⇄ objetos Java |
| **Bean Validation** | El estándar de `@NotBlank`, `@Email`, `@Size` |
| **`@Valid`** | Dispara la validación de un argumento del controlador |
| **JWT** | Token firmado con los datos del usuario dentro. **Legible, no cifrado** |
| **Claims** | Los datos del payload del JWT (`sub`, `iat`, `exp`) |
| **HS512** | HMAC-SHA512: firma simétrica (la misma clave firma y verifica) |
| **BCrypt** | Algoritmo de hash de contraseñas: lento, con salt, unidireccional |
| **Filter chain** | La cadena de filtros de Spring Security |
| **`UserDetails`** | La representación de un usuario para Spring Security. ≈ `UserInterface` |
| **`GrantedAuthority`** | Un permiso o rol. En este proyecto: `ROLE_ADMIN`, `ROLE_USER` |
| **Autenticación** | ¿Quién eres? |
| **Autorización** | ¿Qué puedes hacer? |
| **CORS** | Reglas que permiten a un navegador llamar a una API de otro dominio |
| **CSRF** | Ataque que aprovecha las cookies automáticas. No aplica con JWT en cabecera |
| **Stateless** | El servidor no guarda estado de sesión entre peticiones |

### Java y Maven

| Término | Significado |
|---|---|
| **JVM** | La máquina virtual que ejecuta el bytecode |
| **Maven** | Gestor de dependencias y build. ≈ Composer + tareas de build |
| **`pom.xml`** | El manifiesto de Maven. ≈ `composer.json` |
| **Scope** | Cuándo está disponible una dependencia (`compile`, `runtime`, `test`) |
| **BOM** | Conjunto de versiones compatibles entre sí (lo aporta el `<parent>`) |
| **Procesador de anotaciones** | Programa que genera código al compilar (Lombok, MapStruct) |
| **Lombok** | Genera getters, setters y constructores al compilar |
| **MapStruct** | Genera los mappers entidad ⇄ DTO al compilar |
| **Genéricos** | `List<Event>`: el tipo que va dentro de otro tipo |
| **`Optional<T>`** | Caja que puede tener un valor o estar vacía. Evita los `null` |
| **Lambda** | `x -> x.getName()`. ≈ `fn($x) => $x->getName()` |
| **Method reference** | `mapper::toDto`. Atajo para una lambda que solo llama a un método |
| **Stream** | Cadena de operaciones sobre una colección |
| **Checked exception** | Excepción que hay que capturar o declarar. **No provoca rollback por defecto** |
| **Unchecked exception** | `RuntimeException`. Sí provoca rollback |

---

## Parte 4 — Chuleta de anotaciones del proyecto

### Contenedor
| Anotación | Uso |
|---|---|
| `@SpringBootApplication` | Clase de arranque (= `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`) |
| `@Component` | Bean genérico |
| `@Service` | Bean de lógica de negocio |
| `@Repository` | Bean de acceso a datos (+ traducción de excepciones) |
| `@Configuration` | Clase que declara beans con `@Bean` |
| `@Bean` | Método que fabrica un bean (para clases de librerías o construcción con lógica) |
| `@Value("${prop}")` | Inyecta un valor de `application.properties` |
| `@Autowired` | Inyección explícita (evítala salvo casos como `UserMapper`) |

### Web
| Anotación | Uso |
|---|---|
| `@RestController` | Controlador REST (respuestas JSON) |
| `@RequestMapping("/ruta")` | Prefijo de rutas de la clase |
| `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` | Endpoints |
| `@PathVariable` | Variable de la ruta |
| `@RequestParam` | Parámetro de query string |
| `@RequestBody` | Cuerpo JSON deserializado |
| `@Valid` | Dispara la validación |
| `@PageableDefault` | Valores por defecto de la paginación |
| `@ControllerAdvice` | Manejador global de excepciones |
| `@ExceptionHandler(X.class)` | Método que atiende un tipo de excepción |

### Persistencia
| Anotación | Uso |
|---|---|
| `@Entity` / `@Table` | Clase ⇄ tabla |
| `@Id` / `@GeneratedValue` | Clave primaria |
| `@Column` | Configuración de la columna |
| `@ManyToOne` / `@ManyToMany` / `@OneToMany` | Relaciones |
| `@JoinColumn` / `@JoinTable` | Clave foránea / tabla intermedia |
| `mappedBy` | Marca el lado inverso |
| `@Transactional` | Delimita la transacción |
| `@EntityGraph` | Carga relaciones de golpe (soluciona el N+1) |
| `@Query` | Consulta JPQL o SQL a medida |

### Seguridad
| Anotación | Uso |
|---|---|
| `@EnableMethodSecurity` | **Activa `@PreAuthorize`** |
| `@PreAuthorize("hasRole('X')")` | Permiso a nivel de método (sin el prefijo `ROLE_`) |

### Lombok y MapStruct
| Anotación | Uso |
|---|---|
| `@Data` | Getters + setters + toString + equals + hashCode |
| `@Getter` / `@Setter` | Solo eso (**preferible en entidades**) |
| `@RequiredArgsConstructor` | Constructor con los campos `final` → inyección |
| `@NoArgsConstructor` / `@AllArgsConstructor` | Constructores |
| `@ToString.Exclude` / `@EqualsAndHashCode.Exclude` | Excluir campos (**necesario en relaciones**) |
| `@Slf4j` | Añade un logger `log` |
| `@Mapper(componentModel = "spring")` | Mapper de MapStruct como bean |
| `@Mapping(target=, source=, ignore=)` | Configura un campo del mapeo |
| `@MappingTarget` | Actualiza un objeto existente en lugar de crear uno |
| `@Named` / `qualifiedByName` | Método de mapeo personalizado |

### Validación
| Anotación | Válido si |
|---|---|
| `@NotNull` | No es `null` |
| `@NotBlank` | String con contenido real (ni vacío ni solo espacios) |
| `@NotEmpty` | No vacío |
| `@Size(min, max)` | Longitud en rango |
| `@Email` | Formato de email |

---

## Parte 5 — Referencia rápida de la API

| Método | Ruta | Roles | Cuerpo | Respuesta |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | público | `RegisterDto` | 201 texto |
| POST | `/api/v1/auth/login` | público | `LoginDto` | 200 `{accessToken, tokenType}` |
| GET | `/api/v1/events` | ADMIN, USER | — | 200 `Page<EventResponseDto>` |
| GET | `/api/v1/events/{id}` | ADMIN, USER | — | 200 `EventResponseDto` |
| POST | `/api/v1/events` | ADMIN | `EventRequestDto` | 201 |
| PUT | `/api/v1/events/{id}` | ADMIN | `EventRequestDto` | 200 |
| DELETE | `/api/v1/events/{id}` | ADMIN | — | 204 |
| GET | `/api/v1/categories` | ADMIN, USER | — | 200 `List<CategoryDto>` |
| GET | `/api/v1/categories/{id}` | ADMIN, USER | — | 200 |
| POST | `/api/v1/categories` | ADMIN | `CategoryDto` | 201 |
| PUT | `/api/v1/categories/{id}` | ADMIN | `CategoryDto` | 200 |
| DELETE | `/api/v1/categories/{id}` | ADMIN | — | 204 |
| GET | `/api/v1/speakers` | ADMIN, USER | — | 200 `List<SpeakerResponseDto>` |
| GET | `/api/v1/speakers/{id}` | ADMIN, USER | — | 200 |
| POST | `/api/v1/speakers` | ADMIN | `SpeakerRequestDto` | 201 |
| PUT | `/api/v1/speakers/{id}` | ADMIN | `SpeakerRequestDto` | 200 |
| DELETE | `/api/v1/speakers/{id}` | ADMIN | — | 204 |

**Parámetros de paginación** (solo en `/api/v1/events`):
`?page=0&size=10&sort=name,asc&name=texto`

---

← [11 — Recorrido de una petición](11-recorrido-de-una-peticion.md) | [Volver al índice](00-INDICE.md)

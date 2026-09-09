# 09 — La capa web: controladores y manejo de errores

---

## 1. Qué hace (y qué NO hace) un controlador

Un controlador tiene **una sola responsabilidad**: traducir entre el mundo HTTP y el mundo Java.

| Sí es responsabilidad del controlador | NO lo es |
|---|---|
| Leer la URL, los parámetros y el cuerpo | Consultar la base de datos |
| Disparar la validación (`@Valid`) | Reglas de negocio |
| Elegir el código de estado HTTP | Gestionar transacciones |
| Llamar **a un servicio** | Decidir si un dato es válido según el negocio |
| Convertir a DTO (o delegarlo) | |

**Un controlador debe ser aburrido.** Si tiene un `if` complicado, esa lógica pertenece al servicio.
Los tres controladores de negocio de este proyecto cumplen bien esta regla: son casi todo delegación.

---

## 2. Anatomía de un controlador

[CategoryController.java](../src/main/java/com/gestion/eventos/api/controller/CategoryController.java)

```java
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;
    private final CategoryMapper categoryMapper;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<List<CategoryDto>> getAllCategories() {
        List<Category> categories = categoryService.findAll();
        return ResponseEntity.ok(categories.stream()
                .map(categoryMapper::toDto)
                .collect(Collectors.toList()));
    }
}
```

### `@RestController` = `@Controller` + `@ResponseBody`

| Anotación | Qué hace con el valor devuelto |
|---|---|
| `@Controller` | Lo interpreta como el **nombre de una vista** (Thymeleaf, JSP...) |
| `@RestController` | Lo **serializa a JSON** y lo escribe en el cuerpo de la respuesta |

Para una API REST siempre `@RestController`.

> **Symfony:** `@Controller` ≈ `return $this->render('...')`;
> `@RestController` ≈ `return $this->json($data)` en todos los métodos.

### `@RequestMapping("/api/v1/categories")` — el prefijo

Todas las rutas de la clase cuelgan de ahí. `@GetMapping("/{id}")` es realmente
`GET /api/v1/categories/{id}`.

> **Symfony:** `#[Route('/api/v1/categories')]` a nivel de clase. Mismo concepto.

El `/api/v1/` es una buena práctica: versionar la API desde el principio permite publicar un `/v2/`
sin romper a los clientes existentes.

### `@RequiredArgsConstructor` — la inyección

Ya visto en el [doc 03](03-contenedor-beans-inyeccion.md): Lombok genera el constructor con los
campos `final`, y Spring inyecta por él.

---

## 3. El mapeo de rutas y métodos HTTP

| Anotación | Verbo HTTP | Semántica REST | En el proyecto |
|---|---|---|---|
| `@GetMapping` | GET | Leer. Sin efectos secundarios | Listar y ver eventos, categorías, ponentes |
| `@PostMapping` | POST | Crear | Crear recursos, login, registro |
| `@PutMapping` | PUT | Reemplazar **completo** | Actualizar recursos |
| `@PatchMapping` | PATCH | Actualizar **parcial** | *(no se usa)* |
| `@DeleteMapping` | DELETE | Borrar | Borrar recursos |

Todas son atajos de `@RequestMapping(method = RequestMethod.GET)`.

### Cómo llegan los datos: las tres fuentes

```java
// 1. @PathVariable — un trozo de la ruta
@GetMapping("/{id}")
public ResponseEntity<CategoryDto> getCategoryById(@PathVariable Long id)
//   GET /api/v1/categories/5  →  id = 5

// 2. @RequestParam — un parámetro de query string
@RequestParam(required = false) String name
//   GET /api/v1/events?name=confe  →  name = "confe"

// 3. @RequestBody — el cuerpo JSON, deserializado por Jackson
@Valid @RequestBody EventRequestDto requestDto
//   POST con {"name":"X","date":"2026-01-15",...}  →  un objeto EventRequestDto
```

**La conversión de tipos es automática.** `"5"` (texto de la URL) se convierte a `Long`. Si mandas
`/categories/abc`, Spring devuelve un 400 antes de ejecutar tu método.

**Jackson** es la librería que convierte JSON ⇄ objetos Java, tanto al entrar (`@RequestBody`) como
al salir. Convierte por convención: el campo Java `categoryName` ⇄ la propiedad JSON
`"categoryName"`. Y sabe manejar `LocalDate` en formato ISO (`"2026-01-15"`).

> **Symfony:** `@PathVariable` ≈ parámetro de ruta; `@RequestParam` ≈ `$request->query->get()`;
> `@RequestBody` ≈ `$serializer->deserialize($request->getContent(), Dto::class, 'json')`.

---

## 4. `ResponseEntity`: control total de la respuesta

Un `ResponseEntity<T>` representa **la respuesta HTTP completa**: cuerpo + código de estado +
cabeceras.

Las tres formas que usa el proyecto:

```java
// 200 OK con cuerpo
return ResponseEntity.ok(dto);

// 201 CREATED con cuerpo
return new ResponseEntity<>(responseDto, HttpStatus.CREATED);

// 204 NO CONTENT sin cuerpo
return ResponseEntity.noContent().build();
```

### Los códigos de estado que devuelve esta API

| Código | Cuándo | Dónde se genera |
|---|---|---|
| **200 OK** | GET y PUT correctos | `ResponseEntity.ok(...)` |
| **201 CREATED** | POST que crea algo | `new ResponseEntity<>(dto, HttpStatus.CREATED)` |
| **204 NO CONTENT** | DELETE correcto | `ResponseEntity.noContent().build()` |
| **400 BAD REQUEST** | Validación fallida / usuario ya existe | `GlobalExceptionHandler` / `AuthController` |
| **401 UNAUTHORIZED** | Sin token o token inválido | `JwtAuthEntryPoint` |
| **403 FORBIDDEN** | Autenticado pero sin permiso | Spring Security (`@PreAuthorize`) |
| **404 NOT FOUND** | Recurso inexistente | `GlobalExceptionHandler` |
| **409 CONFLICT** | Violación de integridad (email duplicado) | `GlobalExceptionHandler` |
| **500 SERVER ERROR** | Cualquier otro fallo | `GlobalExceptionHandler` |

**El uso de los códigos es correcto en todo el proyecto.** Un detalle: el 201 debería incluir
idealmente una cabecera `Location` con la URL del recurso creado:

```java
URI location = ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{id}").buildAndExpand(saved.getId()).toUri();
return ResponseEntity.created(location).body(responseDto);
```

Es lo que dicta REST estrictamente. Detalle menor.

### La diferencia 401 vs 403 (muy confundida)

- **401 Unauthorized** = "no sé quién eres". Falta el token o es inválido. → *Autenticación*
- **403 Forbidden** = "sé quién eres, pero no puedes". Token válido, rol insuficiente. →
  *Autorización*

En esta API: sin token → 401 (`JwtAuthEntryPoint`); con token de `user` intentando un
`POST /api/v1/events` (que exige ADMIN) → 403.

---

## 5. Los tres controladores de negocio

### `EventController` — el más completo

[EventController.java](../src/main/java/com/gestion/eventos/api/controller/EventController.java)

| Método | Ruta | Roles | Notas |
|---|---|---|---|
| `getAllEvents` | `GET /api/v1/events` | ADMIN, USER | Paginado + filtro `?name=` |
| `createEvent` | `POST /api/v1/events` | ADMIN | 201 |
| `getEventById` | `GET /api/v1/events/{id}` | ADMIN, USER | 404 si no existe |
| `updateEvent` | `PUT /api/v1/events/{id}` | ADMIN | |
| `deleteEvent` | `DELETE /api/v1/events/{id}` | ADMIN | 204 |

Un detalle de diseño interesante: los métodos del servicio devuelven **entidades** (`Event`) y el
controlador mapea a DTO… **excepto `findAll`**, que ya devuelve `Page<EventResponseDto>`:

```java
// findAll: el SERVICIO mapea
Page<EventResponseDto> events = eventService.findAll(name, pageable);
return ResponseEntity.ok(events);

// createEvent: el CONTROLADOR mapea
Event eventSaved = eventService.save(requestDto);
EventResponseDto responseDto = eventMapper.toResponseDto(eventSaved);
```

**Es una inconsistencia**, pero con una razón sólida detrás: en `findAll` hay que mapear **dentro de
la transacción** para no reventar con `LazyInitializationException` al recorrer los 10 eventos y sus
relaciones (ver [doc 05](05-dominio-entidades-jpa.md)). En los métodos de un solo elemento funciona
igual porque `open-in-view` mantiene la sesión abierta.

Lo coherente sería que **el servicio devolviera siempre DTOs** y el controlador no conociera
`EventMapper`. Es una mejora recogida en el [doc 12](12-observaciones-y-glosario.md).

### `CategoryController` y `SpeakerController`

Mismo patrón, sin paginación (devuelven `List<...>`). `SpeakerController` usa
`speakerMapper.toResponseDtoList(speakers)` —el mapeo de listas de MapStruct— en lugar del
`stream().map(...).collect(...)` que usa `CategoryController`. **La versión del mapper es más
limpia**; unificarlas mejoraría la consistencia.

---

## 6. `@PreAuthorize`: seguridad a nivel de método

```java
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")     // lectura: ambos roles
@PreAuthorize("hasAnyRole('ADMIN')")             // escritura: solo admin
```

Se evalúa **antes** de ejecutar el método. Si falla, se lanza una excepción y el método nunca corre.

Funciona gracias a `@EnableMethodSecurity` en
[SecurityConfig](../src/main/java/com/gestion/eventos/api/security/config/SecurityConfig.java#L31),
y por debajo es **otro proxy AOP**, exactamente igual que `@Transactional`
([doc 04](04-por-que-interfaces.md)).

### ⚠️ El detalle de `hasRole` y el prefijo `ROLE_`

Los roles en la base de datos se llaman `ROLE_ADMIN` y `ROLE_USER`. Pero en la anotación escribes
`hasAnyRole('ADMIN')` **sin el prefijo**.

**Spring Security añade `ROLE_` automáticamente** en `hasRole`/`hasAnyRole`. La convención es:

| Expresión | Comprueba realmente |
|---|---|
| `hasRole('ADMIN')` | que tengas la authority `ROLE_ADMIN` |
| `hasAuthority('ROLE_ADMIN')` | exactamente `ROLE_ADMIN` (sin añadir nada) |

Por eso `UserDetailsServiceImpl` crea las authorities con el nombre completo:
```java
new SimpleGrantedAuthority(role.getName())    // "ROLE_ADMIN"
```
y encaja con `hasAnyRole('ADMIN')`. **Si guardaras los roles como `"ADMIN"` a secas, `hasRole`
dejaría de funcionar.** Es una fuente clásica de confusión.

Otras expresiones disponibles: `isAuthenticated()`, `permitAll()`, `hasAuthority(...)`,
`@postAuthorize`, y expresiones sobre argumentos: `@PreAuthorize("#id == authentication.principal.id")`.

> **Symfony:** `#[IsGranted('ROLE_ADMIN')]` — mismo concepto, y allí **sí** escribes el prefijo.

---

## 7. `GlobalExceptionHandler`: errores centralizados

[GlobalExceptionHandler.java](../src/main/java/com/gestion/eventos/api/exception/GlobalExceptionHandler.java)

```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationExceptions(...) { ... }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleResourceNotFoundException(...) { ... }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,String>> handleDataIntegrityViolationSimple(...) { ... }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> handleGeneralException(...) { ... }
}
```

**`@ControllerAdvice`** = un interceptor global de excepciones para **todos** los controladores.
Spring busca el `@ExceptionHandler` cuyo tipo sea el más específico para la excepción lanzada.

> **Symfony:** un `EventSubscriber` escuchando `kernel.exception`. Idéntico propósito.

### Por qué esto es tan valioso

Sin él, cada controlador tendría `try/catch`. Con él:

```java
// El servicio simplemente lanza:
throw new ResourceNotFoundException("Evento no encontrado con id: " + id);

// El controlador no sabe nada:
Event event = eventService.findById(id);     // sin try/catch

// Y el cliente recibe:
// 404 { "status": 404, "error": "Not Found", "message": "Evento no encontrado con id: 99" }
```

**La excepción atraviesa las capas y se traduce a HTTP en un único punto.** Eso es separación de
responsabilidades bien hecha.

### `ResourceNotFoundException`

```java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) { super(message); }
}
```

Cuatro líneas. Que herede de `RuntimeException` es importante por dos motivos:
1. No obliga a declarar `throws` por todas partes.
2. **Provoca rollback automático** en `@Transactional` (ver [doc 07](07-transaccional-a-fondo.md)).

### ⚠️ El problema del handler genérico `Exception.class`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String,String>> handleGeneralException(Exception ex) {
    ex.printStackTrace();
    return new ResponseEntity<>(errorDetails, HttpStatus.INTERNAL_SERVER_ERROR);   // 500
}
```

Este handler captura **absolutamente todo lo que no encaje en los anteriores**. Y ahí hay un riesgo
concreto: cuando `@PreAuthorize` deniega el acceso, Spring Security lanza una
`AuthorizationDeniedException` (que es un `AccessDeniedException`). Esa excepción sale del método
del controlador y **puede acabar capturada por este handler genérico**, convirtiendo un **403
Forbidden** en un **500 Internal Server Error**.

**Merece la pena que lo compruebes tú misma** (es un buen ejercicio):

```powershell
# 1. Login como 'user' (que NO es admin)
# 2. Intentar crear un evento con ese token
# 3. Mirar si devuelve 403 (correcto) o 500 (el problema)
```

Si sale 500, la solución es añadir un handler específico **antes**:

```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
    Map<String, Object> body = new HashMap<>();
    body.put("status", HttpStatus.FORBIDDEN.value());
    body.put("error", "Forbidden");
    body.put("message", "No tienes permisos para realizar esta operación.");
    return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
}
```

Otros dos apuntes sobre este handler:

- **`ex.printStackTrace()`** debería ser un logger (`log.error("...", ex)`). El propio comentario del
  código lo reconoce. Con `System.err` los errores no van al sistema de logs y se pierden en
  producción.
- En `handleDataIntegrityViolationSimple` hay un `errorDetails.put("message", ...)` **dos veces**: el
  segundo pisa al primero, así que siempre se expone el mensaje crudo de la base de datos. El propio
  comentario dice "no recomendado". En producción convendría dejar solo el mensaje genérico, porque
  el mensaje raíz de la BD puede filtrar nombres de tablas y restricciones.

### Una alternativa moderna: `ProblemDetail`

Spring Boot 3+ trae soporte para RFC 7807 (`application/problem+json`), el estándar de errores HTTP:

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ProblemDetail handle(ResourceNotFoundException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    pd.setTitle("Recurso no encontrado");
    return pd;
}
```

Los `Map<String, Object>` a mano funcionan perfectamente; `ProblemDetail` es simplemente más
estándar y menos propenso a inconsistencias entre handlers (fíjate en que ahora unos devuelven
`{status, error, message}` y otros `{error, message}` — formatos distintos para el mismo cliente).

---

## 8. El flujo web completo

```
  Petición HTTP
       │
       ▼
  ┌────────────────────────────────────────────┐
  │ Filtros de Spring Security (doc 10)        │
  │  · JwtAuthenticationFilter valida el token │
  │  · establece el SecurityContext            │
  └───────────────────┬────────────────────────┘
                      ▼
  ┌────────────────────────────────────────────┐
  │ DispatcherServlet (el front controller)    │
  │  1. HandlerMapping: ¿qué método atiende    │
  │     GET /api/v1/events/5?                  │
  │  2. Resuelve argumentos:                   │
  │     @PathVariable → convierte "5" a Long   │
  │     @RequestBody  → Jackson deserializa    │
  │     @Valid        → Hibernate Validator    │
  └───────────────────┬────────────────────────┘
                      ▼
  ┌────────────────────────────────────────────┐
  │ Proxy de @PreAuthorize → ¿tiene el rol?    │
  └───────────────────┬────────────────────────┘
                      ▼
  ┌────────────────────────────────────────────┐
  │ EventController.getEventById(5)            │
  └───────────────────┬────────────────────────┘
                      ▼
        Proxy de @Transactional → Servicio → Repositorio → BD
                      │
                      ▼ (si algo lanza excepción)
  ┌────────────────────────────────────────────┐
  │ GlobalExceptionHandler → JSON de error     │
  └───────────────────┬────────────────────────┘
                      ▼
  ┌────────────────────────────────────────────┐
  │ Jackson serializa el DTO a JSON            │
  └───────────────────┬────────────────────────┘
                      ▼
                  Respuesta HTTP
```

**El `DispatcherServlet` es el *front controller* de Spring MVC**, exactamente el mismo patrón que
el `Kernel`/`public/index.php` de Symfony: un único punto de entrada que despacha a la acción
correcta.

---

## 9. Resumen del capítulo

- `@RestController` + `@RequestMapping` definen la ruta base; `@GetMapping`/`@PostMapping`/etc. los
  endpoints.
- Los datos entran por `@PathVariable` (ruta), `@RequestParam` (query) o `@RequestBody` (JSON, vía
  Jackson).
- `ResponseEntity<T>` da control sobre cuerpo, estado y cabeceras. Los códigos están bien usados en
  todo el proyecto.
- `@PreAuthorize("hasAnyRole('ADMIN')")` protege por rol. **Sin el prefijo `ROLE_`**, que Spring
  añade solo.
- `@ControllerAdvice` + `@ExceptionHandler` centralizan los errores. Ojo con el handler genérico
  `Exception.class`, que puede convertir un 403 en un 500.
- El controlador debe ser aburrido: recibir, delegar, responder.

---

← [08 — DTOs, MapStruct y validación](08-dtos-mapstruct-validacion.md) | **Siguiente:** [10 — Seguridad: Spring Security + JWT](10-seguridad-jwt.md)

# 11 — Recorrido completo de una petición

> Este documento **une todo lo anterior**. Vamos a seguir dos peticiones línea a línea, archivo a
> archivo, para que veas cómo encajan las piezas de los diez documentos previos.

---

## Petición A: `POST /api/v1/events` — crear un evento

**Lo que envía el cliente:**

```http
POST /api/v1/events HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbiIsImlhdCI6...
Content-Type: application/json

{
  "name": "Spring Boot desde cero",
  "date": "2026-11-20",
  "location": "Sala Magna",
  "categoryId": 1,
  "speakersIds": [1, 2]
}
```

---

### PASO 1 — Tomcat recibe la petición

El Tomcat embebido (que arrancó dentro del proceso Java, [doc 02](02-estructura-y-arranque.md))
coge un **hilo libre** de su pool y le asigna esta petición.

> 🔑 A partir de aquí, todo ocurre **en ese hilo**. Otras peticiones se están procesando a la vez en
> otros hilos. Esta es la diferencia fundamental con PHP ([doc 01](01-springboot-vs-symfony.md)).

---

### PASO 2 — La cadena de filtros de Spring Security

La petición entra en los ~15 filtros ([doc 10](10-seguridad-jwt.md)). El que nos interesa:

**[JwtAuthenticationFilter.doFilterInternal()](../src/main/java/com/gestion/eventos/api/security/jwt/JwtAuthenticationFilter.java#L29)**

```java
String token = getJwtFromRequest(request);
```
Lee `Authorization: Bearer eyJhbGci...` → detecta el prefijo `"Bearer "` → `substring(7)` →
`"eyJhbGci..."`.

```java
if (StringUtils.hasText(token) && jwtGenerator.validateToken(token)
    && SecurityContextHolder.getContext().getAuthentication() == null) {
```
- ¿Hay texto? ✔
- `validateToken` → `Jwts.parser().verifyWith(clave).parseSignedClaims(token)`
  → recalcula el HMAC-SHA512 y lo compara con la firma; comprueba que `exp` no ha pasado ✔
- ¿No hay nadie autenticado ya? ✔

```java
String username = jwtGenerator.getUsernameFromJwt(token);   // "admin"
UserDetails userDetails = userDetailsService.loadUserByUsername(username);
```

**[UserDetailsServiceImpl.loadUserByUsername()](../src/main/java/com/gestion/eventos/api/security/service/UserDetailsServiceImpl.java#L27)** ejecuta:

```sql
SELECT * FROM users WHERE username = 'admin';
SELECT r.* FROM roles r JOIN users_roles ur ON ... WHERE ur.user_id = 1;   -- roles son EAGER
```

y devuelve un `UserDetails` con las authorities `[ROLE_ADMIN, ROLE_USER]`.

```java
UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
        userDetails, null, userDetails.getAuthorities());
SecurityContextHolder.getContext().setAuthentication(authToken);
```

**El usuario queda registrado en el `ThreadLocal` de este hilo.**

```java
filterChain.doFilter(request, response);   // continúa
```

Después, el `AuthorizationFilter` evalúa `anyRequest().authenticated()` → hay autenticación ✔

---

### PASO 3 — El `DispatcherServlet` busca el método

El *front controller* de Spring MVC consulta su tabla de rutas:

```
POST /api/v1/events
  → EventController (por @RequestMapping("/api/v1/events"))
  → createEvent()   (por @PostMapping sin ruta adicional)
```

---

### PASO 4 — Resolución de argumentos y validación

**Jackson deserializa** el JSON del cuerpo en un `EventRequestDto`:

```java
EventRequestDto dto = new EventRequestDto();
dto.setName("Spring Boot desde cero");
dto.setDate(LocalDate.of(2026, 11, 20));    // "2026-11-20" → LocalDate
dto.setLocation("Sala Magna");
dto.setCategoryId(1L);
dto.setSpeakersIds(Set.of(1L, 2L));
```

**`@Valid` dispara Hibernate Validator** ([doc 08](08-dtos-mapstruct-validacion.md)):

| Campo | Restricción | Resultado |
|---|---|---|
| `name` | `@NotBlank` | ✔ |
| `date` | `@NotNull` | ✔ |
| `location` | `@NotBlank` | ✔ |
| `categoryId` | `@NotNull` | ✔ |

> **Si alguna fallara:** se lanzaría `MethodArgumentNotValidException`, el método **no se
> ejecutaría**, y `GlobalExceptionHandler` devolvería un `400` con el mapa de errores.

---

### PASO 5 — El proxy de `@PreAuthorize`

```java
@PostMapping
@PreAuthorize("hasAnyRole('ADMIN')")
public ResponseEntity<EventResponseDto> createEvent(...)
```

El proxy AOP ([doc 04](04-por-que-interfaces.md)) intercepta antes de ejecutar:
- `hasAnyRole('ADMIN')` → busca la authority `ROLE_ADMIN` (el prefijo lo añade Spring)
- El usuario tiene `[ROLE_ADMIN, ROLE_USER]` → ✔ **acceso concedido**

> **Si fuera el usuario `user`:** `AuthorizationDeniedException` → 403, y el método del controlador
> nunca se ejecuta.

---

### PASO 6 — El controlador

**[EventController.createEvent()](../src/main/java/com/gestion/eventos/api/controller/EventController.java#L51)**

```java
Event eventSaved = eventService.save(requestDto);
```

⚠️ `eventService` **no es** un `EventService`: es un **proxy CGLIB** que envuelve a `EventService`.

---

### PASO 7 — El proxy de `@Transactional` abre la transacción

```java
@Override
@Transactional
public Event save(EventRequestDto requestDto) {
```

El proxy ejecuta antes de entrar:
```
transactionManager.getTransaction()  →  conexión del pool Hikari  →  BEGIN
                                     →  se abre el CONTEXTO DE PERSISTENCIA
```

---

### PASO 8 — El servicio, línea a línea

**[EventService.save()](../src/main/java/com/gestion/eventos/api/service/EventService.java#L55)**

```java
Event event = eventMapper.toEntity(requestDto);
```
`EventMapperImpl` (generado por MapStruct al compilar) copia `name`, `date`, `location`.
**`id`, `category`, `speakers` y `attendedUsers` se ignoran** (`@Mapping(ignore = true)`).
Resultado: un `Event` en memoria, **sin relaciones y sin id**.

```java
Category category = categoryService.findById(requestDto.getCategoryId());
```
Llama a `CategoryServiceImpl.findById()`, que también es `@Transactional(readOnly = true)`.
**No abre una transacción nueva:** con `Propagation.REQUIRED` se une a la existente
([doc 07](07-transaccional-a-fondo.md)).

```sql
SELECT * FROM categories WHERE id = 1;
```
Si no existiera → `ResourceNotFoundException` → rollback de todo → 404.

```java
event.setCategory(category);
```

```java
Set<Speaker> speakers = requestDto.getSpeakersIds().stream()
        .map(speakerService::findById)
        .collect(Collectors.toSet());
```
```sql
SELECT * FROM speakers WHERE id = 1;
SELECT * FROM speakers WHERE id = 2;
```
*(Dos consultas. Un `findAllById(ids)` haría solo una — mejora anotada en el
[doc 12](12-observaciones-y-glosario.md).)*

```java
speakers.forEach(event::addSpeaker);
```
El método helper de [Event.java:60](../src/main/java/com/gestion/eventos/api/domain/Event.java#L60)
actualiza **los dos lados** de la relación ([doc 05](05-dominio-entidades-jpa.md)):
```java
this.speakers.add(speaker);        // lado propietario → se guardará
speaker.getEvents().add(this);     // lado inverso → coherencia en memoria
```

```java
return eventRepository.save(event);
```
El proxy de Spring Data ve `id == null` → `entityManager.persist(event)`.
La entidad pasa a estar **gestionada** por el contexto de persistencia.

---

### PASO 9 — Commit: aquí se ejecuta el SQL de verdad

Al salir del método, el proxy hace `commit`, e Hibernate vuelca lo pendiente:

```sql
INSERT INTO events (name, date, location, category_id) VALUES (?, ?, ?, 1);
-- id generado: 61

INSERT INTO event_speakers (event_id, speakers_id) VALUES (61, 1);
INSERT INTO event_speakers (event_id, speakers_id) VALUES (61, 2);

COMMIT;
```

> 🔑 **Este es el momento clave que diferencia a JPA de Doctrine**: nunca has llamado a `flush()`.
> El `persist` marcó la entidad y el `commit` la escribió.

Si cualquier INSERT fallara → `ROLLBACK` → **no queda nada a medias**.

La conexión vuelve al pool. El objeto `event` sale del método ya con `id = 61`.

---

### PASO 10 — Vuelta al controlador

```java
EventResponseDto responseDto = eventMapper.toResponseDto(eventSaved);
return new ResponseEntity<>(responseDto, HttpStatus.CREATED);
```

El mapper convierte la entidad al DTO de salida, incluyendo `categoryId`, `categoryName` (navegando
a `category.name`) y la lista completa de ponentes.

*(Como `category` y `speakers` ya están cargados en memoria desde el paso 8, aquí no hay consultas
extra.)*

---

### PASO 11 — Serialización y respuesta

Jackson convierte el DTO a JSON:

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "id": 61,
  "name": "Spring Boot desde cero",
  "date": "2026-11-20",
  "location": "Sala Magna",
  "categoryId": 1,
  "categoryName": "Taller",
  "speakers": [
    {"id":1,"name":"John Doe","email":"john.doe@example.com","bio":"Experto en desarrollo de software."},
    {"id":2,"name":"Jane Smith","email":"jane.smith@example.com","bio":"Especialista en marketing digital."}
  ]
}
```

Spring Security **limpia el `SecurityContextHolder`** y el hilo vuelve al pool de Tomcat, listo para
la siguiente petición.

---

### Resumen visual de la petición A

```
Tomcat (hilo #23)
  └─ JwtAuthenticationFilter ──── SELECT users, SELECT roles
       └─ AuthorizationFilter ✔
            └─ DispatcherServlet
                 ├─ Jackson: JSON → EventRequestDto
                 ├─ Hibernate Validator: @Valid ✔
                 └─ Proxy @PreAuthorize ✔ ROLE_ADMIN
                      └─ EventController.createEvent()
                           └─ Proxy @Transactional ── BEGIN
                                └─ EventService.save()
                                     ├─ EventMapperImpl.toEntity()
                                     ├─ SELECT categories WHERE id=1
                                     ├─ SELECT speakers WHERE id=1
                                     ├─ SELECT speakers WHERE id=2
                                     ├─ event.addSpeaker() ×2
                                     └─ repository.save() → persist
                           ────────── COMMIT
                                      INSERT events
                                      INSERT event_speakers ×2
                           └─ EventMapperImpl.toResponseDto()
                 └─ Jackson: DTO → JSON
  201 CREATED
```

**Consultas totales: 6** (2 de seguridad, 3 de lectura, 3 de escritura).

---

## Petición B: `GET /api/v1/events?name=Conferencia&page=0&size=5`

Ahora una lectura paginada, para ver la paginación y el problema N+1.

### Pasos 1-3: idénticos

Filtro JWT, autorización, `DispatcherServlet` → `EventController.getAllEvents`.

### PASO 4 — Resolución de argumentos

```java
public ResponseEntity<Page<EventResponseDto>> getAllEvents(
        @RequestParam(required = false) String name,
        @PageableDefault(page = 0, size = 10, sort = "name") Pageable pageable)
```

Spring construye los argumentos desde la URL ([doc 06](06-repositorios-spring-data.md)):
- `name = "Conferencia"` (del query string)
- `pageable = PageRequest.of(0, 5, Sort.by("name"))` — `page` y `size` vienen de la URL; `sort`, del
  `@PageableDefault`

### PASO 5 — `@PreAuthorize("hasAnyRole('ADMIN', 'USER')")` ✔

### PASO 6 — El servicio, dentro de la transacción de solo lectura

**[EventService.findAll()](../src/main/java/com/gestion/eventos/api/service/EventService.java#L36)**

```java
@Transactional(readOnly = true)
```
`BEGIN` + **`FlushMode.MANUAL`**: Hibernate no guardará copias del estado original ni buscará
cambios al terminar. Menos memoria, menos CPU ([doc 07](07-transaccional-a-fondo.md)).

```java
if (name != null && !name.trim().isEmpty()) {
    eventsPage = eventRepository.findByNameContainingIgnoreCase(name, pageable);
}
```

El proxy de Spring Data parsea el nombre del método y genera **dos** consultas:

```sql
-- 1. Los datos
SELECT * FROM events
 WHERE LOWER(name) LIKE LOWER('%Conferencia%')
 ORDER BY name ASC
 LIMIT 5 OFFSET 0;

-- 2. El total (necesario para totalElements y totalPages)
SELECT COUNT(*) FROM events
 WHERE LOWER(name) LIKE LOWER('%Conferencia%');
```

```java
List<EventResponseDto> dtos = eventsPage.getContent().stream()
        .map(eventMapper::toResponseDto)
        .toList();
```

⚠️ **Aquí ocurre el N+1** ([doc 05](05-dominio-entidades-jpa.md)). Por cada uno de los 5 eventos, el
mapper toca `event.getCategory().getId()` y `event.getSpeakers()`, que son **LAZY**:

```sql
SELECT * FROM categories WHERE id = 2;            -- evento 1
SELECT s.* FROM speakers s JOIN event_speakers ... WHERE event_id = 3;
SELECT * FROM categories WHERE id = 1;            -- evento 2
SELECT s.* FROM speakers s JOIN event_speakers ... WHERE event_id = 6;
...                                               -- ×5
```

**Total: 2 + hasta 10 consultas = 12 consultas para devolver 5 eventos.**

*(Algunas se ahorran por la caché de primer nivel: si dos eventos comparten categoría, la segunda
vez sale del contexto de persistencia sin consultar. Aun así, el patrón es el N+1.)*

> **Arranca la app con `spring.jpa.show-sql=true` (ya está activado) y haz esta petición.** Ver los
> 12 `SELECT` en la consola es la mejor lección posible sobre el N+1.

**La solución** es un `@EntityGraph` en el repositorio:

```java
@EntityGraph(attributePaths = {"category", "speakers"})
Page<Event> findByNameContainingIgnoreCase(String name, Pageable pageable);
```

→ pasa de 12 consultas a 2.

```java
return new PageImpl<>(dtos, pageable, eventsPage.getTotalElements());
```
Se reconstruye la página con los DTOs. Equivalente y más limpio:
`return eventsPage.map(eventMapper::toResponseDto);`

**El mapeo se hace DENTRO de la transacción**, así que las relaciones LAZY se pueden cargar sin
problema. Cuando el resultado sale del método, ya son DTOs planos: imposible que salte una
`LazyInitializationException`.

### PASO 7 — Commit y respuesta

`COMMIT` (sin escrituras: solo cierra la transacción de lectura).

```json
{
  "content": [
    {"id": 3, "name": "Evento 03: Conferencia de Tecnología 4", "date": "2026-09-11",
     "location": "Sala 4", "categoryId": 1, "categoryName": "Conferencia",
     "speakers": [{"id": 2, "name": "Jane Smith", ...}]},
    ...
  ],
  "pageable": {"pageNumber": 0, "pageSize": 5, "sort": {"sorted": true, ...}},
  "totalElements": 60,
  "totalPages": 12,
  "first": true,
  "last": false,
  "numberOfElements": 5
}
```

---

## Comparación lado a lado con Symfony

La misma petición A, en las dos plataformas:

| Fase | Symfony | Spring Boot |
|---|---|---|
| Entrada | Nginx → PHP-FPM arranca un proceso | Tomcat asigna un hilo (el proceso ya vive) |
| Bootstrap | Kernel + contenedor (cacheado) | **Nada**: todo se construyó al arrancar |
| Seguridad | Firewall + Authenticator (JWT) | Cadena de filtros + `JwtAuthenticationFilter` |
| Usuario actual | Servicio `Security` | `SecurityContextHolder` (**ThreadLocal**) |
| Ruta | `RouterListener` → `#[Route]` | `DispatcherServlet` → `@PostMapping` |
| Cuerpo → objeto | `$serializer->deserialize(...)` | Jackson + `@RequestBody` |
| Validación | `$validator->validate($dto)` | `@Valid` (automático) |
| Permisos | `#[IsGranted('ROLE_ADMIN')]` | `@PreAuthorize` (proxy AOP) |
| Transacción | `$em->beginTransaction()` / `wrapInTransaction` | `@Transactional` (proxy AOP) |
| Guardar | `persist()` + **`flush()` obligatorio** | `save()` + **commit automático** |
| Salida | `$this->json($dto)` | `ResponseEntity` + Jackson |
| Fin | 💀 el proceso muere | El hilo vuelve al pool; los objetos siguen vivos |

---

## Las tres ideas que resumen todo el proyecto

**1. Cada capa tiene una responsabilidad y solo una.**
```
Controlador → HTTP        (traduce peticiones, elige códigos de estado)
Servicio    → Negocio     (reglas + límites transaccionales)
Repositorio → Datos       (consultas)
Entidad     → Estructura  (el modelo persistente)
DTO         → Contrato    (lo que ve el mundo exterior)
Mapper      → Traducción  (entidad ⇄ DTO)
```

**2. Los proxies son el mecanismo invisible que lo sostiene.**
`@Transactional`, `@PreAuthorize`, los repositorios, `@Configuration`… todos funcionan porque Spring
envuelve tus objetos. Cuando algo "no se aplica", casi siempre es porque la llamada no pasó por el
proxy.

**3. El contenedor lo cablea todo una sola vez, al arrancar.**
Nada se construye por petición. Por eso los beans deben ser *stateless*, y por eso los errores de
configuración aparecen **al arrancar** y no a las tres semanas en producción.

---

← [10 — Seguridad JWT](10-seguridad-jwt.md) | **Siguiente:** [12 — Observaciones, mejoras y glosario](12-observaciones-y-glosario.md)

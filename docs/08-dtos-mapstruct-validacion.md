# 08 — DTOs, MapStruct y validación

---

## 1. ¿Qué es un DTO y por qué existe?

**DTO** = *Data Transfer Object*. Un objeto plano, sin lógica, cuyo único trabajo es **transportar
datos entre capas** — en particular, entre tu API y el cliente.

La regla que sigue este proyecto: **las entidades JPA nunca salen ni entran por HTTP**. Lo que viaja
son DTOs.

### Las cinco razones para no exponer entidades

**1. Seguridad — no filtrar campos sensibles.**

```java
// Entidad User                        // UserResponseDto
private String password;   // ☠️       // (no existe)
private String username;               private String username;
```

Si devolvieras la entidad `User` en un JSON, **enviarías el hash de la contraseña al cliente**. El
DTO simplemente no tiene ese campo. Es imposible filtrarlo por descuido.

**2. Seguridad — evitar el *mass assignment*.**

Si aceptaras una entidad `Event` en el `@RequestBody`, un cliente malicioso podría mandar
`{"id": 999, "attendedUsers": [...]}` y modificar cosas que no debe.
`EventRequestDto` **solo tiene** `name`, `date`, `location`, `categoryId` y `speakersIds`. Todo lo
demás se ignora, punto.

> Es el mismo problema que en Symfony resuelves con `allow_extra_fields: false` y los Form Types,
> o con grupos de deserialización.

**3. Desacoplar la API del modelo de datos.**

Si renombras el campo `location` a `venue` en la entidad, **la API no cambia**: ajustas el mapper y
los clientes ni se enteran. Sin DTOs, cualquier refactor de la base de datos rompe a tus
consumidores.

**4. Evitar la recursión infinita al serializar.**

```
Event → speakers → Speaker → events → Event → speakers → 💥 StackOverflowError
```

Es el mismo problema que el `toString()` del [doc 05](05-dominio-entidades-jpa.md). Con DTOs
desaparece: `EventResponseDto` tiene `Set<SpeakerResponseDto>`, y `SpeakerResponseDto` **no tiene
eventos**. La recursión se corta por diseño.

**5. Formas distintas para necesidades distintas.**

El mismo `Event` se representa de tres maneras según el caso:

| DTO | Campos | Uso |
|---|---|---|
| `EventRequestDto` | name, date, location, **categoryId**, **speakersIds** | Entrada: el cliente manda **IDs** |
| `EventResponseDto` | id, name, date, location, categoryId, **categoryName**, **speakers completos** | Salida: el cliente recibe **objetos** |
| `EventSummaryDto` | id, name, date, location | Versión ligera, para listas anidadas |

**Fíjate en la asimetría de entrada/salida.** Al crear un evento mandas
`{"categoryId": 3, "speakersIds": [1,2]}`; al leerlo recibes
`{"categoryId": 3, "categoryName": "Taller", "speakers": [{"id":1,"name":"John Doe",...}]}`.
Eso es diseño de API deliberado y bien hecho: el cliente no debería tener que hacer tres peticiones
para pintar una lista de eventos.

`EventSummaryDto` existe para evitar la recursión desde `UserResponseDto`: un usuario lista sus
eventos asistidos, pero **en versión resumida**, sin volver a arrastrar ponentes y categorías.

> **Equivalente Symfony:** allí es habitual no usar DTOs y resolverlo con **serialization groups**
> (`#[Groups(['event:read'])]`) sobre la propia entidad. Funciona, pero acopla la entidad a la API y
> es fácil filtrar un campo por error. API Platform empuja hacia DTOs por las mismas razones.

---

## 2. Los 8 DTOs del proyecto

### De negocio ([dto/](../src/main/java/com/gestion/eventos/api/dto/))

| DTO | Anotaciones | Notas |
|---|---|---|
| `EventRequestDto` | `@Data` + validaciones | Entrada de eventos |
| `EventResponseDto` | `@Data` | Salida de eventos |
| `EventSummaryDto` | `@Data` | Versión ligera |
| `CategoryDto` | `@Data @AllArgsConstructor @NoArgsConstructor` | **Se usa para entrada Y salida** |
| `SpeakerRequestDto` | `@Data` + validaciones | Entrada de ponentes |
| `SpeakerResponseDto` | `@Data @AllArgsConstructor @NoArgsConstructor` | Salida de ponentes |
| `UserResponseDto` | `@Data` | Salida de usuarios (**sin password**) |
| `RoleDto` | `@Data @AllArgsConstructor @NoArgsConstructor` | Anidado en `UserResponseDto` |

📌 **`CategoryDto` es el caso mixto.** Se usa como entrada en `createCategory` y `updateCategory`, y
como salida en los GET. Eso significa que **tiene un campo `id`** que el cliente podría mandar y que
se ignora silenciosamente en el `POST`. Para una categoría de tres campos es perfectamente
razonable; si algún día crece, conviene separarla en `CategoryRequestDto` / `CategoryResponseDto`
como se ha hecho con `Event` y `Speaker`.

📌 **`CategoryDto` no tiene validaciones** (`@NotBlank`, `@Size`), aunque el controlador la recibe
con `@Valid`. Es decir: `@Valid` está puesto, pero no hay nada que validar. Se podría mandar
`{"name": ""}` y pasaría. Es una mejora fácil (ver [doc 12](12-observaciones-y-glosario.md)).

### De seguridad ([security/dto/](../src/main/java/com/gestion/eventos/api/security/dto/))

| DTO | Contenido |
|---|---|
| `LoginDto` | `username`, `password` |
| `RegisterDto` | `username`, `password`, `email`, `name`, `Set<String> roles` — **con validaciones completas** |
| `JwtAuthResponseDto` | `accessToken`, `tokenType = "Bearer "` |

---

## 3. MapStruct: el conversor que se escribe solo

Convertir entidad ⇄ DTO a mano es tedioso y propenso a errores:

```java
// A mano — 8 líneas por cada conversión, multiplicado por 12 conversiones
public EventResponseDto toDto(Event e) {
    EventResponseDto dto = new EventResponseDto();
    dto.setId(e.getId());
    dto.setName(e.getName());
    dto.setDate(e.getDate());
    dto.setLocation(e.getLocation());
    dto.setCategoryId(e.getCategory().getId());
    // ... y los ponentes, uno a uno
    return dto;
}
```

MapStruct genera todo eso **en tiempo de compilación**, a partir de una interfaz.

### Cómo funciona

```java
@Mapper(componentModel = "spring")
public interface CategoryMapper {
    CategoryDto toDto(Category category);
    Category toEntity(CategoryDto categoryDto);
}
```

Durante `mvn compile`, el procesador de anotaciones de MapStruct:
1. Lee la interfaz.
2. Empareja los campos **por nombre**: `category.getName()` → `dto.setName()`.
3. **Escribe un archivo `.java` real**: `target/generated-sources/annotations/.../CategoryMapperImpl.java`.
4. Como `componentModel = "spring"`, le pone `@Component` para que sea un bean inyectable.

El resultado se parece a esto:

```java
@Component
public class CategoryMapperImpl implements CategoryMapper {
    @Override
    public CategoryDto toDto(Category category) {
        if (category == null) return null;
        CategoryDto dto = new CategoryDto();
        dto.setId(category.getId());
        dto.setName(category.getName());
        dto.setDescription(category.getDescription());
        return dto;
    }
    // ...
}
```

> **🔬 Haz esto:** ejecuta `.\mvnw.cmd clean compile` y abre
> `target/generated-sources/annotations/com/gestion/eventos/api/mapper/EventMapperImpl.java`.
> Es la mejor forma de convencerte de que no hay magia: solo código Java que alguien escribió por
> ti. Ese archivo tiene unas 80 líneas que no has tenido que teclear ni mantener.

### Ventajas frente a alternativas

| | MapStruct | ModelMapper / reflexión |
|---|---|---|
| Cuándo trabaja | Al compilar | En cada llamada, en runtime |
| Velocidad | Igual que código a mano | Lenta (reflexión) |
| Errores de mapeo | **Fallo de compilación** | Fallo en producción |
| ¿Puedes leer el código? | Sí | No |

---

## 4. `EventMapper`, la pieza más elaborada

[EventMapper.java](../src/main/java/com/gestion/eventos/api/mapper/EventMapper.java)

```java
@Mapper(componentModel = "spring")
public interface EventMapper {

    // ENTRADA: DTO → Entidad
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "speakers", ignore = true)
    @Mapping(target = "attendedUsers", ignore = true)
    Event toEntity(EventRequestDto eventRequestDto);

    // SALIDA: Entidad → DTO
    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "speakers", source = "speakers")
    EventResponseDto toResponseDto(Event event);
    List<EventResponseDto> toEventResponseDtoList(List<Event> events);

    // ACTUALIZACIÓN: DTO → Entidad EXISTENTE
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "speakers", ignore = true)
    @Mapping(target = "attendedUsers", ignore = true)
    void updateEventFromDto(EventRequestDto dto, @MappingTarget Event event);

    EventSummaryDto toSummaryDto(Event event);
    List<EventSummaryDto> toSummaryDtoList(List<Event> events);
}
```

### `ignore = true`: por qué se ignoran esos campos

```java
@Mapping(target = "id", ignore = true)
```

**El `id` nunca viene del cliente.** Lo genera la base de datos. Si MapStruct intentara mapearlo,
un cliente podría forzar el id de un evento.

```java
@Mapping(target = "category", ignore = true)
@Mapping(target = "speakers", ignore = true)
```

**Estas relaciones no se pueden mapear automáticamente**, porque el DTO trae `categoryId` (un
`Long`) y la entidad necesita una `Category` (un objeto que hay que **buscar en la base de datos**).
MapStruct no puede hacer eso solo, así que se ignoran aquí y se resuelven en el servicio:

```java
// EventService.save() — EventService.java:55-69
Event event = eventMapper.toEntity(requestDto);    // sin categoría ni ponentes

Category category = categoryService.findById(requestDto.getCategoryId());   // ← búsqueda real
event.setCategory(category);

if (requestDto.getSpeakersIds() != null && !requestDto.getSpeakersIds().isEmpty()) {
    Set<Speaker> speakers = requestDto.getSpeakersIds().stream()
            .map(speakerService::findById)          // ← búsqueda real, una por id
            .collect(Collectors.toSet());
    speakers.forEach(event::addSpeaker);            // ← usa el método helper
}
```

**Esta separación es correcta y deliberada:** el mapper hace conversión pura y estúpida; el servicio
hace lo que requiere ir a la base de datos. Un mapper que consultase la BD sería un mapper con
responsabilidades de servicio.

*(La alternativa sería que el mapper inyectase los repositorios, como hace `UserMapper` con los
roles. Ambas soluciones son válidas; ésta es la más limpia.)*

### `@MappingTarget`: actualizar en lugar de crear

```java
void updateEventFromDto(EventRequestDto dto, @MappingTarget Event event);
```

Sin `@MappingTarget`, MapStruct crearía un `Event` nuevo. Con él, **modifica el objeto que le
pasas**. Devuelve `void` porque muta el argumento.

Esto es esencial para el patrón de actualización con JPA: necesitas modificar **la entidad
gestionada** que cargaste de la base de datos, para que el *dirty checking* del
[doc 07](07-transaccional-a-fondo.md) detecte los cambios. Si crearas un objeto nuevo, JPA no
sabría que corresponde a una fila existente.

```java
// EventService.update() — EventService.java:82-86
Event existingEvent = eventRepository.findById(id).orElseThrow(...);  // gestionada
eventMapper.updateEventFromDto(requestDto, existingEvent);            // se modifica in-place
```

### `source`: cuando los nombres no coinciden

```java
@Mapping(target = "categoryId",   source = "category.id")
@Mapping(target = "categoryName", source = "category.name")
```

MapStruct empareja por nombre, pero aquí `EventResponseDto.categoryId` debe salir de
`event.getCategory().getId()`. La navegación con punto se lo indica. MapStruct genera además la
comprobación de nulos:

```java
// código generado, aproximadamente:
Category category = event.getCategory();
if (category != null) {
    dto.setCategoryId(category.getId());
    dto.setCategoryName(category.getName());
}
```

⚠️ **Aquí es donde se dispara el N+1 del que hablábamos**: `event.getCategory()` es LAZY, así que
cada llamada al mapper puede provocar un `SELECT`. Con 10 eventos, 10 consultas extra. El mapper no
tiene la culpa; la solución es cargar los datos con `@EntityGraph` antes de mapear.

### Mapeo de colecciones automático

```java
@Mapping(target = "speakers", source = "speakers")
```

MapStruct ve que hay que convertir `Set<Speaker>` en `Set<SpeakerResponseDto>`, encuentra que puede
generar un `Speaker → SpeakerResponseDto` (los campos coinciden) y **escribe el bucle solo**.
Igual con `List<Event> → List<EventResponseDto>`.

---

## 5. `UserMapper`: el mapper con dependencias

Ya lo vimos en el [doc 04](04-por-que-interfaces.md), pero aquí está el detalle del mapeo:

```java
@Mapper(componentModel = "spring")
public abstract class UserMapper {

    @Autowired
    protected RoleRepository roleRepository;

    @Mapping(target = "password", ignore = true)      // ⚠️ CRÍTICO
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roles", source = "registerDto.roles", qualifiedByName = "mapRoleStringsToRoles")
    @Mapping(target = "attendedEvents", ignore = true)
    public abstract User registerDtoToUser(RegisterDto registerDto);

    @Named("mapRoleStringsToRoles")
    public Set<Role> mapRoleStringsToRoles(Set<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return roleRepository.findByName("ROLE_USER")     // rol por defecto
                    .map(Collections::singleton)
                    .orElseThrow(() -> new ResourceNotFoundException("Error: Rol 'ROLE_USER' no encontrado..."));
        }
        return roleNames.stream()
                .map(roleName -> roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ResourceNotFoundException("Error: Rol no encontrado: " + roleName)))
                .collect(Collectors.toSet());
    }
}
```

Tres cosas destacables:

**a) `@Mapping(target = "password", ignore = true)` es una decisión de seguridad importante.** La
contraseña **no** se copia del DTO a la entidad aquí; se asigna aparte, ya cifrada:

```java
// AuthController.java:61-62
User user = userMapper.registerDtoToUser(registerDto);
user.setPassword(passwordEncoder.encode(registerDto.getPassword()));   // ← hasheada
```

Así es **estructuralmente imposible** guardar una contraseña en claro por descuido: el mapper
sencillamente no toca ese campo. 👍

**b) `@Named` + `qualifiedByName`** es cómo se le dice a MapStruct "para este campo concreto, llama
a este método mío en lugar de intentar mapearlo tú".

**c) El rol por defecto.** Si el registro no especifica roles, se asigna `ROLE_USER`. Es la política
correcta: nadie se autoconcede permisos por omisión.

⚠️ **Pero hay un agujero de seguridad real aquí**, y conviene que lo veas: `RegisterDto` acepta un
`Set<String> roles` que el **cliente** controla. Nada impide que alguien mande:

```json
POST /api/v1/auth/register
{"username":"malo","password":"123456","email":"a@b.c","name":"X","roles":["ROLE_ADMIN"]}
```

...y se registre **como administrador**. El endpoint `/api/v1/auth/**` es `permitAll()`. Para un
proyecto de aprendizaje da igual, pero está en la lista de observaciones del
[doc 12](12-observaciones-y-glosario.md) porque es el tipo de fallo que se cuela en producción.

---

## 6. Bean Validation: `@Valid` y las restricciones

### Las anotaciones que usa el proyecto

```java
// EventRequestDto
@NotBlank(message = "El nombre del evento no puede estar vacío.")
private String name;

@NotNull(message = "La fecha no puede ser nula.")
private LocalDate date;

@NotNull(message = "La categoría es obligatoria.")
private Long categoryId;
```

```java
// SpeakerRequestDto
@NotBlank(message = "El nombre del ponente no puede estar vacío.")
@Size(max = 100, message = "El nombre del ponente no puede exceder los 100 caracteres.")
private String name;

@NotBlank @Email @Size(max = 100)
private String email;

@Size(max = 500, message = "La biografía no puede exceder los 500 caracteres.")
private String bio;
```

El catálogo completo:

| Anotación | Válido si | Equivalente Symfony |
|---|---|---|
| `@NotNull` | no es `null` | `#[Assert\NotNull]` |
| `@NotEmpty` | no es null ni vacío (colecciones, String) | `#[Assert\NotBlank(allowNull: false)]` |
| `@NotBlank` | String no null, no vacío y **no solo espacios** | `#[Assert\NotBlank]` |
| `@Size(min, max)` | longitud dentro del rango | `#[Assert\Length]` |
| `@Email` | formato de email | `#[Assert\Email]` |
| `@Min` / `@Max` | rango numérico | `#[Assert\Range]` |
| `@Positive` / `@Negative` | signo | `#[Assert\Positive]` |
| `@Past` / `@Future` | fechas | `#[Assert\LessThan('now')]` |
| `@Pattern(regexp=...)` | expresión regular | `#[Assert\Regex]` |

**Diferencia entre `@NotNull`, `@NotEmpty` y `@NotBlank`** (fuente eterna de confusión):

| Valor | `@NotNull` | `@NotEmpty` | `@NotBlank` |
|---|---|---|---|
| `null` | ❌ | ❌ | ❌ |
| `""` | ✅ | ❌ | ❌ |
| `"   "` | ✅ | ✅ | ❌ |
| `"texto"` | ✅ | ✅ | ✅ |

Para `String` casi siempre quieres `@NotBlank`. El proyecto lo usa correctamente.

### `@Valid`: el disparador

Las anotaciones **no hacen nada por sí solas**. Lo que dispara la validación es `@Valid` en el
parámetro del controlador:

```java
@PostMapping
public ResponseEntity<EventResponseDto> createEvent(@Valid @RequestBody EventRequestDto requestDto) {
```

Al llegar la petición, Spring:
1. Jackson deserializa el JSON en el DTO.
2. Ve `@Valid` → llama a Hibernate Validator.
3. Si hay errores → lanza `MethodArgumentNotValidException` y **el método del controlador nunca se
   ejecuta**.
4. Esa excepción la captura `GlobalExceptionHandler` y la convierte en un 400 con el detalle.

```json
{
  "status": 400,
  "error": "Bad request",
  "message": "Errores validación",
  "errors": {
    "name": "El nombre del evento no puede estar vacío.",
    "categoryId": "La categoría es obligatoria."
  }
}
```

Un JSON de errores campo-a-campo, perfecto para que un frontend pinte los mensajes bajo cada input.
Es equivalente a recorrer el `ConstraintViolationList` de Symfony.

### ⚠️ Falta `@Valid` en el registro y el login

```java
// AuthController.java:39 y :52
public ResponseEntity<JwtAuthResponseDto> authenticateUser(@RequestBody LoginDto loginDto)
public ResponseEntity<String> registerUser(@RequestBody RegisterDto registerDto)
//                                          ↑ falta @Valid
```

`RegisterDto` tiene validaciones muy completas (`@NotBlank`, `@Size(min=4,max=20)`, `@Email`,
`@Size(min=6)` en la contraseña)… **y no se aplican ninguna**, porque falta `@Valid`. Ahora mismo se
puede registrar un usuario con contraseña `"a"` y email `"noesunemail"`.

La corrección es añadir una palabra:

```java
public ResponseEntity<String> registerUser(@Valid @RequestBody RegisterDto registerDto) {
```

Está en la lista del [doc 12](12-observaciones-y-glosario.md).

### Validación en entidad vs validación en DTO

Fíjate en que las validaciones están en los **DTOs**, no en las entidades. Es lo correcto:

- **DTO** → valida la **entrada del usuario**, con mensajes pensados para humanos.
- **Entidad** → `@Column(nullable = false)` valida la **integridad de la base de datos**.

Son dos capas de defensa distintas y complementarias. La primera da mensajes bonitos; la segunda
impide que un bug corrompa los datos.

---

## 7. Resumen del capítulo

- Los **DTOs** aíslan la API del modelo: evitan filtrar `password`, impiden el *mass assignment*,
  cortan la recursión al serializar y permiten refactorizar la BD sin romper clientes.
- Este proyecto separa `Request` / `Response` / `Summary` para `Event`, y con buen criterio: la
  entrada lleva **IDs** y la salida lleva **objetos**.
- **MapStruct** genera los mappers **al compilar**, en código Java legible que puedes abrir en
  `target/generated-sources/`.
- `ignore = true` para lo que el cliente no debe controlar (`id`) o lo que requiere ir a la base de
  datos (`category`, `speakers`); esos se resuelven en el servicio.
- `@MappingTarget` actualiza una entidad **existente**, que es lo que hace falta para que funcione
  el dirty checking.
- `UserMapper` es `abstract class` porque necesita `RoleRepository`; e ignora `password` a
  propósito, para que solo pueda asignarse ya cifrada.
- **Bean Validation** (`@NotBlank`, `@Email`, `@Size`) se dispara con `@Valid` en el controlador.
  Falta en `AuthController`, y `CategoryDto` no tiene restricciones.

---

← [07 — `@Transactional` a fondo](07-transaccional-a-fondo.md) | **Siguiente:** [09 — La capa web: controladores](09-capa-web-controladores.md)

# 06 — Repositorios: Spring Data JPA

> Cinco interfaces, **cero líneas de implementación**, y todo el acceso a datos resuelto.
> Este es el componente donde Spring hace más magia, y donde más se separa de Doctrine.

---

## 1. Los 5 repositorios del proyecto

Están todos en [repository/](../src/main/java/com/gestion/eventos/api/repository/) y son
sorprendentemente cortos:

```java
// EventRepository.java — el archivo COMPLETO
public interface EventRepository extends JpaRepository<Event, Long> {
    Page<Event> findByNameContainingIgnoreCase(String name, Pageable pageable);
}

// CategoryRepository.java
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);
    boolean existsByName(String name);
}

// SpeakerRepository.java
public interface SpeakerRepository extends JpaRepository<Speaker, Long> {
    Optional<Speaker> findByEmail(String email);
    boolean existsByEmail(String email);
}

// UserRepository.java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Boolean existsByUsername(String username);
    Boolean existsByEmail(String email);
}

// RoleRepository.java
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);
}
```

Ninguno tiene `@Repository`. **No hace falta**: Spring Data detecta cualquier interfaz que extienda
`Repository` y la registra automáticamente. Ponerle `@Repository` no molesta, pero es redundante.

---

## 2. Qué te da `JpaRepository` gratis

`JpaRepository<Event, Long>` significa: *"repositorio de entidades `Event` cuya clave primaria es de
tipo `Long`"*.

La jerarquía de interfaces heredadas:

```
        Repository<T, ID>              ← interfaz marcadora, vacía
              ▲
    CrudRepository<T, ID>              ← save, findById, findAll, delete, count, existsById
              ▲
 PagingAndSortingRepository<T, ID>     ← findAll(Pageable), findAll(Sort)
              ▲
   ListCrudRepository / JpaRepository  ← saveAll, flush, saveAndFlush, deleteAllInBatch, getReferenceById
              ▲
      TU EventRepository
```

**Métodos que puedes llamar sin escribir nada:**

| Método | Qué hace | Dónde se usa en el proyecto |
|---|---|---|
| `save(entity)` | INSERT si el id es null, UPDATE si no | `CategoryServiceImpl.save`, `EventService.save/update`, `SpeakerServiceImpl` |
| `saveAll(iterable)` | Guarda una colección | `DataLoader` con los 60 eventos |
| `findById(id)` | `Optional<T>` | Todos los servicios |
| `findAll()` | `List<T>` | `CategoryServiceImpl.findAll`, `SpeakerServiceImpl.findAll` |
| `findAll(Pageable)` | `Page<T>` paginado | `EventService.findAll` |
| `existsById(id)` | `boolean` sin cargar la entidad | `CategoryServiceImpl.deleteById`, `SpeakerServiceImpl.deleteById` |
| `count()` | `long` | `DataLoader` (`if (eventRepository.count() == 0)`) |
| `delete(entity)` | DELETE de una entidad ya cargada | `EventService.deleteById` |
| `deleteById(id)` | DELETE por id | `CategoryServiceImpl`, `SpeakerServiceImpl` |
| `flush()` | Fuerza el volcado a BD (ver doc 07) | *(no se usa — y está bien así)* |
| `getReferenceById(id)` | Devuelve un **proxy** sin consultar la BD | *(no se usa; útil para asignar FKs)* |

### Un detalle sobre `save()`

`save()` **no siempre hace un INSERT**. Spring Data mira si la entidad es "nueva":

- `id == null` → `entityManager.persist()` → INSERT
- `id != null` → `entityManager.merge()` → UPDATE (o SELECT + UPDATE)

Y hay un caso que sorprende: en `CategoryServiceImpl.update()`, la llamada a
`categoryRepository.save(existingCategory)` **es innecesaria**, porque la entidad ya está gestionada
dentro de la transacción y se guardaría sola por *dirty checking*. Es redundante pero inofensiva.
Se explica en el [doc 07](07-transaccional-a-fondo.md).

---

## 3. Los *derived queries*: consultas derivadas del nombre del método

Esta es la parte que más impresiona. Escribes el nombre de un método siguiendo una gramática, y
Spring Data lo traduce a JPQL.

```java
Optional<Category> findByName(String name);
```

Spring Data lo parsea así:

```
find    By    Name
 │       │      │
 │       │      └─ propiedad de la entidad Category → c.name
 │       └─ empieza el criterio
 └─ verbo: devolver resultados

→ JPQL generado:  SELECT c FROM Category c WHERE c.name = :name
→ SQL:            SELECT * FROM categories WHERE name = ?
```

### La gramática, en tres partes

**1. El verbo (prefijo):**

| Prefijo | Devuelve |
|---|---|
| `find...By` / `get...By` / `read...By` | Las entidades |
| `count...By` | `long` |
| `exists...By` | `boolean` |
| `delete...By` / `remove...By` | Borra (necesita `@Transactional`) |

**2. Las propiedades:** deben coincidir con los **campos de la entidad** (no con las columnas de la
BD). Se pueden encadenar: `findByCategoryName(String n)` → navega a `event.category.name` y genera
un JOIN.

**3. Los operadores (palabras clave):**

| Palabra clave | JPQL / SQL generado |
|---|---|
| `Is`, `Equals` (o nada) | `= ?` |
| `Containing` | `LIKE %?%` |
| `StartingWith` / `EndingWith` | `LIKE ?%` / `LIKE %?` |
| `IgnoreCase` | `LOWER(x) = LOWER(?)` |
| `Between` | `BETWEEN ? AND ?` |
| `LessThan` / `GreaterThan` | `< ?` / `> ?` |
| `After` / `Before` | para fechas |
| `IsNull` / `IsNotNull` | `IS NULL` / `IS NOT NULL` |
| `In` | `IN (?)` |
| `And` / `Or` | combinan criterios |
| `OrderBy...Asc/Desc` | `ORDER BY` |
| `Not` | negación |

### El ejemplo real del proyecto

```java
Page<Event> findByNameContainingIgnoreCase(String name, Pageable pageable);
```

Se descompone en:

```
find  By  Name  Containing  IgnoreCase
                    │           └─ LOWER(...)
                    └─ LIKE %...%

→ SELECT e FROM Event e WHERE LOWER(e.name) LIKE LOWER(CONCAT('%', :name, '%'))
```

Y como recibe un `Pageable`, Spring Data añade automáticamente:
- el `LIMIT` / `OFFSET`
- el `ORDER BY` del `Sort`
- **una segunda consulta `SELECT COUNT(*)`** para saber el total de elementos

Es lo que hace posible el buscador de `GET /api/v1/events?name=confe&page=0&size=10`.

### Ejemplos de lo que podrías escribir sin implementar nada

```java
List<Event> findByDateAfter(LocalDate fecha);
List<Event> findByCategoryId(Long categoryId);                    // navega a la relación
List<Event> findByLocationAndDateBetween(String loc, LocalDate d1, LocalDate d2);
List<Event> findTop5ByOrderByDateDesc();                          // los 5 más próximos
long countByCategoryName(String nombreCategoria);
boolean existsByNameAndDate(String name, LocalDate date);
```

### ⚠️ El riesgo de los derived queries

Los nombres se vuelven ilegibles rápido:

```java
findByCategoryNameAndDateBetweenAndLocationContainingIgnoreCaseOrderByDateDesc(...)
```

Cuando llegues a ese punto, **usa `@Query`**.

---

## 4. `@Query`: cuando el nombre no basta

```java
@Query("SELECT e FROM Event e WHERE e.date > :fecha AND e.category.name = :cat")
List<Event> buscarProximosPorCategoria(@Param("fecha") LocalDate fecha,
                                       @Param("cat") String categoria);
```

Esto es **JPQL**: se parece a SQL pero opera sobre **entidades y campos Java**, no sobre tablas y
columnas. `Event` es la clase, no la tabla `events`.

Si necesitas SQL nativo de verdad:

```java
@Query(value = "SELECT * FROM events WHERE date > ?1", nativeQuery = true)
List<Event> buscarNativo(LocalDate fecha);
```

> **JPQL ≈ DQL de Doctrine.** Prácticamente la misma sintaxis y la misma filosofía.
> `nativeQuery = true` ≈ `$conn->executeQuery()` en Doctrine.

**Este proyecto no usa `@Query` todavía.** Es el siguiente paso natural, sobre todo para arreglar
el N+1:

```java
@Query("SELECT DISTINCT e FROM Event e LEFT JOIN FETCH e.category LEFT JOIN FETCH e.speakers")
List<Event> findAllConTodo();
```

---

## 5. Paginación: `Pageable`, `Page` y `Sort`

Esta es una de las cosas donde Spring te da mucho más hecho que Symfony (donde normalmente instalas
KnpPaginatorBundle o Pagerfanta).

### En el controlador

[EventController.java:40-47](../src/main/java/com/gestion/eventos/api/controller/EventController.java#L40-L47)

```java
@GetMapping
public ResponseEntity<Page<EventResponseDto>> getAllEvents(
        @RequestParam(required = false) String name,
        @PageableDefault(page = 0, size = 10, sort = "name") Pageable pageable) {
    Page<EventResponseDto> events = eventService.findAll(name, pageable);
    return ResponseEntity.ok(events);
}
```

**Spring construye el `Pageable` solo, leyendo los parámetros de la URL.** No escribes ni una línea
para ello:

| URL | Resultado |
|---|---|
| `/api/v1/events` | página 0, 10 elementos, ordenado por `name` (los valores de `@PageableDefault`) |
| `/api/v1/events?page=2` | página 2 (la tercera; se cuenta desde 0) |
| `/api/v1/events?size=25` | 25 elementos por página |
| `/api/v1/events?sort=date,desc` | ordenado por fecha descendente |
| `/api/v1/events?sort=date,desc&sort=name,asc` | orden múltiple |
| `/api/v1/events?name=confe&page=1&size=5` | filtro + paginación combinados |

`@PageableDefault` define qué pasa si no se envían parámetros. Sin esta anotación, el defecto de
Spring sería página 0, tamaño 20, sin orden.

### En el servicio

[EventService.java:36-51](../src/main/java/com/gestion/eventos/api/service/EventService.java#L36-L51)

```java
@Transactional(readOnly = true)
public Page<EventResponseDto> findAll(String name, Pageable pageable) {
    Page<Event> eventsPage;

    if (name != null && !name.trim().isEmpty()) {
        eventsPage = eventRepository.findByNameContainingIgnoreCase(name, pageable);
    } else {
        eventsPage = eventRepository.findAll(pageable);
    }

    List<EventResponseDto> dtos = eventsPage.getContent().stream()
            .map(eventMapper::toResponseDto)
            .toList();

    return new PageImpl<>(dtos, pageable, eventsPage.getTotalElements());
}
```

**El patrón "filtro opcional"**: si llega `name`, busca; si no, devuelve todo. Simple y efectivo
para un caso. Si algún día hacen falta 5 filtros combinables, la herramienta adecuada sería
**Specifications** (`JpaSpecificationExecutor`) o **Querydsl**, que son el equivalente al
`QueryBuilder` dinámico de Doctrine.

**Un apunte de estilo:** la construcción manual del `PageImpl` se puede simplificar. `Page` ya tiene
un método `map()` que conserva los metadatos de paginación:

```java
return eventsPage.map(eventMapper::toResponseDto);   // ← equivalente y más limpio
```

Hace exactamente lo mismo en menos líneas y sin riesgo de equivocarse con los argumentos.

### Qué contiene un `Page<T>`

El JSON que sale al cliente:

```json
{
  "content": [ { "id": 1, "name": "Evento 01: ...", ... } ],
  "pageable": { "pageNumber": 0, "pageSize": 10, "sort": {...}, "offset": 0 },
  "totalElements": 60,
  "totalPages": 6,
  "last": false,
  "first": true,
  "size": 10,
  "number": 0,
  "numberOfElements": 10,
  "empty": false
}
```

Métodos útiles de `Page<T>`: `getContent()`, `getTotalElements()`, `getTotalPages()`,
`hasNext()`, `map(fn)`.

### `Page` vs `Slice` vs `List`

| Tipo devuelto | Consultas | Cuándo |
|---|---|---|
| `Page<T>` | 2 (datos + `COUNT`) | Necesitas saber el total y el número de páginas |
| `Slice<T>` | 1 | Solo necesitas saber "¿hay más?" (scroll infinito). Más rápido |
| `List<T>` | 1 | Aplicas límite pero no te importan los metadatos |

Con 60 registros da igual, pero con millones de filas el `COUNT(*)` de `Page` puede ser lo más caro
de la petición. Merece la pena saberlo.

---

## 6. Cómo se implementan estos repositorios (el mecanismo)

Recordatorio del [doc 04](04-por-que-interfaces.md): durante el arranque, Spring Data:

1. Encuentra las interfaces que extienden `Repository`.
2. Crea un **proxy JDK** que implementa cada una.
3. Le pone dentro una instancia de `SimpleJpaRepository` (la implementación real de los métodos
   estándar), que a su vez usa el `EntityManager`.
4. Para los métodos declarados por ti, construye un `PartTreeJpaQuery` que parsea el nombre y
   prepara la consulta.
5. Registra el proxy como bean.

**Detalle importante:** `SimpleJpaRepository` está anotada con `@Transactional(readOnly = true)` a
nivel de clase, y sus métodos de escritura (`save`, `delete`) con `@Transactional`. Es decir:
**cada llamada a un repositorio ya es transaccional por sí sola** si no hay una transacción abierta.

Eso lleva a la pregunta obvia: *entonces, ¿para qué `@Transactional` en los servicios?*
Es exactamente el tema del siguiente documento.

---

## 7. Comparación completa con Doctrine

| | Doctrine (Symfony) | Spring Data JPA |
|---|---|---|
| Definir un repositorio | Escribes `class XRepository extends ServiceEntityRepository` | Declaras `interface XRepository extends JpaRepository<X, Long>` |
| Consulta simple | `$repo->findOneBy(['name' => $n])` | `findByName(n)` — método declarado |
| Consulta compleja | `createQueryBuilder()` + DQL | `@Query` con JPQL, o Specifications |
| Guardar | `$em->persist($x); $em->flush();` | `repo.save(x)` (o nada, si estás en `@Transactional`) |
| Borrar | `$em->remove($x); $em->flush();` | `repo.delete(x)` |
| Paginación | Pagerfanta / KnpPaginator (bundles externos) | `Pageable`/`Page` **integrado** |
| Nivel de magia | Bajo: tú escribes las consultas | Alto: se derivan del nombre |
| Nivel de control | Alto | Alto también, pero hay que pedirlo (`@Query`) |

Ninguna es mejor. Doctrine es más explícita; Spring Data ahorra muchísimo código repetitivo a
cambio de aprenderse una gramática.

---

## 8. Resumen del capítulo

- `JpaRepository<T, ID>` te da ~20 métodos CRUD sin escribir nada.
- Los métodos que **declaras** se traducen a consultas **por su nombre** (`findByName`,
  `existsByEmail`, `findByNameContainingIgnoreCase`).
- Cuando el nombre se vuelve monstruoso, pasa a `@Query` con JPQL.
- La **paginación viene integrada**: `Pageable` se rellena solo desde la URL, `Page<T>` trae los
  metadatos, y `@PageableDefault` fija los valores por defecto.
- `eventsPage.map(mapper::toResponseDto)` es más limpio que construir un `PageImpl` a mano.
- Los repositorios ya son transaccionales por dentro; lo que las transacciones de servicio añaden se
  ve en el siguiente documento.

---

← [05 — Entidades JPA](05-dominio-entidades-jpa.md) | **Siguiente:** [07 — `@Transactional` a fondo](07-transaccional-a-fondo.md)

# 05 — La capa de dominio: entidades JPA e Hibernate

> Las 5 clases de [domain/](../src/main/java/com/gestion/eventos/api/domain/) son el corazón del
> modelo de datos. Cada una es una tabla, y las relaciones entre ellas son la parte más delicada
> del proyecto.

---

## 1. JPA vs Hibernate: aclaremos esto primero

| | Qué es |
|---|---|
| **JPA** (*Jakarta Persistence API*) | Una **especificación**: un conjunto de interfaces y anotaciones estándar. No hace nada por sí sola. |
| **Hibernate** | Una **implementación** de esa especificación. Es quien realmente genera el SQL. |
| **Spring Data JPA** | Una capa **por encima** de JPA que añade los repositorios automáticos. |

```
Tu código
   ↓ usa
Spring Data JPA   (repositorios, paginación)
   ↓ usa
JPA               (@Entity, @Column, EntityManager — el estándar)
   ↓ implementado por
Hibernate         (genera el SQL, gestiona la caché, el dirty checking)
   ↓ usa
JDBC              (el driver de H2 / PostgreSQL)
   ↓
Base de datos
```

Por eso las anotaciones se importan de `jakarta.persistence.*` (el estándar) y no de
`org.hibernate.*`. Podrías cambiar Hibernate por EclipseLink y tu código seguiría compilando.

> **Comparación:** Doctrine es a la vez la especificación y la implementación. No hay separación.
> Y `jakarta.*` era `javax.*` hasta 2019, cuando Oracle cedió Java EE a la Eclipse Foundation y
> hubo que renombrarlo todo. Si ves tutoriales con `javax.persistence`, son anteriores a Spring
> Boot 3.

---

## 2. El modelo de datos de este proyecto

```
                                ┌──────────────┐
                                │   Category   │
                                ├──────────────┤
                                │ id           │
                                │ name (unique)│
                                │ description  │
                                └──────┬───────┘
                                       │ 1
                                       │
                                       │ N     (@ManyToOne — obligatorio)
                                ┌──────┴───────┐
       ┌────────────────────────┤    Event     ├───────────────────────┐
       │ N                      ├──────────────┤                     N │
       │                        │ id           │                       │
       │                        │ name         │                       │
       │                        │ date         │                       │
       │                        │ location     │                       │
       │                        └──────────────┘                       │
       │ N                                                           N │
┌──────┴───────┐                                              ┌────────┴─────┐
│   Speaker    │                                              │     User     │
├──────────────┤                                              ├──────────────┤
│ id           │   tabla intermedia:                          │ id           │
│ name         │   event_speakers                             │ name         │
│ email(unique)│                                              │ username     │
│ bio          │   tabla intermedia:                          │ email        │
└──────────────┘   user_attended_events                       │ password     │
                                                              └──────┬───────┘
                                                                     │ N
                                                                     │  tabla: users_roles
                                                                     │ N
                                                              ┌──────┴───────┐
                                                              │     Role     │
                                                              ├──────────────┤
                                                              │ id           │
                                                              │ name         │
                                                              └──────────────┘
```

**En palabras:**
- Un **evento** pertenece a **una** categoría; una categoría tiene **muchos** eventos.
- Un **evento** tiene **muchos** ponentes; un ponente participa en **muchos** eventos.
- Un **usuario** asiste a **muchos** eventos; un evento tiene **muchos** asistentes.
- Un **usuario** tiene **muchos** roles; un rol lo tienen **muchos** usuarios.

Tablas que Hibernate crea: `events`, `categories`, `speakers`, `users`, `roles`,
`event_speakers`, `user_attended_events`, `users_roles`. **Ocho tablas para cinco entidades**: las
tres extra son las tablas de unión de los `@ManyToMany`.

---

## 3. Anatomía de una entidad simple: `Category`

[Category.java](../src/main/java/com/gestion/eventos/api/domain/Category.java)

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 500)
    private String description;
}
```

Línea por línea:

| Anotación | Qué hace | Equivalente Doctrine |
|---|---|---|
| `@Entity` | "Esta clase se mapea a una tabla". Hibernate la gestionará. | `#[ORM\Entity]` |
| `@Table(name = "categories")` | Nombre de la tabla. Sin esto sería `Category`. | `#[ORM\Table(name: 'categories')]` |
| `@Id` | Este campo es la clave primaria. | `#[ORM\Id]` |
| `@GeneratedValue(strategy = IDENTITY)` | El id lo genera la base de datos (`AUTO_INCREMENT`). | `#[ORM\GeneratedValue]` |
| `@Column(nullable = false, unique = true)` | `NOT NULL` + `UNIQUE` en el SQL generado. | `#[ORM\Column(nullable: false, unique: true)]` |
| `@Column(length = 500)` | `VARCHAR(500)` en lugar del `VARCHAR(255)` por defecto. | `#[ORM\Column(length: 500)]` |

SQL que genera Hibernate (`ddl-auto=update`):

```sql
CREATE TABLE categories (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500)
);
```

### Las estrategias de `@GeneratedValue`

| Estrategia | Cómo funciona | Cuándo usarla |
|---|---|---|
| `IDENTITY` | Columna auto-incremental de la BD | MySQL, H2, SQL Server. **La que usa este proyecto.** |
| `SEQUENCE` | Una secuencia de BD | PostgreSQL, Oracle. Más eficiente: permite *batch inserts*. |
| `TABLE` | Una tabla que guarda contadores | Portátil pero lento. Evítala. |
| `AUTO` | Hibernate elige | Cómodo, pero puede sorprenderte al cambiar de BD. |

⚠️ Con `IDENTITY`, Hibernate **no puede agrupar los INSERT en lotes**, porque necesita ejecutar cada
uno para conocer el id generado. Si algún día migras a PostgreSQL, `SEQUENCE` sería más rápido para
las cargas masivas (como los 60 eventos del `DataLoader`).

### `@NoArgsConstructor` no es opcional

**JPA exige un constructor sin argumentos** (público o protegido). Hibernate lo necesita para
instanciar la entidad por reflexión cuando la lee de la base de datos.

Fíjate en un detalle: `Category` y `Speaker` llevan `@NoArgsConstructor` + `@AllArgsConstructor`,
pero `Event`, `User` y `Role` solo tienen `@Data`. ¿Por qué funcionan igual? Porque **si no
declaras ningún constructor, Java crea uno vacío por defecto**. `@AllArgsConstructor` en `Category`
declara uno con argumentos, lo cual eliminaría el implícito — de ahí que haya que añadir
`@NoArgsConstructor` explícitamente. Se ve en acción en el `DataLoader`:

```java
new Category(null, "Conferencia", "Eventos de gran escala...")   // ← @AllArgsConstructor
```

---

## 4. Las relaciones

### 4.1 `@ManyToOne` — Evento → Categoría

[Event.java:51-53](../src/main/java/com/gestion/eventos/api/domain/Event.java#L51-L53)

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "category_id", nullable = false)
private Category category;
```

- `@ManyToOne`: muchos eventos → una categoría.
- `@JoinColumn(name = "category_id")`: la columna **en la tabla `events`** que guarda la clave
  foránea.
- `nullable = false`: todo evento **debe** tener categoría.
- `fetch = LAZY`: ver sección 5.

SQL:
```sql
ALTER TABLE events ADD COLUMN category_id BIGINT NOT NULL;
ALTER TABLE events ADD FOREIGN KEY (category_id) REFERENCES categories(id);
```

**Nota de diseño:** la relación es **unidireccional**. `Event` conoce su `Category`, pero `Category`
**no tiene** un `Set<Event> events`. Eso es una decisión deliberada y buena: si `Category` tuviera
la lista de sus eventos, sería fácil cargar accidentalmente miles de eventos al tocar una categoría.
Cuando necesites "los eventos de la categoría X", lo pides al repositorio de eventos, que es lo
correcto.

### 4.2 `@ManyToMany` — el lado propietario y el inverso

Esta es la parte que más cuesta. Presta atención al concepto de **lado propietario**.

**Lado PROPIETARIO** (Event) — [Event.java:41-49](../src/main/java/com/gestion/eventos/api/domain/Event.java#L41-L49):

```java
@ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
@JoinTable(
        name = "event_speakers",
        joinColumns = @JoinColumn(name = "event_id"),
        inverseJoinColumns = @JoinColumn(name = "speakers_id")
)
@ToString.Exclude
@EqualsAndHashCode.Exclude
private Set<Speaker> speakers = new HashSet<>();
```

**Lado INVERSO** (Speaker) — [Speaker.java:44-47](../src/main/java/com/gestion/eventos/api/domain/Speaker.java#L44-L47):

```java
@ManyToMany(mappedBy = "speakers")     // ← mappedBy = "yo NO mando"
@ToString.Exclude
@EqualsAndHashCode.Exclude
private Set<Event> events = new HashSet<>();
```

#### ¿Qué significa "lado propietario"?

En la base de datos, una relación N:M es **una sola tabla intermedia**. Pero en Java hay **dos
colecciones** (`event.speakers` y `speaker.events`). JPA necesita saber **cuál de las dos mira**
para decidir qué escribir en esa tabla.

- El lado que define `@JoinTable` es el **propietario**. JPA mira **esta** colección al guardar.
- El lado que define `mappedBy` es el **inverso**. JPA lo **ignora completamente** al escribir. Solo
  sirve para navegar/leer.

> ### ⚠️ Consecuencia crítica
> Si haces `speaker.getEvents().add(evento)` y guardas... **no se guarda nada**. JPA no mira el
> lado inverso.
>
> Tienes que hacer `evento.getSpeakers().add(speaker)`.

`@JoinTable` describe la tabla intermedia:
```sql
CREATE TABLE event_speakers (
    event_id    BIGINT REFERENCES events(id),      -- joinColumns: MI clave
    speakers_id BIGINT REFERENCES speakers(id)     -- inverseJoinColumns: la clave del OTRO
);
```

*(Nota menor: `speakers_id` es un nombre un poco raro — lo habitual sería `speaker_id` en singular.
Funciona perfectamente, pero si algún día escribes SQL a mano, recuérdalo.)*

#### Los métodos helper: la solución al problema del lado inverso

[Event.java:60-68](../src/main/java/com/gestion/eventos/api/domain/Event.java#L60-L68)

```java
public void addSpeaker(Speaker speaker) {
    this.speakers.add(speaker);        // lado propietario → esto SE GUARDA
    speaker.getEvents().add(this);     // lado inverso → esto mantiene la coherencia en memoria
}

public void removeSpeaker(Speaker speaker) {
    this.speakers.remove(speaker);
    speaker.getEvents().remove(this);
}
```

**Esto es una muy buena práctica y está bien resuelto en el proyecto.** ¿Por qué actualizar también
el lado inverso si JPA lo ignora?

Porque **dentro de la misma transacción**, si añades un ponente y luego consultas
`speaker.getEvents()`, el objeto que tienes en memoria es el mismo (está en el contexto de
persistencia). Si no lo actualizas, verías datos inconsistentes hasta el siguiente reinicio.

Lo mismo existe en `User`:
[User.java:53-61](../src/main/java/com/gestion/eventos/api/domain/User.java#L53-L61) con
`addAttendedEvent` / `removeAttendedEvent`.

Se usa en la práctica en [EventService.update()](../src/main/java/com/gestion/eventos/api/service/EventService.java#L102-L114):

```java
// Quitar los que ya no están
new HashSet<>(existingEvent.getSpeakers())      // ← copia defensiva, ver nota
        .forEach(currentSpeaker -> {
            if (!updatedSpeakers.contains(currentSpeaker)) {
                existingEvent.removeSpeaker(currentSpeaker);
            }
        });

// Añadir los nuevos
updatedSpeakers.forEach(newSpeaker -> {
    if (!existingEvent.getSpeakers().contains(newSpeaker)) {
        existingEvent.addSpeaker(newSpeaker);
    }
});
```

📌 **La `new HashSet<>(...)` de la primera línea es importante y está bien puesta.** Si iteraras
directamente sobre `existingEvent.getSpeakers()` y a la vez lo modificaras con `removeSpeaker`,
Java lanzaría una `ConcurrentModificationException`. Al copiar el conjunto, iteras sobre la copia y
modificas el original. Es un detalle que denota cuidado.

#### `@ManyToMany` de User a Role

[User.java:34-40](../src/main/java/com/gestion/eventos/api/domain/User.java#L34-L40)

```java
@ManyToMany(fetch = FetchType.EAGER) // Lazy loading para evitar cargar roles innecesarios
@JoinTable(
        name = "users_roles",
        joinColumns = @JoinColumn(name = "user_id", referencedColumnName = "id"),
        inverseJoinColumns = @JoinColumn(name = "role_id", referencedColumnName = "id")
)
private Set<Role> roles = new HashSet<>();
```

Aquí es **EAGER**, y es la decisión correcta: cada vez que se autentica un usuario, en
[UserDetailsServiceImpl](../src/main/java/com/gestion/eventos/api/security/service/UserDetailsServiceImpl.java)
se necesitan sus roles inmediatamente para construir las *authorities*. Si fuera LAZY, saldría una
`LazyInitializationException` (ver sección 5).

⚠️ Pero **el comentario de esa línea dice "Lazy loading" y el código dice `EAGER`**. El comentario
es un resto de una versión anterior y contradice al código. Merece la pena corregirlo para no
confundirte dentro de tres meses.

Además, esta relación **es unidireccional**: `Role` no tiene `Set<User>`. Correcto: no querrías
cargar todos los usuarios al tocar un rol.

### 4.3 `cascade` — propagar operaciones

```java
@ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
```

| Tipo | Significado |
|---|---|
| `PERSIST` | Al guardar el evento, guarda también los ponentes nuevos |
| `MERGE` | Al actualizar el evento, actualiza también los ponentes |
| `REMOVE` | ☠️ Al borrar el evento, borra los ponentes. **Jamás en un `@ManyToMany`** |
| `ALL` | Todos los anteriores. Peligroso en `@ManyToMany` por el `REMOVE` |

En este proyecto está bien elegido: `PERSIST` y `MERGE`, **sin `REMOVE`**. Borrar un evento no debe
borrar a John Doe de la base de datos. 👍

> **Doctrine:** `cascade: ['persist', 'merge']` — mismo concepto, misma trampa con `remove`.

---

## 5. LAZY vs EAGER: la decisión más importante de JPA

```java
@ManyToOne(fetch = FetchType.LAZY)     // NO cargues la categoría hasta que la pida
@ManyToMany(fetch = FetchType.EAGER)   // Carga los roles SIEMPRE, junto con el usuario
```

### Qué hace LAZY por dentro

Cuando cargas un evento con `category` LAZY, Hibernate **no** ejecuta el `SELECT` de la categoría.
En su lugar, mete en el campo un **objeto proxy**: una subclase de `Category` generada al vuelo,
vacía salvo por el id.

```java
Event e = eventRepository.findById(1L).get();   // SELECT * FROM events WHERE id=1
                                                //   → e.category es un PROXY vacío
e.getCategory().getName();                      // ← AQUÍ salta el SELECT de categories
```

Cualquier método que toques del proxy dispara la consulta. **Excepto `getId()`**, porque el id ya lo
conoce (está en la clave foránea). Esto tiene una aplicación directa en el proyecto, en
[EventService.update()](../src/main/java/com/gestion/eventos/api/service/EventService.java#L88):

```java
if (!existingEvent.getCategory().getId().equals(requestDto.getCategoryId())) {
```

`getCategory().getId()` **no dispara ninguna consulta**. Solo si el id ha cambiado se busca la
categoría nueva. Es una optimización real, aunque probablemente involuntaria.

### Los valores por defecto (memorízalos)

| Relación | Fetch por defecto | ¿Por qué? |
|---|---|---|
| `@ManyToOne` | **EAGER** ⚠️ | Es un solo objeto, "parece barato". Pero encadenado sale caro. |
| `@OneToOne` | **EAGER** ⚠️ | Igual. |
| `@OneToMany` | LAZY ✅ | Es una colección; cargarla siempre sería absurdo. |
| `@ManyToMany` | LAZY ✅ | Igual. |

**Recomendación universal: pon `LAZY` explícitamente en todos los `@ManyToOne` y `@OneToOne`.** Este
proyecto lo hace en `Event.category`, y es lo correcto.

### La `LazyInitializationException`

El error más famoso de Hibernate:

```
org.hibernate.LazyInitializationException: could not initialize proxy - no Session
```

Ocurre cuando intentas cargar una relación LAZY **fuera de la transacción**, cuando la sesión de
Hibernate ya está cerrada.

```java
// ❌ Esto explotaría:
public Event dameEvento(Long id) {        // sin @Transactional
    return repo.findById(id).get();       // sesión abierta y cerrada aquí
}
// más tarde, en el controlador:
evento.getSpeakers().size();              // 💥 LazyInitializationException
```

**Cómo lo evita este proyecto** — y esto es una decisión de arquitectura muy importante que conviene
que veas: **la conversión a DTO se hace DENTRO del servicio, dentro de la transacción.**

```java
// EventService.findAll() — EventService.java:35-51
@Transactional(readOnly = true)                       // ← transacción abierta
public Page<EventResponseDto> findAll(String name, Pageable pageable) {
    Page<Event> eventsPage = eventRepository.findAll(pageable);

    List<EventResponseDto> dtos = eventsPage.getContent().stream()
            .map(eventMapper::toResponseDto)          // ← aquí se tocan los LAZY: OK, hay sesión
            .toList();

    return new PageImpl<>(dtos, pageable, eventsPage.getTotalElements());
}                                                     // ← transacción cerrada, pero ya son DTOs
```

Cuando el controlador recibe el `Page<EventResponseDto>`, ya son objetos planos sin proxies. **Nada
puede explotar.** Este es el patrón correcto y está bien aplicado.

*(Nota: en `EventController.getEventById` sí se devuelve una entidad `Event` al controlador y se
mapea allí. Funciona porque Spring Boot activa por defecto `spring.jpa.open-in-view=true`, que
mantiene la sesión abierta durante toda la petición. Ese ajuste es cómodo pero controvertido —
consulta la observación nº 5 del [doc 12](12-observaciones-y-glosario.md).)*

### El problema N+1

Con LAZY, esto pasa sin que te des cuenta:

```java
List<Event> events = repo.findAll();          // 1 consulta: SELECT * FROM events
for (Event e : events) {
    e.getCategory().getName();                // N consultas, una por evento
}
// Total: 1 + N consultas
```

En la página de 10 eventos de este proyecto, `findAll` genera:
- 1 consulta de eventos
- hasta 10 consultas de categorías (LAZY)
- hasta 10 consultas de ponentes (LAZY, `@ManyToMany`)

**≈ 21 consultas para devolver 10 eventos.** Arranca la app con `show-sql=true` y míralo tú misma
en la consola: es el mejor ejercicio para entender el N+1.

**La solución** es `@EntityGraph` o un `JOIN FETCH`, que cargan todo en una sola consulta:

```java
public interface EventRepository extends JpaRepository<Event, Long> {

    @EntityGraph(attributePaths = {"category", "speakers"})
    Page<Event> findAll(Pageable pageable);      // ahora carga todo de una vez
}
```

> **Doctrine tiene exactamente el mismo problema y la misma solución** (`->leftJoin('e.category')
> ->addSelect('c')`). Si has sufrido el N+1 en Symfony, es el mismo bicho.

Se detalla como mejora concreta en el [doc 12](12-observaciones-y-glosario.md).

---

## 6. ⚠️ Lombok en entidades JPA: la trampa de `@Data`

Todas las entidades de este proyecto usan `@Data`. **Esto es peligroso en entidades JPA**, y el
proyecto ya aplica la mitigación correcta, pero conviene que sepas por qué existe.

### El problema 1: `equals()` y `hashCode()`

`@Data` genera un `equals()` que compara **todos los campos**. En una entidad con relaciones:

```java
// Si Event.equals() comparara 'speakers':
event1.equals(event2)
   → compara speakers          → tiene que CARGAR la colección LAZY   → consulta a BD
   → speaker.equals(...)       → compara sus 'events'                 → más consultas
   → 💥 recursión infinita entre Event y Speaker
```

**La mitigación aplicada:** `@EqualsAndHashCode.Exclude` en todas las colecciones bidireccionales:

```java
@ManyToMany(...)
@ToString.Exclude
@EqualsAndHashCode.Exclude
private Set<Speaker> speakers = new HashSet<>();
```

Esto está bien hecho en `Event.speakers`, `Event.attendedUsers`, `Speaker.events` y
`User.attendedEvents`. 👍

**Lo que aún queda expuesto:** `Event.category` **no** está excluido. `Event.equals()` compara la
categoría, y como es LAZY, comparar dos eventos puede disparar consultas a la base de datos.

### El problema 2: `toString()` y la recursión infinita

Sin `@ToString.Exclude`, hacer `System.out.println(event)` daría:

```
Event.toString() → imprime speakers → Speaker.toString() → imprime events
                → Event.toString()  → imprime speakers → ... 💥 StackOverflowError
```

También está bien mitigado con `@ToString.Exclude`.

### El problema 3 (el sutil): `hashCode` y los `HashSet`

Este es el más traicionero:

```java
Set<Speaker> speakers = new HashSet<>();
Speaker s = new Speaker();          // id = null todavía
speakers.add(s);                    // se guarda en el bucket del hashCode con id=null
repository.save(s);                 // ahora id = 7  → ¡el hashCode CAMBIA!
speakers.contains(s);               // ❌ false — busca en el bucket equivocado
```

La solución canónica en entidades JPA es escribir `equals`/`hashCode` a mano, basados solo en el id
y con un `hashCode` constante:

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Event other)) return false;
    return id != null && id.equals(other.getId());
}

@Override
public int hashCode() {
    return getClass().hashCode();   // constante: no cambia al asignarse el id
}
```

**¿Le afecta esto al proyecto ahora mismo?** No, porque en
[EventService.update()](../src/main/java/com/gestion/eventos/api/service/EventService.java#L94-L97)
los `Speaker` que se comparan con `.contains()` vienen todos de la base de datos y ya tienen id
asignado. Funciona. Pero es un campo de minas si algún día se manejan entidades nuevas sin persistir.

### La receta segura para entidades JPA

```java
@Getter @Setter                                    // en lugar de @Data
@NoArgsConstructor
@Entity
public class Event {
    // + equals/hashCode escritos a mano basados en el id
    // + @ToString.Exclude en las colecciones si usas @ToString
}
```

> **En Doctrine no tienes este problema** porque PHP compara objetos por identidad (`===`) o campo a
> campo (`==`), y no hay un `HashSet` que dependa de un `hashCode`. Es un problema específico de
> Java.

---

## 7. `User`, `Role` y una observación de seguridad

[User.java](../src/main/java/com/gestion/eventos/api/domain/User.java) tiene:

```java
private String name;
private String username;
private String email;
private String password;
```

**Sin `@Column`.** Eso significa: todos son `VARCHAR(255)` y **todos aceptan `NULL`**. Comparado con
`Category`, que sí valida a nivel de base de datos, aquí no hay red de seguridad: si un bug dejara
pasar un usuario sin `username`, la BD lo aceptaría.

Sería mejor:
```java
@Column(nullable = false, unique = true, length = 50)
private String username;

@Column(nullable = false, unique = true)
private String email;

@Column(nullable = false)
private String password;
```

El `unique` es especialmente relevante: ahora mismo la unicidad de `username` y `email` **solo** se
comprueba en [AuthController.registerUser()](../src/main/java/com/gestion/eventos/api/security/controller/AuthController.java#L53-L59)
con `existsByUsername`. Si dos peticiones de registro llegan a la vez con el mismo usuario, ambas
pueden pasar la comprobación y crear dos usuarios duplicados (una *race condition*). La restricción
`UNIQUE` en la base de datos es lo que lo impide de verdad.

Y el campo `password`: guarda el hash BCrypt, nunca la contraseña. Se cifra en `AuthController`
(`passwordEncoder.encode(...)`) y en `DataLoader`. Correcto. Un BCrypt ocupa 60 caracteres, así que
`VARCHAR(255)` va sobrado.

---

## 8. Resumen del capítulo

- **JPA** es la especificación, **Hibernate** la implementación, **Spring Data JPA** la capa cómoda
  encima.
- `@Entity` + `@Table` + `@Id` + `@GeneratedValue` + `@Column` = una tabla.
- En un `@ManyToMany`, **el lado propietario es el que tiene `@JoinTable`**; el que tiene
  `mappedBy` se ignora al guardar. Por eso existen los métodos `addSpeaker`/`removeSpeaker`, que
  están bien implementados aquí.
- `LAZY` es lo que quieres casi siempre; ponlo explícito en `@ManyToOne`. `User.roles` es EAGER a
  propósito y con buen criterio, porque la autenticación los necesita siempre.
- Cuidado con el **N+1**: `EventService.findAll()` lo tiene, y se arregla con `@EntityGraph`.
- **`@Data` en entidades JPA es una trampa**; el proyecto la mitiga con `@ToString.Exclude` y
  `@EqualsAndHashCode.Exclude`, pero lo ideal es `@Getter @Setter` + `equals`/`hashCode` a mano
  basados en el id.
- Convertir a DTO **dentro** de la transacción evita la `LazyInitializationException`. El proyecto
  lo hace bien en `findAll`.

---

← [04 — Por qué tantas interfaces](04-por-que-interfaces.md) | **Siguiente:** [06 — Repositorios: Spring Data JPA](06-repositorios-spring-data.md)

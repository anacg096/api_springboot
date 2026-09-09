# 04 — Por qué tantas interfaces

> **Aquí se responde tu pregunta sobre las interfaces.**
> En este proyecto hay **13 interfaces**. Algunas parecen redundantes, otras están vacías, y otras
> no tienen ninguna clase que las implemente… y aun así funcionan. Vamos a ver por qué.

---

## 1. Primero: ¿qué es una interfaz en Java?

Un **contrato**: una lista de métodos **sin implementación**. Dice *qué* se puede hacer, no *cómo*.

```java
public interface CategoryService {
    List<Category> findAll();
    Category findById(Long id);
    Category save(Category category);
    Category update(Long id, Category category);
    void deleteById(Long id);
}
```

Ninguno de esos métodos tiene cuerpo. Una clase que la implemente **está obligada** a escribirlos
todos, o no compila:

```java
public class CategoryServiceImpl implements CategoryService {
    @Override public List<Category> findAll() { ... }
    @Override public Category findById(Long id) { ... }
    // etc.
}
```

`@Override` no es obligatorio, pero es buena práctica: le pide al compilador que verifique que
realmente estás sobrescribiendo algo del contrato. Si te equivocas en el nombre, te avisa.

> **En PHP es idéntico.** `interface CategoryServiceInterface` + `class CategoryService implements
> CategoryServiceInterface`. Symfony lo hace constantemente (`EntityManagerInterface`,
> `LoggerInterface`, `UserInterface`...). O sea que este concepto ya lo tienes.

---

## 2. Las interfaces de este proyecto, clasificadas

Hay **cuatro razones distintas** para tener interfaces aquí, y son muy diferentes entre sí:

| Grupo | Ejemplos | ¿Quién la implementa? | Razón |
|---|---|---|---|
| **A. Servicios** | `IEventService`, `CategoryService`, `SpeakerService` | **Tú**: `EventService`, `CategoryServiceImpl`, `SpeakerServiceImpl` | Diseño: desacoplar contrato de implementación |
| **B. Repositorios** | `EventRepository`, `CategoryRepository`, `UserRepository`, `RoleRepository`, `SpeakerRepository` | **Spring Data, en tiempo de ejecución** | Spring genera la implementación entera |
| **C. Mappers** | `EventMapper`, `CategoryMapper`, `SpeakerMapper`, `RoleMapper` | **MapStruct, al compilar** | Genera el código de conversión |
| **D. Contratos de framework** | `UserDetailsService`, `CommandLineRunner`, `AuthenticationEntryPoint` | **Tú**, para engancharte a Spring | Es la forma de extender el framework |

Vamos uno por uno.

---

## 3. Grupo A — Las interfaces de servicio: `IEventService` + `EventService`

Este es el grupo que probablemente te parece "redundante", y con razón: hay **una interfaz y una
sola implementación**. ¿Para qué?

```java
// IEventService.java  ← el contrato
public interface IEventService {
    Page<EventResponseDto> findAll(String name, Pageable pageable);
    Event save(EventRequestDto event);
    Event findById(Long id);
    Event update(Long id, EventRequestDto requestDto);
    void deleteById(Long id);
}

// EventService.java  ← la implementación
@Service
@RequiredArgsConstructor
public class EventService implements IEventService { ... }

// EventController.java  ← el consumidor
private final IEventService eventService;   // ← depende de la INTERFAZ, no de la clase
```

### Razón 1 (histórica, la que más se cita): los proxies JDK

Esta es la razón técnica original, y sigue siendo importante entenderla aunque hoy sea menos
determinante.

Cuando Spring necesita añadir comportamiento a un bean (transacciones, seguridad, caché), no
modifica tu clase: la **envuelve en un proxy**. Y hay dos mecanismos:

| Mecanismo | Requisito | Qué hace |
|---|---|---|
| **JDK Dynamic Proxy** | La clase **debe implementar una interfaz** | Crea un objeto nuevo que implementa esa misma interfaz |
| **CGLIB** | No requiere interfaz | Genera en memoria una **subclase** de tu clase |

Históricamente (Spring 4 y anteriores), si tu clase no implementaba interfaz, tenías que activar
CGLIB a mano. Por eso se generalizó el patrón `XService` + `XServiceImpl`.

**Hoy en día Spring Boot usa CGLIB por defecto**, así que técnicamente ya no hace falta la interfaz.
Pero el patrón se quedó, y tiene otras razones válidas.

### Razón 2 (la que de verdad importa hoy): el Principio de Inversión de Dependencias

> *"Depende de abstracciones, no de implementaciones concretas."*

`EventController` no sabe **nada** de `EventService`. Solo conoce el contrato `IEventService`. Eso
significa que:

- Puedes crear `EventServiceConCache implements IEventService` y cambiar cuál se inyecta sin tocar
  el controlador.
- En un test puedes inyectar un `IEventService` falso.
- Puedes leer el contrato completo de la capa de negocio en un archivo de 7 líneas, sin bucear en
  120 líneas de implementación.

### Razón 3: los tests

```java
@WebMvcTest(EventController.class)
class EventControllerTest {
    @MockitoBean private IEventService eventService;   // un doble, no el servicio real

    @Test void devuelve404() {
        when(eventService.findById(99L)).thenThrow(new ResourceNotFoundException("..."));
        // testeas SOLO el controlador, sin base de datos
    }
}
```

Sin interfaz también se puede mockear (Mockito usa CGLIB), pero con interfaz es más limpio y rápido.

### ¿Merece la pena siempre?

Opinión honesta y bastante consensuada en la comunidad: **no**. Crear una interfaz por cada servicio
"por si acaso" es a menudo ceremonia innecesaria; hay un término para eso, *"single implementation
interface"*, y hay quien lo considera un antipatrón. Muchos equipos modernos escriben directamente
`@Service public class EventService` sin interfaz, y añaden la interfaz **el día que aparece la
segunda implementación** (que en el 90% de los casos no llega).

Dicho esto: **para aprender está muy bien tenerla**, porque te obliga a pensar en el contrato antes
que en el código, y porque es el patrón que te vas a encontrar en el 80% de los proyectos Spring
reales, incluidos los de empresa. Aquí el curso te lo enseña por eso.

### ⚠️ Una inconsistencia en el proyecto

Fíjate en los nombres:

| Interfaz | Implementación | Convención |
|---|---|---|
| `IEventService` | `EventService` | prefijo `I` (estilo C#/.NET) |
| `CategoryService` | `CategoryServiceImpl` | sufijo `Impl` (estilo Java clásico) |
| `SpeakerService` | `SpeakerServiceImpl` | sufijo `Impl` |

Se están mezclando dos convenciones. Además, `EventService` **es una implementación** pero su nombre
parece el de una interfaz, lo que confunde al leer. En Java la convención dominante es la segunda
(`CategoryService` / `CategoryServiceImpl`); el prefijo `I` viene del mundo .NET. No es un error
funcional, pero unificarlo a `EventService`/`EventServiceImpl` haría el código más legible.

---

## 4. Grupo B — Los repositorios: interfaces que NADIE implementa

Este es el caso que de verdad rompe la cabeza al venir de Symfony.

```java
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);
    boolean existsByName(String name);
}
```

**Eso es el archivo entero.** No hay `CategoryRepositoryImpl` en ningún sitio. Y sin embargo:

```java
categoryRepository.findByName("Taller");   // ← esto funciona y ejecuta un SELECT real
```

### ¿Cómo?

Durante el arranque, Spring Data JPA:

1. Escanea el proyecto buscando interfaces que extiendan `Repository` (o `JpaRepository`, que
   hereda de ella).
2. Por cada una, **genera en memoria un objeto proxy** que implementa esa interfaz.
3. Ese proxy delega en una clase real de Spring, `SimpleJpaRepository`, para los métodos heredados
   (`findAll`, `save`, `findById`, `deleteById`, `count`, `existsById`…).
4. Para los métodos que tú declaras (`findByName`, `existsByEmail`,
   `findByNameContainingIgnoreCase`), **analiza el nombre del método**, lo traduce a una consulta
   JPQL y la ejecuta.
5. Registra ese proxy como bean.

```
   Tú escribes:              Spring crea en memoria:            Que por dentro usa:
┌──────────────────┐      ┌───────────────────────────┐      ┌──────────────────────┐
│ interface        │ ───► │ $Proxy93                  │ ───► │ SimpleJpaRepository  │
│ CategoryRepo     │      │ implements CategoryRepo   │      │   + EntityManager    │
│  (0 líneas impl) │      │ (generado en runtime)     │      │   + generador JPQL   │
└──────────────────┘      └───────────────────────────┘      └──────────────────────┘
```

**Aquí la interfaz no es una preferencia de diseño: es un requisito técnico.** Un proxy JDK solo
puede implementar interfaces. La interfaz *es* la especificación desde la que se genera el código.

> **Comparación directa con Symfony:**
> ```php
> // Symfony: TÚ escribes la clase y el método
> class CategoryRepository extends ServiceEntityRepository {
>     public function findByName(string $name): ?Category {
>         return $this->createQueryBuilder('c')
>             ->andWhere('c.name = :n')->setParameter('n', $name)
>             ->getQuery()->getOneOrNullResult();
>     }
> }
> ```
> ```java
> // Spring: DECLARAS el método y ya está
> Optional<Category> findByName(String name);
> ```
> Spring hace más magia; Doctrine te da más control explícito. Ambas escuelas son defendibles.

Todo el detalle de cómo se derivan las consultas está en el [doc 06](06-repositorios-spring-data.md).

---

## 5. Grupo C — Los mappers: interfaces implementadas al compilar

```java
@Mapper(componentModel = "spring")
public interface CategoryMapper {
    CategoryDto toDto(Category category);
    Category toEntity(CategoryDto categoryDto);
}
```

Otra interfaz sin implementación visible. Pero aquí el mecanismo es **distinto** al de los
repositorios, y la diferencia es conceptualmente importante:

| | Repositorios | Mappers |
|---|---|---|
| Quién implementa | Spring Data | MapStruct |
| **Cuándo** | En **tiempo de ejecución** (al arrancar) | En **tiempo de compilación** |
| Dónde está el código | En memoria, invisible | En un `.java` real: `target/generated-sources/annotations/.../CategoryMapperImpl.java` |
| ¿Puedes leerlo? | No fácilmente | **Sí, ábrelo. Es Java normal.** |

`componentModel = "spring"` es lo que hace que la clase generada lleve `@Component`, y por tanto sea
un bean inyectable. Sin ese atributo, MapStruct generaría la clase pero no sería un bean y no
podrías inyectarla.

Como se genera al compilar, **si el mapeo es imposible el proyecto no compila**. Eso es una gran
ventaja: los errores de mapeo se detectan antes de ejecutar nada.

Detalles en el [doc 08](08-dtos-mapstruct-validacion.md).

### El caso especial de `UserMapper`: por qué es `abstract class` y no `interface`

```java
@Mapper(componentModel = "spring")
public abstract class UserMapper {          // ← CLASE ABSTRACTA, no interfaz

    @Autowired
    protected RoleRepository roleRepository;   // ← necesita una dependencia

    public abstract User registerDtoToUser(RegisterDto registerDto);

    @Named("mapRoleStringsToRoles")
    public Set<Role> mapRoleStringsToRoles(Set<String> roleNames) {
        // ← ESTE método SÍ tiene cuerpo: convierte "ROLE_ADMIN" en la entidad Role
        //   buscándola en la base de datos
    }
}
```

**El motivo:** convertir un `Set<String>` de nombres de rol en un `Set<Role>` de entidades requiere
**ir a la base de datos**. MapStruct no puede generar eso solo; hay que escribirlo a mano. Y para
escribirlo hace falta inyectar `RoleRepository`.

Una interfaz no puede tener campos ni dependencias inyectadas. Una **clase abstracta** sí. Por eso:

- Los métodos `abstract` → los implementa MapStruct.
- Los métodos con cuerpo → los escribes tú, y MapStruct los **llama** desde el código que genera.
- `@Named` + `qualifiedByName` es cómo se le dice a MapStruct "para este campo, usa este método mío".

Es el ejemplo perfecto de una regla útil: **usa `interface` para mappers puros; usa `abstract class`
cuando el mapper necesite dependencias o lógica propia.**

---

## 6. Grupo D — Interfaces del framework: enganchar tu código a Spring

Aquí las interfaces vienen de Spring, y tú las implementas para **participar** en su funcionamiento.

### `UserDetailsService` — le enseñas a Spring Security dónde están tus usuarios

```java
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado..."));
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(), user.getPassword(), mapRolesToAuthorities(user.getRoles()));
    }
}
```

Spring Security **no sabe** cómo son tus usuarios ni dónde están. Define el contrato
`UserDetailsService` con un único método, y dice: "implementa esto y yo lo llamaré cuando necesite
autenticar a alguien".

Es un **punto de extensión**. Tú das el "cómo"; el framework pone el "cuándo".

> **Equivalente Symfony:** el `UserProvider` (`providers: entity:` en `security.yaml`), y
> `UserInterface` en tu entidad.

Fíjate en el detalle: el método **no devuelve tu `User`**, sino un
`org.springframework.security.core.userdetails.User`, que es la clase de Spring Security. Tu entidad
`User` es de tu dominio y no debería contaminarse con las interfaces del framework de seguridad.
Aquí se traduce una a la otra. (La alternativa, muy común, es hacer que tu `User` implemente
`UserDetails` directamente — más rápido de escribir, más acoplado.)

### `CommandLineRunner` — "ejecútame al terminar de arrancar"

```java
@Component
public class DataLoader implements CommandLineRunner {
    @Override
    public void run(String... args) { /* cargar datos */ }
}
```

Un solo método. Spring Boot busca todos los beans que la implementen y los ejecuta tras el arranque.
No hay que registrarlo en ninguna parte: **implementar la interfaz *es* el registro**.

### `AuthenticationEntryPoint` — "qué hago cuando alguien no está autenticado"

```java
@Component
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, authException.getMessage());
    }
}
```

Sin esto, Spring Security intentaría redirigir a un formulario de login HTML — inútil en una API
REST. Implementando esta interfaz cambias ese comportamiento por "devuelve un 401 seco".

Se conecta con la cadena en `SecurityConfig`:
```java
.exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthEntryPoint))
```

---

## 7. LOS PROXIES: el mecanismo que hay detrás de todo

Esto merece su propia sección porque **explica `@Transactional`, `@PreAuthorize`, `@Cacheable` y
la mitad de los comportamientos "mágicos" de Spring**.

### El problema

Quieres que `CategoryServiceImpl.save()` se ejecute dentro de una transacción. Podrías escribirlo:

```java
public Category save(Category category) {
    EntityTransaction tx = em.getTransaction();
    tx.begin();
    try {
        Category saved = repo.save(category);
        tx.commit();
        return saved;
    } catch (Exception e) {
        tx.rollback();
        throw e;
    }
}
```

Ese código sería **idéntico** en los 20 métodos que modifican datos. Es ruido que oculta la lógica
real. La transacción es una **preocupación transversal** (*cross-cutting concern*): afecta a todo el
código pero no es de lo que trata ningún método.

### La solución: no toques el método, envuélvelo

```java
@Transactional
public Category save(Category category) {
    return categoryRepository.save(category);   // ← solo la lógica de verdad
}
```

Spring detecta la anotación y, **al crear el bean, no te da tu objeto: te da un proxy**.

```
   El controlador cree que tiene esto:      Pero en realidad tiene esto:

   ┌─────────────────────┐                  ┌──────────────────────────────┐
   │ CategoryServiceImpl │                  │ CategoryServiceImpl$$SpringCGLIB │
   └─────────────────────┘                  │                              │
                                            │  save(c) {                   │
                                            │    tx.begin();          ←┐   │
                                            │    try {                 │   │
                                            │      r = real.save(c);   │ EL PROXY
                                            │      tx.commit();        │ AÑADE ESTO
                                            │    } catch {             │   │
                                            │      tx.rollback();      │   │
                                            │      throw;             ←┘   │
                                            │    }                         │
                                            │    return r;                 │
                                            │  }                           │
                                            └──────────┬───────────────────┘
                                                       │ delega en
                                                       ▼
                                            ┌──────────────────────────────┐
                                            │ CategoryServiceImpl (el tuyo)│
                                            │  save(c) { return repo... }  │
                                            └──────────────────────────────┘
```

Esto se llama **AOP** (*Aspect-Oriented Programming*, programación orientada a aspectos).

### Los dos tipos de proxy

**JDK Dynamic Proxy** — cuando la clase implementa una interfaz:
```
El proxy implementa CategoryService y guarda dentro una referencia a CategoryServiceImpl.
Solo puede interceptar los métodos DECLARADOS EN LA INTERFAZ.
```

**CGLIB** — cuando no hay interfaz (y es el **predeterminado en Spring Boot**):
```
El proxy es una SUBCLASE generada en memoria: class CategoryServiceImpl$$SpringCGLIB extends CategoryServiceImpl
Sobrescribe los métodos y llama a super.metodo().
Por eso las clases y métodos con @Transactional NO pueden ser 'final': no se podrían sobrescribir.
```

> **¿Existe algo así en PHP?** Symfony genera proxies para servicios *lazy* y Doctrine para
> entidades *lazy*, pero PHP no tiene un sistema AOP generalizado en el framework. En Symfony, un
> comportamiento transversal se hace con **decoradores** de servicio o **event subscribers**, que es
> más explícito y menos mágico.

### ⚠️ La consecuencia práctica más importante: la auto-invocación no funciona

Como el comportamiento vive en el **proxy**, solo se aplica cuando la llamada **entra desde fuera**.

```java
@Service
public class EventService {

    public void metodoA() {
        this.metodoB();      // ⚠️ this = el objeto REAL, no el proxy
    }                        //    → @Transactional de metodoB SE IGNORA

    @Transactional
    public void metodoB() { ... }
}
```

Este es **el error nº 1 de los principiantes en Spring**, y explica el 90% de los "mi
`@Transactional` no funciona" de StackOverflow.

**En este proyecto hay un caso que conviene mirar**, en
[EventService.java:123](../src/main/java/com/gestion/eventos/api/service/EventService.java#L123):

```java
@Override
@Transactional
public void deleteById(Long id) {
    Event eventToDelete = this.findById(id);   // ← auto-invocación
    eventRepository.delete(eventToDelete);
}
```

`this.findById(id)` **salta el proxy**, así que el `@Transactional(readOnly = true)` de `findById`
se ignora. **Aquí no causa ningún problema** —de hecho es lo que quieres, porque ya estamos dentro
de la transacción de escritura de `deleteById`, y si el proxy sí se aplicara, `Propagation.REQUIRED`
haría exactamente lo mismo: unirse a la transacción existente—. Pero es importante que sepas
*por qué* funciona, y que en otros casos (por ejemplo si `findById` llevara
`Propagation.REQUIRES_NEW`) el resultado sería silenciosamente incorrecto.

**Otras consecuencias de los proxies que debes conocer:**

| Regla | Motivo |
|---|---|
| `@Transactional` solo funciona en métodos `public` | El proxy no puede interceptar `private`/`protected` |
| La clase no puede ser `final` | CGLIB necesita heredar de ella |
| El método no puede ser `final` ni `static` | CGLIB necesita sobrescribirlo |
| Llamar a `this.metodo()` salta el aspecto | El `this` es el objeto real, no el proxy |

Todo esto se desarrolla en el [doc 07](07-transaccional-a-fondo.md).

---

## 8. Resumen del capítulo

Las 13 interfaces de este proyecto existen por **cuatro razones muy distintas**:

1. **Servicios** (`IEventService`, `CategoryService`, `SpeakerService`) → **decisión de diseño**.
   Desacoplan al consumidor de la implementación y facilitan los tests. Con una sola
   implementación es discutible que merezcan la pena, pero es el patrón dominante en la industria.
2. **Repositorios** (`EventRepository`, etc.) → **requisito técnico**. Spring Data **genera** la
   implementación en tiempo de ejecución, y solo puede hacerlo sobre una interfaz.
3. **Mappers** (`EventMapper`, etc.) → **requisito técnico**. MapStruct genera la implementación
   **al compilar**, en un `.java` que puedes abrir y leer.
4. **Contratos del framework** (`UserDetailsService`, `CommandLineRunner`,
   `AuthenticationEntryPoint`) → **puntos de extensión**. Implementarlas es cómo enchufas tu código
   dentro de Spring.

Y por debajo de casi todo está el mecanismo de los **proxies**: Spring envuelve tus beans para
añadirles transacciones y seguridad sin que tu código se entere. Recuerda las dos consecuencias:
métodos `public`, y `this.metodo()` no pasa por el proxy.

---

← [03 — Beans e Inyección](03-contenedor-beans-inyeccion.md) | **Siguiente:** [05 — La capa de dominio: entidades JPA](05-dominio-entidades-jpa.md)

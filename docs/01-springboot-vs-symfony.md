# 01 — Spring Boot vs Symfony: el modelo mental

> Antes de mirar una sola anotación, necesitas entender **la diferencia de plataforma**.
> Si no, Spring te va a parecer "Symfony pero con la sintaxis rara", y no lo es.
> El 80% de las cosas que te chocan de Spring vienen de esta diferencia.

---

## 1. La diferencia que lo explica todo: PHP muere, Java vive

### Cómo funciona una petición en Symfony (PHP)

```
Petición HTTP  →  Apache/Nginx  →  PHP-FPM arranca un proceso
                                    │
                                    ├─ carga el autoloader de Composer
                                    ├─ construye el Kernel
                                    ├─ construye el CONTENEDOR DE SERVICIOS ENTERO
                                    ├─ crea la conexión a la base de datos
                                    ├─ ejecuta tu controlador
                                    ├─ devuelve la respuesta
                                    └─ 💀 SE MUERE TODO. La memoria se libera.
```

Cada petición en PHP es un **mundo nuevo**. Esto se llama arquitectura *share-nothing*.
Symfony hace trampas muy inteligentes (cachea el contenedor compilado en `var/cache/`) para que
esa reconstrucción sea barata, pero conceptualmente: **nada sobrevive entre peticiones**.

### Cómo funciona una petición en Spring Boot (Java)

```
ARRANQUE (una sola vez, tarda 2-5 segundos):
    java -jar api.jar
       ├─ arranca la JVM
       ├─ escanea tus clases buscando anotaciones
       ├─ construye el CONTENEDOR (ApplicationContext) → crea TODOS los objetos
       ├─ abre un POOL de conexiones a la base de datos (ej. 10 conexiones abiertas)
       ├─ arranca un servidor web Tomcat EMBEBIDO dentro del propio proceso
       └─ se queda escuchando en el puerto 8080... para siempre

LUEGO, CADA PETICIÓN (milisegundos):
    Petición HTTP → Tomcat coge un HILO (thread) libre del pool
                  → busca el controlador (que YA EXISTE en memoria desde el arranque)
                  → lo ejecuta
                  → devuelve la respuesta
                  → el hilo vuelve al pool. Los objetos SIGUEN VIVOS.
```

**El proceso Java no muere.** Vive durante días o meses. Los objetos que Spring creó al arrancar
se reutilizan en las miles de peticiones siguientes.

### Consecuencias prácticas (esto es lo importante)

| Consecuencia | Explicación |
|---|---|
| **Los objetos de Spring NO pueden guardar estado del usuario** | Si `EventService` guardara `private User usuarioActual`, dos peticiones simultáneas de usuarios distintos se pisarían. Por eso todos los servicios llevan solo dependencias `final` y ningún dato mutable. En Symfony esto también aplica, pero el daño estaría limitado a una petición; en Spring el objeto es **compartido por todos los usuarios a la vez**. |
| **Arrancar es lento, ejecutar es rapidísimo** | Symfony tarda ~20ms por petición porque reconstruye cosas. Spring tarda 3 segundos al arrancar y luego ~1ms por petición. |
| **La concurrencia es real** | Java atiende varias peticiones **a la vez, en el mismo proceso**, en hilos distintos. En PHP cada petición es un proceso aislado. Por eso Spring habla tanto de "thread-safe" y por eso existe `ThreadLocal` (el `SecurityContextHolder` que verás en el doc 10 es exactamente eso). |
| **No hay `var/cache/` que borrar** | El "caché del contenedor" de Symfony no existe: Spring construye el contenedor en memoria al arrancar y ahí se queda. Si cambias código, reinicias la app. |
| **El servidor web va dentro de tu aplicación** | No configuras Apache ni Nginx apuntando a un `public/index.php`. Tu aplicación **es** el servidor. `spring-boot-starter-webmvc` mete un Tomcat dentro del `.jar`. |

> **Idea clave para retener:** en Symfony piensas "cada petición construye lo que necesita".
> En Spring piensas "todo se construye una vez al arrancar y luego solo se usa".

---

## 2. Tabla de equivalencias Symfony → Spring Boot

Esta tabla es tu chuleta. Vuelve a ella siempre que te pierdas.

### Proyecto y dependencias

| Symfony / PHP | Spring Boot / Java | Notas |
|---|---|---|
| `composer.json` | `pom.xml` (Maven) | Ambos declaran dependencias. Maven además compila, testea y empaqueta. |
| `composer install` | `mvnw install` | Maven descarga a `~/.m2/repository` (equivalente a `vendor/`, pero **global** en tu máquina, no por proyecto). |
| `vendor/` | `~/.m2/repository/` + `target/` | `target/` es donde va lo compilado de tu proyecto. |
| Packagist | Maven Central | El repositorio público de librerías. |
| Un *bundle* (ej. `DoctrineBundle`) | Un *starter* (ej. `spring-boot-starter-data-jpa`) | Un paquete que trae dependencias **y configuración automática**. |
| `bin/console` | `mvnw` + la propia app | No hay una consola tan rica; los comandos van con `spring-boot:run`, `test`, etc. |
| `.env` / `%env(DATABASE_URL)%` | `application.properties` + `@Value("${...}")` | Ver doc 02. |
| PSR-4 autoloading | Paquetes Java (`package com.gestion...`) | En Java el paquete **debe** coincidir con la ruta de carpetas. Es obligatorio, no una convención. |

### Contenedor de servicios

| Symfony | Spring Boot | Notas |
|---|---|---|
| Un *servicio* | Un *bean* | Mismo concepto: un objeto que gestiona el framework. |
| `config/services.yaml` | Anotaciones + `@Configuration` | Spring casi no usa ficheros de configuración de servicios. |
| `autowire: true` | Inyección por constructor (automática) | En Spring el autowiring es el comportamiento por defecto, siempre. |
| `autoconfigure: true` | `@ComponentScan` (implícito en `@SpringBootApplication`) | Spring escanea tu paquete base y registra lo que lleve `@Component` y derivadas. |
| `#[AsService]` / registro en `services.yaml` | `@Component`, `@Service`, `@Repository`, `@Controller` | Ver doc 03. |
| Una *factory* de servicio en `services.yaml` | Un método `@Bean` dentro de una clase `@Configuration` | **Esta es la respuesta a tu pregunta sobre `@Bean`.** Ver doc 03. |
| Servicios *lazy* con proxies (ghost objects) | Proxies AOP (CGLIB / JDK) | Spring usa proxies para MUCHO más que lazy: `@Transactional`, `@PreAuthorize`, `@Cacheable`... Ver doc 04. |
| Los servicios son *singleton* por defecto | Los beans son *singleton* por defecto | Igual… pero en Spring el singleton dura **toda la vida del proceso**, no una petición. |

### Controladores y rutas

| Symfony | Spring Boot |
|---|---|
| `#[Route('/api/v1/events', methods: ['GET'])]` | `@GetMapping` dentro de una clase con `@RequestMapping("/api/v1/events")` |
| `extends AbstractController` | Nada. Es una clase normal con `@RestController` |
| `#[Route('/{id}')]` + `int $id` | `@GetMapping("/{id}")` + `@PathVariable Long id` |
| `$request->query->get('name')` | `@RequestParam String name` |
| `$request->getContent()` + serializer | `@RequestBody EventRequestDto dto` (Jackson deserializa solo) |
| `return $this->json($data, 201)` | `return new ResponseEntity<>(dto, HttpStatus.CREATED)` |
| `#[MapEntity]` / ParamConverter | No existe equivalente automático: cargas la entidad tú en el servicio |
| Serialization Groups `#[Groups(['read'])]` | DTOs explícitos + MapStruct (ver doc 08) |

### Persistencia

| Symfony / Doctrine | Spring Boot / JPA-Hibernate | Notas |
|---|---|---|
| Doctrine ORM | JPA (la especificación) + Hibernate (la implementación) | JPA es un estándar; Hibernate es quien lo implementa. Doctrine se inspiró en Hibernate. |
| `#[ORM\Entity]` | `@Entity` | Casi idéntico. |
| `#[ORM\Column]` | `@Column` | |
| `#[ORM\ManyToOne]` | `@ManyToOne` | |
| `EntityManagerInterface` | `EntityManager` | El mismo concepto: el gestor del contexto de persistencia. |
| `$em->persist($x); $em->flush();` | `repository.save(x)` **o simplemente modificar la entidad** dentro de `@Transactional` | ⚠️ **Diferencia grande.** Ver doc 07. |
| `class EventRepository extends ServiceEntityRepository` | `interface EventRepository extends JpaRepository<Event, Long>` | ⚠️ En Spring **no escribes la clase**, solo la interfaz. Ver doc 06. |
| `$repo->findBy(['name' => $n])` | `findByName(String n)` — **derivado del nombre del método** | Spring parsea el nombre del método y genera el SQL. |
| DQL | JPQL | Prácticamente el mismo lenguaje. |
| `doctrine:migrations:migrate` | `spring.jpa.hibernate.ddl-auto=update` (o Flyway/Liquibase) | Este proyecto usa `ddl-auto`, que es cómodo para aprender pero **no se usa en producción**. |
| `DoctrineFixturesBundle` | Una clase que implemente `CommandLineRunner` | Aquí es [DataLoader.java](../src/main/java/com/gestion/eventos/api/data/DataLoader.java). |

### Validación

| Symfony | Spring Boot |
|---|---|
| `#[Assert\NotBlank]` | `@NotBlank` (Jakarta Bean Validation) |
| `#[Assert\Email]` | `@Email` |
| `#[Assert\Length(max: 100)]` | `@Size(max = 100)` |
| `$validator->validate($dto)` | `@Valid` en el parámetro del controlador (automático) |
| Componente Validator | `spring-boot-starter-validation` (Hibernate Validator) | Mismo estándar por debajo: *Bean Validation*. Symfony tiene el suyo propio, pero las anotaciones se parecen muchísimo. |

### Seguridad

| Symfony | Spring Boot |
|---|---|
| `config/packages/security.yaml` | La clase [SecurityConfig.java](../src/main/java/com/gestion/eventos/api/security/config/SecurityConfig.java) con `@Bean SecurityFilterChain` |
| `firewalls:` | `SecurityFilterChain` |
| `access_control:` | `.authorizeHttpRequests(auth -> auth.requestMatchers(...)...)` |
| `providers: entity: ...` | `UserDetailsService` (aquí, [UserDetailsServiceImpl](../src/main/java/com/gestion/eventos/api/security/service/UserDetailsServiceImpl.java)) |
| `UserInterface` | `UserDetails` |
| `password_hashers: auto` | `@Bean PasswordEncoder → new BCryptPasswordEncoder()` |
| `#[IsGranted('ROLE_ADMIN')]` | `@PreAuthorize("hasRole('ADMIN')")` |
| Un `Authenticator` custom | Un filtro que extiende `OncePerRequestFilter` |
| `LexikJWTAuthenticationBundle` | Aquí: la librería `jjwt` usada a mano en [JwtGenerator](../src/main/java/com/gestion/eventos/api/security/jwt/JwtGenerator.java) |
| `Security::getUser()` | `SecurityContextHolder.getContext().getAuthentication()` |

### Errores

| Symfony | Spring Boot |
|---|---|
| Un `EventSubscriber` en `kernel.exception` | Una clase con `@ControllerAdvice` |
| `NotFoundHttpException` | Aquí: `ResourceNotFoundException` propia + `@ExceptionHandler` |
| `$this->createNotFoundException()` | `throw new ResourceNotFoundException(...)` |

---

## 3. Cinco cosas de Java que te van a chocar viniendo de PHP

### 3.1 Todo está tipado, y en serio

PHP tiene tipos opcionales. Java **no compila** si los tipos no cuadran. Esto es incómodo al
principio y una bendición después: el 90% de los errores que en PHP descubres en runtime, en Java
te los dice el compilador.

```java
// Esto NO compila:
Long id = "5";           // String no es Long
List<Event> e = new ArrayList<Category>();   // tipos genéricos incompatibles
```

### 3.2 Los genéricos `<...>`

`List<Event>` significa "una lista **de eventos**". El `<Event>` es un *genérico*: le dice al
compilador qué hay dentro. En PHP escribirías `/** @var Event[] $events */` en un comentario; en
Java forma parte del tipo y se comprueba de verdad.

Verás genéricos por todas partes:
- `JpaRepository<Event, Long>` → "repositorio de Events cuyo id es un Long"
- `Optional<Category>` → "puede que haya una Category o puede que no"
- `ResponseEntity<EventResponseDto>` → "una respuesta HTTP cuyo cuerpo es un EventResponseDto"
- `Page<EventResponseDto>` → "una página de resultados de EventResponseDto"

### 3.3 `Optional<T>` en lugar de `null`

En PHP devuelves `null` y el que llama se acuerda (o no) de comprobarlo. En Java moderno se
devuelve `Optional<T>`: una caja que **puede** contener un valor.

```java
// CategoryServiceImpl.java:27
return categoryRepository.findById(id).orElseThrow(
        () -> new ResourceNotFoundException("Categoría no encontrada con id: " + id)
);
```

Se lee: "busca por id; si la caja viene vacía, lanza esta excepción; si viene llena, devuélveme lo
que hay dentro". El compilador te **obliga** a decidir qué pasa si no hay valor. En PHP:

```php
$category = $repo->find($id);
if (!$category) { throw new NotFoundHttpException(); }
return $category;
```

### 3.4 Las lambdas `->` y los method references `::`

```java
() -> new ResourceNotFoundException("...")      // una función sin argumentos
error -> errors.put(error.getField(), ...)      // una función con un argumento
categoryMapper::toDto                            // atajo para: x -> categoryMapper.toDto(x)
```

Es el equivalente a `fn() => ...` y `fn($x) => ...` de PHP. El `::` (*method reference*) es azúcar
sintáctico para una lambda que solo llama a un método.

### 3.5 Los Streams

```java
// CategoryController.java:30-33
categories.stream()
        .map(categoryMapper::toDto)
        .collect(Collectors.toList())
```

Equivalente PHP: `array_map(fn($c) => $mapper->toDto($c), $categories)`.

Los streams son cadenas de operaciones sobre colecciones. Los verás mucho:
- `.stream()` abre la cadena
- `.map(...)` transforma cada elemento
- `.filter(...)` descarta elementos
- `.forEach(...)` ejecuta algo por cada uno
- `.collect(Collectors.toList())` / `.toList()` / `.collect(Collectors.toSet())` cierra la cadena y
  devuelve una colección

---

## 4. Lombok: la magia que hace que las clases parezcan vacías

Cuando abras [Category.java](../src/main/java/com/gestion/eventos/api/domain/Category.java) verás
esto:

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Category {
    private Long id;
    private String name;
    private String description;
}
```

¿Y los getters? ¿Y los setters? No están escritos… porque **Lombok los genera al compilar**.

Java no tiene propiedades como PHP; el convenio es que un campo `private` se expone con
`getName()` / `setName()`. Escribir eso a mano es tedioso, así que Lombok lo genera:

| Anotación de Lombok | Qué genera |
|---|---|
| `@Getter` / `@Setter` | Los getters y setters |
| `@Data` | `@Getter` + `@Setter` + `toString()` + `equals()` + `hashCode()` + constructor de campos `final` |
| `@NoArgsConstructor` | `public Category() {}` — constructor vacío (JPA lo **necesita**) |
| `@AllArgsConstructor` | `public Category(Long id, String name, String description)` |
| `@RequiredArgsConstructor` | Un constructor con **solo los campos `final`**. Es el que usan todos los servicios y controladores de este proyecto para la inyección de dependencias. |
| `@ToString.Exclude` | Excluye ese campo del `toString()` generado |
| `@EqualsAndHashCode.Exclude` | Excluye ese campo del `equals()`/`hashCode()` generados |

> **Cómo verlo con tus propios ojos:** compila el proyecto (`.\mvnw.cmd compile`) y abre
> `target/classes/`. El `.class` compilado tiene todos esos métodos aunque tu `.java` no los tenga.

En Symfony no tienes nada equivalente porque PHP no lo necesita tanto (tiene `__get`, propiedades
públicas, `readonly promoted properties`...). Lombok es la respuesta de la comunidad Java a la
verbosidad del lenguaje.

⚠️ Lombok tiene una trampa seria cuando se usa `@Data` en entidades JPA con relaciones. Se explica
en el [doc 05, sección "Lombok en entidades"](05-dominio-entidades-jpa.md).

---

## 5. Resumen del capítulo

1. **Java vive, PHP muere.** El contenedor de Spring se construye una vez y los objetos se
   comparten entre peticiones concurrentes. Por eso todo es *stateless*.
2. Los conceptos de Symfony **existen todos** en Spring, con otro nombre y otra sintaxis:
   servicio→bean, `services.yaml`→anotaciones, Doctrine→JPA/Hibernate, `security.yaml`→
   `SecurityFilterChain`.
3. La mayor diferencia práctica en el día a día es **Doctrine vs JPA en el manejo de
   transacciones**: en Doctrine llamas a `flush()`; en JPA, dentro de `@Transactional`, los cambios
   se guardan solos. Eso es el doc 07.
4. La segunda mayor diferencia es **por qué hay tantas interfaces**: porque Spring genera
   implementaciones y proxies en tiempo de ejecución. Eso es el doc 04.

---

← [Índice](00-INDICE.md) | **Siguiente:** [02 — Estructura del proyecto y arranque](02-estructura-y-arranque.md)

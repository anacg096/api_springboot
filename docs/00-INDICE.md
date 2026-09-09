# Documentación del proyecto `api` — Gestión de Eventos (Spring Boot 4)

> Guía completa del proyecto, escrita para alguien que **viene de PHP/Symfony** y quiere entender
> Spring Boot de verdad: no solo "qué anotación pongo", sino **por qué** existe cada pieza,
> qué problema resuelve y qué hace Spring por debajo.

---

## Cómo leer esta documentación

Los documentos están pensados para leerse **en orden**, porque cada uno se apoya en el anterior.
Pero cada uno es autocontenido si ya sabes lo básico y vienes a consultar algo concreto.

| # | Documento | Qué vas a entender |
|---|-----------|--------------------|
| 01 | [Spring Boot vs Symfony: el modelo mental](01-springboot-vs-symfony.md) | La diferencia **fundamental** entre PHP y Java como plataformas. Por qué Spring parece "raro" viniendo de Symfony. Tabla de equivalencias. |
| 02 | [Estructura del proyecto y arranque](02-estructura-y-arranque.md) | Qué hay en cada carpeta, qué es Maven y el `pom.xml`, qué hace `application.properties`, y qué ocurre exactamente cuando arrancas la app. |
| 03 | [El contenedor de Spring: Beans e Inyección de Dependencias](03-contenedor-beans-inyeccion.md) | Qué es un *Bean*, qué es el contenedor IoC, `@Component` / `@Service` / `@Repository` / `@Configuration` / `@Bean`, y las 3 formas de inyectar. **Aquí se responde tu pregunta sobre `@Bean`.** |
| 04 | [Por qué tantas interfaces](04-por-que-interfaces.md) | La razón real de `IEventService` + `EventService`, de los repositorios que son interfaces vacías, y de los mappers que no tienen código. **Los proxies dinámicos son la clave de todo.** |
| 05 | [La capa de dominio: entidades JPA e Hibernate](05-dominio-entidades-jpa.md) | `@Entity`, `@Column`, relaciones `@ManyToMany` / `@ManyToOne`, LAZY vs EAGER, el lado propietario, Lombok en entidades y sus trampas. |
| 06 | [Repositorios: Spring Data JPA](06-repositorios-spring-data.md) | Cómo `findByNameContainingIgnoreCase` se convierte en SQL sin escribir una línea. Paginación. Comparativa con los repositorios de Doctrine. |
| 07 | [`@Transactional` a fondo](07-transaccional-a-fondo.md) | **El documento más importante.** Qué es el contexto de persistencia, el *dirty checking*, `readOnly`, propagación, y por qué en Spring casi nunca llamas a `flush()` como en Doctrine. |
| 08 | [DTOs, MapStruct y validación](08-dtos-mapstruct-validacion.md) | Por qué no devolvemos entidades, cómo MapStruct genera código en tiempo de compilación, y cómo funciona `@Valid`. |
| 09 | [La capa web: controladores y errores](09-capa-web-controladores.md) | `@RestController`, `ResponseEntity`, `@PathVariable`, `@RequestBody`, paginación automática, y el manejador global de excepciones. |
| 10 | [Seguridad: Spring Security + JWT](10-seguridad-jwt.md) | La cadena de filtros, el `SecurityFilterChain`, cómo se emite y valida un token, `@PreAuthorize`, y la comparativa con `security.yaml` de Symfony. |
| 11 | [Recorrido completo de una petición](11-recorrido-de-una-peticion.md) | Traza paso a paso, línea a línea, de `POST /api/v1/auth/login` y de `GET /api/v1/events`. Une todos los conceptos anteriores. |
| 12 | [Observaciones, mejoras y glosario](12-observaciones-y-glosario.md) | Cosas que he detectado en el código actual (bugs latentes y mejoras), comandos útiles, y un glosario de términos. |

---

## Resumen de una frase: ¿qué es este proyecto?

Es una **API REST de gestión de eventos**: permite registrar usuarios, autenticarlos con JWT, y
hacer CRUD de eventos, categorías y ponentes, con control de acceso por roles (`ROLE_ADMIN` /
`ROLE_USER`), guardando todo en una base de datos H2 en memoria.

## Mapa visual del proyecto

```
                     PETICIÓN HTTP
                          │
                          ▼
        ┌─────────────────────────────────────┐
        │   Cadena de filtros de seguridad    │  security/  (JWT, CORS, autorización)
        └─────────────────────────────────────┘
                          │
                          ▼
        ┌─────────────────────────────────────┐
        │       CONTROLADOR (@RestController) │  controller/
        │   Traduce HTTP ⇄ objetos Java       │  Valida entrada (@Valid)
        └─────────────────────────────────────┘
                 │ DTO                  ▲ DTO
                 ▼                      │
        ┌─────────────────────────────────────┐
        │       SERVICIO (@Service)           │  service/
        │   Lógica de negocio + transacciones │  @Transactional
        └─────────────────────────────────────┘
                 │ Entidad              ▲ Entidad
                 ▼                      │
        ┌─────────────────────────────────────┐
        │    REPOSITORIO (interfaz)           │  repository/
        │    Spring Data genera el SQL        │
        └─────────────────────────────────────┘
                          │
                          ▼
                   Hibernate (JPA)
                          │
                          ▼
                    Base de datos H2

   Piezas transversales:
   · domain/     → las entidades (las tablas)
   · dto/        → los objetos que viajan por HTTP
   · mapper/     → conversión entidad ⇄ DTO (MapStruct)
   · exception/  → errores traducidos a respuestas JSON
   · data/       → carga de datos iniciales al arrancar
```

## Inventario del código (lo que hay realmente escrito)

**36 clases/interfaces Java**, repartidas así:

- **1** clase de arranque — [ApiApplication.java](../src/main/java/com/gestion/eventos/api/ApiApplication.java)
- **5** entidades — `Event`, `Category`, `Speaker`, `User`, `Role`
- **5** repositorios — todos interfaces, ninguno con implementación
- **5** servicios (3 interfaces + 3 implementaciones, contando `IEventService`/`EventService`)
- **3** controladores de negocio + **1** de autenticación
- **8** DTOs (5 de negocio + 3 de seguridad)
- **5** mappers de MapStruct
- **2** clases de excepciones
- **5** clases de seguridad (config, filtro, generador JWT, punto de entrada, `UserDetailsService`)
- **1** cargador de datos de prueba
- **1** test

---

## Requisitos para ejecutarlo

- **Java 21** (definido en `pom.xml` → `<java.version>21</java.version>`)
- **Maven** — no hace falta instalarlo, el proyecto trae el *wrapper* (`mvnw.cmd`)
- No hace falta base de datos: usa **H2 en memoria**

```powershell
# Desde c:\wamp64\www\Springboot\api
.\mvnw.cmd spring-boot:run
```

La API queda en `http://localhost:8080` y la consola de la base de datos en
`http://localhost:8080/h2-console`.

Usuarios que se crean solos al arrancar (ver [DataLoader.java](../src/main/java/com/gestion/eventos/api/data/DataLoader.java)):

| Usuario | Contraseña | Roles |
|---------|-----------|-------|
| `admin` | `admin1234` | `ROLE_ADMIN`, `ROLE_USER` |
| `user`  | `123456`    | `ROLE_USER` |

---

**Siguiente:** [01 — Spring Boot vs Symfony: el modelo mental](01-springboot-vs-symfony.md)

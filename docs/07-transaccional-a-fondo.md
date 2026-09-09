# 07 — `@Transactional` a fondo

> **Aquí se responde tu pregunta sobre `@Transactional`.**
> Es el documento más denso y el más importante. Si vienes de Doctrine, aquí es donde más se te va
> a mover el suelo, porque el modelo mental de "persist + flush" **no aplica igual**.

---

## 1. ¿Qué es una transacción? (repaso rápido)

Un bloque de operaciones sobre la base de datos que se ejecuta **como una sola unidad**: o se
aplican todas, o ninguna.

```sql
BEGIN;
  UPDATE cuentas SET saldo = saldo - 100 WHERE id = 1;
  UPDATE cuentas SET saldo = saldo + 100 WHERE id = 2;
COMMIT;   -- si algo falla entre medias: ROLLBACK y no ha pasado nada
```

Las propiedades **ACID**:

| | |
|---|---|
| **A**tomicidad | Todo o nada |
| **C**onsistencia | La BD pasa de un estado válido a otro válido |
| **I**slamiento | Las transacciones concurrentes no se ven a medias |
| **D**urabilidad | Tras el `COMMIT`, los datos sobreviven a un apagón |

---

## 2. Cómo se hace sin Spring, y por qué es horrible

```java
public Event save(EventRequestDto dto) {
    EntityTransaction tx = entityManager.getTransaction();
    tx.begin();                                    // ← ruido
    try {
        Event event = mapper.toEntity(dto);
        Category cat = categoryService.findById(dto.getCategoryId());
        event.setCategory(cat);
        Event saved = repository.save(event);
        tx.commit();                               // ← ruido
        return saved;
    } catch (RuntimeException e) {
        if (tx.isActive()) tx.rollback();          // ← ruido
        throw e;
    }
}
```

De 12 líneas, **7 son fontanería** y 5 son lógica de negocio. Y ese bloque se repetiría idéntico en
cada método que escriba en la base de datos.

## 3. Con Spring

```java
@Transactional
public Event save(EventRequestDto dto) {
    Event event = mapper.toEntity(dto);
    Category cat = categoryService.findById(dto.getCategoryId());
    event.setCategory(cat);
    return repository.save(event);
}
```

Solo lógica. El `begin`/`commit`/`rollback` los pone **el proxy** (repasa el
[doc 04, sección 7](04-por-que-interfaces.md)):

```
Llamada del controlador
        │
        ▼
┌────────────────────────────────────────┐
│ PROXY de EventService                  │
│                                        │
│  1. transactionManager.begin()         │  ← BEGIN
│  2. try {                              │
│        resultado = real.save(dto);   ──┼──► TU CÓDIGO
│  3.    transactionManager.commit()     │  ← COMMIT
│     } catch (RuntimeException e) {     │
│  4.    transactionManager.rollback()   │  ← ROLLBACK
│        throw e;                        │
│     }                                  │
└────────────────────────────────────────┘
```

**⚠️ Importante:** hay **dos** anotaciones `@Transactional` distintas y hay que importar la correcta:

| Import | Cuál es |
|---|---|
| `org.springframework.transaction.annotation.Transactional` | ✅ **La de Spring.** Tiene todas las opciones (`readOnly`, `propagation`, `isolation`, `rollbackFor`, `timeout`). **Es la que usa este proyecto.** |
| `jakarta.transaction.Transactional` | La del estándar Jakarta. Funciona, pero tiene menos opciones (por ejemplo, no tiene `readOnly`). |

---

## 4. El contexto de persistencia: la clave de todo

Esto es lo que **de verdad** hay que entender, y donde más difiere de tu intuición de Doctrine.

Cuando se abre una transacción, Hibernate abre una **Session** (en términos JPA, un
*persistence context*). Es un **mapa en memoria de las entidades que estás manejando**:

```
        CONTEXTO DE PERSISTENCIA (vive lo que dura la transacción)
   ┌────────────────────────────────────────────────────────┐
   │  Event#42     → Event{id=42, name="Evento 42", ...}    │
   │  Category#3   → Category{id=3, name="Taller", ...}     │
   │  Speaker#1    → Speaker{id=1, name="John Doe", ...}    │
   └────────────────────────────────────────────────────────┘
```

Sirve para tres cosas:

**1. Caché de primer nivel.** Dentro de una transacción, pedir dos veces la misma entidad
ejecuta **una sola** consulta:

```java
@Transactional
public void ejemplo() {
    Category a = repo.findById(1L).get();   // SELECT ... FROM categories WHERE id=1
    Category b = repo.findById(1L).get();   // ← SIN consulta: lo saca del contexto
    System.out.println(a == b);             // true — es EL MISMO objeto en memoria
}
```

**2. Identidad garantizada.** Dentro de una transacción, una fila de la BD = un único objeto Java.

**3. `Dirty checking` — y aquí está la gran diferencia con Doctrine.**

---

## 5. 🔥 Dirty checking: por qué no llamas a `flush()`

Hibernate **guarda una copia** del estado original de cada entidad al cargarla. Al hacer `commit`,
compara la copia con el estado actual y **genera automáticamente los UPDATE de lo que haya
cambiado**.

```java
@Transactional
public Category update(Long id, Category category) {
    Category existing = repo.findById(id).orElseThrow(...);   // gestionada por el contexto

    existing.setName(category.getName());          // ← Hibernate se entera
    existing.setDescription(category.getDescription());

    return repo.save(existing);                    // ← ⚠️ ESTA LÍNEA NO HACE FALTA
}
// ← al salir del método: COMMIT
//   Hibernate compara, ve que name y description cambiaron, y ejecuta:
//   UPDATE categories SET name=?, description=? WHERE id=?
```

**Sí: puedes borrar el `repo.save(existing)` y el código sigue funcionando exactamente igual.**

Este código funcionaría igual de bien:

```java
@Transactional
public Category update(Long id, Category category) {
    Category existing = repo.findById(id).orElseThrow(...);
    existing.setName(category.getName());
    existing.setDescription(category.getDescription());
    return existing;      // sin save(): el UPDATE se genera solo al hacer commit
}
```

### Compara con Doctrine

```php
// Symfony/Doctrine — el flush es OBLIGATORIO y EXPLÍCITO
public function update(int $id, Category $data): Category {
    $existing = $this->repo->find($id);
    $existing->setName($data->getName());
    $this->em->flush();          // ← SIN ESTO NO SE GUARDA NADA
    return $existing;
}
```

| | Doctrine | JPA/Hibernate + Spring |
|---|---|---|
| ¿Cuándo se escribe en la BD? | Cuando **tú** llamas a `flush()` | Automáticamente al hacer **commit** (fin del método `@Transactional`) |
| ¿Hay dirty checking? | Sí, pero se dispara en el `flush()` | Sí, y se dispara solo |
| ¿Es obligatorio `persist()`? | Sí, para entidades **nuevas** | Sí (`save()`), para entidades **nuevas** |
| ¿Y para actualizar una existente? | `flush()` basta | **No hace falta nada** |

> **La regla que debes interiorizar:**
> - **Entidad nueva** → `repository.save(x)` (es un `persist`, imprescindible).
> - **Entidad ya cargada dentro de la transacción** → **modifícala y ya está**. El `save()` es
>   decorativo.

**En este proyecto**, `CategoryServiceImpl.update()`, `SpeakerServiceImpl.update()` y
`EventService.update()` hacen el `save()` redundante. No es un bug — es inofensivo y muchos equipos
lo dejan por claridad visual ("aquí se guarda"). Pero es importante que sepas que la línea no es la
que hace el trabajo. Y hay un caso donde importa mucho: si crees que **quitar** el `save()` evita
guardar algo, te equivocas. **Cualquier cambio en una entidad gestionada dentro de una transacción
se persiste**, lo llames o no.

### Cuándo se hace el `flush` realmente

Hibernate no escribe inmediatamente; acumula y vuelca en tres momentos:

1. Al hacer **commit** (el caso habitual).
2. Antes de **ejecutar una consulta** que pudiera verse afectada por los cambios pendientes.
3. Si llamas a `flush()` a mano (rara vez necesario).

---

## 6. Los atributos de `@Transactional`

### 6.1 `readOnly` — el que más se usa en este proyecto

```java
@Transactional(readOnly = true)
public List<Category> findAll() { return categoryRepository.findAll(); }
```

Aparece **7 veces** en el proyecto, siempre en métodos de lectura. Qué hace:

1. **Desactiva el dirty checking.** Hibernate pone el `FlushMode` en `MANUAL`: no guarda copias del
   estado original ni compara nada al terminar. **Menos memoria y menos CPU**, especialmente notable
   al cargar muchas entidades (los 60 eventos, por ejemplo).
2. **Marca la conexión JDBC como de solo lectura.** Algunas bases de datos optimizan con esto.
3. **En arquitecturas con réplicas**, permite enrutar la consulta a un servidor secundario.
4. **Documenta la intención**: quien lee el código sabe que ese método no escribe.

⚠️ **`readOnly = true` no impide escribir.** No es una barrera de seguridad: si dentro llamas a
`save()`, se guardará (el repositorio abre su propia semántica). Es una **optimización y una
declaración de intenciones**, no un candado.

### 6.2 `propagation` — qué pasa cuando una transacción llama a otra

Este es el atributo que explica una parte esencial del diseño de este proyecto.

```java
// EventService.save() está en una transacción...
@Transactional
public Event save(EventRequestDto requestDto) {
    Event event = eventMapper.toEntity(requestDto);
    Category category = categoryService.findById(requestDto.getCategoryId());  // ← ...y llama a
    //                  ↑ CategoryServiceImpl.findById también es @Transactional(readOnly=true)
```

**¿Qué ocurre? ¿Se abre una segunda transacción?**

**No.** Por defecto, `propagation = Propagation.REQUIRED`, que significa: *"si ya hay una
transacción activa, únete a ella; si no, crea una nueva"*.

Así que hay **una sola transacción** para todo `EventService.save()`, que abarca:
- el `SELECT` de la categoría
- los `SELECT` de los ponentes
- el `INSERT` del evento
- los `INSERT` en `event_speakers`

Si algo falla al insertar los ponentes, **se deshace todo, incluido el evento**. Eso es exactamente
lo que quieres. 👍

Los siete tipos de propagación:

| Propagación | Si YA hay transacción | Si NO hay |
|---|---|---|
| **`REQUIRED`** (por defecto) | Se une a ella | Crea una |
| `REQUIRES_NEW` | **Suspende** la actual y crea otra independiente | Crea una |
| `SUPPORTS` | Se une | Se ejecuta sin transacción |
| `NOT_SUPPORTED` | Suspende la actual | Sin transacción |
| `MANDATORY` | Se une | **Lanza excepción** |
| `NEVER` | **Lanza excepción** | Sin transacción |
| `NESTED` | Crea un *savepoint* (rollback parcial) | Crea una |

`REQUIRES_NEW` tiene un uso muy concreto: **auditoría/logging que debe persistir aunque la
operación principal falle**.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void registrarIntento(String usuario) {
    // Esto se guarda aunque la transacción de fuera haga rollback
}
```

⚠️ Un detalle sutil de `readOnly` + `REQUIRED`: si un método `@Transactional(readOnly = false)`
llama a uno `readOnly = true`, **el segundo se une a la transacción existente y el `readOnly` se
ignora**. `readOnly` solo tiene efecto cuando el método **inicia** la transacción. Por eso
`categoryService.findById()` dentro de `eventService.save()` no es realmente de solo lectura: forma
parte de la transacción de escritura.

### 6.3 `rollbackFor` — ⚠️ la trampa más importante

**Por defecto, Spring solo hace rollback ante excepciones NO comprobadas** (`RuntimeException` y
`Error`). Ante una excepción comprobada (*checked*), **hace COMMIT**.

```java
@Transactional
public void metodo() throws IOException {
    repo.save(algo);
    throw new IOException("fallo");   // ⚠️ IOException es CHECKED
}
// → ¡COMMIT! El save se guarda a pesar de la excepción
```

Para cambiarlo:

```java
@Transactional(rollbackFor = Exception.class)   // rollback ante CUALQUIER excepción
```

**¿Afecta a este proyecto?** No: `ResourceNotFoundException extends RuntimeException`, así que
provoca rollback correctamente. Pero es la clase de detalle que arruina un proyecto en producción.

*(Recordatorio de vocabulario Java: una excepción **checked** obliga al que llama a capturarla o
declararla con `throws`; una **unchecked** (`RuntimeException`) no. PHP no tiene esta distinción:
todas sus excepciones se comportan como unchecked.)*

### 6.4 `isolation` — nivel de aislamiento

| Nivel | Evita |
|---|---|
| `READ_UNCOMMITTED` | nada (permite *dirty reads*) |
| `READ_COMMITTED` | dirty reads. Por defecto en PostgreSQL, SQL Server |
| `REPEATABLE_READ` | + non-repeatable reads. Por defecto en MySQL |
| `SERIALIZABLE` | todo, pero es el más lento |
| `DEFAULT` | usa el de la base de datos ← **lo que hace este proyecto** |

Raramente hay que tocarlo. Está bien dejarlo por defecto.

### 6.5 `timeout`

```java
@Transactional(timeout = 5)   // segundos; aborta si tarda más
```

No se usa aquí. Útil para operaciones que podrían bloquear filas mucho tiempo.

---

## 7. Dónde está `@Transactional` en este proyecto

**14 usos, todos en la capa de servicio.** Ese es el sitio correcto:

| Clase | Método | Anotación |
|---|---|---|
| `EventService` | `findAll` | `@Transactional(readOnly = true)` |
| `EventService` | `save` | `@Transactional` |
| `EventService` | `findById` | `@Transactional(readOnly = true)` |
| `EventService` | `update` | `@Transactional` |
| `EventService` | `deleteById` | `@Transactional` |
| `CategoryServiceImpl` | `findAll`, `findById` | `@Transactional(readOnly = true)` |
| `CategoryServiceImpl` | `save`, `update`, `deleteById` | `@Transactional` |
| `SpeakerServiceImpl` | `findById`, `findAll` | `@Transactional(readOnly = true)` |
| `SpeakerServiceImpl` | `save`, `update`, `deleteById` | `@Transactional` |
| `DataLoader` | `run` | `@Transactional` |

### ¿Por qué en el servicio y no en el controlador o el repositorio?

```
Controlador   →  ❌ Mezclaría HTTP con persistencia. Además la transacción duraría
                    también la serialización JSON, manteniendo la conexión ocupada más tiempo.

SERVICIO      →  ✅ AQUÍ. Es donde vive la "unidad de trabajo de negocio": una operación
                    de negocio = una transacción. Un método puede tocar varias entidades
                    y todas deben confirmarse o deshacerse juntas.

Repositorio   →  ❌ Demasiado granular. Cada método sería su propia transacción y no
                    podrías agrupar varias operaciones en una sola unidad atómica.
```

**Un ejemplo concreto de por qué importa**, en `EventService.save()`: si la transacción estuviera en
el repositorio, el `INSERT` del evento y los `INSERT` en `event_speakers` serían transacciones
distintas. Un fallo a mitad dejaría un evento sin ponentes. Con la transacción en el servicio,
o se guarda todo o no se guarda nada.

### ¿Por qué `@Transactional` en el `DataLoader`?

```java
@Override
@Transactional
public void run(String... args) throws Exception {
```

Porque hace **decenas de operaciones relacionadas** (roles, usuarios, categorías, ponentes, 60
eventos con sus relaciones). Con una sola transacción:
- si algo falla a mitad, no queda la base de datos a medio poblar
- el rendimiento es mucho mejor: una transacción en vez de cientos
- las entidades siguen **gestionadas** durante todo el método, lo que hace que
  `event.addSpeaker(john)` funcione correctamente con las relaciones bidireccionales

---

## 8. ⚠️ Los cinco errores clásicos con `@Transactional`

### Error 1: la auto-invocación (el más común de todos)

```java
@Service
public class MiServicio {
    public void a() {
        this.b();          // ⚠️ salta el proxy → @Transactional IGNORADO
    }
    @Transactional
    public void b() { ... }
}
```

Como se explicó en el [doc 04](04-por-que-interfaces.md), el aspecto vive en el proxy y `this` es el
objeto real. **Existe un caso de esto en el proyecto**, en
[EventService.deleteById()](../src/main/java/com/gestion/eventos/api/service/EventService.java#L123):

```java
@Transactional
public void deleteById(Long id) {
    Event eventToDelete = this.findById(id);   // ← auto-invocación
    eventRepository.delete(eventToDelete);
}
```

Aquí **no causa ningún daño**: ya estamos dentro de la transacción de `deleteById`, y con
`REQUIRED` el resultado sería idéntico aunque el proxy sí se aplicara. Pero merece la pena que
entiendas por qué es inofensivo *en este caso concreto*, y que en otro (con `REQUIRES_NEW`, o si
`findById` fuera lo único transaccional) el fallo sería silencioso.

**Soluciones si alguna vez lo necesitas:** mover el método a otro bean, o inyectarse a sí mismo, o
usar `AopContext.currentProxy()`. La primera es la buena.

### Error 2: método no `public`

```java
@Transactional
private void guardar() { ... }    // ⚠️ el proxy no puede interceptarlo → se ignora
```

### Error 3: capturar la excepción y no relanzarla

```java
@Transactional
public void metodo() {
    try {
        repo.save(x);
        throw new RuntimeException("fallo");
    } catch (Exception e) {
        log.error("ups", e);      // ⚠️ te la comes → NO hay rollback → COMMIT
    }
}
```

### Error 4: excepciones checked

Ya visto en 6.3: por defecto **no** provocan rollback.

### Error 5: transacciones demasiado largas

```java
@Transactional
public void malo() {
    repo.save(x);
    llamarApiExterna();      // ⚠️ 3 segundos con la conexión y los bloqueos retenidos
}
```

Una transacción mantiene ocupada una conexión del pool y bloquea filas. Manténlas cortas y **nunca
metas llamadas de red dentro**.

---

## 9. Comparativa final Doctrine vs Spring

| Tarea | Symfony/Doctrine | Spring/JPA |
|---|---|---|
| Abrir transacción | `$em->beginTransaction()` o `$em->wrapInTransaction(fn)` | `@Transactional` |
| Guardar entidad nueva | `$em->persist($x); $em->flush();` | `repo.save(x)` |
| Actualizar existente | modificar + `$em->flush()` | **solo modificar** |
| Confirmar | `$em->commit()` o `flush()` implícito | Al terminar el método |
| Deshacer | `$em->rollback()` | Lanzar una `RuntimeException` |
| Solo lectura | no existe equivalente directo | `@Transactional(readOnly = true)` |
| Nivel de control | Explícito, tú mandas | Declarativo, el framework manda |

La filosofía difiere: **Doctrine es imperativa** ("yo digo cuándo se guarda"), **Spring es
declarativa** ("declaro los límites de la unidad de trabajo y el framework se encarga").

Cuesta al principio porque parece que pierdes control. Lo que ganas es que **no puedes olvidarte de
un `flush()`**, que es un bug clásico de Symfony.

---

## 10. Resumen del capítulo

1. `@Transactional` **envuelve el método en `BEGIN`/`COMMIT`/`ROLLBACK`** mediante un **proxy**.
2. Dentro de una transacción existe el **contexto de persistencia**: caché de primer nivel,
   identidad garantizada y **dirty checking**.
3. **El dirty checking es la gran diferencia con Doctrine:** si modificas una entidad cargada dentro
   de la transacción, **se guarda sola**. Los `save()` de los métodos `update()` de este proyecto
   son redundantes.
4. **`readOnly = true`** desactiva el dirty checking en las lecturas: menos memoria y menos CPU. El
   proyecto lo usa correctamente en los 7 métodos de lectura.
5. **`REQUIRED`** (por defecto) hace que las llamadas anidadas entre servicios compartan **una sola
   transacción**. Por eso `EventService.save()` es atómico de verdad.
6. Por defecto **solo hay rollback ante `RuntimeException`**. Las excepciones *checked* hacen
   commit.
7. La transacción va **en el servicio**, nunca en el controlador ni en el repositorio.
8. Recuerda las trampas del proxy: métodos `public`, y `this.metodo()` no lo atraviesa.

---

← [06 — Repositorios](06-repositorios-spring-data.md) | **Siguiente:** [08 — DTOs, MapStruct y validación](08-dtos-mapstruct-validacion.md)

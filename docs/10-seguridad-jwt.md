# 10 — Seguridad: Spring Security + JWT

> Este es el subsistema más grande del proyecto: 9 clases en
> [security/](../src/main/java/com/gestion/eventos/api/security/). Vamos a verlo entero.

---

## 1. Conceptos previos

### Autenticación vs Autorización

| | Pregunta | En este proyecto |
|---|---|---|
| **Autenticación** | ¿Quién eres? | Login con usuario/contraseña → se emite un JWT |
| **Autorización** | ¿Qué puedes hacer? | `@PreAuthorize("hasAnyRole('ADMIN')")` |

### Sesión vs Token: el cambio de paradigma

**Sesión (lo tradicional, y lo que hace Symfony por defecto):**
```
Login → el servidor CREA una sesión y la guarda (memoria/Redis/BD)
      → devuelve una cookie con el ID de sesión
Cada petición → cookie → el servidor BUSCA la sesión → sabe quién eres
```
El servidor **guarda estado**. Con varios servidores necesitas sesiones compartidas (sticky
sessions o Redis).

**Token JWT (lo que hace este proyecto):**
```
Login → el servidor genera un TOKEN FIRMADO con tus datos dentro
      → NO guarda nada
Cada petición → cabecera Authorization: Bearer <token>
              → el servidor VERIFICA LA FIRMA → sabe quién eres
```
El servidor es **stateless**. Escala horizontalmente sin esfuerzo, y por eso
`SessionCreationPolicy.STATELESS` aparece en la configuración.

| | Sesión | JWT |
|---|---|---|
| Estado en el servidor | Sí | No |
| Escalado horizontal | Necesita sesión compartida | Trivial |
| Revocar un acceso | Inmediato (borras la sesión) | **Difícil**: el token vale hasta que caduca |
| Tamaño por petición | Cookie pequeña | Token de ~200-500 bytes |
| Ideal para | Webs con navegador | APIs, SPAs, móviles |

⚠️ **La gran desventaja del JWT: no se puede revocar.** Si despides a un empleado, su token sigue
siendo válido hasta que expire. Por eso se usan expiraciones cortas — aquí, `600000` ms = **10
minutos** — combinadas con *refresh tokens* (que este proyecto todavía no tiene).

---

## 2. Qué es un JWT

Tres partes separadas por puntos, cada una en Base64URL:

```
eyJhbGciOiJIUzUxMiJ9 . eyJzdWIiOiJhZG1pbiIsImlhdCI6MTc1NzMy... . 4f2a9b8c1d...
      HEADER                        PAYLOAD                          SIGNATURE
```

**HEADER** — qué algoritmo firma:
```json
{ "alg": "HS512" }
```

**PAYLOAD** — los *claims* (los datos). Los que emite este proyecto:
```json
{
  "sub": "admin",           // subject: el username
  "iat": 1757328000,        // issued at: cuándo se emitió
  "exp": 1757328600         // expiration: cuándo caduca (iat + 10 min)
}
```

**SIGNATURE** — `HMAC-SHA512(header + "." + payload, jwt.secret)`

> ### 🚨 El payload NO está cifrado, solo codificado
> Cualquiera puede pegar el token en [jwt.io](https://jwt.io) y leer su contenido. **Base64 no es
> cifrado.** Nunca metas datos sensibles en un JWT.
>
> Lo que la firma garantiza es la **integridad**: si alguien cambia `"sub":"admin"` por
> `"sub":"root"`, la firma deja de cuadrar y el token se rechaza. Solo quien conoce
> `jwt.secret` puede generar una firma válida.

Fíjate en lo que **no** lleva el token de este proyecto: los **roles**. Solo el username. Eso
significa que en cada petición hay que ir a la base de datos a buscar el usuario y sus roles
(lo hace `JwtAuthenticationFilter`). Es una decisión con un compromiso claro:

| | Roles en el token | Roles desde la BD (lo que hace este proyecto) |
|---|---|---|
| Consultas por petición | 0 | 1 (+1 por los roles, aunque son EAGER en el mismo SELECT) |
| Si cambias los permisos de alguien | Tarda hasta 10 min en aplicarse | **Efecto inmediato** |
| Tamaño del token | Mayor | Menor |

Con expiraciones de 10 minutos, la opción elegida es perfectamente razonable y más segura.

---

## 3. La cadena de filtros: el corazón de Spring Security

**Spring Security es, esencialmente, una cadena de filtros de servlet** que se ejecuta *antes* de
que la petición llegue a tu controlador.

```
Petición HTTP
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│  FILTER CHAIN de Spring Security (~15 filtros)              │
│                                                             │
│   ...                                                       │
│   CorsFilter                    (solo si se activa .cors()) │
│   CsrfFilter                    ← DESACTIVADO aquí          │
│   ┌───────────────────────────────────────────┐             │
│   │  JwtAuthenticationFilter  ← ⭐ EL TUYO    │             │
│   │  addFilterBefore(...)                     │             │
│   └───────────────────────────────────────────┘             │
│   UsernamePasswordAuthenticationFilter  (login por formulario)│
│   ...                                                       │
│   ExceptionTranslationFilter    ← usa JwtAuthEntryPoint     │
│   AuthorizationFilter           ← aplica authorizeHttpRequests│
└─────────────────────────────────────────────────────────────┘
     │
     ▼
DispatcherServlet → tu controlador
```

**El orden importa muchísimo**, y por eso:

```java
http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

*"Mete mi filtro JWT justo antes del filtro de login por formulario."* Tiene que ejecutarse antes de
que el `AuthorizationFilter` decida si tienes permiso, porque es quien establece **quién eres**.

> **Symfony:** los firewalls y sus *authenticators* hacen exactamente esto. La diferencia es que en
> Symfony lo declaras en `security.yaml` y aquí lo construyes en Java.

---

## 4. `SecurityConfig`, línea a línea

[SecurityConfig.java](../src/main/java/com/gestion/eventos/api/security/config/SecurityConfig.java)

```java
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
```

- `@Configuration` → declara beans con `@Bean` (ver [doc 03](03-contenedor-beans-inyeccion.md)).
- `@EnableMethodSecurity` → **activa `@PreAuthorize`**. Sin esta línea, todas las anotaciones
  `@PreAuthorize` de los controladores serían **decorativas y se ignorarían silenciosamente**. Es
  crítica.
- Se inyecta `UserDetailsService` (la interfaz), y Spring resuelve tu `UserDetailsServiceImpl`.

*(Apunte: `userDetailsService` está inyectado pero no se usa explícitamente en ningún método de esta
clase. No sobra del todo — su presencia como bean es lo que hace que Spring Security lo use — pero
el campo en sí podría eliminarse sin romper nada.)*

### El `SecurityFilterChain`

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
```

**CSRF desactivado.** ¿Es seguro? **Sí, en este caso.** El ataque CSRF explota que el navegador
envía las cookies automáticamente. Como aquí la autenticación va en una cabecera `Authorization`
que el navegador **no** añade sola, el ataque no aplica. Desactivar CSRF en una API stateless con
JWT es la práctica estándar. (En una app con sesiones y cookies, desactivarlo sería un fallo grave.)

```java
        .exceptionHandling(exception ->
                exception.authenticationEntryPoint(jwtAuthEntryPoint))
```

"Cuando alguien no autenticado toque algo protegido, llama a `JwtAuthEntryPoint`". Sin esto, Spring
Security intentaría redirigir a una página de login HTML, inútil para una API.

```java
        .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**No crear sesiones HTTP jamás.** Cada petición se autentica desde cero con su token. Es la
coherencia con el modelo JWT.

```java
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .anyRequest().authenticated())
```

Las reglas de acceso a nivel de URL. **El orden importa: gana la primera que coincide.**

| Ruta | Acceso |
|---|---|
| `/api/v1/auth/**` | Público (login y registro deben serlo) |
| `/h2-console/**` | Público ⚠️ (ver aviso) |
| Todo lo demás | Requiere estar autenticado |

Esto es la **primera capa** de defensa (por URL). La **segunda** es `@PreAuthorize` en los métodos
(por rol). Defensa en profundidad. 👍

> **Symfony:** esto es `access_control:` en `security.yaml`:
> ```yaml
> access_control:
>     - { path: ^/api/v1/auth, roles: PUBLIC_ACCESS }
>     - { path: ^/, roles: IS_AUTHENTICATED_FULLY }
> ```

```java
        .headers(AbstractHttpConfigurer::disable);
```

⚠️ **Esto es más agresivo de lo que parece, y merece una corrección.** El comentario del código
dice "Desactiva los encabezados de seguridad para permitir el acceso a la consola H2", y la
intención es correcta: la consola de H2 usa `<iframe>`, y Spring Security lo bloquea por defecto con
`X-Frame-Options: DENY`.

Pero `.headers(disable)` desactiva **todas** las cabeceras de seguridad, no solo esa:
`X-Content-Type-Options`, `Strict-Transport-Security`, `Cache-Control`, `X-XSS-Protection`...

Lo correcto es desactivar **solo** la que estorba:

```java
.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
```

Así la consola H2 funciona y conservas el resto de protecciones.

```java
    http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

Ya explicado arriba: coloca tu filtro en el punto correcto de la cadena.

### ⚠️ El CORS no está activado

El bean `corsConfigurationSource()` está perfectamente escrito (permite `http://localhost:4200`,
métodos, cabeceras, credenciales, cache de 1 hora)... **pero la cadena de filtros nunca activa el
soporte CORS.** Declarar el bean no basta: hay que añadirlo a la cadena:

```java
http
    .cors(Customizer.withDefaults())     // ← ESTA LÍNEA FALTA
    .csrf(AbstractHttpConfigurer::disable)
    ...
```

Con `.cors(Customizer.withDefaults())`, Spring Security busca un bean llamado
`corsConfigurationSource` y lo usa. Sin esa línea, el `CorsFilter` no se añade a la cadena y la
configuración se queda sin efecto: un frontend Angular en el 4200 recibiría errores de CORS.

Es coherente con el estado del repositorio — ese bean es justamente el cambio sin commitear que
tienes en el working tree, así que es trabajo en curso. Cuando lo pruebes con el frontend, esa es la
línea que faltará.

Requiere el import `org.springframework.security.config.Customizer`.

### 🔒 Sobre `/h2-console/**` en `permitAll()`

Es lo que hace falta para trastear en desarrollo, y para aprender está bien. Solo ten presente que
esa consola permite **ejecutar SQL arbitrario sin autenticación**. Nunca debe llegar a producción.
La forma limpia es aislarlo con perfiles:

```java
@Bean
@Profile("dev")     // solo en desarrollo
public SecurityFilterChain devChain(HttpSecurity http) { ... }
```

---

## 5. El registro de un usuario

[AuthController.registerUser()](../src/main/java/com/gestion/eventos/api/security/controller/AuthController.java#L51-L67)

```java
@PostMapping("/register")
public ResponseEntity<String> registerUser(@RequestBody RegisterDto registerDto) {
    if (userRepository.existsByUsername(registerDto.getUsername())) {
        return new ResponseEntity<>("Nombre de usuario, ya existe...", HttpStatus.BAD_REQUEST);
    }
    if (userRepository.existsByEmail(registerDto.getEmail())) {
        return new ResponseEntity<>("Email de usuario, ya existe...", HttpStatus.BAD_REQUEST);
    }

    User user = userMapper.registerDtoToUser(registerDto);      // roles resueltos por el mapper
    user.setPassword(passwordEncoder.encode(registerDto.getPassword()));   // ← BCrypt

    userRepository.save(user);
    return new ResponseEntity<>("Usuario registrado...", HttpStatus.CREATED);
}
```

### BCrypt: por qué no se guarda la contraseña

`passwordEncoder.encode("admin1234")` produce algo así:

```
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
 │   │  └────────── salt (22 chars) ──────┴──── hash ────────┘
 │   └─ cost factor: 10  → 2^10 = 1024 iteraciones
 └─ versión del algoritmo
```

Tres propiedades esenciales:

1. **Es unidireccional.** No existe "descifrar". La verificación consiste en volver a hashear lo que
   escribe el usuario y comparar.
2. **Lleva salt aleatorio integrado.** La misma contraseña genera hashes **distintos** cada vez. Eso
   inutiliza las *rainbow tables*.
3. **Es deliberadamente lento.** El *cost factor* hace que probar millones de contraseñas por fuerza
   bruta sea inviable. Y se puede subir conforme mejora el hardware.

Por eso `BCryptPasswordEncoder` se declara como `@Bean`
([doc 03](03-contenedor-beans-inyeccion.md)): es una clase de Spring Security a la que no puedes
poner `@Component`.

> **Symfony:** `password_hashers: App\Entity\User: 'auto'` — y `auto` elige bcrypt o argon2i.
> Mismo concepto.

### ⚠️ Dos problemas en este endpoint

1. **Falta `@Valid`** → las validaciones de `RegisterDto` no se aplican (ver
   [doc 08](08-dtos-mapstruct-validacion.md)).
2. **Escalada de privilegios**: `RegisterDto` acepta `Set<String> roles` del cliente, y el endpoint
   es público. Cualquiera puede registrarse como `ROLE_ADMIN`:
   ```json
   {"username":"malo","password":"123456","email":"a@b.c","name":"X","roles":["ROLE_ADMIN"]}
   ```
   La solución: quitar `roles` de `RegisterDto` y forzar siempre `ROLE_USER` en el registro público,
   con un endpoint separado protegido con `@PreAuthorize("hasRole('ADMIN')")` para crear
   administradores.

---

## 6. El login: cómo se emite el token

[AuthController.authenticateUser()](../src/main/java/com/gestion/eventos/api/security/controller/AuthController.java#L38-L49)

```java
@PostMapping("/login")
public ResponseEntity<JwtAuthResponseDto> authenticateUser(@RequestBody LoginDto loginDto) {
    Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(loginDto.getUsername(), loginDto.getPassword()));

    SecurityContextHolder.getContext().setAuthentication(authentication);

    String token = jwtGenerator.generateToken(authentication);

    return new ResponseEntity<>(new JwtAuthResponseDto(token), HttpStatus.OK);
}
```

### Qué pasa dentro de `authenticationManager.authenticate(...)`

```
1. Se crea un UsernamePasswordAuthenticationToken con las credenciales SIN verificar
        │
2. El AuthenticationManager lo pasa a los AuthenticationProvider registrados
        │
3. DaoAuthenticationProvider (el que corresponde a usuario/contraseña):
        │
        ├─ llama a userDetailsService.loadUserByUsername("admin")   ← ⭐ TU CÓDIGO
        │      └─ SELECT * FROM users WHERE username = 'admin'
        │      └─ + SELECT roles (son EAGER)
        │      └─ devuelve un UserDetails con el hash y las authorities
        │
        ├─ llama a passwordEncoder.matches("admin1234", "$2a$10$N9qo...")   ← ⭐ TU BEAN
        │      └─ ¿coinciden? Si no: BadCredentialsException
        │
4. Devuelve un Authentication AUTENTICADO (authenticated = true) con las authorities
```

**Aquí encajan las tres piezas que has escrito tú:**
- `UserDetailsServiceImpl` → de dónde salen los usuarios
- `PasswordEncoder` (el `@Bean`) → cómo se comparan las contraseñas
- `AuthenticationManager` (el `@Bean`) → el orquestador

Si la contraseña es incorrecta, `authenticate()` lanza `BadCredentialsException` y el método nunca
llega a generar el token.

*(Detalle: el `SecurityContextHolder.setAuthentication(...)` del login es prácticamente inútil en
una API stateless — el contexto se limpia al terminar la petición y no hay sesión que lo conserve.
No hace daño, pero no aporta nada.)*

### `UserDetailsServiceImpl`

[UserDetailsServiceImpl.java](../src/main/java/com/gestion/eventos/api/security/service/UserDetailsServiceImpl.java)

```java
@Override
public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado con ese username: " + username));

    return new org.springframework.security.core.userdetails.User(
            user.getUsername(), user.getPassword(), mapRolesToAuthorities(user.getRoles()));
}

private Collection<? extends GrantedAuthority> mapRolesToAuthorities(Set<Role> roles) {
    return roles.stream()
            .map(role -> new SimpleGrantedAuthority(role.getName()))   // "ROLE_ADMIN"
            .collect(Collectors.toList());
}
```

Dos cosas importantes:

1. **Traduce tu `User` de dominio al `User` de Spring Security.** Son dos clases distintas con el
   mismo nombre (por eso el nombre completo con paquete). Esto mantiene tu entidad limpia de
   dependencias del framework de seguridad.
2. **`role.getName()` devuelve `"ROLE_ADMIN"` con prefijo**, que es exactamente lo que
   `hasAnyRole('ADMIN')` espera encontrar (ver [doc 09](09-capa-web-controladores.md)).

**Aquí es donde importa que `User.roles` sea `EAGER`** ([doc 05](05-dominio-entidades-jpa.md)): este
método no tiene `@Transactional`, así que si los roles fueran LAZY, `user.getRoles()` lanzaría una
`LazyInitializationException` al salir de la sesión.

### `JwtGenerator`

[JwtGenerator.java](../src/main/java/com/gestion/eventos/api/security/jwt/JwtGenerator.java)

```java
@Component
public class JwtGenerator {
    @Value("${jwt.secret}")     private String jwtSecret;
    @Value("${jwt.expiration}") private long jwtExpiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Authentication authentication) {
        String username = authentication.getName();
        Date currentDate = new Date();
        Date expireDate = new Date(currentDate.getTime() + jwtExpiration);

        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(expireDate)
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }
```

**`@Value("${jwt.secret}")`** inyecta un valor de `application.properties` en un campo. Es el
equivalente exacto a `%env(JWT_SECRET)%` / `#[Autowire('%jwt_secret%')]` de Symfony.

**HS512** = HMAC con SHA-512: firma **simétrica**, la misma clave firma y verifica. Requiere que el
secreto tenga al menos 512 bits (64 caracteres); el de este proyecto tiene 76, así que cumple. Si
fuera más corto, `Keys.hmacShaKeyFor` lanzaría una excepción al arrancar.

*(La alternativa serían algoritmos asimétricos como RS256, donde una clave privada firma y una
pública verifica — útil cuando varios servicios deben validar tokens sin poder emitirlos.)*

```java
    public String getUsernameFromJwt(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (MalformedJwtException e)   { System.out.println("Invalid JWT token: " + ...); }
          catch (ExpiredJwtException e)     { System.out.println("JWT token is expired: " + ...); }
          catch (UnsupportedJwtException e) { ... }
          catch (IllegalArgumentException e){ ... }
          catch (SignatureException e)      { ... }
        return false;
    }
```

`parseSignedClaims` **verifica la firma y la expiración a la vez**. Si el token fue manipulado o ha
caducado, lanza excepción. La captura por tipos permite distinguir el motivo.

*(Dos mejoras menores: usar un logger en lugar de `System.out.println`, y saber que el bloque
`catch (SignatureException)` nunca se alcanza porque `MalformedJwtException` y las demás se evalúan
antes en algunos casos — en jjwt 0.12+ la firma inválida lanza `SignatureException`, que sí se
captura, pero conviene que el orden de los catch vaya de más específico a más general.)*

---

## 7. Cada petición: `JwtAuthenticationFilter`

[JwtAuthenticationFilter.java](../src/main/java/com/gestion/eventos/api/security/jwt/JwtAuthenticationFilter.java)

```java
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtGenerator jwtGenerator;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = getJwtFromRequest(request);                        // 1

        if (StringUtils.hasText(token) && jwtGenerator.validateToken(token)
            && SecurityContextHolder.getContext().getAuthentication() == null) {   // 2

            String username = jwtGenerator.getUsernameFromJwt(token);     // 3
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);  // 4

            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());       // 5

            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authenticationToken); // 6
        }

        filterChain.doFilter(request, response);                          // 7
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);      // quita "Bearer "
        }
        return null;
    }
}
```

Paso a paso:

1. **Extrae el token** de la cabecera `Authorization: Bearer eyJhbGci...`. El `substring(7)` quita
   exactamente los 7 caracteres de `"Bearer "`.
2. **Tres condiciones**: hay token, la firma y la fecha son válidas, y **todavía no hay nadie
   autenticado** (esa tercera comprobación evita pisar una autenticación previa).
3. **Extrae el username** del claim `sub`.
4. **Carga el usuario y sus roles de la base de datos.** Aquí es donde se paga la consulta por
   petición de la que hablábamos, y donde se gana la revocación inmediata de permisos.
5. **Construye un `Authentication` ya autenticado.** El `null` del segundo argumento son las
   credenciales: **no se guarda la contraseña en el contexto**, y eso está bien. Usar este
   constructor de tres argumentos marca el token como `authenticated = true`.
6. **Lo guarda en el `SecurityContextHolder`.** A partir de aquí, `@PreAuthorize` y cualquier código
   que pregunte "quién es el usuario actual" lo encontrará.
7. **Continúa la cadena.** ⚠️ **Fíjate en que esta línea está FUERA del `if`**, y es fundamental: si
   no hay token, la petición **sigue adelante sin autenticar**. Quien decide si eso es un problema
   es el `AuthorizationFilter` más adelante (que devolverá 401 para las rutas protegidas y dejará
   pasar `/api/v1/auth/**`). Un filtro que cortara aquí rompería los endpoints públicos.

### `OncePerRequestFilter`

Garantiza que el filtro se ejecuta **una sola vez por petición**, incluso si hay *forwards* internos
del servlet container. Es la clase base correcta para este caso.

### `SecurityContextHolder`: dónde vive "el usuario actual"

```java
SecurityContextHolder.getContext().setAuthentication(authenticationToken);
```

Por dentro es un **`ThreadLocal`**: una variable cuyo valor es **propio de cada hilo**.

Y aquí vuelve la diferencia fundamental del [doc 01](01-springboot-vs-symfony.md): como Java atiende
muchas peticiones **simultáneamente en hilos distintos** dentro del mismo proceso, hace falta un
mecanismo que asocie "el usuario actual" al hilo que atiende esa petición. En PHP no existe ese
problema, porque cada petición es un proceso aislado — por eso Symfony puede tener simplemente un
servicio `Security` con el token dentro.

Spring Security limpia el `ThreadLocal` al terminar la petición. Si no lo hiciera, el hilo volvería
al pool "contaminado" y la siguiente petición heredaría al usuario anterior. 😱

Para leerlo desde cualquier sitio:

```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
String username = auth.getName();
```

O, más limpio, en un controlador:

```java
@GetMapping("/me")
public ResponseEntity<String> quienSoy(@AuthenticationPrincipal UserDetails user) {
    return ResponseEntity.ok(user.getUsername());
}
```

---

## 8. `JwtAuthEntryPoint`: el 401

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

Se invoca cuando alguien **no autenticado** intenta acceder a algo protegido. Sin él, Spring
Security redirigiría a un formulario de login.

`sendError` genera la respuesta de error por defecto del contenedor (HTML). Para una API sería más
elegante devolver JSON:

```java
response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
response.setContentType("application/json");
response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Token ausente o inválido\"}");
```

Así el cliente recibe el mismo formato de error que en el resto de la API.

---

## 9. El flujo completo, de principio a fin

```
╔══════════════════════ REGISTRO ══════════════════════╗
  POST /api/v1/auth/register  {"username":"ana", "password":"secreto123", ...}
       │
       ├─ Ruta pública (permitAll) → el filtro JWT no encuentra token y deja pasar
       ├─ AuthController: ¿existe el username? ¿el email?
       ├─ UserMapper: DTO → User  (password IGNORADA, roles resueltos desde BD)
       ├─ passwordEncoder.encode("secreto123") → "$2a$10$..."
       └─ INSERT INTO users + INSERT INTO users_roles
  201 CREATED "Usuario registrado..."
╚══════════════════════════════════════════════════════╝

╔═══════════════════════ LOGIN ════════════════════════╗
  POST /api/v1/auth/login  {"username":"ana","password":"secreto123"}
       │
       ├─ authenticationManager.authenticate(...)
       │     ├─ UserDetailsServiceImpl.loadUserByUsername("ana")  → SELECT
       │     └─ passwordEncoder.matches("secreto123", hash)       → ✔
       ├─ jwtGenerator.generateToken(auth)
       │     └─ {"sub":"ana","iat":...,"exp":+10min} firmado con HS512
  200 OK {"accessToken":"eyJhbGciOiJIUzUxMiJ9...","tokenType":"Bearer "}
╚══════════════════════════════════════════════════════╝

╔═════════════ PETICIÓN AUTENTICADA ═══════════════════╗
  GET /api/v1/events    Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...
       │
       ├─ JwtAuthenticationFilter
       │     ├─ extrae el token tras "Bearer "
       │     ├─ validateToken: ¿firma correcta? ¿no ha caducado?
       │     ├─ getUsernameFromJwt → "ana"
       │     ├─ loadUserByUsername("ana") → SELECT + roles
       │     └─ SecurityContextHolder ← Authentication(ana, [ROLE_USER])
       │
       ├─ AuthorizationFilter: "anyRequest().authenticated()" → ✔ hay autenticación
       │
       ├─ Proxy de @PreAuthorize("hasAnyRole('ADMIN','USER')") → ✔ tiene ROLE_USER
       │
       ├─ EventController.getAllEvents(...)
       │     └─ EventService.findAll(...)  [@Transactional(readOnly=true)]
       │           └─ SELECT eventos + COUNT + mapeo a DTO
       │
       └─ Jackson serializa el Page<EventResponseDto>
  200 OK {"content":[...], "totalElements":60, ...}
╚══════════════════════════════════════════════════════╝

╔═══════════════ SIN PERMISOS ═════════════════════════╗
  POST /api/v1/events   (con token de 'ana', que solo es ROLE_USER)
       ├─ Filtro JWT: ✔ autenticada
       ├─ @PreAuthorize("hasAnyRole('ADMIN')") → ✘ no tiene ROLE_ADMIN
  403 FORBIDDEN     (⚠️ ver observación sobre el handler genérico, doc 09)
╚══════════════════════════════════════════════════════╝

╔════════════════ SIN TOKEN ═══════════════════════════╗
  GET /api/v1/events   (sin cabecera Authorization)
       ├─ JwtAuthenticationFilter: no hay token → sigue sin autenticar
       ├─ AuthorizationFilter: requiere autenticación → AuthenticationException
       └─ JwtAuthEntryPoint.commence(...)
  401 UNAUTHORIZED
╚══════════════════════════════════════════════════════╝
```

---

## 10. Probarlo con curl

```powershell
# 1. Login como admin
curl -X POST http://localhost:8080/api/v1/auth/login `
     -H "Content-Type: application/json" `
     -d '{\"username\":\"admin\",\"password\":\"admin1234\"}'

# → {"accessToken":"eyJhbGciOiJIUzUxMiJ9...","tokenType":"Bearer "}

# 2. Usar el token
curl http://localhost:8080/api/v1/events `
     -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."

# 3. Sin token → 401
curl http://localhost:8080/api/v1/events

# 4. Crear un evento (requiere ADMIN)
curl -X POST http://localhost:8080/api/v1/events `
     -H "Authorization: Bearer <TOKEN_ADMIN>" `
     -H "Content-Type: application/json" `
     -d '{\"name\":\"Mi evento\",\"date\":\"2026-12-01\",\"location\":\"Sala 1\",\"categoryId\":1,\"speakersIds\":[1]}'

# 5. Lo mismo con el token de 'user' → 403
```

**Recuerda que el token caduca a los 10 minutos.** Si empiezas a recibir 401 de repente, es eso:
vuelve a hacer login.

---

## 11. Resumen del capítulo

- Spring Security es una **cadena de filtros** que corre antes que tu controlador. Tu filtro JWT se
  inserta con `addFilterBefore`.
- El `SecurityFilterChain` es un `@Bean` que hace de `security.yaml`: CSRF off, sesiones
  `STATELESS`, rutas públicas vs protegidas, y el punto de entrada para los 401.
- **`@EnableMethodSecurity` es lo que hace que `@PreAuthorize` funcione.** Sin ella, esas
  anotaciones se ignoran en silencio.
- El JWT lleva `sub`, `iat`, `exp`, firmado con HS512. **El payload es legible por cualquiera**; lo
  que protege es la firma.
- Los roles **no** van en el token: se cargan de la BD en cada petición. Cuesta una consulta y a
  cambio los cambios de permisos son inmediatos.
- `UserDetailsService` + `PasswordEncoder` + `AuthenticationManager` son las tres piezas que
  conectan tu modelo con Spring Security.
- El `SecurityContextHolder` es un `ThreadLocal`: el usuario actual está asociado al **hilo**, no al
  proceso. Consecuencia directa del modelo de concurrencia de Java.
- **Pendientes conocidas:** activar `.cors(...)`, acotar `.headers(...)`, añadir `@Valid`, y quitar
  `roles` del registro público.

---

← [09 — La capa web](09-capa-web-controladores.md) | **Siguiente:** [11 — Recorrido completo de una petición](11-recorrido-de-una-peticion.md)

package com.gestion.eventos.api.security.config;


import java.util.Arrays;
import java.util.Collections;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.gestion.eventos.api.security.jwt.JwtAuthEntryPoint;
import com.gestion.eventos.api.security.jwt.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {
    
    private final UserDetailsService userDetailsService;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Configura CORS para permitir solicitudes desde el frontend
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(jwtAuthEntryPoint) // Configura el punto de entrada para manejar errores de autenticación
                        )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) // Desactiva la creación de sesiones, ya que estamos usando JWT para autenticación
                        )
                .authorizeHttpRequests(auth ->
                    auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .anyRequest().authenticated()
                )
                    .headers(AbstractHttpConfigurer::disable); // Desactiva los encabezados de seguridad para permitir el acceso a la consola H2

                // Agrega el filtro de autenticación JWT antes del filtro de autenticación de nombre de usuario y contraseña
                // Esto asegura que cada solicitud entrante pase por el filtro JWT para verificar la validez del token antes de llegar a los controladores
                // El filtro JwtAuthenticationFilter intercepta las solicitudes entrantes y valida el token JWT presente en la cabecera Authorization. Si el token es válido, se establece la autenticación en el contexto de seguridad, permitiendo que la solicitud continúe hacia los controladores protegidos.
                http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager
            (AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

     // <<<< NUEVO MÉTODO: Bean para la configuración de CORS >>>>
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // En desarrollo, permitimos todos los orígenes.
        // EN PRODUCCIÓN, CAMBIAR ESTO POR LOS DOMINIOS ESPECÍFICOS DE TU FRONTEND (ej. "https://tumiweb.com")
        configuration.setAllowedOrigins(Collections.singletonList("http://localhost:4200")); // O Arrays.asList("http://localhost:3000", "http://otrafuente.com")
        // Métodos HTTP permitidos
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        // Encabezados HTTP permitidos (Authorization, Content-Type, etc.)
        configuration.setAllowedHeaders(Collections.singletonList("*"));
        // configuration.setAllowedHeaders(Arrays.asList(
        //         "Authorization", 
        //         "Content-Type", 
        //         "Accept"
        //     ));
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        // Permite enviar credenciales (como cookies o encabezados de autorización)
        configuration.setAllowCredentials(true);
        // Tiempo máximo en segundos que la respuesta de una pre-solicitud (preflight) puede ser cacheada por el navegador
        configuration.setMaxAge(3600L); // 1 hora

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Aplicar esta configuración CORS a todas las rutas de nuestra API
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }



    
}

// src/main/java/com/gestion/eventos/api/data/DataLoader.java
package com.gestion.eventos.api.data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gestion.eventos.api.domain.Category;
import com.gestion.eventos.api.domain.Event; // ¡Importa la entidad Event!
import com.gestion.eventos.api.domain.Role;
import com.gestion.eventos.api.domain.Speaker;
import com.gestion.eventos.api.domain.User;
import com.gestion.eventos.api.repository.CategoryRepository;
import com.gestion.eventos.api.repository.EventRepository; // ¡Importa el EventRepository!
import com.gestion.eventos.api.repository.RoleRepository;
import com.gestion.eventos.api.repository.SpeakerRepository;
import com.gestion.eventos.api.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DataLoader implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final CategoryRepository categoryRepository;
    private final SpeakerRepository speakerRepository;
    private final EventRepository eventRepository; // ¡Inyecta el EventRepository!


    @Override
    @Transactional
    public void run(String... args) throws Exception {

        // --- LÓGICA EXISTENTE PARA ROLES Y USUARIOS ---
        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet( () -> {
                    Role newRole = new Role();
                    newRole.setName("ROLE_ADMIN");
                    return roleRepository.save(newRole);
                });

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> {
                    Role newRole = new Role();
                    newRole.setName("ROLE_USER");
                    return roleRepository.save(newRole);
                });

        if(userRepository.findByUsername("admin").isEmpty()){
            User admin = new User();
            admin.setName("Administrador");
            admin.setUsername("admin");
            admin.setEmail("admin@example.com");
            admin.setPassword(passwordEncoder.encode("admin1234"));

            Set<Role> adminRoles = new HashSet<>();
            adminRoles.add(adminRole);
            adminRoles.add(userRole);

            admin.setRoles(adminRoles);

            userRepository.save(admin);
            System.out.println("Usuario 'admin' creado.");
        }

        if (userRepository.findByUsername("user").isEmpty()) {
            User regularUser = new User();
            regularUser.setName("Usuario Normal");
            regularUser.setUsername("user");
            regularUser.setEmail("user@example.com");
            regularUser.setPassword(passwordEncoder.encode("123456"));

            Set<Role> userRoles = new HashSet<>();
            userRoles.add(userRole);
            regularUser.setRoles(userRoles);

            userRepository.save(regularUser);
            System.out.println("Usuario 'user' creado.");
        }

        // --- LÓGICA EXISTENTE PARA CATEGORÍAS ---
        Category conferencia = categoryRepository.findByName("Conferencia")
                .orElseGet(() -> {
                    Category newCat = new Category(null, "Conferencia", "Eventos de gran escala con múltiples oradores.");
                    return categoryRepository.save(newCat);
                });
        Category taller = categoryRepository.findByName("Taller")
                .orElseGet(() -> {
                    Category newCat = new Category(null, "Taller", "Eventos interactivos y prácticos.");
                    return categoryRepository.save(newCat);
                });
        Category webinar = categoryRepository.findByName("Webinar")
                .orElseGet(() -> {
                    Category newCat = new Category(null, "Webinar", "Seminarios online en vivo.");
                    return categoryRepository.save(newCat);
                });
        // --- También puedes usar existsByName para verificar antes de crear como lo tenías.
        // --- Lo he cambiado a findByName().orElseGet() para obtener las instancias guardadas
        // --- que necesitaremos para los eventos.


        // --- LÓGICA EXISTENTE PARA ORADORES ---
        Speaker john = speakerRepository.findByEmail("john.doe@example.com")
                .orElseGet(() -> {
                    Speaker newSpeaker = new Speaker(null, "John Doe", "john.doe@example.com", "Experto en desarrollo de software.", new HashSet<>());
                    return speakerRepository.save(newSpeaker);
                });
        Speaker jane = speakerRepository.findByEmail("jane.smith@example.com")
                .orElseGet(() -> {
                    Speaker newSpeaker = new Speaker(null, "Jane Smith", "jane.smith@example.com", "Especialista en marketing digital.", new HashSet<>());
                    return speakerRepository.save(newSpeaker);
                });
        // Asegúrate de que los repositorios de Category y Speaker tengan métodos findByName y findByEmail respectivamente.
        // Si no los tienen, añádelos:
        // CategoryRepository: Optional<Category> findByName(String name);
        // SpeakerRepository: Optional<Speaker> findByEmail(String email);


        // --- NUEVA LÓGICA PARA CREAR Y GUARDAR EVENTOS ---
        if (eventRepository.count() == 0) { // Solo cargar eventos si la tabla está vacía
            List<Event> events = new ArrayList<>();
            LocalDate baseDate = LocalDate.now();

            for (int i = 1; i <= 60; i++) { // Cambia 60 al número deseado de eventos
                Event event = new Event();
                event.setName("Evento " + (i < 10 ? "0" + i : i) + ": Conferencia de Tecnología " + (i % 5 + 1));
                event.setDate(baseDate.plusDays(i)); // Fechas futuras
                event.setLocation("Sala " + (i % 10 + 1)); // 10 localizaciones diferentes

                // Asignar una categoría
                if (i % 3 == 0) {
                    event.setCategory(conferencia);
                } else if (i % 3 == 1) {
                    event.setCategory(taller);
                } else {
                    event.setCategory(webinar);
                }

                // Asignar al menos un orador
                if (i % 2 == 0) {
                    event.addSpeaker(john); // Usamos el método addSpeaker para manejar la relación
                } else {
                    event.addSpeaker(jane);
                }
                // Si quieres que algunos tengan ambos oradores:
                if (i % 5 == 0) {
                    event.addSpeaker(john);
                    event.addSpeaker(jane);
                }


                events.add(event);
            }

            eventRepository.saveAll(events);
            System.out.println("Cargados " + events.size() + " eventos de prueba en la base de datos.");
        }
    }
}
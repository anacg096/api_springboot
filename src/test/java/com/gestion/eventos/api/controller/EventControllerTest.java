package com.gestion.eventos.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gestion.eventos.api.domain.Category;
import com.gestion.eventos.api.domain.Event;
import com.gestion.eventos.api.domain.Speaker;
import com.gestion.eventos.api.dto.EventRequestDto;
import com.gestion.eventos.api.dto.EventResponseDto;
import com.gestion.eventos.api.dto.SpeakerResponseDto;
import com.gestion.eventos.api.exception.ResourceNotFoundException;
import com.gestion.eventos.api.mapper.EventMapper;
import com.gestion.eventos.api.security.jwt.JwtAuthEntryPoint;
import com.gestion.eventos.api.security.jwt.JwtAuthenticationFilter;
import com.gestion.eventos.api.security.jwt.JwtGenerator;
import com.gestion.eventos.api.service.EventService;

@WebMvcTest(
        controllers = EventController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class // Excluye la configuración de seguridad principal y UserDetailsService
        },
        // Añadimos excludeFilters para evitar que Spring escanee y cree tus beans de seguridad específicos
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                JwtAuthenticationFilter.class,
                JwtGenerator.class,
                JwtAuthEntryPoint.class // Si JwtAuthEntryPoint también es un @Component y causa problemas
        })
)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private EventService eventService;
    private EventMapper eventMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private EventResponseDto eventResponseDto;
    private Event eventEntity;

    @TestConfiguration
    static class EventControllerTestConfig{
        @Bean
        ObjectMapper objectMapper(){
                return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        @Primary
        EventService eventService(){
            return mock(EventService.class);
        }

        @Bean
        @Primary
        EventMapper eventMapper(){
            return mock(EventMapper.class);
        }
    }

    @BeforeEach
    void setUp(@Autowired EventService eventServiceMock, @Autowired EventMapper eventMapperMock){
        this.eventService = eventServiceMock;
        this.eventMapper = eventMapperMock;

        reset(eventService, eventMapper);

        Category category = new Category(10L, "Tecnología", "Eventos tecnológicos");
        Speaker speaker1 = new Speaker(20L, "Juan Pérez", "juan.perez@example.com", "Experto en Spring Boot.", new HashSet<>());
        Speaker speaker2 = new Speaker(21L, "María García", "maria.garcia@example.com", "Arquitecta de software.", new HashSet<>());

        eventEntity = new Event();
        eventEntity.setId(1L);
        eventEntity.setName("Conferencia Tech");
        eventEntity.setDate(LocalDate.of(2024, 11, 15));
        eventEntity.setLocation("Online");
        eventEntity.setCategory(category);
        eventEntity.addSpeaker(speaker1);
        eventEntity.addSpeaker(speaker2);

        eventResponseDto = new EventResponseDto();
        eventResponseDto.setId(1L);
        eventResponseDto.setName("Conferencia Tech");
        eventResponseDto.setDate(LocalDate.of(2024, 11, 15));
        eventResponseDto.setLocation("Online");
        eventResponseDto.setCategoryName("Tecnología");
        eventResponseDto.setCategoryId(10L);

        // DTOs de Speakers para el EventResponseDto
        SpeakerResponseDto speakerResponse1 = new SpeakerResponseDto(20L, "Juan Pérez", "juan.perez@example.com", "Experto en Spring Boot.");
        SpeakerResponseDto speakerResponse2 = new SpeakerResponseDto(21L, "María García", "maria.garcia@example.com", "Arquitecta de software.");

        Set<SpeakerResponseDto> speakersDto = new HashSet<>();
        speakersDto.add(speakerResponse1);
        speakersDto.add(speakerResponse2);
        eventResponseDto.setSpeakers(speakersDto);

    }

    @Test
    @DisplayName("GET /api/v1/events/{id} - Debe retornar un evento por ID cuando existe")
    @WithMockUser(username = "testUser", roles = "USER")
    void shouldReturnEventById() throws Exception{

        //Preparación
        when(eventService.findById(anyLong())).thenReturn(eventEntity);

        when(eventMapper.toResponseDto(any(Event.class))).thenReturn(eventResponseDto);

        //Ejecución
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/events/{id}", 1L)

                        .accept(MediaType.APPLICATION_JSON)

                )
                //Verificación

                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Conferencia Tech"))
                .andExpect(jsonPath("$.location").value("Online"))
                .andExpect(jsonPath("$.categoryName").value("Tecnología"))

                .andExpect(jsonPath("$.speakers.length()").value(2))
                .andExpect(jsonPath("$.speakers[2]").doesNotExist())

                // --- Verificación completa de Juan Pérez (sin asumir si es [0] o [1]) ---
                .andExpect(jsonPath("$.speakers[?(@.name == 'Juan Pérez')].name").value("Juan Pérez"))
                .andExpect(jsonPath("$.speakers[?(@.name == 'Juan Pérez')].email").value("juan.perez@example.com"))
                .andExpect(jsonPath("$.speakers[?(@.name == 'Juan Pérez')].bio").value("Experto en Spring Boot."))

                // --- Verificación completa de María García (sin asumir si es [0] o [1]) ---
                .andExpect(jsonPath("$.speakers[?(@.name == 'María García')].name").value("María García"))
                .andExpect(jsonPath("$.speakers[?(@.name == 'María García')].email").value("maria.garcia@example.com"))
                .andExpect(jsonPath("$.speakers[?(@.name == 'María García')].bio").value("Arquitecta de software."));

        verify(eventService, times(1)).findById(1L);
        verify(eventMapper, times(1)).toResponseDto(eventEntity);

    }

    @Test
    @DisplayName("GET /api/v1/events/{id} - Debe retornar 404 Not Found cuando el evento no existe")
    @WithMockUser(username = "testUser", roles = "USER")
    void shouldReturnNotFoundWhenEventDoesNotExist() throws Exception{
        when(eventService.findById(anyLong())).thenThrow(
                new ResourceNotFoundException("Evento no encontrado con id: 99")
        );

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/events/{id}", 99L) // Un ID que sabemos que no existe
                        .accept(MediaType.APPLICATION_JSON))

                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Evento no encontrado con id: 99"));

        verify(eventService, times(1)).findById(99L);
        verify(eventMapper, never()).toResponseDto(any(Event.class));
    }

    @Test
    @DisplayName("GET /api/v1/events - Debe retornar todos los eventos paginados y filtrados")
    @WithMockUser(username = "testUser", roles = "USER")
    void shouldReturnAllEventsPagedAndFiltered() throws Exception{

        //Preparación
        SpeakerResponseDto speakerResponseA = new SpeakerResponseDto(20L, "Juan Pérez", "juan.perez@example.com", "Experto en Spring Boot.");
        Set<SpeakerResponseDto> speakersA = new HashSet<>(Set.of(speakerResponseA));

        EventResponseDto eventResponse2 = new EventResponseDto();
        eventResponse2.setId(2L);
        eventResponse2.setName("Webinar de Spring Security");
        eventResponse2.setDate(LocalDate.of(2024, 12, 1));
        eventResponse2.setLocation("Virtual");
        eventResponse2.setCategoryName("Tecnología");
        eventResponse2.setCategoryId(10L);
        eventResponse2.setSpeakers(speakersA); // Asigna los speakers

        EventResponseDto eventResponse3 = new EventResponseDto();
        eventResponse3.setId(3L);
        eventResponse3.setName("Conferencia Cloud Nativo");
        eventResponse3.setDate(LocalDate.of(2025, 1, 20));
        eventResponse3.setLocation("Auditorio Principal");
        eventResponse3.setCategoryName("Tecnología");
        eventResponse3.setCategoryId(10L);
        eventResponse3.setSpeakers(speakersA); // Asigna los mismos speakers

        List<EventResponseDto> eventResponseList = List.of(eventResponse2, eventResponse3);

        Pageable pageableMock = PageRequest.of(0, 10);

        Page<EventResponseDto> eventResponseDtoPage = new PageImpl<>(eventResponseList,
                pageableMock, eventResponseList.size());

        when(eventService.findAll(eq("Spring"), any(Pageable.class))).thenReturn(eventResponseDtoPage);

        //Ejecución
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/events")
                        .param("page", "0")
                        .param("size", "10")
                        .param("name", "Spring")
                        .accept(MediaType.APPLICATION_JSON)
                )
                //Verificación
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0]").exists())
                .andExpect(jsonPath("$.content[1]").exists())
                .andExpect(jsonPath("$.content[2]").doesNotExist())

                // Verificar los datos del primer evento en la página
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Webinar de Spring Security"))
                .andExpect(jsonPath("$.content[0].date").value("2024-12-01"))
                .andExpect(jsonPath("$.content[0].location").value("Virtual"))
                .andExpect(jsonPath("$.content[0].categoryName").value("Tecnología"))
                .andExpect(jsonPath("$.content[0].speakers.length()").value(1))
                .andExpect(jsonPath("$.content[0].speakers[?(@.name == 'Juan Pérez')].id").value(20))
                .andExpect(jsonPath("$.content[0].speakers[?(@.name == 'Juan Pérez')].name").value("Juan Pérez"))
                .andExpect(jsonPath("$.content[0].speakers[?(@.name == 'Juan Pérez')].email").value("juan.perez@example.com"))
                .andExpect(jsonPath("$.content[0].speakers[?(@.name == 'Juan Pérez')].bio").value("Experto en Spring Boot."))

                // Verificar los datos del segundo evento en la página
                .andExpect(jsonPath("$.content[1].id").value(3))
                .andExpect(jsonPath("$.content[1].name").value("Conferencia Cloud Nativo"))
                .andExpect(jsonPath("$.content[1].date").value("2025-01-20"))
                .andExpect(jsonPath("$.content[1].location").value("Auditorio Principal"))
                .andExpect(jsonPath("$.content[1].categoryName").value("Tecnología"))
                .andExpect(jsonPath("$.content[1].speakers.length()").value(1))
                .andExpect(jsonPath("$.content[1].speakers[?(@.name == 'Juan Pérez')].id").value(20))
                .andExpect(jsonPath("$.content[1].speakers[?(@.name == 'Juan Pérez')].name").value("Juan Pérez"))
                .andExpect(jsonPath("$.content[1].speakers[?(@.name == 'Juan Pérez')].email").value("juan.perez@example.com"))
                .andExpect(jsonPath("$.content[1].speakers[?(@.name == 'Juan Pérez')].bio").value("Experto en Spring Boot."))

                .andExpect(jsonPath("$.pageable.pageNumber").value(0))
                .andExpect(jsonPath("$.pageable.pageSize").value(10))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));

        verify(eventService, times(1)).findAll(eq("Spring"), any(Pageable.class));
        //test defensivo
        verify(eventService, never()).findById(anyLong()); // Explícito pero redundante
    }

    @Test
    @DisplayName("POST /api/v1/events - Debe crear un evento y retornar 201 Created")
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    void shouldCreateEventSuccessfully() throws Exception {
        // Arrange (Preparación)

        // 1. Crear el DTO de entrada para la petición POST
        EventRequestDto eventRequestDto = new EventRequestDto();
        eventRequestDto.setName("Taller de Microservicios");
        eventRequestDto.setDate(LocalDate.of(2025, 3, 10));
        eventRequestDto.setLocation("Centro de Convenciones");
        eventRequestDto.setCategoryId(10L); // ID de la categoría (ej. Tecnología)
        eventRequestDto.setSpeakersIds(Set.of(20L, 21L)); // IDs de speakers (Juan Pérez, María García)


        // --- Datos para el Event que el servicio MOCK debería devolver ---
        // Este Event es lo que eventService.save() retornaría *antes* de ser mapeado.
        Event savedEventEntity = new Event();
        savedEventEntity.setId(5L); // El nuevo ID asignado
        savedEventEntity.setName("Taller de Microservicios");
        savedEventEntity.setDate(LocalDate.of(2025, 3, 10));
        savedEventEntity.setLocation("Centro de Convenciones");
        // Para el Event, necesitamos objetos Category y Speaker reales (o mocks si no tuvieras los comunes)
        Category categoryForSavedEvent = new Category(10L, "Tecnología", "Eventos tecnológicos");
        Speaker speaker1ForSavedEvent = new Speaker(20L, "Juan Pérez", "juan.perez@example.com", "Experto en Spring Boot.", new HashSet<>());
        Speaker speaker2ForSavedEvent = new Speaker(21L, "María García", "maria.garcia@example.com", "Arquitecta de software.", new HashSet<>());
        savedEventEntity.setCategory(categoryForSavedEvent);
        savedEventEntity.addSpeaker(speaker1ForSavedEvent);
        savedEventEntity.addSpeaker(speaker2ForSavedEvent);


        // --- Datos para el EventResponseDto que el mapper MOCK debería devolver ---
        // Este es el resultado final que el controlador enviará.
        EventResponseDto createdEventResponseDto = new EventResponseDto();
        createdEventResponseDto.setId(5L);
        createdEventResponseDto.setName("Taller de Microservicios");
        createdEventResponseDto.setDate(LocalDate.of(2025, 3, 10));
        createdEventResponseDto.setLocation("Centro de Convenciones");
        createdEventResponseDto.setCategoryName("Tecnología");
        createdEventResponseDto.setCategoryId(10L);

        SpeakerResponseDto speakerResponse1 = new SpeakerResponseDto(20L, "Juan Pérez", "juan.perez@example.com", "Experto en Spring Boot.");
        SpeakerResponseDto speakerResponse2 = new SpeakerResponseDto(21L, "María García", "maria.garcia@example.com", "Arquitecta de software.");
        Set<SpeakerResponseDto> speakersDto = new HashSet<>();
        speakersDto.add(speakerResponse1);
        speakersDto.add(speakerResponse2);
        createdEventResponseDto.setSpeakers(speakersDto);

        // 3. Configurar el comportamiento de los mocks
        // Paso 1: eventService.save() es llamado y devuelve un Event
        when(eventService.save(any(EventRequestDto.class))).thenReturn(savedEventEntity);
        // Paso 2: eventMapper.toResponseDto() es llamado con el Event devuelto y devuelve un EventResponseDto
        when(eventMapper.toResponseDto(savedEventEntity)).thenReturn(createdEventResponseDto);

        mockMvc.perform(post("/api/v1/events") // Endpoint para crear
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(eventRequestDto)))

                // Assert (Verificación):
                .andExpect(status().isCreated()) // Esperamos un 201 Created
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Taller de Microservicios"))
                .andExpect(jsonPath("$.date").value("2025-03-10"))
                .andExpect(jsonPath("$.location").value("Centro de Convenciones"))
                .andExpect(jsonPath("$.categoryName").value("Tecnología"))
                .andExpect(jsonPath("$.categoryId").value(10))
                .andExpect(jsonPath("$.speakers.length()").value(2))
                .andExpect(jsonPath("$.speakers[?(@.name == 'Juan Pérez')].id").value(20))
                .andExpect(jsonPath("$.speakers[?(@.name == 'María García')].id").value(21));

        // 4. Verificación de interacciones con mocks:
        verify(eventService, times(1)).save(any(EventRequestDto.class));
        verify(eventMapper, times(1)).toResponseDto(savedEventEntity); // Se verifica que el mapper fue llamado con el Event correcto

        // Verificaciones defensivas (que no se llamen otros métodos)
        verify(eventService, never()).findAll(anyString(), any(Pageable.class));
        verify(eventService, never()).findById(anyLong());
    }














    @Test
    @DisplayName("PUT /api/v1/events/{id} - Debe actualizar un evento existente y retornar 200 OK")
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    void shouldUpdateEventSuccessfully() throws Exception {

        final Long eventIdToUpdate = 1L; // El ID del evento que vamos a actualizar

        // 1. Crear el DTO de entrada para la petición PUT (nuevos datos para el evento)
        EventRequestDto updateEventRequestDto = new EventRequestDto();
        updateEventRequestDto.setName("Conferencia Tech v2.0 (Actualizada)");
        updateEventRequestDto.setDate(LocalDate.of(2025, 1, 15)); // Nueva fecha
        updateEventRequestDto.setLocation("Centro de Convenciones Principal"); // Nueva ubicación
        updateEventRequestDto.setCategoryId(11L); // Nueva categoría (ej. "Desarrollo de Software")
        updateEventRequestDto.setSpeakersIds(Set.of(22L)); // Nuevos speakers (ej. solo uno)

        // --- Datos del Event que el servicio MOCK debería devolver después de la actualización ---
        // Este Event simula el estado del evento después de que eventService.update() lo haya procesado.
        Event updatedEventEntity = new Event();
        updatedEventEntity.setId(eventIdToUpdate);
        updatedEventEntity.setName("Conferencia Tech v2.0 (Actualizada)");
        updatedEventEntity.setDate(LocalDate.of(2025, 1, 15));
        updatedEventEntity.setLocation("Centro de Convenciones Principal");
        // Necesitamos la Category y el Speaker reales (o mocks) que corresponden a los nuevos IDs
        Category newCategory = new Category(11L, "Desarrollo de Software", "Eventos de desarrollo");
        Speaker newSpeaker = new Speaker(22L, "Carlos López", "carlos.lopez@example.com", "Experto en Frontend.", new HashSet<>());
        updatedEventEntity.setCategory(newCategory);
        updatedEventEntity.addSpeaker(newSpeaker);


        // --- Datos para el EventResponseDto que el mapper MOCK debería devolver ---
        // Este es el resultado final que el controlador enviará como respuesta 200 OK.
        EventResponseDto updatedEventResponseDto = new EventResponseDto();
        updatedEventResponseDto.setId(eventIdToUpdate);
        updatedEventResponseDto.setName("Conferencia Tech v2.0 (Actualizada)");
        updatedEventResponseDto.setDate(LocalDate.of(2025, 1, 15));
        updatedEventResponseDto.setLocation("Centro de Convenciones Principal");
        updatedEventResponseDto.setCategoryName("Desarrollo de Software");
        updatedEventResponseDto.setCategoryId(11L);
        updatedEventResponseDto.setSpeakers(Set.of(new SpeakerResponseDto(22L, "Carlos López", "carlos.lopez@example.com", "Experto en Frontend.")));

        // 2. Configurar el comportamiento de los mocks
        // Paso 1: eventService.update() es llamado y devuelve el Event actualizado
        when(eventService.update(eq(eventIdToUpdate), any(EventRequestDto.class))).thenReturn(updatedEventEntity);
        // Paso 2: eventMapper.toResponseDto() es llamado con el Event actualizado y devuelve el EventResponseDto
        when(eventMapper.toResponseDto(updatedEventEntity)).thenReturn(updatedEventResponseDto);

        mockMvc.perform(put("/api/v1/events/{id}", eventIdToUpdate) // Endpoint para actualizar con el ID
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateEventRequestDto)))// El DTO con los nuevos datos


                // Assert (Verificación):
                .andExpect(status().isOk()) // Esperamos un 200 OK para actualización exitosa
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(eventIdToUpdate)) // El ID sigue siendo el mismo
                .andExpect(jsonPath("$.name").value("Conferencia Tech v2.0 (Actualizada)"))
                .andExpect(jsonPath("$.date").value("2025-01-15"))
                .andExpect(jsonPath("$.location").value("Centro de Convenciones Principal"))
                .andExpect(jsonPath("$.categoryName").value("Desarrollo de Software"))
                .andExpect(jsonPath("$.categoryId").value(11))
                .andExpect(jsonPath("$.speakers.length()").value(1))
                .andExpect(jsonPath("$.speakers[0].id").value(22))
                .andExpect(jsonPath("$.speakers[0].name").value("Carlos López"));


        // 4. Verificación de interacciones con mocks:
        // Aseguramos que el método update del servicio fue llamado exactamente una vez con el ID y cualquier EventRequestDto.
        verify(eventService, times(1)).update(eq(eventIdToUpdate), any(EventRequestDto.class));
        verify(eventMapper, times(1)).toResponseDto(updatedEventEntity); // Aseguramos que el mapper fue llamado con el Event actualizado

        // Verificaciones defensivas (que no se llamen otros métodos)
        verify(eventService, never()).save(any(EventRequestDto.class)); // No debería llamar a save al actualizar
        verify(eventService, never()).findAll(anyString(), any(Pageable.class));
        verify(eventService, never()).findById(anyLong()); // El findById se hace internamente en eventService.update, pero no lo llamamos directamente aquí del controller

    }

    @Test
    @DisplayName("DELETE /api/v1/events/{id} - Debe eliminar un evento y retornar 204 No Content")
    @WithMockUser(username = "adminUser", roles = "ADMIN") // Asumiendo rol ADMIN para eliminar
    void shouldDeleteEventSuccessfully() throws Exception {

        final Long eventIdToDelete = 1L;

        doNothing().when(eventService).deleteById(eventIdToDelete);

        mockMvc.perform(delete("/api/v1/events/{id}", eventIdToDelete))

                .andExpect(status().isNoContent());

        verify(eventService, times(1)).deleteById(eventIdToDelete);
        verify(eventMapper, never()).toResponseDto(any(Event.class));
    }

    @Test
    @DisplayName("DELETE /api/v1/events/{id} - Debe retornar 404 Not Found si el evento a eliminar no existe")
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    void shouldReturnNotFoundWhenDeletingNonExistentEvent() throws Exception {
        final Long nonExistentEventId = 999L;

        doThrow(new ResourceNotFoundException("Evento no encontrado con ID: " + nonExistentEventId))
                .when(eventService).deleteById(nonExistentEventId);

        mockMvc.perform(delete("/api/v1/events/{id}", nonExistentEventId))

                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Evento no encontrado con ID: " + nonExistentEventId));

        verify(eventService, times(1)).deleteById(nonExistentEventId);
        verify(eventMapper, never()).toResponseDto(any(Event.class));
    }










}
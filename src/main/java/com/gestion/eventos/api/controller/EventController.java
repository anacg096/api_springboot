package com.gestion.eventos.api.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gestion.eventos.api.domain.Event;
import com.gestion.eventos.api.dto.EventRequestDto;
import com.gestion.eventos.api.dto.EventResponseDto;
import com.gestion.eventos.api.mapper.EventMapper;
import com.gestion.eventos.api.service.IEventService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Eventos", description = "Operaciones relacionadas con la gestión de eventos")
public class EventController {

    private final IEventService eventService;
    private static final Logger logger = LoggerFactory.getLogger(EventController.class);
    private final EventMapper eventMapper;

    @GetMapping("/problematic")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Obtener todos los eventos con carga problemática de detalles",
               description = "Devuelve una lista de todos los eventos, cargando sus detalles de una manera que puede ser ineficiente (problema N+1).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de eventos obtenida exitosamente"),
            @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    public ResponseEntity<List<Event>> getAllEventsProblematic(){
        logger.info("Recibida solicitud GET /problematic para todos los eventos.");
        List<Event> events = eventService.getAllEventsAndTheirDetailsProblematic();
        logger.debug("Devolviendo {} eventos desde /problematic.", events.size());
        return ResponseEntity.ok(events);
    }

    @GetMapping("/optimized-join-fetch")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Obtener todos los eventos con optimización Join Fetch",
               description = "Devuelve una lista de todos los eventos, cargando sus detalles de forma optimizada utilizando Join Fetch.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de eventos obtenida exitosamente"),
            @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    public ResponseEntity<List<Event>> getAllEventsOptimizedWithJoinFetch(){
        logger.info("Recibida solicitud GET /optimized-join-fetch.");
        List<Event> events = eventService.getAllEventsAndTheirDetailsOptimizedWithJoinFetch();
        logger.debug("Devolviendo {} eventos desde /optimized-join-fetch.", events.size());
        return ResponseEntity.ok(events);
    }

    @GetMapping("/optimized/all-details")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Obtener todos los eventos con todos los detalles optimizados",
               description = "Devuelve una lista de todos los eventos con todos sus detalles relacionados, cargados de forma eficiente.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de eventos obtenida exitosamente"),
            @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    public ResponseEntity<List<Event>> getAllEventsWithAllDetails() {
        logger.info("Recibida solicitud GET /optimized/all-details.");
        List<Event> events = eventService.findAllEventsWithAllDetailsOptimized();
        logger.debug("Devolviendo {} eventos desde /optimized/all-details.", events.size());
        return ResponseEntity.ok(events);
    }



    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Obtener todos los eventos paginados y filtrados",
               description = "Devuelve una página de eventos. Se puede filtrar por nombre y se admite paginación.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Página de eventos obtenida exitosamente"),
            @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    public ResponseEntity<Page<EventResponseDto>> getAllEvents(
            @RequestParam(required = false) String name,
            @PageableDefault(page = 0, size = 10, sort = "name") Pageable pageable
    ){
        logger.info("Recibida solicitud GET /events con nombre '{}' y paginación {}.", name, pageable);
        Page<EventResponseDto> events = eventService.findAll(name, pageable);
        logger.debug("Devolviendo {} eventos paginados.", events.getTotalElements());
        return ResponseEntity.ok(events);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN')")
    @Operation(summary = "Crear un nuevo evento",
               description = "Permite a un administrador crear un nuevo evento en el sistema.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Evento creado exitosamente"),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida"),
            @ApiResponse(responseCode = "403", description = "Acceso denegado"),
            @ApiResponse(responseCode = "500", description = "Error interno del servidor")
    })
    public ResponseEntity<EventResponseDto> createEvent(@Valid @RequestBody EventRequestDto requestDto){
        logger.info("Recibida solicitud POST para crear evento: {}", requestDto.getName());
        Event eventSaved = eventService.save(requestDto);
        EventResponseDto responseDto = eventMapper.toResponseDto(eventSaved);
        logger.debug("Evento creado exitosamente con ID: {}", eventSaved.getId()); // Usamos ID para ser más preciso
        return new ResponseEntity<>(responseDto, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Obtener un evento por su ID", description = "Devuelve los detalles de un evento específico por su ID.")
    @ApiResponses(
            value = {
                    @ApiResponse(responseCode = "200", description = "Evento encontrado exitosamente"),
                    @ApiResponse(responseCode = "404", description = "Evento no encontrado"),
                    @ApiResponse(responseCode = "403", description = "Acceso denegado")
            }
    )
    public ResponseEntity<EventResponseDto> getEventById(@PathVariable Long id){
        logger.info("Recibida solicitud GET /events/{} para buscar evento.", id);
        Event event = eventService.findById(id); // Si no lo encuentra, IEventService lanzará ResourceNotFoundException
        EventResponseDto responseDto = eventMapper.toResponseDto(event);
        logger.debug("Evento con ID {} encontrado y mapeado.", id);
        return ResponseEntity.ok(responseDto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @Operation(summary = "Actualizar evento por ID",
               description = "Actualiza la información de un evento existente por su ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Evento actualizado exitosamente"),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida"),
            @ApiResponse(responseCode = "403", description = "Acceso denegado"),
            @ApiResponse(responseCode = "404", description = "Evento no encontrado"),
            @ApiResponse(responseCode = "500", description = "Error interno del servidor")
    })
    public ResponseEntity<EventResponseDto> updateEvent( @PathVariable Long id,
                                                        @Valid @RequestBody EventRequestDto requestDto
    ){
        logger.info("Recibida solicitud PUT /events/{} para actualizar evento: {}", id, requestDto.getName());
        Event updateEvent = eventService.update(id, requestDto); // Si falla, GlobalExceptionHandler lo captura
        logger.debug("Evento con ID {} actualizado exitosamente.", id);
        return ResponseEntity.ok(eventMapper.toResponseDto(updateEvent));
    }


    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @Operation(summary = "Eliminar evento por ID",
               description = "Elimina un evento del sistema por su ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Evento eliminado exitosamente"),
            @ApiResponse(responseCode = "403", description = "Acceso denegado"),
            @ApiResponse(responseCode = "404", description = "Evento no encontrado"),
            @ApiResponse(responseCode = "500", description = "Error interno del servidor")
    })
    public ResponseEntity<Void> deleteEvent(@PathVariable Long id){
        logger.info("Recibida solicitud DELETE /events/{} para eliminar evento.", id);
        eventService.deleteById(id); // Si falla, GlobalExceptionHandler lo captura
        logger.debug("Evento con ID {} eliminado exitosamente.", id);
        return ResponseEntity.noContent().build();
    }
}
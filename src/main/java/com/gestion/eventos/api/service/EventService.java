package com.gestion.eventos.api.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory; // Importar LoggerFactory
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestion.eventos.api.domain.Category;
import com.gestion.eventos.api.domain.Event;
import com.gestion.eventos.api.domain.Speaker;
import com.gestion.eventos.api.domain.User;
import com.gestion.eventos.api.dto.EventRequestDto;
import com.gestion.eventos.api.dto.EventResponseDto;
import com.gestion.eventos.api.exception.ResourceNotFoundException;
import com.gestion.eventos.api.mapper.EventMapper;
import com.gestion.eventos.api.repository.EventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EventService implements IEventService {

    private static final Logger logger = LoggerFactory.getLogger(EventService.class); // Añadir Logger
    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final CategoryService categoryService;
    private final SpeakerService speakerService;

    @Override
    @Transactional(readOnly = true)
    public Page<EventResponseDto> findAll(String name, Pageable pageable) {
        logger.debug("Buscando eventos en el servicio (name: '{}', pageable: {}).", name, pageable);
        Page<Event> eventsPage;

        if(name!=null && !name.trim().isEmpty()){
            eventsPage = eventRepository.findByNameContainingIgnoreCase(name, pageable);
            logger.debug("Filtrando eventos por nombre: '{}'. Encontrados: {}.", name, eventsPage.getTotalElements());
        }else {
            eventsPage = eventRepository.findAll(pageable);
            logger.debug("Buscando todos los eventos sin filtro. Encontrados: {}.", eventsPage.getTotalElements());
        }

        List<EventResponseDto> dtos = eventsPage.getContent().stream()
                .map(eventMapper::toResponseDto)
                .toList();

        logger.info("Encontrados {} eventos paginados y mapeados a DTOs.", eventsPage.getTotalElements());
        return new PageImpl<>(dtos, pageable, eventsPage.getTotalElements());
    }

    @Override
    @Transactional
    public Event save(EventRequestDto requestDto) {
        logger.debug("Procesando save de evento en el servicio para: {}", requestDto.getName());
        Event event = eventMapper.toEntity(requestDto);

        logger.debug("Asignando categoría con ID: {}", requestDto.getCategoryId());
        Category category = categoryService.findById(requestDto.getCategoryId());
        event.setCategory(category);

        if(requestDto.getSpeakersIds() !=null && !requestDto.getSpeakersIds().isEmpty()){
            logger.debug("Asignando {} oradores al evento.", requestDto.getSpeakersIds().size());
            Set<Speaker> speakers = requestDto.getSpeakersIds().stream()
                    .map(speakerService::findById)
                    .collect(Collectors.toSet());
            speakers.forEach(event::addSpeaker);
        } else {
            logger.debug("No se especificaron oradores para el evento.");
        }

        Event savedEvent = eventRepository.save(event);
        logger.info("Evento '{}' guardado en DB con ID: {}.", savedEvent.getName(), savedEvent.getId());
        return savedEvent;
    }

    @Override
    @Transactional(readOnly = true)
    public Event findById(Long id) {
        logger.debug("Buscando evento en el repositorio por ID: {}.", id);
        return eventRepository.findById(id).orElseThrow(
                () -> {
                    logger.warn("Evento con ID {} no encontrado en el servicio, lanzando ResourceNotFoundException.", id);
                    return new ResourceNotFoundException("Evento no encontrado con id: " + id);
                }
        );
    }

    @Override
    @Transactional
    public Event update(Long id, EventRequestDto requestDto) {
        logger.debug("Iniciando actualización de evento con ID {} en el servicio.", id);
        Event existingEvent = eventRepository.findById(id)
                .orElseThrow(
                        () -> {
                            logger.warn("Intento de actualizar evento con ID {} que no existe, lanzando ResourceNotFoundException.", id);
                            return new ResourceNotFoundException("Evento no encontrado con ID: " + id);
                        }
                );
        logger.debug("Evento existente con ID {} encontrado. Mapeando DTO a entidad.", id);
        eventMapper.updateEventFromDto(requestDto, existingEvent);

        if(!existingEvent.getCategory().getId().equals(requestDto.getCategoryId())){
            logger.debug("Cambiando categoría del evento de ID {} a ID {}.", existingEvent.getCategory().getId(), requestDto.getCategoryId());
            Category category = categoryService.findById(requestDto.getCategoryId());
            existingEvent.setCategory(category);
        }

        Set<Speaker> updatedSpeakers;
        if(requestDto.getSpeakersIds() !=null && !requestDto.getSpeakersIds().isEmpty()){
            logger.debug("Procesando {} oradores para la actualización del evento.", requestDto.getSpeakersIds().size());
            updatedSpeakers = requestDto.getSpeakersIds().stream()
                    .map(speakerService::findById)
                    .collect(Collectors.toSet());
        } else {
            updatedSpeakers = new HashSet<>();
            logger.debug("No se especificaron oradores para la actualización, se eliminarán los existentes si los hay.");
        }

        // Eliminar oradores que ya no están en la lista de actualización
        new HashSet<>(existingEvent.getSpeakers())
                .forEach(currentSpeaker -> {
                    if(!updatedSpeakers.contains(currentSpeaker)){
                        existingEvent.removeSpeaker(currentSpeaker);
                        logger.debug("Eliminando orador '{}' (ID: {}) del evento.", currentSpeaker.getName(), currentSpeaker.getId());
                    }
                });

        // Añadir nuevos oradores
        updatedSpeakers.forEach(newSpeaker -> {
            if(!existingEvent.getSpeakers().contains(newSpeaker)){
                existingEvent.addSpeaker(newSpeaker);
                logger.debug("Añadiendo orador '{}' (ID: {}) al evento.", newSpeaker.getName(), newSpeaker.getId());
            }
        });

        Event updatedEvent = eventRepository.save(existingEvent);
        logger.info("Evento con ID {} actualizado en la base de datos.", id);
        return updatedEvent;
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        logger.debug("Solicitud de eliminación para evento con ID {} en el servicio.", id);
        Event eventToDelete = this.findById(id); // findById ya lanzará ResourceNotFoundException si no existe
        eventRepository.delete(eventToDelete);
        logger.info("Evento con ID {} eliminado de la base de datos.", id);
    }

    @Transactional(readOnly = true)
    public List<Event> getAllEventsAndTheirDetailsProblematic(){
        logger.debug("Iniciando getAllEventsAndTheirDetailsProblematic en el servicio.");
        List<Event> events = eventRepository.findAll();

        events.forEach( event ->{
            logger.debug("Procesando acceso tardío para Evento ID: {}", event.getId());
            event.getSpeakers().size();
            event.getSpeakers().stream().map(Speaker::getName).collect(Collectors.toSet());
            event.getCategory().getName();
            event.getAttendedUsers().size();
        });
        logger.debug("Finalizado procesamiento problématico para {} eventos.", events.size());
        return events;
    }

    @Transactional(readOnly = true)
    public List<Event> getAllEventsAndTheirDetailsOptimizedWithJoinFetch(){
        logger.debug("Iniciando getAllEventsAndTheirDetailsOptimizedWithJoinFetch en el servicio.");
        List<Event> events = eventRepository.findAllWithCategoryAndSpeakers();

        events.forEach( event ->{
            logger.debug("Detalles de evento (Join Fetch) - ID: {}, Nombre: {}, Categoría: {}, Oradores: {}",
                    event.getId(),
                    event.getName(),
                    event.getCategory().getName(),
                    event.getSpeakers().stream().map(Speaker::getName).collect(Collectors.joining(", "))
            );
            // Estos System.out.println() probablemente no deberían ir en un servicio real, pero los mantengo por tu demo
            System.out.println("Event: " + event.getName());
            System.out.println("Category: " + event.getCategory().getName());
            System.out.println("Speakers: " + event.getSpeakers()
                    .stream().map(Speaker::getName)
                    .collect(Collectors.joining(", "))
            );
        });
        logger.debug("Finalizado getAllEventsAndTheirDetailsOptimizedWithJoinFetch para {} eventos.", events.size());
        return events;
    }

    @Transactional(readOnly = true)
    public List<Event> findAllEventsWithAllDetailsOptimized() {
        logger.debug("Iniciando findAllEventsWithAllDetailsOptimized (@EntityGraph) en el servicio.");
        System.out.println("\n--- DEMO: findAllWithAllDetails (@EntityGraph con Category, Speakers, AttendedUsers) ---"); // Mantengo el System.out por tu demo
        List<Event> events = eventRepository.findAllWithAllDetails();

        events.forEach(event -> {
            logger.debug("Detalles de evento (EntityGraph) - ID: {}, Nombre: {}, Categoría: {}, Oradores: {}, Usuarios Asistentes: {}",
                    event.getId(),
                    event.getName(),
                    event.getCategory() != null ? event.getCategory().getName() : "N/A",
                    event.getSpeakers() != null && !event.getSpeakers().isEmpty() ? event.getSpeakers().stream().map(Speaker::getName).collect(Collectors.joining(", ")) : "N/A",
                    event.getAttendedUsers() != null && !event.getAttendedUsers().isEmpty() ? event.getAttendedUsers().stream().map(User::getUsername).collect(Collectors.joining(", ")) : "N/A"
            );
            // Estos System.out.println() probablemente no deberían ir en un servicio real, pero los mantengo por tu demo
            System.out.println("Event: " + event.getName());
            if (event.getCategory() != null) {
                System.out.println("  Category: " + event.getCategory().getName());
            }
            if (event.getSpeakers() != null && !event.getSpeakers().isEmpty()) {
                System.out.println("  Speakers: " + event.getSpeakers().stream().map(Speaker::getName).collect(Collectors.joining(", ")));
            }
            if (event.getAttendedUsers() != null && !event.getAttendedUsers().isEmpty()) {
                System.out.println("  Attended Users: " + event.getAttendedUsers().stream().map(User::getUsername).collect(Collectors.joining(", ")));
            }
        });
        logger.debug("Finalizado findAllEventsWithAllDetailsOptimized para {} eventos.", events.size());
        return events;
    }




}


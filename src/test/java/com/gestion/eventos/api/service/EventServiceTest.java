package com.gestion.eventos.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.gestion.eventos.api.domain.Category;
import com.gestion.eventos.api.domain.Event;
import com.gestion.eventos.api.domain.Speaker;
import com.gestion.eventos.api.dto.EventRequestDto;
import com.gestion.eventos.api.dto.EventResponseDto;
import com.gestion.eventos.api.exception.ResourceNotFoundException;
import com.gestion.eventos.api.mapper.EventMapper;
import com.gestion.eventos.api.repository.EventRepository;

@ExtendWith(MockitoExtension.class)
public class EventServiceTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private CategoryService categoryService;
    @Mock
    private  SpeakerService speakerService;

    @InjectMocks
    private EventService eventService;

    private Event event;
    private EventRequestDto eventRequestDto;
    private EventResponseDto eventResponseDto;
    private Category category;
    private Speaker speaker1;
    private Speaker speaker2;
    private Pageable pageable;

    @BeforeEach
    void setUp(){
         // Inicializar datos de prueba
        category = new Category(1L, "Conferencia", "Descripción de conferencia");
        speaker1 = new Speaker(10L, "John Doe", "john@example.com", "Bio de John", new HashSet<>());
        speaker2 = new Speaker(11L, "Jane Smith", "jane@example.com", "Bio de Jane", new HashSet<>());

        event = new Event();
        event.setId(1L);
        event.setName("Spring Boot Conf");
        event.setDate(LocalDate.of(2023, 10, 26));
        event.setLocation("Online");
        event.setCategory(category);
        event.getSpeakers().add(speaker1);
        event.getSpeakers().add(speaker2);

        eventRequestDto = new EventRequestDto();
        eventRequestDto.setName("Spring Boot Conf");
        eventRequestDto.setDate(LocalDate.of(2023, 10, 26));
        eventRequestDto.setLocation("Online");
        eventRequestDto.setCategoryId(1L);
        eventRequestDto.setSpeakersIds(new HashSet<>(Set.of(10L, 11L)));

        eventResponseDto = new EventResponseDto();
        eventResponseDto.setId(1L);
        eventResponseDto.setName("Spring Boot Conf");
        eventResponseDto.setDate(LocalDate.of(2023, 10, 26));
        eventResponseDto.setLocation("Online");
        eventResponseDto.setCategoryId(1L);
        eventResponseDto.setCategoryName("Conferencia");


        pageable = PageRequest.of(0, 10);
    }
    
    @Test
    @DisplayName("Debe retornar un Evento cuando el ID existe")
    void shouldReturnEventWhenIdExists(){
        when(eventRepository.findById(anyLong())).thenReturn(Optional.of(event));

        Event foundEvent = eventService.findById(1L);

        assertNotNull(foundEvent);
        assertEquals(event.getId(), foundEvent.getId());
        verify(eventRepository, times(1)).findById(1L);
    }

    @Test
    @DisplayName("Debe lanzar ResourceNotFoundException cuando el ID no existe")
    void shouldThrowResourceNotFoundExceptionWhenIdDoesNotExist(){

        when(eventRepository.findById(anyLong())).thenReturn(Optional.empty());

        ResourceNotFoundException thrown = assertThrows(
                ResourceNotFoundException.class, () -> {
                    eventService.findById(99L);
                });

        assertEquals("Evento no encontrado con id: 99", thrown.getMessage());

        verify(eventRepository, times(1)).findById(99L);
    }

    @Test
    @DisplayName("Debe guardar un Evento exitosamente con categoria y oradores")
    void shouldSaveEventSuccessfullyWithCategoryAndSpeakers(){

        Event eventWithoutId = new Event();
        eventWithoutId.setName(eventRequestDto.getName());
        eventWithoutId.setDate(eventRequestDto.getDate());
        eventWithoutId.setLocation(eventRequestDto.getLocation());

        when(eventMapper.toEntity(any(EventRequestDto.class))).thenReturn(eventWithoutId);

        when(categoryService.findById(eventRequestDto.getCategoryId())).thenReturn(category);
        when(speakerService.findById(10L)).thenReturn(speaker1);
        when(speakerService.findById(11L)).thenReturn(speaker2);

        when(eventRepository.save(any(Event.class))).thenAnswer(
                invocation -> {
                    Event savedEvent = invocation.getArgument(0);
                    savedEvent.setId(1L);
                    return savedEvent;
                });

        Event savedEvent = eventService.save(eventRequestDto);

        assertNotNull(savedEvent);
        assertEquals(1L, savedEvent.getId());
        assertEquals(eventRequestDto.getName(), savedEvent.getName());
        assertEquals(category, savedEvent.getCategory());
        assertEquals(2, savedEvent.getSpeakers().size());

        assertTrue(savedEvent.getSpeakers().contains(speaker1));
        assertTrue(savedEvent.getSpeakers().contains(speaker2));

        verify(eventMapper, times(1)).toEntity(eventRequestDto);
        verify(categoryService, times(1)).findById(eventRequestDto.getCategoryId());
        verify(speakerService, times(1)).findById(10L);
        verify(speakerService, times(1)).findById(11L);
        verify(eventRepository, times(1)).save(any(Event.class));
    }

    @Test
    @DisplayName("Debe guardar un Evento exitosamente sin oradores")
    void shouldSaveEventSuccessfullyWithoutSpeakers(){
        eventRequestDto.setSpeakersIds(null);

        Event eventWithoutId = new Event();
        eventWithoutId.setName(eventRequestDto.getName());
        eventWithoutId.setLocation(eventRequestDto.getLocation());
        eventWithoutId.setDate(eventRequestDto.getDate());

        when(eventMapper.toEntity(any(EventRequestDto.class))).thenReturn(eventWithoutId);

        when(categoryService.findById(eventRequestDto.getCategoryId())).thenReturn(category);

        when(eventRepository.save(any(Event.class))).thenAnswer(
                invocation -> {
                    Event savedEvent = invocation.getArgument(0);
                    savedEvent.setId(1L);
                    return savedEvent;
                });

        Event savedEvent = eventService.save(eventRequestDto);

        assertNotNull(savedEvent);
        assertEquals(1L, savedEvent.getId());
        assertEquals(eventRequestDto.getName(), savedEvent.getName());
        assertEquals(category, savedEvent.getCategory());
        assertTrue(savedEvent.getSpeakers().isEmpty());

        verify(eventMapper, times(1)).toEntity(eventRequestDto);
        verify(categoryService, times(1)).findById(eventRequestDto.getCategoryId());
        verify(speakerService, never()).findById(anyLong());
        verify(eventRepository, times(1)).save(any(Event.class));
    }


    @Test
    @DisplayName("Debe lanzar ResourceNotFoundException si la categoría no existe al guardar")
    void shouldThrowResourceNotFoundExceptionWhenCategoryNotFoundOnSave(){
        Event eventWithoutId = new Event();

        when(eventMapper.toEntity(any(EventRequestDto.class))).thenReturn(eventWithoutId);

        when(categoryService.findById(anyLong())).thenThrow(
                new ResourceNotFoundException("Categoría no encontrada con id: "
                        + eventRequestDto.getCategoryId()));

        ResourceNotFoundException thrown = assertThrows( ResourceNotFoundException.class, () -> {
            eventService.save(eventRequestDto);
        });

        assertEquals("Categoría no encontrada con id: " + eventRequestDto.getCategoryId(),
                thrown.getMessage());

        verify(eventRepository, never()).save(any(Event.class));

    }

    @Test
    @DisplayName("Debe retornar una pagina de eventos sin filtro de nombre")
    void shouldReturnPageOfEventsWithoutNameFilter(){
        List<Event> events = Collections.singletonList(event);
        Page<Event> eventPage = new PageImpl<>(events, pageable, 1);

        when(eventRepository.findAll(pageable)).thenReturn(eventPage);

        when(eventMapper.toResponseDto(any(Event.class))).thenReturn(eventResponseDto);

        Page<EventResponseDto> result = eventService.findAll(null, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(eventResponseDto, result.getContent().get(0));

        verify(eventRepository, times(1)).findAll(pageable);
        verify(eventRepository, never()).findByNameContainingIgnoreCase(anyString(), any(Pageable.class));
        verify(eventMapper, times(1)).toResponseDto(event);
    }

    @Test
    @DisplayName("Debe retornar una pagina de eventos con filtro de nombre")
    void shouldReturnPageOfEventsWithNameFilter(){

        String filterName = "Spring";

        List<Event> events = Collections.singletonList(event);
        Page<Event> eventPage = new PageImpl<>(events, pageable, 1);

        when(eventRepository.findByNameContainingIgnoreCase(filterName, pageable)).thenReturn(eventPage);

        when(eventMapper.toResponseDto(any(Event.class))).thenReturn(eventResponseDto);

        Page<EventResponseDto> result = eventService.findAll(filterName, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(eventResponseDto, result.getContent().get(0));

        verify(eventRepository, times(1)).findByNameContainingIgnoreCase(filterName, pageable);
        verify(eventRepository, never()).findAll(any(Pageable.class));
        verify(eventMapper, times(1)).toResponseDto(event);

    }
}

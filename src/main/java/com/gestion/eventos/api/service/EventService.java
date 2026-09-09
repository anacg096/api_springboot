package com.gestion.eventos.api.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
public class EventService implements IEventService{

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final CategoryService categoryService;
    private final SpeakerService speakerService;

    @Override
    @Transactional(readOnly = true)
    public Page<EventResponseDto> findAll(String name, Pageable pageable) {
        Page<Event> eventsPage;

        if(name!=null && !name.trim().isEmpty()){
            eventsPage = eventRepository.findByNameContainingIgnoreCase(name, pageable);
        }else {
            eventsPage = eventRepository.findAll(pageable);
        }

        List<EventResponseDto> dtos = eventsPage.getContent().stream()
                .map(eventMapper::toResponseDto)
                .toList();


        return new PageImpl<>(dtos, pageable, eventsPage.getTotalElements());
    }

    @Override
    @Transactional
    public Event save(EventRequestDto requestDto) {
        Event event = eventMapper.toEntity(requestDto);

        Category category = categoryService.findById(requestDto.getCategoryId());
        event.setCategory(category);

        if(requestDto.getSpeakersIds() !=null && !requestDto.getSpeakersIds().isEmpty()){
            Set<Speaker> speakers = requestDto.getSpeakersIds().stream()
                    .map(speakerService::findById)
                    .collect(Collectors.toSet());
            speakers.forEach(event::addSpeaker);
        }

        return eventRepository.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public Event findById(Long id) {
        return eventRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("Evento no encontrado con id: " + id)
        );
    }

    @Override
    @Transactional
    public Event update(Long id, EventRequestDto requestDto) {
        Event existingEvent = eventRepository.findById(id)
                .orElseThrow(
                        () -> new ResourceNotFoundException("Evento no encontrado con ID: " + id)
                );
        eventMapper.updateEventFromDto(requestDto, existingEvent);

        if(!existingEvent.getCategory().getId().equals(requestDto.getCategoryId())){
            Category category = categoryService.findById(requestDto.getCategoryId());
            existingEvent.setCategory(category);
        }

        Set<Speaker> updatedSpeakers;
        if(requestDto.getSpeakersIds() !=null && !requestDto.getSpeakersIds().isEmpty()){
            updatedSpeakers = requestDto.getSpeakersIds().stream()
                    .map(speakerService::findById)
                    .collect(Collectors.toSet());
        } else {
            updatedSpeakers = new HashSet<>();
        }
        //Punto de partida existingEvent tiene [Luis] updatedSpeakers[Luis, Fernando]
        new HashSet<>(existingEvent.getSpeakers())
                .forEach(currentSpeaker -> {
                    if(!updatedSpeakers.contains(currentSpeaker)){
                        existingEvent.removeSpeaker(currentSpeaker);
                    }
                });


        updatedSpeakers.forEach(newSpeaker -> {
            if(!existingEvent.getSpeakers().contains(newSpeaker)){
                existingEvent.addSpeaker(newSpeaker);
            }
        });

        //existingEvent tiene [Luis, Fernando]
        return eventRepository.save(existingEvent);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        Event eventToDelete = this.findById(id);
        eventRepository.delete(eventToDelete);
    }

    @Transactional(readOnly = true)
    public List<Event> getAllEventsAndTheirDetailsProblematic(){
        List<Event> events = eventRepository.findAll();

        events.forEach( event ->{
            event.getSpeakers().size();
            event.getSpeakers().stream().map(Speaker::getName).collect(Collectors.toSet());
            event.getCategory().getName();
            event.getAttendedUsers().size();
        });

        return events;
    }
    @Transactional(readOnly = true)
    public List<Event> getAllEventsAndTheirDetailsOptimizedWithJoinFetch(){
        List<Event> events = eventRepository.findAllWithCategoryAndSpeakers();

        events.forEach( event -> {
                    System.out.println("Event ID: " + event.getId());
                    System.out.println("Event Name: " + event.getName());
                    System.out.println("Category: " + event.getCategory().getName());
                    System.out.println("Speakers: " + event.getSpeakers()
                            .stream().map(Speaker::getName)
                            .collect(Collectors.joining(", "))
                    );
                    System.out.println("---");
                }
        );

        return events;
    }

    @Transactional(readOnly = true)
    public List<Event> findAllEventsWithAllDetailsOptimized() {
        System.out.println("\n--- DEMO: findAllWithAllDetails (@EntityGraph con Category, Speakers, AttendedUsers) ---");

        List<Event> events = eventRepository.findAllWithAllDetails();

        events.forEach(event -> {

                    System.out.println("Event ID: " + event.getId());
                    System.out.println("Event Name: " + event.getName());

                    if (event.getCategory() != null) {
                        System.out.println("  Category: " + event.getCategory().getName());
                    } else {
                        System.out.println("  Category: N/A");
                    }

                    if (event.getSpeakers() != null && !event.getSpeakers().isEmpty()) {
                        System.out.println("  Speakers: " + event.getSpeakers().stream().map(Speaker::getName).collect(Collectors.joining(", ")));
                    } else {
                        System.out.println("  Speakers: N/A");
                    }

                    if (event.getAttendedUsers() != null && !event.getAttendedUsers().isEmpty()) {
                        System.out.println("  Attended Users: " + event.getAttendedUsers().stream().map(User::getUsername).collect(Collectors.joining(", ")));
                    } else {
                        System.out.println("  Attended Users: N/A");
                    }
                    System.out.println("---");
                }
        );
        return events;
    }




}


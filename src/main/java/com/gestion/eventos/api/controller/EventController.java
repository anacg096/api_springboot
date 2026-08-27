package com.gestion.eventos.api.controller;


import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gestion.eventos.api.domain.Event;
import com.gestion.eventos.api.dto.EventRequestDto;
import com.gestion.eventos.api.dto.EventResponseDto;
import com.gestion.eventos.api.mapper.EventMapper;
import com.gestion.eventos.api.service.IEventService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final IEventService eventService;
    private final EventMapper eventMapper;


    @GetMapping
    public List<EventResponseDto> getAllEvents(){
        List<Event> events = eventService.findAll();

        return eventMapper.toEventResponseDtoList(events);
    }

    // @PostMapping
    // public EventResponseDto createEvent(@RequestBody EventRequestDto requestDto){
    //     Event eventToSave = eventMapper.toEntity(requestDto);
    //     Event eventSaved = eventService.save(eventToSave);
    //     return eventMapper.toResponseDto(eventSaved);
    // }

    @PostMapping
    public ResponseEntity<EventResponseDto> createEvent(@Valid @RequestBody EventRequestDto requestDto){
        Event eventToSave = eventMapper.toEntity(requestDto);
        Event eventSaved = eventService.save(eventToSave);
        EventResponseDto responseDto = eventMapper.toResponseDto(eventSaved);

        return new ResponseEntity<>(responseDto, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponseDto> getEventById(@PathVariable Long id){
        Event event = eventService.findById(id);
        EventResponseDto responseDto = eventMapper.toResponseDto(event);
        return ResponseEntity.ok(responseDto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EventResponseDto> updateEvent( @PathVariable Long id,
                                                        @Valid @RequestBody EventRequestDto requestDto
    ){
        Event eventToUpdate = eventService.findById(id);
        eventMapper.updateEventFromDto(requestDto, eventToUpdate);
        Event updateEvent = eventService.save(eventToUpdate);
        return ResponseEntity.ok(eventMapper.toResponseDto(updateEvent));
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable Long id){
        eventService.deleteById(id);
        return ResponseEntity.noContent().build(); // Return a 204 No Content response
    }










}
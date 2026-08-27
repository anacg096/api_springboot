package com.gestion.eventos.api.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.gestion.eventos.api.domain.Event;
import com.gestion.eventos.api.repository.EventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EventService implements IEventService{

    private final EventRepository eventRepository;


    @Override
    public List<Event> findAll() {
        return eventRepository.findAll();
    }

    @Override
    public Event save(Event event) {
        return eventRepository.save(event);
    }

    @Override
    public Event findById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new com.gestion.eventos.api.exception.ResourceNotFoundException("Event not found with id: " + id));
    }

    @Override
    public void deleteById(Long id) {
        Event eventToDelete = findById(id);
        eventRepository.delete(eventToDelete);
    }
}


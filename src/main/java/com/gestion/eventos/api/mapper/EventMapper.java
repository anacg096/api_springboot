package com.gestion.eventos.api.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import com.gestion.eventos.api.domain.Event;
import com.gestion.eventos.api.dto.EventRequestDto;
import com.gestion.eventos.api.dto.EventResponseDto;

@Mapper(componentModel = "spring")
public interface EventMapper {
    Event toEntity(EventRequestDto eventRequestDto);
    EventResponseDto toResponseDto(Event event);

    List<EventResponseDto> toEventResponseDtoList(List<Event> events);

    void updateEventFromDto(EventRequestDto dto, @MappingTarget Event event);
}

package com.gestion.eventos.api.service;

import com.gestion.eventos.api.domain.Speaker;
import com.gestion.eventos.api.dto.SpeakerRequestDto;
import java.util.List;

public interface SpeakerService {
    Speaker save(SpeakerRequestDto requestDto);
    Speaker findById(Long id);
    List<Speaker> findAll();
    Speaker update(Long id, SpeakerRequestDto requestDto);
    void deleteById(Long id);
}

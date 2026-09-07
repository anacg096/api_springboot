package com.gestion.eventos.api.controller;

import java.util.List;

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
import org.springframework.web.bind.annotation.RestController;

import com.gestion.eventos.api.domain.Speaker;
import com.gestion.eventos.api.dto.SpeakerRequestDto;
import com.gestion.eventos.api.dto.SpeakerResponseDto;
import com.gestion.eventos.api.mapper.SpeakerMapper;
import com.gestion.eventos.api.service.SpeakerService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/speakers")
@RequiredArgsConstructor
public class SpeakerController {
    private final SpeakerService speakerService;
    private final SpeakerMapper speakerMapper;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<SpeakerResponseDto> createSpeaker(@Valid @RequestBody SpeakerRequestDto requestDto){
        Speaker speaker = speakerService.save(requestDto);
        return new ResponseEntity<>(speakerMapper.toDto(speaker), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<SpeakerResponseDto> getSpeakerById(@PathVariable Long id){
        Speaker speaker = speakerService.findById(id);
        return ResponseEntity.ok(speakerMapper.toDto(speaker));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<List<SpeakerResponseDto>> getAllSpeakers(){
        List<Speaker> speakers = speakerService.findAll();
        return ResponseEntity.ok(speakerMapper.toResponseDtoList(speakers));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<SpeakerResponseDto> updateSpeaker(@PathVariable Long id,
                                                            @Valid @RequestBody SpeakerRequestDto requestDto){
        Speaker updatedSpeaker = speakerService.update(id, requestDto);
        return ResponseEntity.ok(speakerMapper.toDto(updatedSpeaker));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<Void> deleteSpeaker(@PathVariable Long id){
        speakerService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

}
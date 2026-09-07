package com.gestion.eventos.api.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gestion.eventos.api.domain.Speaker;

public interface SpeakerRepository extends JpaRepository<Speaker, Long> {
    Optional<Speaker> findByEmail(String email);

    boolean existsByEmail(String email);
}

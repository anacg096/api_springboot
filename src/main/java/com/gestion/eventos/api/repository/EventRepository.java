package com.gestion.eventos.api.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.gestion.eventos.api.domain.Event;

import lombok.NonNull;

public interface EventRepository extends JpaRepository<Event, Long> {
    Page<Event> findByNameContainingIgnoreCase(String name, Pageable pageable);

    // Método para obtener todos los eventos junto con sus categorías y ponentes asociados
    @Query("SELECT e FROM Event e JOIN FETCH e.category LEFT JOIN FETCH e.speakers")
    List<Event> findAllWithCategoryAndSpeakers();

    // Método para obtener un evento por su ID junto con su categoría y ponentes asociados
    @Query("SELECT e FROM Event e JOIN FETCH e.category LEFT JOIN FETCH e.speakers WHERE e.id = :id")
    Optional<Event> findByIdWithCategoryAndSpeakers(Long id);

    // Método para obtener todos los eventos junto con sus categorías, ponentes y usuarios que asistieron
    @Override
    @NonNull
    @EntityGraph(attributePaths = {"category", "speakers"})
    List<Event> findAll();

    // Método para obtener un evento por su ID junto con su categoría, ponentes y usuarios que asistieron
    @Override
    @NonNull
    @EntityGraph(attributePaths = {"category", "speakers"})
    Optional<Event> findById(Long id);

    // Método para obtener todos los eventos junto con sus categorías, ponentes y usuarios que asistieron
    @EntityGraph(attributePaths = {"category", "speakers", "attendedUsers"})
    @Query("SELECT e FROM Event e")
    List<Event> findAllWithAllDetails();
}

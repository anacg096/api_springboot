package com.gestion.eventos.api.dto;

import java.time.LocalDate;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Detalles de la solicitud para crear/actualizar un evento")
public class EventRequestDto {
    @Schema(description = "Nombre del evento", example = "Conferencia de Spring Boot")
    @NotBlank(message = "El nombre del evento no puede estar vacío.")
    private String name;

    @NotNull(message = "La fecha no puede ser nula.")
    private LocalDate date;

    @NotBlank(message = "La ubicación no puede estar vacía.")
    private String location;

    @NotNull(message = "La categoría es obligatoria.")
    private Long categoryId;

    private Set<Long> speakersIds;
}
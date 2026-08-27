package com.gestion.eventos.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EventRequestDto {
    @NotBlank(message = "El nombre del evento no puede estar vacío.")
    private String name;

    @NotNull(message = "La fecha no puede ser nula.")
    private LocalDate date;

    @NotBlank(message = "La ubicación no puede estar vacía.")
    private String location;
}
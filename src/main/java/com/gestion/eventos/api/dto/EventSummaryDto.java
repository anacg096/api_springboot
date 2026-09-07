package com.gestion.eventos.api.dto;

import java.time.LocalDate;

import lombok.Data;
@Data
public class EventSummaryDto {
    private Long id;
    private String name;
    private LocalDate date;
    private String location;
}
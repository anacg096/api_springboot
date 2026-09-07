package com.gestion.eventos.api.dto;

import java.util.List;

import lombok.Data;

@Data
public class UserResponseDto {
    private Long id;
    private String name;
    private String username;
    private String email;

    private List<RoleDto> roles;
    
    private List<EventSummaryDto> attendedEvents;
}

package com.gestion.eventos.api.security.dto;

import lombok.Data;

@Data
public class LoginDto {
    private String username;
    private String password;
}

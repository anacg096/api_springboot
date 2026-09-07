package com.gestion.eventos.api.mapper;

import org.mapstruct.Mapper;

import com.gestion.eventos.api.domain.Category;
import com.gestion.eventos.api.dto.CategoryDto;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryDto toDto(Category category);
    Category toEntity(CategoryDto categoryDto);
}

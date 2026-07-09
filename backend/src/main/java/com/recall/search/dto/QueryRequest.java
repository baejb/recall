package com.recall.search.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(@NotBlank String query) {
}

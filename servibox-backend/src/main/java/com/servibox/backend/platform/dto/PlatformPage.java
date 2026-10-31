package com.servibox.backend.platform.dto;

import java.util.List;

public record PlatformPage<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
}

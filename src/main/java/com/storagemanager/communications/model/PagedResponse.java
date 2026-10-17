package com.storagemanager.communications.model;

import java.util.List;

import org.springframework.data.domain.Page;

public record PagedResponse<T>(
        List<T> content,
        int page,
        int totalPages,
        long totalElements
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getTotalPages(),
                page.getTotalElements()
        );
    }
}

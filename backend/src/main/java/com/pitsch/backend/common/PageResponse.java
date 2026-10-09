package com.pitsch.backend.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Standard paginated envelope: {@code {items, page, size, totalItems, totalPages}} (page is zero-based). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> ofList(List<T> items, int page, int size, long total) {
        int pages = size == 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new PageResponse<>(items, page, size, total, pages);
    }
}

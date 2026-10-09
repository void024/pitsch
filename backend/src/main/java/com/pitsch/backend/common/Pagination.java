package com.pitsch.backend.common;

import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Validates page/size/sort query parameters against an allowlist of sortable fields. */
public final class Pagination {

    public static final int MAX_PAGE_SIZE = 100;

    private Pagination() { }

    /**
     * @param sort "field" or "field,asc|desc"; the field must be a key of {@code sortable} (API name -> entity property)
     */
    public static Pageable of(Integer page, Integer size, String sort, Map<String, String> sortable, String defaultSort) {
        int p = page == null ? 0 : page;
        int s = size == null ? 25 : size;
        if (p < 0) {
            throw ApiException.badRequest("page must be >= 0");
        }
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String spec = sort == null || sort.isBlank() ? defaultSort : sort.trim();
        String[] parts = spec.split(",");
        String property = sortable.get(parts[0].trim());
        if (property == null) {
            throw ApiException.badRequest("sort must be one of " + sortable.keySet());
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(p, s, Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id")));
    }

    public static <T extends Enum<T>> T enumParam(Class<T> type, String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(name + " has an unsupported value");
        }
    }

    public static String oneOf(String value, Set<String> allowed, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(v)) {
            throw ApiException.badRequest(name + " must be one of " + allowed);
        }
        return v;
    }
}

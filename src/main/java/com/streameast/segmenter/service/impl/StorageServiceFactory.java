package com.streameast.segmenter.service.impl;

import com.streameast.segmenter.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class StorageServiceFactory {

    private final Map<String, StorageService> storageServices;

    public StorageServiceFactory(List<StorageService> storageServices) {
        this.storageServices = storageServices.stream()
                .collect(Collectors.toMap(StorageService::getStorageType, Function.identity()));
    }

    public Optional<StorageService> getAvailableStorageServices(String type) {
        return Optional.ofNullable(storageServices.get(type.toUpperCase()));
    }

    public List<String> getAvailableStorageServices(List<String> types) {
        return types.stream()
                .filter(item -> storageServices.get(item.toUpperCase()) != null)
                .collect(Collectors.toList());
    }

    public List<StorageService> getStorageServices(List<String> types) {
        return types.stream()
                .map(type -> storageServices.get(type.toUpperCase()))
                .filter(Objects::nonNull) // Filter out any null values
                .collect(Collectors.toList());
    }

}

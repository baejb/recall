package com.recall.memory;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class MemoryService {

    private final MemoryRepository repository;

    public MemoryService(MemoryRepository repository) {
        this.repository = repository;
    }

    public Optional<Memory> find(Long id) {
        return repository.findById(id);
    }

    public List<Memory> recent() {
        return repository.findTop50ByOrderByUpdatedAtDesc();
    }
}

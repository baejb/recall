package com.recall.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryRepository extends JpaRepository<Memory, Long> {

    List<Memory> findTop50ByOrderByUpdatedAtDesc();
}

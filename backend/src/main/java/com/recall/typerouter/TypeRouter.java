package com.recall.typerouter;

import com.recall.memory.MemoryType;
import org.springframework.stereotype.Service;

/** Routes an extraction to troubleshooting / knowledge (or both). */
@Service
public class TypeRouter {

    public MemoryType route(String extractionJson) {
        // TODO: decide from the extracted structure.
        return MemoryType.TROUBLESHOOTING;
    }
}

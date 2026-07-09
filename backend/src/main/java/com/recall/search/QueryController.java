package com.recall.search;

import com.recall.search.dto.QueryRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final SearchService searchService;

    public QueryController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@Valid @RequestBody QueryRequest request) {
        return searchService.answer(request.query());
    }
}

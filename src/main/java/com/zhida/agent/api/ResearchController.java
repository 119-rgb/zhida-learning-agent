package com.zhida.agent.api;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.AgentEvent;
import com.zhida.agent.application.ResearchOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import com.zhida.agent.conversation.ConversationHistory;
import org.springframework.beans.factory.ObjectProvider;

@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {

    private final ResearchOrchestrator orchestrator;
    private final ObjectProvider<ConversationHistory> history;
    private final com.zhida.agent.auth.OwnerResolver owners;

    public ResearchController(ResearchOrchestrator orchestrator, ObjectProvider<ConversationHistory> history, com.zhida.agent.auth.OwnerResolver owners) {
        this.orchestrator = orchestrator;
        this.history = history;
        this.owners = owners;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> stream(@Valid @RequestBody ResearchRequest request, java.security.Principal principal) {
        ConversationHistory persistence = history.getIfAvailable();
        return (persistence == null ? orchestrator.stream(request) : persistence.stream(request, orchestrator, owners.owner(principal)))
                .map(event -> ServerSentEvent.<AgentEvent>builder()
                        .id(Long.toString(event.eventId()))
                        .event(event.type())
                        .data(event)
                        .build());
    }
}

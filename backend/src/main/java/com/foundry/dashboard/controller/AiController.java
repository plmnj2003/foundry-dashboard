package com.foundry.dashboard.controller;

import com.foundry.dashboard.dto.ChatResponse;
import com.foundry.dashboard.service.AiChatService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiChatService aiChatService;

    public AiController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        if (question.isBlank()) {
            return new ChatResponse("질문을 입력해주세요.", "error", List.of(), 0.0);
        }
        return aiChatService.answerStructured(question);
    }
}

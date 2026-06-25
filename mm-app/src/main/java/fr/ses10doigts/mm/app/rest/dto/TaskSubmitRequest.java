package fr.ses10doigts.mm.app.rest.dto;

public record TaskSubmitRequest(
        String content,
        String projectId,
        String conversationId
) {}
package com.foundry.dashboard.dto;

import java.util.List;

public record ChatResponse(String answer, String type, List<Source> sources, double confidence) {

    public record Source(String filename, int chunkIndex, double similarity) {
    }
}

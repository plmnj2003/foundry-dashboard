package com.foundry.rag.service;

public record SearchResult(int documentId, String filename, int chunkIndex, String content, double similarity) {
}

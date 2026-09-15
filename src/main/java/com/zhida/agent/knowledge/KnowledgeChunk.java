package com.zhida.agent.knowledge;

import java.util.Map;

/** Business fragment independent of the embedding provider. */
public record KnowledgeChunk(
    String id, String content, Map<String, Object> metadata, Double score) {
  public KnowledgeChunk(String id, String content, Map<String, Object> metadata) {
    this(id, content, Map.copyOf(metadata), null);
  }

  public KnowledgeChunk {
    metadata = Map.copyOf(metadata);
  }

  public String getId() {
    return id;
  }

  public String getText() {
    return content;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public Double getScore() {
    return score;
  }
}

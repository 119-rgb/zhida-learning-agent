package com.zhida.agent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhida")
public class ZhidaProperties {

    private final Ai ai = new Ai();
    private final Search search = new Search();
    private final WebReader webReader = new WebReader();
    private final Rag rag = new Rag();
    private final Execution execution = new Execution();
    public Execution getExecution() { return execution; }

    public static class Execution {
        private int timeoutSeconds = 90;
        private int maxToolCalls = 8;
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int value) {
            if (value < 1 || value > 600) throw new IllegalArgumentException("timeoutSeconds must be 1..600");
            timeoutSeconds = value;
        }
        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int value) {
            if (value < 1 || value > 50) throw new IllegalArgumentException("maxToolCalls must be 1..50");
            maxToolCalls = value;
        }
    }

    public Ai getAi() {
        return ai;
    }

    public Search getSearch() {
        return search;
    }

    public WebReader getWebReader() {
        return webReader;
    }

    public Rag getRag() {
        return rag;
    }

    public static class Ai {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Search {
        private String tavilyApiKey = "";
        private int maxResults = 5;

        public String getTavilyApiKey() {
            return tavilyApiKey;
        }

        public void setTavilyApiKey(String tavilyApiKey) {
            this.tavilyApiKey = tavilyApiKey;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
    }

    public static class WebReader {
        private int maxContentLength = 12_000;

        public int getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
        }
    }

    public static class Rag {
        private String uploadDir = "./data/uploads";
        private String vectorStoreFile = "./data/vector/vector-store.json";
        private String modelCacheDir = "./data/models";
        private String modelUri = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx";
        private String tokenizerUri = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json";
        private long maxFileSize = 20L * 1024 * 1024;
        private int maxDocumentsPerKnowledgeBase = 20;
        private int chunkSize = 800;
        private int chunkOverlap = 120;
        private int topK = 5;

        public String getUploadDir() { return uploadDir; }
        public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
        public String getVectorStoreFile() { return vectorStoreFile; }
        public void setVectorStoreFile(String vectorStoreFile) { this.vectorStoreFile = vectorStoreFile; }
        public String getModelCacheDir() { return modelCacheDir; }
        public void setModelCacheDir(String modelCacheDir) { this.modelCacheDir = modelCacheDir; }
        public String getModelUri() { return modelUri; }
        public void setModelUri(String modelUri) { this.modelUri = modelUri; }
        public String getTokenizerUri() { return tokenizerUri; }
        public void setTokenizerUri(String tokenizerUri) { this.tokenizerUri = tokenizerUri; }
        public long getMaxFileSize() { return maxFileSize; }
        public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
        public int getMaxDocumentsPerKnowledgeBase() { return maxDocumentsPerKnowledgeBase; }
        public void setMaxDocumentsPerKnowledgeBase(int maxDocumentsPerKnowledgeBase) { this.maxDocumentsPerKnowledgeBase = maxDocumentsPerKnowledgeBase; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }
}

package com.zhida.agent.knowledge;

import com.zhida.agent.common.config.ZhidaProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class LocalVectorKnowledgeIndex {

    private final ZhidaProperties.Rag properties;
    private SimpleVectorStore vectorStore;

    public LocalVectorKnowledgeIndex(ZhidaProperties properties) {
        this.properties = properties.getRag();
    }

    public synchronized void add(List<Document> documents) {
        if (documents.isEmpty()) {
            return;
        }
        SimpleVectorStore store = store();
        store.add(documents);
        save(store);
    }

    public synchronized List<Document> search(String knowledgeBaseId, String query, int topK) {
        return store().similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.30)
                .filterExpression("knowledgeBaseId == '" + knowledgeBaseId + "'")
                .build());
    }

    public synchronized void delete(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        SimpleVectorStore store = store();
        store.delete(ids);
        save(store);
    }

    private SimpleVectorStore store() {
        if (vectorStore != null) {
            return vectorStore;
        }
        try {
            Path cacheDirectory = Path.of(properties.getModelCacheDir()).toAbsolutePath().normalize();
            Files.createDirectories(cacheDirectory);

            TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
            embeddingModel.setResourceCacheDirectory(cacheDirectory.toString());
            embeddingModel.setModelResource(properties.getModelUri());
            embeddingModel.setTokenizerResource(properties.getTokenizerUri());
            embeddingModel.setTokenizerOptions(Map.of("padding", "true", "truncation", "true"));
            embeddingModel.afterPropertiesSet();

            SimpleVectorStore created = SimpleVectorStore.builder(embeddingModel).build();
            File persistenceFile = vectorStoreFile();
            if (persistenceFile.isFile()) {
                created.load(persistenceFile);
            }
            vectorStore = created;
            return created;
        } catch (Exception exception) {
            throw new IllegalStateException("本地中文向量模型初始化失败，请检查网络或模型地址", exception);
        }
    }

    private void save(SimpleVectorStore store) {
        try {
            File file = vectorStoreFile();
            Files.createDirectories(file.toPath().getParent());
            store.save(file);
        } catch (Exception exception) {
            throw new IllegalStateException("向量索引保存失败", exception);
        }
    }

    private File vectorStoreFile() {
        return Path.of(properties.getVectorStoreFile()).toAbsolutePath().normalize().toFile();
    }
}

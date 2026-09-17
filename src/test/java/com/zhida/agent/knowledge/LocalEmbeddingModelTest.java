package com.zhida.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhida.agent.common.config.ZhidaProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 内置离线向量模型（EMBEDDING_MODEL=local）的真实链路验证。
 *
 * <p>这里刻意**不注入替身**：直接用打包在依赖里的 all-MiniLM-L6-v2，验证在没有第三方向量服务、
 * 没有 API Key、且假设无外网的情况下，文档向量化与检索是否真的可用。模型文件随 jar 分发，
 * 首次加载会有一定耗时，因此本类用例数量保持精简。
 */
class LocalEmbeddingModelTest {

  @TempDir Path directory;

  @Test
  void bundledOnnxModelProducesStableEmbeddings() {
    AllMiniLmL6V2EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
    Embedding first = model.embed("订单已经付款但服务没有开通").content();
    Embedding second = model.embed("订单已经付款但服务没有开通").content();

    // 维度固定为 384：向量库按模型身份重建，维度变化会让旧索引失效。
    assertThat(first.vector().length).isEqualTo(384);
    // 同一输入必须得到相同向量，否则幂等索引与检索结果无法复现。
    assertThat(first.vector()).isEqualTo(second.vector());
  }

  @Test
  void localModeIndexesAndRetrievesWithoutAnyApiKey() {
    ZhidaProperties properties = new ZhidaProperties();
    properties.getRag().setEmbeddingModel("local");
    // 明确留空外部服务配置：离线模式不得依赖它们。
    properties.getRag().setEmbeddingBaseUrl("");
    properties.getRag().setEmbeddingApiKey("");
    properties.getRag().setVectorStoreFile(directory.resolve("vector/vector.json").toString());

    LocalVectorKnowledgeIndex index = new LocalVectorKnowledgeIndex(properties);
    index.add(
        List.of(
            chunk("chunk-activation", "已付款但未开通时，请在售后工作台提交工单并关联订单号。"),
            chunk("chunk-refund", "退款需要客服人工审核，AI 助手不能承诺退款或到账时间。")));

    var matches = index.search("demo-namespace", "付款了但是服务没有开通怎么办", 2);
    assertThat(matches).isNotEmpty();
    // 最相关的必须是"未开通处理"那条，而不是"退款"那条。
    assertThat(matches.get(0).getId()).isEqualTo("chunk-activation");
  }

  @Test
  void switchingBetweenLocalAndRemoteRequiresReindex() {
    ZhidaProperties properties = new ZhidaProperties();
    properties.getRag().setEmbeddingModel("local");
    properties.getRag().setVectorStoreFile(directory.resolve("vector/vector.json").toString());
    LocalVectorKnowledgeIndex index = new LocalVectorKnowledgeIndex(properties);
    index.add(List.of(chunk("chunk-1", "虚构产品开通说明")));

    // 切到外部服务模式：向量空间不同，必须要求重建索引，绝不能沿用本地模型的向量。
    properties.getRag().setEmbeddingModel("text-embedding-v3");
    properties.getRag().setEmbeddingBaseUrl("https://example.invalid/v1");
    assertThat(new LocalVectorKnowledgeIndex(properties).requiresReindex()).isTrue();
  }

  @Test
  void externalModeStillRequiresAllThreeSettings() {
    ZhidaProperties properties = new ZhidaProperties();
    properties.getRag().setEmbeddingModel("");
    properties.getRag().setVectorStoreFile(directory.resolve("vector/vector.json").toString());
    LocalVectorKnowledgeIndex index = new LocalVectorKnowledgeIndex(properties);

    // 未配置任何模式时给出可执行的提示，并指明离线模型的开关。
    assertThatThrownBy(() -> index.add(List.of(chunk("chunk-1", "内容"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMBEDDING_MODEL=local");
  }

  private static KnowledgeChunk chunk(String id, String content) {
    return new KnowledgeChunk(id, content, Map.of("knowledgeBaseId", "demo-namespace"));
  }
}

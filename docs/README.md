# 文档导航 · Docs Index

> **解决什么**：告诉你这个项目有哪些文档、各自解决什么问题、按什么顺序读。`docs/` 下的技术文档（架构 / 代码阅读 / 配置）与 [可观测](../observability/README.md) 开头都有一行「读者 / 解决什么」，照着走即可。

## 推荐阅读顺序（跑通 → 看懂 → 贡献）

| 顺序 | 文档 | 读者 | 解决什么 |
|---|---|---|---|
| 1 | [根 README](../README.md)（中：[README.zh-CN](../README.zh-CN.md)） | 所有人 | **5 分钟跑通**：clone → 配 key + 数据库 → 启动 → 问答 |
| 2 | [架构](architecture.md) | 想懂设计的人 | 为什么这么设计（写/读两条路径、两处落库、接口化缝） |
| 3 | [代码阅读](code-tour.md) | 想看代码的人 | 两条链路逐步映射到类/方法，从哪开始读 |
| 4 | [配置](configuration.md) | 要调配置的人 | 每个环境变量、下载地址、密钥配置 |
| 5 | [可观测](../observability/README.md) | 要接监控的人 | Jaeger/Prometheus/Grafana 一步步配好 |
| 6 | [贡献](../CONTRIBUTING.md) | 想贡献的人 | 构建/测试/代码风格/提交规范 |

## 文档分层总览

```
README.md / README.zh-CN.md     入门：5 分钟跑通
CONTRIBUTING.md                 贡献：构建/测试/规范
LICENSE                         Apache 2.0
.env.example                    环境变量模板（复制成 .env）
docs/
├─ README.md                    本文：文档索引（你在这里）
├─ architecture.md + .svg/.png  架构：为什么这么设计
├─ code-tour.md                 代码：从入口到类/方法怎么串
├─ configuration.md             配置：环境变量 + 下载地址 + 密钥配置
└─ screenshots/                 截图（4K）：4 大盘 + Jaeger trace + demo 页
observability/
├─ README.md                    可观测：一步步接 Jaeger/Prometheus/Grafana
├─ prometheus.yml / docker-compose.yml
└─ grafana/
   ├─ datasource.yaml + dashboard-provider.yaml
   └─ dashboards/               4 个大盘 JSON
```

## 两句话讲清这个项目

- **写路径**：读 Spring AI 文档源码（AsciiDoc，版本化）→ 切 chunk → 稳定 id（+ 内容哈希）→ 分批向量化 → 两落库（向量+chunk 文本进 pgvector 表、BM25 内存索引）。
- **读路径**：提问（中文自动译英）→ 混合检索（向量+BM25+RRF，版本过滤下推）→ 重排 → 按 URL 去重 → 编号上下文 → 流式生成带引用答案。

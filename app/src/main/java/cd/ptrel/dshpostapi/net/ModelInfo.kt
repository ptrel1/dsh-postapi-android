package cd.ptrel.dshpostapi.net

/**
 * 模型能力描述（移植自 SimpleChat 的 net/Provider.kt，只取 ModelInfo 部分）。
 *
 * 驱动 UI：不具备识图能力时不显示输入区的 ⊕；不支持思考时不显示思考档位。
 *
 * ## 与本项目的关系
 *
 * DSH 侧的模型由服务端决定，客户端不配置模型。这个类保留是因为
 * SimpleChat 的 UI 组件（InputBar / ChatModels）按它做能力判断。
 * 接入 DSH 时用一个「能力全开」的默认值即可 —— 服务端会自己拒掉不支持的能力，
 * 客户端显示开关比显示不了更有用（用户能看到为什么被拒）。
 */
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val supportsVision: Boolean = false,
    val supportsThinking: Boolean = false,
    /** 上下文窗口，用于占用进度条与压缩阈值。 */
    val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
) {
    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 128_000

        /**
         * DSH 会话用的默认能力描述。
         *
         * 全部打开：DSH 后端自己会拒绝不支持的能力并给出原因，
         * 客户端提前隐藏入口反而让用户无从判断「为什么没有这个功能」。
         */
        val DSH_DEFAULT = ModelInfo(
            id = "dsh",
            displayName = "DSH",
            supportsVision = true,
            supportsThinking = true,
            contextWindow = 1_000_000,
        )
    }
}

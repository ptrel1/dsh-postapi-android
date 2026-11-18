package cd.ptrel.dshpostapi.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 图标集。
 *
 * 绝大多数图标取自 **Lucide**（https://lucide.dev，ISC License）的官方 SVG 几何，
 * 在构建期转写为 Compose `ImageVector` 源码 —— 因此**没有任何运行时依赖**，
 * 也不受 `material-icons-extended` 的体积与构建耗时问题影响
 * （Google 已明确不推荐该库）。
 *
 * 三个自定义图标例外：[Menu]（对齐 DeepSeek 的双横线）、[Bubble]（品牌标识）、
 * [Stop]（实心圆角方块，停止生成）。
 *
 * 统一约定：24×24 viewport，黑色描边/填充，由 `Icon(tint = ...)` 着色。
 * 描边宽度 2dp、圆头圆角，与 Lucide 原始设计一致。
 */
object AppIcons {

    private fun vector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /** 实心路径。 */
    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    /** 描边路径（Lucide 风格）。 */
    private fun ImageVector.Builder.stroke(
        width: Float = 2f,
        block: PathBuilder.() -> Unit,
    ) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    // ══════════════════════════════════════════════════════
    //  自定义图标
    // ══════════════════════════════════════════════════════

    /** 抽屉按钮：两条横线，对齐 DeepSeek 客户端。 */
    val Menu: ImageVector by lazy {
        vector("Menu") {
            stroke {
                moveTo(3f, 7f)
                horizontalLineTo(21f)
                moveTo(3f, 15f)
                horizontalLineTo(21f)
            }
        }
    }

    /** 品牌标识：极简对话气泡。 */
    val Bubble: ImageVector by lazy {
        vector("Bubble") {
            solid {
                moveTo(8f, 3f)
                horizontalLineTo(16f)
                arcToRelative(5f, 5f, 0f, false, true, 5f, 5f)
                verticalLineTo(13f)
                arcToRelative(5f, 5f, 0f, false, true, -5f, 5f)
                horizontalLineTo(12f)
                lineTo(7f, 22f)
                verticalLineTo(18f)
                horizontalLineTo(8f)
                arcToRelative(5f, 5f, 0f, false, true, -5f, -5f)
                verticalLineTo(8f)
                arcToRelative(5f, 5f, 0f, false, true, 5f, -5f)
                close()
            }
        }
    }

    /**
     * 停止生成：实心圆角方块。
     *
     * 圆角取 3.5 / 边长 12 ≈ 29% —— 先前是 1.5 / 10 = 15%，
     * 在 15dp 下几乎就是个直角方块，和周围一圈圆角/胶囊对不上。
     * 边长也从 10 加到 12：这个图标本来就该比箭头略大，才压得住实心底。
     */
    val Stop: ImageVector by lazy {
        vector("Stop") {
            solid {
                moveTo(9.5f, 6f)
                horizontalLineTo(14.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, 3.5f, 3.5f)
                verticalLineTo(14.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, -3.5f, 3.5f)
                horizontalLineTo(9.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, -3.5f, -3.5f)
                verticalLineTo(9.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, 3.5f, -3.5f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  导航与顶栏
    // ══════════════════════════════════════════════════════

    /** 历史对话（Lucide: panel-left） */
    val PanelLeft: ImageVector by lazy {
        vector("PanelLeft") {
            stroke {
                moveTo(9f, 3f)
                verticalLineToRelative(18f)
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
        }
    }

    /** 圆圈加号（Lucide: circle-plus）。用于「新建对话」与输入区的附件入口。 */
    val PlusCircle: ImageVector by lazy {
        vector("PlusCircle") {
            stroke {
                moveTo(8f, 12f)
                horizontalLineToRelative(8f)
                moveTo(12f, 8f)
                verticalLineToRelative(8f)
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
            }
        }
    }

    /** 静音（Lucide: bell-off） */
    val BellOff: ImageVector by lazy {
        vector("BellOff") {
            stroke {
                moveTo(10.268f, 21f)
                arcToRelative(2f, 2f, 0f, false, false, 3.464f, 0f)
                moveTo(17f, 17f)
                horizontalLineTo(4f)
                arcToRelative(1f, 1f, 0f, false, true, -0.74f, -1.673f)
                curveTo(4.59f, 13.956f, 6f, 12.499f, 6f, 8f)
                arcToRelative(6f, 6f, 0f, false, true, 0.258f, -1.742f)
                moveTo(2f, 2f)
                lineToRelative(20f, 20f)
                moveTo(8.668f, 3.01f)
                arcTo(6f, 6f, 0f, false, true, 18f, 8f)
                curveToRelative(0f, 2.687f, 0.77f, 4.653f, 1.707f, 6.05f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  通用
    // ══════════════════════════════════════════════════════

    /** 关闭（Lucide: x） */
    val Close: ImageVector by lazy {
        vector("Close") {
            stroke {
                moveTo(18f, 6f)
                lineTo(6f, 18f)
                moveTo(6f, 6f)
                lineToRelative(12f, 12f)
            }
        }
    }

    /** 对勾（Lucide: check） */
    val Check: ImageVector by lazy {
        vector("Check") {
            stroke {
                moveTo(20f, 6f)
                lineTo(9f, 17f)
                lineToRelative(-5f, -5f)
            }
        }
    }

    /** 搜索（Lucide: search） */
    val Search: ImageVector by lazy {
        vector("Search") {
            stroke {
                moveTo(21f, 21f)
                lineToRelative(-4.34f, -4.34f)
                moveTo(3f, 11f)
                arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
                arcToRelative(8f, 8f, 0f, true, true, -16f, 0f)
                close()
            }
        }
    }

    /** 更多（Lucide: ellipsis） */
    val More: ImageVector by lazy {
        vector("More") {
            stroke {
                moveTo(11f, 12f)
                arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
                close()
                moveTo(18f, 12f)
                arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
                close()
                moveTo(4f, 12f)
                arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
                close()
            }
        }
    }

    /** 设置（Lucide: settings） */
    val Settings: ImageVector by lazy {
        vector("Settings") {
            stroke {
                moveTo(9.671f, 4.136f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, 4.659f, 0f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 3.319f, 1.915f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, 2.33f, 4.033f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 0f, 3.831f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, -2.33f, 4.033f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, -3.319f, 1.915f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, -4.659f, 0f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, -3.32f, -1.915f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, -2.33f, -4.033f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 0f, -3.831f)
                arcTo(2.34f, 2.34f, 0f, false, true, 6.35f, 6.051f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 3.319f, -1.915f)
                moveTo(9f, 12f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  展开 / 折叠 / 导航方向
    // ══════════════════════════════════════════════════════

    /** 展开（Lucide: chevron-down） */
    val ChevronDown: ImageVector by lazy {
        vector("ChevronDown") {
            stroke {
                moveTo(6f, 9f)
                lineToRelative(6f, 6f)
                lineToRelative(6f, -6f)
            }
        }
    }

    /** 折叠（Lucide: chevron-up） */
    val ChevronUp: ImageVector by lazy {
        vector("ChevronUp") {
            stroke {
                moveTo(18f, 15f)
                lineToRelative(-6f, -6f)
                lineToRelative(-6f, 6f)
            }
        }
    }

    /** 上一个（Lucide: chevron-left） */
    val ChevronLeft: ImageVector by lazy {
        vector("ChevronLeft") {
            stroke {
                moveTo(15f, 18f)
                lineToRelative(-6f, -6f)
                lineToRelative(6f, -6f)
            }
        }
    }

    /** 下一个（Lucide: chevron-right） */
    val ChevronRight: ImageVector by lazy {
        vector("ChevronRight") {
            stroke {
                moveTo(9f, 18f)
                lineToRelative(6f, -6f)
                lineToRelative(-6f, -6f)
            }
        }
    }

    /** 发送（Lucide: arrow-up） */
    val ArrowUp: ImageVector by lazy {
        vector("ArrowUp") {
            stroke {
                moveTo(5f, 12f)
                lineToRelative(7f, -7f)
                lineToRelative(7f, 7f)
                moveTo(12f, 19f)
                verticalLineTo(5f)
            }
        }
    }

    /** 回到底部（Lucide: arrow-down） */
    val ArrowDown: ImageVector by lazy {
        vector("ArrowDown") {
            stroke {
                moveTo(12f, 5f)
                verticalLineToRelative(14f)
                moveTo(19f, 12f)
                lineToRelative(-7f, 7f)
                lineToRelative(-7f, -7f)
            }
        }
    }

    /** 排序（Lucide: arrow-up-down） */
    val Sort: ImageVector by lazy {
        vector("Sort") {
            stroke {
                moveTo(21f, 16f)
                lineToRelative(-4f, 4f)
                lineToRelative(-4f, -4f)
                moveTo(17f, 20f)
                verticalLineTo(4f)
                moveTo(3f, 8f)
                lineToRelative(4f, -4f)
                lineToRelative(4f, 4f)
                moveTo(7f, 4f)
                verticalLineToRelative(16f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  消息操作
    // ══════════════════════════════════════════════════════

    /** 复制（Lucide: copy） */
    val Copy: ImageVector by lazy {
        vector("Copy") {
            stroke {
                moveTo(4f, 16f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                verticalLineTo(4f)
                curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
                horizontalLineToRelative(10f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                moveTo(10f, 8f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(10f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(10f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
        }
    }

    /** 重新生成（Lucide: refresh-cw） */
    val Refresh: ImageVector by lazy {
        vector("Refresh") {
            stroke {
                moveTo(3f, 12f)
                arcToRelative(9f, 9f, 0f, false, true, 9f, -9f)
                arcToRelative(9.75f, 9.75f, 0f, false, true, 6.74f, 2.74f)
                lineTo(21f, 8f)
                moveTo(21f, 3f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(-5f)
                moveTo(21f, 12f)
                arcToRelative(9f, 9f, 0f, false, true, -9f, 9f)
                arcToRelative(9.75f, 9.75f, 0f, false, true, -6.74f, -2.74f)
                lineTo(3f, 16f)
                moveTo(8f, 16f)
                horizontalLineTo(3f)
                verticalLineToRelative(5f)
            }
        }
    }

    /** 编辑（Lucide: pencil） */
    val Edit: ImageVector by lazy {
        vector("Edit") {
            stroke {
                moveTo(21.174f, 6.812f)
                arcToRelative(1f, 1f, 0f, false, false, -3.986f, -3.987f)
                lineTo(3.842f, 16.174f)
                arcToRelative(2f, 2f, 0f, false, false, -0.5f, 0.83f)
                lineToRelative(-1.321f, 4.352f)
                arcToRelative(0.5f, 0.5f, 0f, false, false, 0.623f, 0.622f)
                lineToRelative(4.353f, -1.32f)
                arcToRelative(2f, 2f, 0f, false, false, 0.83f, -0.497f)
                close()
                moveTo(15f, 5f)
                lineToRelative(4f, 4f)
            }
        }
    }

    /** 删除（Lucide: trash） */
    val Trash: ImageVector by lazy {
        vector("Trash") {
            stroke {
                moveTo(10f, 11f)
                verticalLineToRelative(6f)
                moveTo(14f, 11f)
                verticalLineToRelative(6f)
                moveTo(19f, 6f)
                verticalLineToRelative(14f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(7f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(6f)
                moveTo(3f, 6f)
                horizontalLineToRelative(18f)
                moveTo(8f, 6f)
                verticalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(2f)
            }
        }
    }

    /** 置顶（Lucide: pin） */
    val Pin: ImageVector by lazy {
        vector("Pin") {
            stroke {
                moveTo(12f, 17f)
                verticalLineToRelative(5f)
                moveTo(9f, 10.76f)
                arcToRelative(2f, 2f, 0f, false, true, -1.11f, 1.79f)
                lineToRelative(-1.78f, 0.9f)
                arcTo(2f, 2f, 0f, false, false, 5f, 15.24f)
                verticalLineTo(16f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
                horizontalLineToRelative(12f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
                verticalLineToRelative(-0.76f)
                arcToRelative(2f, 2f, 0f, false, false, -1.11f, -1.79f)
                lineToRelative(-1.78f, -0.9f)
                arcTo(2f, 2f, 0f, false, true, 15f, 10.76f)
                verticalLineTo(7f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, -4f)
                horizontalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, 4f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
                close()
            }
        }
    }

    /** 取消置顶（Lucide: pin-off） */
    val PinOff: ImageVector by lazy {
        vector("PinOff") {
            stroke {
                moveTo(12f, 17f)
                verticalLineToRelative(5f)
                moveTo(15f, 9.34f)
                verticalLineTo(7f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, -4f)
                horizontalLineTo(7.89f)
                moveTo(2f, 2f)
                lineToRelative(20f, 20f)
                moveTo(9f, 9f)
                verticalLineToRelative(1.76f)
                arcToRelative(2f, 2f, 0f, false, true, -1.11f, 1.79f)
                lineToRelative(-1.78f, 0.9f)
                arcTo(2f, 2f, 0f, false, false, 5f, 15.24f)
                verticalLineTo(16f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
                horizontalLineToRelative(11f)
            }
        }
    }

    /** 多选（Lucide: list-checks） */
    val ListChecks: ImageVector by lazy {
        vector("ListChecks") {
            stroke {
                moveTo(13f, 5f)
                horizontalLineToRelative(8f)
                moveTo(13f, 12f)
                horizontalLineToRelative(8f)
                moveTo(13f, 19f)
                horizontalLineToRelative(8f)
                moveTo(3f, 17f)
                lineToRelative(2f, 2f)
                lineToRelative(4f, -4f)
                moveTo(3f, 7f)
                lineToRelative(2f, 2f)
                lineToRelative(4f, -4f)
            }
        }
    }

    /** 不参与上下文（Lucide: eye-off） */
    val EyeOff: ImageVector by lazy {
        vector("EyeOff") {
            stroke {
                moveTo(10.733f, 5.076f)
                arcToRelative(10.744f, 10.744f, 0f, false, true, 11.205f, 6.575f)
                arcToRelative(1f, 1f, 0f, false, true, 0f, 0.696f)
                arcToRelative(10.747f, 10.747f, 0f, false, true, -1.444f, 2.49f)
                moveTo(14.084f, 14.158f)
                arcToRelative(3f, 3f, 0f, false, true, -4.242f, -4.242f)
                moveTo(17.479f, 17.499f)
                arcToRelative(10.75f, 10.75f, 0f, false, true, -15.417f, -5.151f)
                arcToRelative(1f, 1f, 0f, false, true, 0f, -0.696f)
                arcToRelative(10.75f, 10.75f, 0f, false, true, 4.446f, -5.143f)
                moveTo(2f, 2f)
                lineToRelative(20f, 20f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  输入区
    // ══════════════════════════════════════════════════════

    /** 深度思考（Lucide: sparkles） */
    val Sparkles: ImageVector by lazy {
        vector("Sparkles") {
            stroke {
                moveTo(11.017f, 2.814f)
                arcToRelative(1f, 1f, 0f, false, true, 1.966f, 0f)
                lineToRelative(1.051f, 5.558f)
                arcToRelative(2f, 2f, 0f, false, false, 1.594f, 1.594f)
                lineToRelative(5.558f, 1.051f)
                arcToRelative(1f, 1f, 0f, false, true, 0f, 1.966f)
                lineToRelative(-5.558f, 1.051f)
                arcToRelative(2f, 2f, 0f, false, false, -1.594f, 1.594f)
                lineToRelative(-1.051f, 5.558f)
                arcToRelative(1f, 1f, 0f, false, true, -1.966f, 0f)
                lineToRelative(-1.051f, -5.558f)
                arcToRelative(2f, 2f, 0f, false, false, -1.594f, -1.594f)
                lineToRelative(-5.558f, -1.051f)
                arcToRelative(1f, 1f, 0f, false, true, 0f, -1.966f)
                lineToRelative(5.558f, -1.051f)
                arcToRelative(2f, 2f, 0f, false, false, 1.594f, -1.594f)
                close()
                moveTo(20f, 2f)
                verticalLineToRelative(4f)
                moveTo(22f, 4f)
                horizontalLineToRelative(-4f)
                moveTo(2f, 20f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                close()
            }
        }
    }

    /** 添加图片（Lucide: image） */
    val Image: ImageVector by lazy {
        vector("Image") {
            stroke {
                moveTo(21f, 15f)
                lineToRelative(-3.086f, -3.086f)
                arcToRelative(2f, 2f, 0f, false, false, -2.828f, 0f)
                lineTo(6f, 21f)
                moveTo(7f, 9f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                close()
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
        }
    }

    /** 拍照（Lucide: camera） */
    val Camera: ImageVector by lazy {
        vector("Camera") {
            stroke {
                // 机身 + 顶部取景凸起
                moveTo(14.5f, 4f)
                horizontalLineToRelative(-5f)
                lineTo(7f, 7f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                verticalLineToRelative(9f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                horizontalLineToRelative(16f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
                verticalLineTo(9f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
                horizontalLineToRelative(-3f)
                lineToRelative(-2.5f, -3f)
                close()
                // 镜头。整圆用两段半弧画 —— 与 Circle 那枚同一套写法
                moveTo(15f, 13f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                close()
            }
        }
    }

    /** 纯文本文件附件（Lucide: file-text） */
    val FileText: ImageVector by lazy {
        vector("FileText") {
            stroke {
                // 折角纸张
                moveTo(15f, 2f)
                horizontalLineTo(6f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                verticalLineToRelative(16f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                horizontalLineToRelative(12f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
                verticalLineTo(7f)
                close()
                // 折角
                moveTo(14f, 2f)
                verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                horizontalLineToRelative(4f)
                // 三条文字线
                moveTo(10f, 9f)
                horizontalLineTo(8f)
                moveTo(16f, 13f)
                horizontalLineTo(8f)
                moveTo(16f, 17f)
                horizontalLineTo(8f)
            }
        }
    }

    /** 思考过程（Lucide: brain） */    val Brain: ImageVector by lazy {
        vector("Brain") {
            stroke {
                moveTo(12f, 18f)
                verticalLineTo(5f)
                moveTo(15f, 13f)
                arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, -4f)
                arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, 4f)
                moveTo(17.598f, 6.5f)
                arcTo(3f, 3f, 0f, true, false, 12f, 5f)
                arcToRelative(3f, 3f, 0f, true, false, -5.598f, 1.5f)
                moveTo(17.997f, 5.125f)
                arcToRelative(4f, 4f, 0f, false, true, 2.526f, 5.77f)
                moveTo(18f, 18f)
                arcToRelative(4f, 4f, 0f, false, false, 2f, -7.464f)
                moveTo(19.967f, 17.483f)
                arcTo(4f, 4f, 0f, true, true, 12f, 18f)
                arcToRelative(4f, 4f, 0f, true, true, -7.967f, -0.517f)
                moveTo(6f, 18f)
                arcToRelative(4f, 4f, 0f, false, true, -2f, -7.464f)
                moveTo(6.003f, 5.125f)
                arcToRelative(4f, 4f, 0f, false, false, -2.526f, 5.77f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  模式
    // ══════════════════════════════════════════════════════

    /** 创作模式（Lucide: feather） */
    val Feather: ImageVector by lazy {
        vector("Feather") {
            stroke {
                moveTo(14.086f, 18.412f)
                arcTo(2f, 2f, 0f, false, true, 12.67f, 19f)
                horizontalLineTo(5f)
                verticalLineToRelative(-7.672f)
                arcToRelative(2f, 2f, 0f, false, true, 0.586f, -1.414f)
                lineTo(11.75f, 3.75f)
                arcToRelative(6f, 6f, 0f, true, true, 8.49f, 8.49f)
                close()
                moveTo(16f, 8f)
                lineTo(2f, 22f)
                moveTo(17.488f, 15f)
                horizontalLineTo(9f)
            }
        }
    }

    /** 扮演模式（Lucide: users） */
    val Users: ImageVector by lazy {
        vector("Users") {
            stroke {
                moveTo(16f, 21f)
                verticalLineToRelative(-2f)
                arcToRelative(4f, 4f, 0f, false, false, -4f, -4f)
                horizontalLineTo(6f)
                arcToRelative(4f, 4f, 0f, false, false, -4f, 4f)
                verticalLineToRelative(2f)
                moveTo(16f, 3.128f)
                arcToRelative(4f, 4f, 0f, false, true, 0f, 7.744f)
                moveTo(22f, 21f)
                verticalLineToRelative(-2f)
                arcToRelative(4f, 4f, 0f, false, false, -3f, -3.87f)
                moveTo(5f, 7f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  选择控件
    // ══════════════════════════════════════════════════════

    /** 未选中（Lucide: circle） */
    val Circle: ImageVector by lazy {
        vector("Circle") {
            stroke {
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
            }
        }
    }

    /** 已选中（Lucide: circle-check） */
    val CircleCheck: ImageVector by lazy {
        vector("CircleCheck") {
            stroke {
                moveTo(16f, 9f)
                lineToRelative(-5.5f, 5.5f)
                lineTo(8f, 12f)
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  设置
    // ══════════════════════════════════════════════════════

    /**
     * 外观（Lucide: contrast）
     *
     * 与 Lucide 原图有一处不同：**右半边是填实的**。那边把内层半圆只画了轮廓
     * （r=6，还缩在圆内），21dp 上一个半圆轮廓读不出"一半深一半浅"。
     * 这里填成同半径（r=10，直边正好落在竖直直径上）—— 浅色下就是那个
     * 「半边黑、半边白」的观感。
     */
    val Contrast: ImageVector by lazy {
        vector("Contrast") {
            stroke {
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
            }
            solid {
                // 右半边：12 点顺时针走到 6 点，再闭合回 12 点
                moveTo(12f, 2f)
                arcToRelative(10f, 10f, 0f, false, true, 0f, 20f)
                close()
            }
        }
    }

    /** 浅色（Lucide: sun） */
    val Sun: ImageVector by lazy {
        vector("Sun") {
            stroke {
                moveTo(12f, 2f)
                verticalLineToRelative(2f)
                moveTo(12f, 20f)
                verticalLineToRelative(2f)
                moveTo(4.93f, 4.93f)
                lineToRelative(1.41f, 1.41f)
                moveTo(17.66f, 17.66f)
                lineToRelative(1.41f, 1.41f)
                moveTo(2f, 12f)
                horizontalLineToRelative(2f)
                moveTo(20f, 12f)
                horizontalLineToRelative(2f)
                moveTo(6.34f, 17.66f)
                lineToRelative(-1.41f, 1.41f)
                moveTo(19.07f, 4.93f)
                lineToRelative(-1.41f, 1.41f)
                moveTo(8f, 12f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
                close()
            }
        }
    }

    /** 深色（Lucide: moon） */
    val Moon: ImageVector by lazy {
        vector("Moon") {
            stroke {
                moveTo(20.985f, 12.486f)
                arcToRelative(9f, 9f, 0f, true, true, -9.473f, -9.472f)
                curveToRelative(0.405f, -0.022f, 0.617f, 0.46f, 0.402f, 0.803f)
                arcToRelative(6f, 6f, 0f, false, false, 8.268f, 8.268f)
                curveToRelative(0.344f, -0.215f, 0.825f, -0.004f, 0.803f, 0.401f)
            }
        }
    }

    /** 跟随系统（Lucide: monitor） */
    val Monitor: ImageVector by lazy {
        vector("Monitor") {
            stroke {
                moveTo(4f, 3f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(8f, 21f)
                lineTo(16f, 21f)
                moveTo(12f, 17f)
                lineTo(12f, 21f)
            }
        }
    }

    /** 高级参数（Lucide: sliders-horizontal） */
    val Sliders: ImageVector by lazy {
        vector("Sliders") {
            stroke {
                moveTo(10f, 5f)
                horizontalLineTo(3f)
                moveTo(12f, 19f)
                horizontalLineTo(3f)
                moveTo(14f, 3f)
                verticalLineToRelative(4f)
                moveTo(16f, 17f)
                verticalLineToRelative(4f)
                moveTo(21f, 12f)
                horizontalLineToRelative(-9f)
                moveTo(21f, 19f)
                horizontalLineToRelative(-5f)
                moveTo(21f, 5f)
                horizontalLineToRelative(-7f)
                moveTo(8f, 10f)
                verticalLineToRelative(4f)
                moveTo(8f, 12f)
                horizontalLineTo(3f)
            }
        }
    }

    /** 导出（Lucide: download） */
    val Download: ImageVector by lazy {
        vector("Download") {
            stroke {
                moveTo(12f, 15f)
                verticalLineTo(3f)
                moveTo(21f, 15f)
                verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineToRelative(-4f)
                moveTo(7f, 10f)
                lineToRelative(5f, 5f)
                lineToRelative(5f, -5f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  设置 · 一级入口（每个入口一个独立图标，不复用）
    // ══════════════════════════════════════════════════════

    /** 数据管理（Lucide: database） */
    val Database: ImageVector by lazy {
        vector("Database") {
            stroke {
                // 顶面椭圆
                moveTo(3f, 5f)
                arcToRelative(9f, 3f, 0f, true, true, 18f, 0f)
                arcToRelative(9f, 3f, 0f, true, true, -18f, 0f)
                close()
                // 外壳：左壁 → 底弧 → 右壁
                moveTo(3f, 5f)
                verticalLineTo(19f)
                arcToRelative(9f, 3f, 0f, false, false, 18f, 0f)
                verticalLineTo(5f)
                // 中层分隔
                moveTo(3f, 12f)
                arcToRelative(9f, 3f, 0f, false, false, 18f, 0f)
            }
        }
    }

    /** API 配置（Lucide: server） */
    val Server: ImageVector by lazy {
        vector("Server") {
            stroke {
                moveTo(4f, 2f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(4f, 14f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(16f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(6f, 6f)
                horizontalLineToRelative(0.01f)
                moveTo(6f, 18f)
                horizontalLineToRelative(0.01f)
            }
        }
    }

    /**
     * 主题色（Lucide: palette）
     *
     * 四个小孔用**描边**而不是填充：`r=0.5` 的填充圆在 21dp 图标上只有
     * 不到 1dp，实际看不见；描边后外径 1.5，才和其它线条一个量级。
     */
    val Palette: ImageVector by lazy {
        vector("Palette") {
            stroke {
                moveTo(12f, 2f)
                curveToRelative(-5.5f, 0f, -10f, 4.5f, -10f, 10f)
                reflectiveCurveToRelative(4.5f, 10f, 10f, 10f)
                curveToRelative(0.926f, 0f, 1.648f, -0.746f, 1.648f, -1.688f)
                curveToRelative(0f, -0.437f, -0.18f, -0.835f, -0.437f, -1.125f)
                curveToRelative(-0.29f, -0.289f, -0.438f, -0.652f, -0.438f, -1.125f)
                arcToRelative(1.64f, 1.64f, 0f, false, true, 1.668f, -1.668f)
                horizontalLineToRelative(1.996f)
                curveToRelative(3.051f, 0f, 5.555f, -2.503f, 5.555f, -5.554f)
                curveTo(21.965f, 6.012f, 17.461f, 2f, 12f, 2f)
                close()
                // 色盘上的四个小孔
                hueDot(13.5f, 6.5f)
                hueDot(17.5f, 10.5f)
                hueDot(8.5f, 7.5f)
                hueDot(6.5f, 12.5f)
            }
        }
    }

    private fun PathBuilder.hueDot(x: Float, y: Float) {
        moveTo(x - 0.5f, y)
        arcToRelative(0.5f, 0.5f, 0f, true, true, 1f, 0f)
        arcToRelative(0.5f, 0.5f, 0f, true, true, -1f, 0f)
        close()
    }

    /** 字体大小（Lucide: a-large-small） */
    /** 界面语言（Lucide: globe） */
    val Globe: ImageVector by lazy {
        vector("Globe") {
            stroke {
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
                moveTo(2f, 12f)
                lineTo(22f, 12f)
                moveTo(12f, 2f)
                arcToRelative(14.5f, 14.5f, 0f, false, false, 0f, 20f)
                arcToRelative(14.5f, 14.5f, 0f, false, false, 0f, -20f)
            }
        }
    }

    val FontSize: ImageVector by lazy {
        vector("FontSize") {
            stroke {
                moveTo(15f, 16f)
                lineTo(17.536f, 8.672f)
                arcToRelative(1.02f, 1.02f, 0f, false, true, 1.928f, 0f)
                lineTo(22f, 16f)
                moveTo(15.697f, 14f)
                horizontalLineTo(21.303f)
                moveTo(2f, 16f)
                lineTo(6.039f, 6.31f)
                arcToRelative(0.5f, 0.5f, 0f, false, true, 0.923f, 0f)
                lineTo(11f, 16f)
                moveTo(3.304f, 13f)
                horizontalLineTo(9.696f)
            }
        }
    }

    /**
     * 自动折叠思考内容（Lucide: fold-vertical）
     *
     * 不用 `chevrons-down-up` —— 那个在 21dp 下就是个 X，和"关闭"混淆。
     * 这个带中间的虚线分隔，语义明确是"收起"。
     */
    val Collapse: ImageVector by lazy {
        vector("Collapse") {
            stroke {
                moveTo(12f, 22f)
                verticalLineToRelative(-6f)
                moveTo(12f, 8f)
                verticalLineTo(2f)
                moveTo(4f, 12f)
                horizontalLineTo(2f)
                moveTo(10f, 12f)
                horizontalLineTo(8f)
                moveTo(16f, 12f)
                horizontalLineTo(14f)
                moveTo(22f, 12f)
                horizontalLineTo(20f)
                moveTo(15f, 19f)
                lineTo(12f, 16f)
                lineTo(9f, 19f)
                moveTo(15f, 5f)
                lineTo(12f, 8f)
                lineTo(9f, 5f)
            }
        }
    }

    /**
     * 对话气泡（Lucide: message-square）。**方形**气泡，尾巴在左下。
     *
     * 两处语义共用同一个字形：对话页空态，以及设置页的「预设 / 提示词库」。
     * 字形既然相同就只定义一次 —— 分开写两份，改形状时必然漏掉一处。
     * [Prompt] 是它的语义别名。
     *
     * 与 [Bubble] 的区别：那个是**实心**的品牌标识，属于启动图与 App 图标；
     * 这里是描边的，跟界面其余图标是一套。
     */
    val MessageSquare: ImageVector by lazy {
        vector("MessageSquare") {
            stroke {
                moveTo(21f, 15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(7f)
                lineTo(3f, 21f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                close()
            }
        }
    }

    /**
     * 「从这里新建对话」（Lucide: message-square-plus）。
     *
     * 气泡里多一个加号 —— 语义正是"把这段对话另起一个"。 */
    val MessageSquarePlus: ImageVector by lazy {
        vector("MessageSquarePlus") {
            stroke {
                moveTo(21f, 15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(7f)
                lineTo(3f, 21f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                close()
                moveTo(12f, 7f)
                verticalLineTo(13f)
                moveTo(9f, 10f)
                horizontalLineTo(15f)
            }
        }
    }


    /**
     * 更多 / 设置（三个竖点）。
     *
     * v0.19.1：替换聊天页顶栏原来的「新建对话」。
     * 用竖三点而不是齿轮：左侧 `Menu`（☰）已经是「历史」，
     * 右侧再来一个列表形状会两边混淆；竖三点是移动端标准的「次级入口」符号。
     */
    val MenuDots: ImageVector by lazy {
        vector("MenuDots") {
            stroke {
                moveTo(12f, 6f)
                verticalLineTo(6.01f)
                moveTo(12f, 12f)
                verticalLineTo(12.01f)
                moveTo(12f, 18f)
                verticalLineTo(18.01f)
            }
        }
    }

    /**
     * 预设 / 提示词库 —— [MessageSquare] 的语义别名。
     *
     * 描边风格，与设置页其它一级入口（Palette / FontSize / Collapse / Info）
     * 是一套。实心的 [Bubble] 那个是品牌标识，混在描边图标里会显得很重。
     */
    val Prompt: ImageVector get() = MessageSquare

    /** 关于（Lucide: info） */
    val Info: ImageVector by lazy {
        vector("Info") {
            stroke {
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
                moveTo(12f, 16f)
                verticalLineTo(12f)
                moveTo(12f, 8f)
                horizontalLineToRelative(0.01f)
            }
        }
    }

    /** 导入（Lucide: upload） */
    val Upload: ImageVector by lazy {
        vector("Upload") {
            stroke {
                moveTo(21f, 15f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(15f)
                moveTo(17f, 8f)
                lineTo(12f, 3f)
                lineTo(7f, 8f)
                moveTo(12f, 3f)
                verticalLineTo(15f)
            }
        }
    }

    /** 继续生成（Lucide: play） */
    val Play: ImageVector by lazy {
        vector("Play") {
            stroke {
                moveTo(7f, 4f)
                lineTo(19f, 12f)
                lineTo(7f, 20f)
                close()
            }
        }
    }

    /** 朗读（Lucide: volume-2） */
    val Volume: ImageVector by lazy {
        vector("Volume") {
            stroke {
                // 喇叭
                moveTo(11f, 5f)
                lineTo(6f, 9f)
                horizontalLineTo(2f)
                verticalLineTo(15f)
                horizontalLineTo(6f)
                lineTo(11f, 19f)
                close()
                // 两道声波
                moveTo(15.54f, 8.46f)
                arcToRelative(5f, 5f, 0f, false, true, 0f, 7.07f)
                moveTo(19.07f, 4.93f)
                arcToRelative(10f, 10f, 0f, false, true, 0f, 14.14f)
            }
        }
    }

    /** 语音输入（Lucide: mic） */
    val Mic: ImageVector by lazy {
        vector("Mic") {
            stroke {
                moveTo(12f, 2f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
                verticalLineTo(12f)
                arcToRelative(3f, 3f, 0f, false, true, -6f, 0f)
                verticalLineTo(5f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
                close()
                moveTo(19f, 10f)
                verticalLineTo(12f)
                arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
                verticalLineTo(10f)
                moveTo(12f, 19f)
                verticalLineTo(22f)
            }
        }
    }
}

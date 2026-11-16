package cd.ptrel.dshpostapi.ui.common

/**
 * 语音相关功能的**总开关**（当前：临时下线）。
 *
 * ### 为什么是常量而不是设置项
 *
 * 这两项是"先关掉、以后再开"，不是用户偏好：关掉期间界面里不该出现任何入口，
 * 也不该有半可用的状态。做成设置项反而会让"关着但能点"这种情况存在。
 *
 * ### 关掉之后要保证什么
 *
 * - 输入栏上**没有**任何语音入口（`holdToTalk` 手势不挂、麦克风图标不出现）；
 * - 每条回复的**朗读**按钮不出现，TTS 引擎不会被初始化；
 * - `AndroidManifest.xml` 里 `RECORD_AUDIO` 已移除 —— 装完之后系统设置里
 *   不会再有"麦克风"这一项，用户不会被一个用不上的权限打扰。
 *
 * 重新开启时：这两个常量改回 `true`，再把清单里的 `RECORD_AUDIO` 与两段
 * `<queries>`（识别服务 / TTS 服务）加回去 —— 少一样都会变成"点了没反应"。
 */
const val VoiceInputEnabled = false

/** 朗读（系统 TTS）。与 [VoiceInputEnabled] 相互独立，可以只开一个。 */
const val ReadAloudEnabled = false

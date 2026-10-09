# 更新日志

本文件记录 TRTC-ASR Java SDK 的所有重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

### 变更

- 识别上下文 `context` 对外暂不支持：实时识别移除 setter（`setContext`），一句话识别与录音文件请求不再携带该字段。

## [1.3.0] - 2026-09-23

### 新增

- 实时说话人分离支持**断点续传**（`speaker context`）：
  - **v2 实时接口同步支持**：URL query 参数 `enable_speaker_context` / `speaker_context_id`
    （`setEnableSpeakerContext` / `setSpeakerContextId`），首响应 `speaker_continue` 解析为
    `SpeechRecognitionResponse.speakerContinue`，经 `SpeechRecognizer.getSpeakerContinue()` 读取
    （v2 的 `onRecognitionStart` 早于服务端首响应，回调里拿不到该字段）；
  - `setEnableSpeakerContext(mode)`：`1` 同步（首响应等快照恢复完成并回报
    `continue_status`）/ `2` 异步（首响应只返回 `speaker_context_id`，
    恢复在后台进行）；`0` 或不调用为关闭（默认）。
  - `setSpeakerContextId(id)`：传回上次首响应返回的 `speaker_context_id`，
    使新连接继续使用断点前的说话人编号；未带 / 过期 / 非法 ID 按新会话处理。
  - 首响应新增 `speaker_continue`，SDK 解出为 `SpeakerContinue`
    （`continue_status`: `fresh`/`resumed`/`degraded`/`disabled` 与
    `speaker_context_id`）：可通过新增的 `getSpeakerContinue()` 读取，也会
    随 `onRecognitionStart` 回调的响应一起下发
    （`SpeechRecognitionResponse.getSpeakerContinue()`）；常量
    `Wire.SPEAKER_CONTEXT_*` / `Wire.CONTINUE_STATUS_*`。
  - v3：同步模式携带 `speaker_context_id` 时，`start()` 会等快照加载完成再返回，
    该路径的首响应等待上限由 5s 放宽到 15s；首次签发、异步模式以及未开启续传仍是 5s。
    v2 的 `start()` 在 WebSocket 建连后即返回，没有这段等待；同步续传请等 getter
    拿到首响应再发送音频。
  - 两个参数需与 `speaker_diarization=1/3` 同开，否则 `start()` 本地报错
    （`1001`），不浪费连接。
  - 示例 `V3RealtimeAsrExample` 新增第 4/5 个参数（断点续传模式与
    `speaker_context_id`）；中英文 README 新增
    「说话人分离断点续传」章节（含重连流程与字段表）。

### 文档

- 修正 README（中/英）：v3 实时 `voice_id` 与活跃流重复时服务端**不会**返回
  `4001`（canary 实测：重复 voice_id 的各连接独立识别、互不影响），错误码表
  4001 行不再标注 "voice_id 冲突"，改为提示客户端自行保证 `voice_id` 唯一。

## [1.2.5] - 2026-09-16

### 文档

- v3 已全量开放，去掉示例、注释与 changelog 中「需为 SDKAppID 开启灰度开关」
  的使用前提；公开注释与测试只描述客户可见的 `auth` 字段
  （`sdkappid` / `usersig` / 离线 `request_id`）。

## [1.2.4] - 2026-09-15

### 文档

- 中英文 README 与其余五个语言 SDK 对齐：前提条件与凭证获取补上「v3 以
  `SDKAppID` 为唯一客户维度、不再需要腾讯云 `AppID`」；FAQ「错误码怎么看」补上
  v3 数字错误码清单与 SDK 本地 10xx 区间说明；配置项表补三态字段、热词维度、
  词级/字级时间与上下文字段说明，并标注 v3 不再携带 v2 的 `customization_id`
  / `replace_text_id`；示例清单补齐 v2 的三个示例；在线时序图补多句 index
  递增说明。

## [1.2.3] - 2026-09-15

### 修复

- **会话结束时可能既不回调 onComplete 也不回调 onFail**：JDK WebSocket
  客户端投递 close 帧不消耗 `request(n)` 信用额度，此前逐帧 `request(1)` 的
  手动信用窗会让 close 帧超车已经到达的终帧文本——`onClose` 先执行并关闭
  回调线程池，终帧永远无法送达。现在改为一次请求无界帧数
  （`Long.MAX_VALUE`），投递严格按到达顺序。
- **stop 过程中连接提前关闭被当作静默成功**：STOPPING 态下收到
  `onClose` / `onError` 但终帧始终未到达时，此前只结束等待、不回调任何
  错误，截断的会话看起来像正常完成。现在会回调 `onFail`（连接在收到
  最终响应前被服务端关闭 / 读取消息失败），截断不再被伪装成成功。
  v2 与 v3 同步修复。

## [1.2.2] - 2026-09-14

### 变更

- 示例与 README 的默认引擎由 `16k_zh_en` 改为 `bigmodel`（推荐用法）；
  `engine_model_type` 为必填项，示例不再提供默认值（缺失时打印用法并以
  退出码 2 结束）；`language` 未显式指定时仅 `bigmodel` 默认补 `zh`，
  其他引擎保持服务端自动检测；参数表与引擎列表同步更新。

### 修复

- **录音文件识别（v2）词级时间戳恒为 0**：`DescribeTaskStatus` 返回的
  `ResultDetail[].Words[]` 里时间偏移字段名是 `StartTime` / `EndTime`，
  SDK 此前按 `OffsetStartMs` / `OffsetEndMs` 解析，取不到值，导致
  `SentenceWords.getOffsetStartMs()` / `getOffsetEndMs()` 全部为 0。现在
  用 `@JsonProperty("StartTime")` + `@JsonAlias("OffsetStartMs")` 兼容两种
  字段名（显式 0 不会被回退覆盖）。

## [1.2.1] - 2026-09-10

### 变更

- README 鉴权章节按**在线（流式）/ 离线（HTTP）**拆分，各自给出 `auth` 字段表，
  并补充 UserSig 规则：identifier 绑定（在线 `voice_id` / 离线 `request_id`）、
  自动签名有效期 86400 秒且每条连接 / 每次请求重新生成、调用 `credential.setUserSig()`
  传入固定签名后不再刷新、签名与站点（`credential.setSite(Credential.SITE_INTL)`）绑定。
  中英文 README 同步。

## [1.2.0] - 2026-09-09

### 新增

- 新增 v3 协议客户端，位于独立包 `com.tencent.trtcasr.v3`，与 v2/v1 客户端（`com.tencent.trtcasr.asr` 包）完全解耦，两者可独立选用、互不影响：
  - `v3.SpeechRecognizer`：WebSocket `/asr/v3`，URL 仅携带 `voice_id`，鉴权与识别参数通过首帧 JSON（`{"type":"start","auth":{...},"params":{...}}`）下发；`start()` 同步等待服务端 ack，鉴权失败（4002）/参数非法（4001）等错误同步抛出
  - `v3.SentenceRecognizer`：`POST /v3/transcribe`，body 为 `{auth, params}` 分块，请求/响应均为 snake_case 扁平结构（无 `Response` 外壳）
  - `v3.FileRecognizer`：`POST /v3/create_transcription` + `/v3/describe_transcription`，任务 ID 为 `transcription_id`（与 v1 `RecTaskId` 不通用）
  - `v3.Wire.newCredential(sdkAppId, secretKey)`：v3 不再需要腾讯云 AppID
  - 服务端数字错误码（4xxx/5xxx）直接作为 `ASRException.getCode()` 抛出，与 SDK 本地错误码（10xx）区间不冲突；离线错误一律以响应 body 的 `code` 为准（鉴权失败也是 HTTP 200）
  - `needvad`/`convertNumMode` 显式传 0 会真正下发（v2 query 传参会吞掉 0 值）；说话人分离的 `speaker_roles` 元素序列化为 snake_case（`role_name`/`audio_url`，v3 包自带 `SpeakerRole` 类型），声纹 ID 列表为 `voiceprint_ids`
  - v3 新增能力：`word_with_space`、`context`（识别上下文 text/terms/general）；录音文件支持 `audio_urls` 分布式录音
  - 下行消息结构与 v2 完全一致，listener 接口（`asr.SpeechRecognitionListener`）两版共用
- 新增 `V3RealtimeAsrExample` / `V3SentenceAsrExample` / `V3FileAsrExample` 示例
- README 改为只承载 v3 协议文档；v2 / v1 协议与客户端说明移至 `docs/v2_protocol.md`

### 变更

- 仓库迁移至 `github.com/Tencent-RTC/trtc-asr-sdk-java`，功能与 API 无任何变化。
  旧仓库保留 `v1.0.0` 并归档，不再更新。

## [1.0.0] - 2026-09-02

首个正式版本。

### 新增

- Credential 可通过 `setSite` 选择国内站（默认，`asr.cloud-rtc.com`）或国际站（`asr-intl.cloud-rtc.com`），三个识别器共用
- 实时语音识别（WebSocket），支持流式写入与优雅停止
- 一句话识别（HTTP）
- 录音文件识别（异步 HTTP，CreateRecTask + DescribeTaskStatus）
- 说话人分离：匿名聚类与声纹角色认证两种模式
- VAD 调优、热词、自定义语言模型、脏词/语气词/标点过滤等识别参数
- 所有请求上报 SDK 自身标识（`platform` / `sdk_lang` / `sdk_type` / `version`），
  便于服务端按语言、版本、平台定位客户问题
- MIT LICENSE
- GitHub Actions CI：JDK 17/21 测试矩阵，外加 `mvn package` 并上传 jar 产物

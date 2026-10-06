# 服务器端流式 Ear-EEG 上传改造说明

本文对应 Android 协议 `inputds-eeg-stream-v1`。目标是在采集过程中接收已经本地持久化的双耳分块，采集结束后只补传缺块并提交 marker，从而缩短“采集完成到训练任务创建”的等待时间。

手机仍保留 `continuous.npz`，并在新接口返回 `404`、`405` 或 `501` 时自动回退原有 `POST /api/v1/me/datasets`。因此建议先部署服务器，再发布手机；反向顺序也不会中断现有训练。

## 1. 必须统一的预处理合同

所有模型，包括 EEGNet 和 CSANet，只接受以下合同：

```json
{
  "version": "1",
  "window_points": 500,
  "stream_steps": [],
  "window_steps": [
    {
      "operation": "bandpass",
      "implementation": "butterworth_filtfilt",
      "low_hz": 1.0,
      "high_hz": 45.0,
      "order": 2
    }
  ]
}
```

`window_points` 随动作时间变化，等于 `round(action_seconds × 500)`。服务器必须移除按 `model_key` 自动切换到 CSANet 0.1–40 Hz + Z-score 的逻辑；模型算法和预处理是两个独立维度。

训练导出的 ONNX 继续写入同一份 `inference_preprocessing` 和其 SHA-256。Android 会严格校验下载模型的合同。

## 2. 推荐的数据表/对象

### streaming_uploads

- `upload_id`：服务器生成，字符串或 UUID。
- `server_user_id`、`profile_id`：所有权边界。
- `client_session_id`：手机 Session ID。
- `protocol_version`：固定 `inputds-eeg-stream-v1`。
- `chunk_format_version`：当前为 `1`。
- `chunk_points`：通常为 2500，即 5 秒。
- `sampling_rate_hz`：固定 500。
- `classification_protocol`、`label_names`。
- `status`：`open | finalizing | finalized | aborted | expired`。
- `created_at`、`updated_at`、`expires_at`。
- `dataset_id`、`dataset_version`：finalize 成功后填写。

对 `(server_user_id, profile_id, client_session_id)` 建唯一索引，使创建接口天然幂等。

### streaming_chunks

- `upload_id`、`chunk_index`，联合唯一键。
- `start_sample`。
- `sample_count`。
- `sha256`。
- `object_key` 或本地临时文件路径。
- `byte_count`、`created_at`。

同一 `upload_id + chunk_index`：

- 哈希和元数据完全相同：返回成功，视为幂等重放。
- 任一字段不同：返回 `409 Conflict`，禁止覆盖。

## 3. 接口一：创建或恢复上传 Session

```http
POST /api/v1/me/streaming-datasets
Authorization: Bearer <token>
Content-Type: application/json
```

请求示例：

```json
{
  "profile_id": "profile-id",
  "client_session_id": "20260818T031500.000Z-ab12cd34",
  "protocol_version": "inputds-eeg-stream-v1",
  "chunk_format_version": 1,
  "chunk_points": 2500,
  "sampling_rate_hz": 500,
  "channels": ["left_ear", "right_ear"],
  "value_unit": "uV",
  "classification_protocol": "four_class",
  "label_names": ["rest", "jaw", "look_left", "look_right"],
  "inference_preprocessing": {
    "version": "1",
    "window_points": 500,
    "stream_steps": [],
    "window_steps": [{
      "operation": "bandpass",
      "implementation": "butterworth_filtfilt",
      "low_hz": 1.0,
      "high_hz": 45.0,
      "order": 2
    }]
  }
}
```

响应必须包含：

```json
{
  "upload_id": "upload-id",
  "status": "open",
  "uploaded_chunks": [0, 1, 2]
}
```

重复提交相同 `client_session_id` 返回原 `upload_id` 和服务器已确认的块，不创建新记录。

校验要求：

- token 只能操作自己的 profile；
- profile 必须启用；
- 采样率必须为 500、通道顺序必须固定；
- 标签顺序必须与四分类或六分类协议完全一致；
- 预处理必须是统一 1–45 Hz 合同；
- 已 finalized 的 Session 可以返回原结果，但不能重新打开。

## 4. 接口二：查询恢复状态

```http
GET /api/v1/me/streaming-datasets/{upload_id}
```

响应：

```json
{
  "upload_id": "upload-id",
  "status": "open",
  "uploaded_chunks": [0, 1, 2]
}
```

手机以服务器返回的列表为准更新本地确认状态。该接口用于 App 重启、HTTP 响应丢失和 WorkManager 重试后的断点续传。

## 5. 接口三：上传一个双耳原子分块

```http
PUT /api/v1/me/streaming-datasets/{upload_id}/chunks/{chunk_index}
Content-Type: application/vnd.inputds.eeg-chunk
X-Chunk-SHA256: <64位小写十六进制>
X-Start-Sample: 12500
X-Sample-Count: 2500
```

请求体是固定小端格式：

| 偏移 | 长度 | 类型 | 值 |
|---:|---:|---|---|
| 0 | 4 | ASCII | `IDSC` |
| 4 | 4 | int32 LE | 格式版本，目前为 1 |
| 8 | 4 | int32 LE | chunk index |
| 12 | 8 | int64 LE | start sample |
| 20 | 4 | int32 LE | 每耳 sample count |
| 24 | 4 | int32 LE | channel count，固定 2 |
| 28 | `count×4` | float32 LE | 全部左耳数据 |
| 后续 | `count×4` | float32 LE | 全部右耳数据 |

总字节数必须是：

```text
28 + sample_count × 2 × 4
```

服务器接收时必须：

1. 限制请求体大小，例如不得超过配置分块点数对应大小。
2. 边接收边计算 SHA-256，不能先把任意大小请求全部读入内存。
3. 比对 URL、请求头和二进制头中的 index/start/count。
4. 拒绝 NaN/Inf；保留物理单位 µV。
5. 将对象写入临时 key，哈希通过后原子提交数据库记录。
6. 成功返回任意 `2xx`；推荐 `201`，幂等重放推荐 `200`。

不要允许单独上传左耳或右耳。一个 chunk 的两耳数据必须共同成功或共同失败。

## 6. 接口四：finalize

```http
POST /api/v1/me/streaming-datasets/{upload_id}/finalize
Content-Type: application/json
```

请求包含最终总点数、完整 metadata、marker 和客户端分块清单：

```json
{
  "profile_id": "profile-id",
  "client_session_id": "session-id",
  "total_sample_count": 100000,
  "metadata": {
    "data_mode": "continuous_marked",
    "data_format": "npz",
    "sampling_rate_hz": 500,
    "channels": ["left_ear", "right_ear"],
    "sample_count": 100000,
    "target_points": 500,
    "markers": [
      {"label": "jaw", "left_sample": 2125, "right_sample": 2125, "round": 1}
    ],
    "inference_preprocessing": {"...": "统一1–45Hz合同"}
  },
  "chunks": [
    {"index": 0, "start_sample": 0, "sample_count": 2500, "sha256": "..."}
  ]
}
```

finalize 必须在事务或等价状态机中执行：

1. 将 `open` 原子改为 `finalizing`；并发 finalize 只能有一个执行者。
2. 清单 index 必须从 0 连续递增。
3. 第一块 `start_sample=0`；后一块起点等于前一块起点加点数。
4. 除最后一块外，点数应等于 `chunk_points`。
5. 分块总点数必须等于 `total_sample_count` 和 metadata 的 `sample_count`。
6. 每个客户端哈希必须与服务器记录一致。
7. marker 的左右采样点必须相等、标签合法且窗口不能越界。
8. 验证统一预处理合同。
9. 将 chunks 合并为训练数据集版本。可以在服务端生成 `continuous.npz`，也可以让训练适配器直接按块顺序读取。
10. 创建正常 dataset/version 记录，再将上传状态改为 `finalized`。

响应必须与旧上传接口兼容：

```json
{
  "dataset_id": "dataset-id",
  "version": 3,
  "inference_preprocessing_sha256": "..."
}
```

重复 finalize 必须返回同一个 dataset/version，不得创建重复版本。

建议错误码：

- `409 STREAM_MISSING_CHUNKS`：仍缺块；detail 中返回缺失 index。
- `409 STREAM_CHUNK_CONFLICT`：重复 index 但哈希不同。
- `422 STREAM_NON_CONTIGUOUS`：采样位置不连续。
- `422 STREAM_MARKER_OUT_OF_RANGE`：marker 窗口越界。
- `422 PREPROCESSING_CONTRACT_MISMATCH`：不是统一 1–45 Hz。

## 7. 可选接口：取消

```http
DELETE /api/v1/me/streaming-datasets/{upload_id}
```

仅允许删除 `open` 上传。已经 finalized 的 dataset 应沿用现有数据删除权限，不通过此接口删除。

无论是否实现 DELETE，都必须设置临时 Session 清理任务：例如 7 天未更新的 `open` 上传改为 `expired`，随后删除临时对象。清理任务不能删除 finalized dataset。

## 8. 与训练接口的衔接

finalize 返回 dataset/version 后，手机继续调用现有：

```http
POST /api/v1/me/training-jobs
GET  /api/v1/me/training-jobs/{job_id}
GET  /api/v1/me/training-jobs/{job_id}/model
```

无需新增流式训练接口。正式训练必须等 finalize 完成，以确保 marker、类别数量和数据完整性最终确定。

多个模型可以引用同一个 finalized dataset/version。服务器不得因为 `model_key` 不同而重复保存同一份连续数据。

## 9. 部署顺序

1. 服务器训练代码先统一为 1–45 Hz。
2. 实现 streaming uploads 表、chunks 表和对象存储临时前缀。
3. 实现 POST/GET/PUT/finalize，完成幂等和权限测试。
4. 保留旧 `POST /api/v1/me/datasets`。
5. 在测试环境用 Android 真机验证在线、断网、杀进程、重复 PUT、重复 finalize。
6. 发布服务器。
7. 发布手机端。
8. 观察流式成功率后，再决定旧完整 NPZ 接口的退役计划；当前手机仍依赖它作为回退。

## 10. 验收清单

- 连续采集时上传变慢不会降低 BLE 配对产出率。
- 第 N 块响应丢失后重传不会重复数据。
- App 被杀死后 GET 状态并只补传缺块。
- 分块乱序到达可接受，但 finalize 前必须齐全且逻辑连续。
- 篡改任一字节会触发 SHA-256 失败。
- 左右耳点数不同、marker 越界、NaN/Inf 均被拒绝。
- 服务器离线完成采集后，恢复网络可从本地继续。
- 新接口不存在时，Android 自动退回完整 NPZ 上传。
- EEGNet 和 CSANet 收到完全相同的 1–45 Hz 预处理合同。
- 重复 finalize 返回同一 dataset/version。

## 11. 手机与服务器最终确认项

以下内容是 `inputds-eeg-stream-v1` 的最终协议，覆盖前文中仍带“建议”或未明确的
部分。

### 11.1 固定分类顺序

```text
four_class: rest, jaw, look_left, look_right
six_class:  rest, look_left, look_right, jaw, look_left_right, look_right_left
```

服务器不得排序。数据集 metadata、训练标签、混淆矩阵、ONNX logits 和手机推理
必须使用同一顺序。

### 11.2 marker 是窗口起点

服务器固定提取 `[marker, marker + window_points)`，不向前取样。
`left_sample == right_sample`，`training_windows.start_offset_samples == 0`；负数、
左右不一致或窗口越过 `total_sample_count` 均拒绝。

### 11.3 分块哈希

`X-Chunk-SHA256` 对完整 `.eegchunk` 文件计算，包括28字节头、左耳 float32 和右耳
float32，输出64位小写十六进制。服务器边接收完整 HTTP body 边计算。

### 11.4 预处理哈希

只对完整 `inference_preprocessing` 对象执行 RFC 8785 JSON Canonicalization Scheme，
再对 canonical JSON 的 UTF-8 字节计算 SHA-256。所有服务器调用点必须复用唯一的
`canonical_preprocessing_sha256` 实现。

### 11.5 Session 冲突

相同设备、profile 和 `client_session_id` 只有在协议版本、分块参数、采样率、通道、
单位、分类协议、标签顺序和预处理 canonical hash 全部相同时才幂等恢复。任何字段
不同返回 `409 STREAM_SESSION_CONFLICT` 并列出冲突字段，不覆盖原 Session。

### 11.6 过期与回退

open Session 按最后活动时间保留7天。创建/恢复、成功 PUT、GET 和 finalize 尝试均
刷新活动时间。过期查询返回 `404`，手机使用本地 `continuous.npz` 回退旧上传接口，
不要求重新采集。

### 11.7 正式 NPZ

finalize 后服务器生成并永久保存正式 `continuous.npz`，内部仅包含一维 `<f4` 的
`left.npy` 和 `right.npy`，长度均等于 `total_sample_count`。只有 NPZ 完整性检查、
持久写入和 dataset/version 事务全部成功后才允许清理分块；分块额外保留24小时。
重复 finalize 直接返回原 dataset/version。

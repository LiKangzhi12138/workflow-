const MOJIBAKE_PATTERN =
  /(?:�|\?\?\?|\?\?|é—|é|å©|ç¼|é–»|å“„|鍚|涓|鏂|鐢|骞|璇|鎴|妯|鏁|褰|浣|瀹|閫|鏌|鏃|鏈|寮|缁|瑙|杩|搴|锛|銆|乊)/
const TECHNICAL_PATTERN =
  /Traceback|org\.springframework|java\.sql|SQLException|BadSqlGrammar|JDBC|Data too long|HTTP 500|Request failed with status code 500|Network Error|weights_only|Weights only load failed|DetectionModel|<html|<!DOCTYPE/i

export function summarizeUiErrorMessage(rawMessage?: string, fallback = '系统处理异常，请稍后重试') {
  const normalized = String(rawMessage || '')
    .replace(/\s+/g, ' ')
    .trim()

  if (!normalized) {
    return fallback
  }

  if (/Python 创建任务失败.*(?:请求体为空|接口字段不匹配)|请求参数异常|\"loc\":\s*\[\s*\"body\"\s*\]|Field required|UNPROCESSABLE_ENTITY|Unprocessable Entity/i.test(normalized)) {
    return 'Python 服务请求参数异常，请检查模型、数据集或任务配置'
  }
  if (/联邦学习聚合失败.*(?:请求体为空|接口字段不匹配)|federated.*(?:422|unprocessable)/i.test(normalized)) {
    return '联邦聚合请求参数异常，请检查模型列表、输出路径和聚合配置'
  }
  if (/weights_only|Weights only load failed|DetectionModel|ultralytics/i.test(normalized)) {
    return '联邦学习聚合失败：YOLO 模型加载失败，请查看服务端日志'
  }
  if (/模型结构不一致|参数键集合无法对齐|张量形状不一致|missing key|unexpected key|size mismatch|state_dict/i.test(normalized)) {
    return '联邦学习聚合失败：模型结构不一致'
  }
  if (/checkpoint|无法提取参数|无法识别模型文件格式/i.test(normalized)) {
    return '联邦学习聚合失败：模型 checkpoint 不合法'
  }
  if (/全局模型保存失败|未返回全局模型路径|global model|output model|torch\.save/i.test(normalized)) {
    return '联邦学习聚合失败：全局模型保存失败'
  }
  if (/联邦学习聚合失败|联邦聚合失败|federated aggregation/i.test(normalized)) {
    return '联邦聚合失败，请检查模型文件是否存在、模型结构是否一致、输出目录是否可写'
  }
  if (/ECONNABORTED|timeout|timed out|超时/i.test(normalized)) {
    return '请求超时，请稍后重试；如果正在验证模型，请稍等后刷新进度'
  }
  if (/Network Error|connection refused|failed to fetch|ERR_CONNECTION/i.test(normalized)) {
    return '网络请求失败，请检查前端、Java 后端和 Python 服务是否正常运行'
  }
  if (TECHNICAL_PATTERN.test(normalized) || MOJIBAKE_PATTERN.test(normalized)) {
    return fallback
  }

  return normalized.length > 160 ? `${normalized.slice(0, 157)}...` : normalized
}

const string = { type: 'string' };
const integer = { type: 'integer' };
const id = { type: 'integer', minimum: 1 };
const ids = { type: 'array', items: id };
const object = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const task = object({ title: string, description: string, category: string, dependsOnIndexes: ids, dependsOnTaskIds: ids }, ['title', 'description', 'category', 'dependsOnIndexes', 'dependsOnTaskIds']);
const verification = object({ name: string, result: { type: 'string', enum: ['PASSED', 'FAILED', 'NOT_RUN'] }, detail: string }, ['name', 'result', 'detail']);
const specs = [
  ['create_project', '建立專案；既有完整名稱會沿用。', { name: string, description: string }, ['name']],
  ['create_tasks', '批次建立任務與前置相依，序號從 1 開始且前置須先建立。', { projectId: id, tasks: { type: 'array', minItems: 1, maxItems: 50, items: task } }, ['projectId', 'tasks']],
  ['list_tasks', '依專案名稱或 ID 查任務、認領與前置狀態。', { projectId: id, projectName: string, status: string, category: string, includeDescription: { type: 'boolean' } }],
  ['claim_next_task', '原子認領可執行 TODO；跳過未完成前置。claimToken 僅回傳一次，不得記錄或外流。', { projectName: string, category: string, assignee: string }, ['projectName', 'category', 'assignee']],
  ['update_task_status', '更新自己的認領狀態。持有 token 才能操作；歸還 TODO 清除認領。', { taskId: id, status: string, note: string, claimToken: string }, ['taskId', 'status']],
  ['reset_task_claim', 'leader 確認 worker 遺失 token 後復原為 TODO；worker 不得使用。', { taskId: id, note: string }, ['taskId']],
  ['block_task', '記錄結構化阻塞；DEPENDENCY 需同專案 blockingTaskIds，持有認領 token 才能操作。', { taskId: id, claimToken: string, reasonType: { type: 'string', enum: ['DEPENDENCY', 'USER_INPUT', 'TECHNICAL', 'ENVIRONMENT', 'EXTERNAL', 'OTHER'] }, detail: string, blockingTaskIds: ids, expectedVersion: integer }, ['taskId', 'reasonType', 'detail']],
  ['complete_task', '完成任務並記錄證據，至少一項驗證，不接受 FAILED；NOT_RUN 需理由。', { taskId: id, claimToken: string, summary: string, verificationResults: { type: 'array', minItems: 1, items: verification }, changedFiles: string, commitRef: string, expectedVersion: integer }, ['taskId', 'summary', 'verificationResults']],
  ['update_task_details', 'leader 修改 TODO/BLOCKED 規格；分類只允許 TODO，必須提供版本。', { taskId: id, title: string, description: string, category: string, expectedVersion: integer }, ['taskId', 'expectedVersion']],
  ['set_task_dependencies', 'leader 完整取代 TODO 前置，空清單清空；同專案、無循環且版本相符。', { taskId: id, prerequisiteTaskIds: ids, expectedVersion: integer }, ['taskId', 'prerequisiteTaskIds', 'expectedVersion']],
  ['preview_archive_project', 'leader 在目前對話獲明確授權後預覽封存；IN_PROGRESS 需再次確認。', { projectName: string }, ['projectName']],
  ['archive_project', 'leader 先 preview 並獲目前明確授權後封存，IN_PROGRESS 需再次確認。', { projectName: string, reason: string, inProgressConfirmed: { type: 'boolean' } }, ['projectName', 'reason']],
  ['restore_project', 'leader 在目前對話獲明確恢復授權後恢復專案，保留既有資料。', { projectName: string, reason: string }, ['projectName', 'reason']],
  ['list_roles', '列出通用或專案覆寫角色。', { projectName: string }],
  ['get_role', '認領前取得角色指引；專案覆寫優先，再退回通用版。', { name: string, projectName: string }, ['name']],
  ['upsert_role', 'leader 在使用者目前明確要求後建立或更新完整角色指引。', { name: string, category: string, instructions: string, projectName: string }, ['name', 'instructions']],
];

export const tools = specs.map(([name, description, properties, required]) => ({ name, description, inputSchema: object(properties, required) }));

export function validArguments(value, schema) {
  if (value === null) return false;
  if (schema.type === 'object') return typeof value === 'object' && !Array.isArray(value)
    && schema.required.every(key => Object.hasOwn(value, key))
    && Object.entries(value).every(([key, item]) => Object.hasOwn(schema.properties, key)
      && ((item === null && !schema.required.includes(key)) || validArguments(item, schema.properties[key])));
  if (schema.type === 'array') return Array.isArray(value) && value.length >= (schema.minItems ?? 0)
    && value.length <= (schema.maxItems ?? 1000) && value.every(item => validArguments(item, schema.items));
  if (schema.type === 'integer') return Number.isSafeInteger(value) && value >= (schema.minimum ?? 0);
  return typeof value === schema.type && (!schema.enum || schema.enum.includes(value));
}

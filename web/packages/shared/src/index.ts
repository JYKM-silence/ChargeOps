/**
 * ChargeOps 共享层唯一出口。
 *
 * ★ 两个 app 只允许从 '@chargeops/shared' 引，禁止深引到 ./src/xxx，
 *   否则 alias 与 tsconfig paths 两处映射会各走各的，容易出现「Vite 能跑、vue-tsc 报错」。
 *
 * ★ shared 里**不放 `.vue`**，也不 import vue —— 它要保持「纯 TS」，
 *   这样两端共用的是**逻辑**而不是**组件**，也就永远不会出现两份 vue 实例的问题。
 *   需要响应式的地方由 app 层包一层（见 admin/h5 的 `useSubmitLock`）。
 */

// ── 接口契约（后端 DTO / VO 的逐字段镜像）────────────────────────────────────
// ★ 分成 admin / member 两个文件而不是一个：两端字段**确实不一样**
//   （`sex` vs `gender`、管理端有 `frozenAmount` 车主端没有），
//   合成一个文件必然要靠可选字段去糊，那等于放弃类型检查。
export type * from './types/admin'
export type * from './types/member'

// ── HTTP 层 ────────────────────────────────────────────────────────────────
export type { ApiResult, PageQuery, PageResult, Paged } from './http/types'
export {
  CODE_FAIL,
  CODE_SUCCESS,
  DEFAULT_PAGE_NUM,
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  isApiResult,
} from './http/types'

export type { ApiErrorKind } from './http/errors'
export { ApiError, FALLBACK_MESSAGE, kindOfHttpStatus } from './http/errors'

export { MSG, msgEquals, msgStartsWith } from './http/messages'

export type { AuthLostReason, HttpClient, HttpClientOptions, RequestOptions } from './http/createHttp'
export { createHttp } from './http/createHttp'

export type { Session } from './http/session'
export {
  ADMIN_TOKEN_TTL_MS,
  MEMBER_TOKEN_TTL_MS,
  SESSION_KEY,
  TOKEN_HEADER,
  SessionStore,
  adminSession,
  clearAllLocalData,
  h5Session,
} from './http/session'

export type { RunOutcome, SubmitLockOptions } from './http/locks'
export {
  ActionLockedError,
  SubmitLock,
  isActionLocked,
  payloadKey,
  resetActionLocks,
  shouldStaySilent,
  withActionLock,
} from './http/locks'

export type { Option, OptionSource } from './http/options'
export {
  OptionLoader,
  createListOptionLoader,
  createOptionLoader,
  invalidateAllOptions,
  invalidateOptionsByTag,
  labelOf,
  resetOptionCache,
  toSelectItems,
} from './http/options'

export type { UploadCheck } from './http/upload'
export {
  ACCEPT_ATTR,
  ALLOWED_EXTENSIONS,
  MAX_FILE_BYTES,
  MAX_REQUEST_BYTES,
  UPLOAD_FIELD_NAME,
  checkUploadFile,
  resolveAssetUrl,
  uploadFile,
} from './http/upload'

// ── 工具 ───────────────────────────────────────────────────────────────────
export {
  PENDING_PAY_TIMEOUT_MS,
  SERVER_DATETIME_PATTERN,
  durationBetween,
  formatCn,
  formatCountdown,
  formatDate,
  formatDuration,
  formatRelative,
  formatServerTime,
  formatShort,
  formatTime,
  lastNDaysRange,
  parseServerTime,
  pendingPayRemainMs,
  startOfToday,
} from './utils/datetime'

export type { Decimal } from './utils/money'
export {
  AMOUNT_SCALE,
  PRICE_SCALE,
  addDecimal,
  compareDecimal,
  formatKwh,
  formatKwhLabel,
  formatMoney,
  formatPrice,
  fromScaled,
  isPositive,
  percentOf,
  segmentsMatchTotal,
  subDecimal,
  sumDecimal,
  toScaled,
} from './utils/money'

export {
  CODE_RE,
  LIMITS,
  MAX_LEN,
  MOBILE_RE,
  TIME_HM_END_RE,
  TIME_HM_RE,
  inRange,
  isAdjustableAmount,
  isCode,
  isMobile,
  isPartialAmountInput,
  isPassword,
  isRefundableAmount,
  isTimeHM,
  isTimeHMEnd,
  oneOf,
} from './utils/validators'

// ── 字典（enumeration 的前端镜像）──────────────────────────────────────────
export type { Tone } from './constants/tone'
export { TONE_VAR, signedTone, toneToElementType } from './constants/tone'

export type { OrderStatusMeta } from './constants/orderStatus'
export {
  BLOCKING_STATUSES,
  HOLDING_CONNECTOR_STATUSES,
  HOLDING_MONEY_STATUSES,
  ORDER_STATUS,
  ORDER_STATUS_LIST,
  ORDER_STATUS_OPTIONS,
  PENDING_PAY_MINUTES,
  TERMINAL_STATUSES,
  blocksDeviceDeletion,
  holdsMoney,
  isTerminal,
  orderStatusLabel,
  orderStatusMeta,
} from './constants/orderStatus'

export type { DescribedEvent, OrderEventLike } from './constants/orderEvent'
export {
  ORDER_EVENT_TYPE,
  describeOrderEvent,
  isNoopEvent,
  orderEventLabel,
  orderEventTone,
  transitionText,
} from './constants/orderEvent'

export type { Dict, DictItem } from './constants/dicts'
export {
  CATEGORY_TYPE,
  CHARGER_STATUS,
  CHARGER_TYPE,
  CONNECTOR_ADMIN_SETTABLE,
  CONNECTOR_ADMIN_SETTABLE_OPTIONS,
  CONNECTOR_STATUS,
  EVENT_SOURCE,
  GENDER,
  MEMBER_PACKAGE_STATUS,
  MEMBER_STATUS,
  PACKAGE_TYPE,
  PAY_METHOD,
  PERIOD_TYPE,
  PLATE_COLOR,
  STATION_STATUS,
  STOP_REASON,
  TICKET_SOURCE,
  TICKET_STATUS,
  WALLET_TXN_TYPE,
  isBalanceAffecting,
  isConnectorAvailable,
} from './constants/dicts'

// ── 领域规则 ───────────────────────────────────────────────────────────────
export type { ActionRule, End, OrderAction } from './domain/orderRules'
export {
  PAY_OUTCOME_HINT,
  actionPath,
  actionRule,
  availableActions,
  canDo,
  whyNot,
} from './domain/orderRules'

export {
  DASHBOARD_CONCURRENCY,
  DASHBOARD_SAMPLE_LIMIT,
  DEFAULT_PAGE_SIZE_UI,
  ORDER_DEFAULT_RANGE_DAYS,
  PHONE_SEARCH_MIN_LENGTH,
  canSearchByPhone,
  defaultOrderRange,
} from './domain/queryDefaults'

export type { DiffResult } from './domain/updateDiff'
export { UNSETTABLE_FIELDS, diffUpdate, isEmptyDiff } from './domain/updateDiff'

export { PERIOD_COLOR_VAR, PERIOD_TONE, periodColor, periodWeakColor, segmentWidthPercent } from './domain/segmentColor'

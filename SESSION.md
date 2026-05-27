# 会话记录 (2026-05-28)

## Bug 修复

### 数据库迁移
- `MIGRATION_3_4` SQL 缺少闭合括号 `)`，导致版本 3→4 升级崩溃

### 竞态条件
- `pendingConfirmData` 是 companion object 静态变量，多协程并发读写导致数据错乱
- 修复：添加 `@Synchronized` getter/setter + `consumePendingConfirmData()` 原子读取并清空
- 所有消费方（`PaymentNotificationService`、`RefundNotificationListener`、`NotifyReceiver`、`NotificationActionReceiver`、`MainActivity`）统一改用新方法

### 无限递归
- `HomeFragment.getPeriodRange()` 的 `else` 分支调用自身 → `StackOverflowError`
- 修复：删除重复的 `getPeriodRange()`，委托 `getPeriodRangeByMode(periodMode)`；else 分支回退月模式

### 统计卡片不显示
- `fragment_home.xml` 中 Summary Card 设了 `visibility="gone"` 且无 ID，代码无法引用
- 修复：添加 `id="@+id/summary_card"` + 移除 `visibility="gone"` + 在 `loadMonthData()` 中设置 `VISIBLE`

### 预算 TextView ID 不匹配
- 代码引用 `tvBudgetRemain`，但 XML 中 ID 是 `tv_budget_remain_card`
- 修复：改为 `binding.tvBudgetRemainCard`

### 商家提取误识别
- "待布苏嘒确认收款"等转账状态文本被误判为商家名
- 修复：`extractMerchant` 添加 `excludePatterns` 正则匹配（"确认收款"、"待.*确认"等）

---

## 新增功能

### 支付方式提取
- 新增 `extractPaymentMethod()` 方法
- 支持：花呗、余额宝、银行卡、信用卡、借记卡、零钱、零钱通、亲属卡
- 兜底：匹配"交易方式"后面的文本节点

### 折扣信息提取
- 新增 `extractDiscount()` 方法
- 从折扣关键词（立减/优惠/红包等）所在文本及相邻节点提取金额
- 向前查找原价，返回格式：`原价¥14.00 碰一下立减-¥0.09`
- 折扣行和金额可能分布在不同 UI 节点（如"碰一下立减"和"-¥0.09"分开）

### 备注格式
- 原来：`商家名` 或 `来源`
- 现在：`商家 · 支付方式 · 折扣信息`
- 示例：`木桶饭 · 花呗 · 原价¥14.00 碰一下立减-¥0.09`
- 转账无商家时：`微信`

### CSV 导入改进
- 新增 `parseCsvLine()` 方法，支持带引号的 CSV 字段解析
- 解决备注中含逗号导致导入错位的问题

---

## 代码清理

### 删除文件
- `NotifyActivity.kt` — 未在 AndroidManifest 注册，无法启动，死代码

### 移除死代码
- `PaymentNotificationService.handleContentChanged()` — 空方法，退款识别已移至 NotificationListenerService
- `CategoryDao.findByKeyword()` — SQL 逻辑错误且未被调用

### 移除重复调用
- `MainActivity.onCreate()` 中移除 `permissionGuide.checkAndGuide()`（`onResume` 中已有）

---

## 验证结果

### 测试截图
| 场景 | 金额 | 商家 | 支付方式 | 折扣 | 备注 |
|------|------|------|---------|------|------|
| 支付宝¥47.42 拼多多 | ¥47.42 | 拼多多平台商户 | 花呗 | 无 | `拼多多平台商户 · 花呗` |
| 支付宝¥13.91 木桶饭(有折扣) | ¥13.91 | 木桶饭 | 花呗 | 原价¥14.00 碰一下立减-¥0.09 | `木桶饭 · 花呗 · 原价¥14.00 碰一下立减-¥0.09` |
| 微信转账¥0.01 布苏嘒 | ¥0.01 | 无 | 无 | 无 | `微信` |

### adb 模拟测试
- 通过 `TestPaymentActivity` 模拟支付成功页面
- 无障碍服务正确识别并自动保存
- 日志确认：金额、商家、支付方式、折扣、自动分类全部正确

---

## 配置
- `opencode.json` 注入 Karpathy Guidelines（引用 `C:/dev/.opencode/skills/karpathy-guidelines/SKILL.md`）

---

# 会话记录 (2026-05-28 续)

## 会话结束流程
- 扫描项目目录结构，统计文件：21 个源代码文件、62 个资源文件、13 个配置文件、14 个文档文件
- 更新 `README.md`，新增"目录结构"章节，展示项目骨架

---

# 会话记录 (2026-05-28 数据导入修复)

## Bug 修复

### XLSX 导入日期解析
- **问题**：微信 XLSX 账单中日期时间列存储的是 Excel 序列号（如 `46170.042592592596`），而非文本字符串
- **原因**：`parseXlsx()` 直接输出原始序列号，`parseWechatTime()` 无法解析
- **修复**：
  - 新增解析 `styles.xml` 识别日期格式的 xf 索引
  - 新增 `excelSerialToDateTime()` 函数将序列号转为 `yyyy-MM-dd HH:mm:ss`
  - 修复 `cellStyleXfs` 索引偏移问题（`getElementsByTagName("xf")` 包含了 `cellStyleXfs` 中的元素）

### 导入记录缺少交易时间
- **问题**：导入的记录显示导入时间而非实际交易时间
- **原因**：`TransactionAdapter` 显示 `createdAt`，但导入时未设置，使用了默认值 `System.currentTimeMillis()`
- **修复**：导入时设置 `createdAt = date`

### 支付宝 CSV 导入失败
- **问题**：支付宝 CSV 文件无法识别
- **原因**：
  1. 文件为 GBK 编码，代码用 UTF-8 读取导致中文乱码
  2. 表头检测只匹配 `交易类型`，支付宝用 `交易分类`
- **修复**：
  - 增加编码检测：先试 UTF-8，不行则用 GBK
  - 表头检测增加 `交易分类` 关键词
  - 列名兼容：`金额(元)`/`金额`、`当前状态`/`交易状态`
  - 跳过 `交易关闭` 状态和收/支为空的记录

### 支付宝分类不准确
- **问题**：导入的支付宝记录分类匹配不准确
- **修复**：
  - 新增 `mapAlipayCategory()` 函数，优先使用支付宝"交易分类"字段映射
  - 映射规则：餐饮美食→食物、日用百货/家居家装→生活、交通出行→交通、住宿服务→住宿、休闲娱乐/文化休闲→娱乐、转账红包/充值缴费/投资理财→其它
  - 收入分类：工资/劳务→工资、兼职→兼职、理财→理财

### 导入重复记录
- **问题**：同一文件导入两次会产生重复记录
- **修复**：
  - 新增 `countDuplicate()` DAO 方法
  - 导入前按金额+类型+日期+备注判断重复，自动跳过
  - Toast 提示"已导入 X 条记录，跳过 Y 条重复记录"

### 日历红绿标记判断错误
- **问题**：只有收入的日子也显示圆点，大额支出显示绿点
- **原因**：
  1. `transactionDates` 包含所有交易日期（收入+支出），导致收入日也显示圆点
  2. `avgDaily` 用有支出天数计算，而非整月天数，导致平均偏高
  3. `getDailyExpenseSums` SQL `GROUP BY date` 按精确时间戳分组，同一天多笔交易未累加
- **修复**：
  - 改用 `dailyExpenseMap.containsKey(day)` 判断（仅支出日显示圆点）
  - `avgDaily` 改为 `总支出 / 月天数`
  - Kotlin 代码用 `+=` 累加同天多笔交易

### 固定支出右滑功能
- **问题**：右滑直接删除，没有确认
- **修复**：右滑改为"跳过本月"/"恢复本月"（toggle），带确认弹窗

### 固定支出设置按钮
- **问题**：设置里"跳过本月"按钮与右滑功能重复
- **修复**：设置按钮改为"删除"，带确认弹窗

## 新增 DAO 方法
- `FixedExpenseDao.getById(id)` — 根据 ID 查询固定支出
- `TransactionDao.countDuplicate(amount, type, date, note)` — 查询重复记录数

# 项目架构

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 100% |
| 架构 | Single Activity + Navigation Component |
| UI | Material Design 3, ViewBinding, ChipGroup, 自定义 View |
| 数据库 | Room (SQLite) + KSP |
| 异步 | Kotlin Coroutines |
| 地图 | 高德地图 SDK v6.4.3 |
| 无障碍 | AccessibilityService (PaymentNotificationService) |
| 通知监听 | NotificationListenerService (RefundNotificationListener) |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |

## 目录结构

```
app/src/main/java/com/suishiji/
├── App.kt                          # Application 入口，DB 初始化
├── MainActivity.kt                 # 唯一 Activity，处理通知 Intent
├── db/
│   ├── Entities.kt                 # Room 实体 (Transaction, Category, FixedExpense)
│   ├── AppDatabase.kt              # Room 数据库，迁移，分类种子数据
│   └── TransactionDao.kt           # DAO 接口
├── service/
│   ├── PaymentNotificationService.kt   # 无障碍服务，支付页面识别
│   ├── RefundNotificationListener.kt   # 通知监听，退款/支付通知识别
│   ├── NotifyActivity.kt               # 通知辅助 Activity
│   ├── NotificationActionReceiver.kt   # 通知操作广播接收器
│   └── NotifyReceiver.kt
├── ui/
│   ├── HomeFragment.kt             # 主页（日历、列表、汇总、FAB）
│   ├── AddRecordDialog.kt          # 添加/编辑记录弹窗
│   ├── CategoryFragment.kt         # 分类统计与分类管理
│   └── BirthdayCelebrationActivity.kt
├── adapter/
│   └── TransactionAdapter.kt       # 交易列表适配器
├── view/
│   ├── RingChartView.kt            # 自定义饼图/环形图
│   ├── SwipeableCalendarLayout.kt
│   └── CelebrationView.kt
└── util/
    ├── AppLocationManager.kt       # 高德地图定位
    ├── PreciseLocator.kt           # GPS 精确定位
    ├── LogManager.kt               # 调试日志
    ├── ToastManager.kt             # 自定义 Toast
    └── PermissionGuideManager.kt   # 权限引导
```

## 数据流

### 自动记账流程

```
支付成功页面出现
       ↓
AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
       ↓
PaymentNotificationService.handleWindowChanged()
       ↓
getAllTexts() — 提取页面所有文字
       ↓
extractAmount() — 提取金额（¥/元/数字，排除优惠）
       ↓
extractMerchant() — 提取商户名
       ↓
processPayment()
       ↓
自动分类（按商户匹配 category 关键词）
       ↓
获取位置（AppLocationManager.locateFast）
       ↓
弹确认通知 + 3 秒倒计时
       ↓
用户点击通知？──→ 打开 AddRecordDialog（不自保）
       │
       否
       ↓
3 秒后自动保存到数据库
```

### 通知监听记账流程

```
支付宝/微信发送支付/退款通知
       ↓
RefundNotificationListener.onNotificationPosted()
       ↓
匹配关键字（支付/退款/消费）
       ↓
extractAmount() — 提取金额
       ↓
弹确认通知 + 3 秒倒计时
       ↓
自动保存（无分类/商户信息，需用户补充）
```

## 数据库

### transactions

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | 自增主键 |
| amount | Double | 金额 |
| type | String | expense / income |
| category | String | 分类名称 |
| note | String | 备注（商户名或来源） |
| date | Long | 日期时间戳 |
| latitude | Double? | 纬度 |
| longitude | Double? | 经度 |
| address | String? | 地址 |
| createdAt | Long | 创建时间 |

### categories

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | 自增主键 |
| name | String | 分类名 |
| icon | String | 图标标识 |
| isIncome | Boolean | 是否为收入分类 |
| keywords | String | 逗号分隔的识别关键词 |

### fixed_expenses

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | 自增主键 |
| dayOfMonth | Int | 每月扣款日 |
| amount | Double | 金额 |
| category | String | 分类 |
| note | String | 备注 |

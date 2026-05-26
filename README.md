# 随时记

自动识别微信/支付宝支付成功页面，自动记账的个人理财助手。

## 功能

- **自动记账** — 通过无障碍服务自动识别微信和支付宝的支付/退款通知，提取金额、商户、自动分类
- **手动记账** — 支持手动添加收支记录，带分类、日期、备注、定位
- **位置记录** — 集成高德地图 SDK，每笔交易记录地理位置，在地图上展示
- **统计图表** — 月度日历、分类饼图/条形图、每日支出排行
- **固定支出** — 管理每月固定支出（房租、订阅等），自动计入月结
- **数据导入导出** — CSV 导入导出
- **深色模式** — 支持明暗主题切换
- **月度预算** — 设置预算，实时跟踪进度

## 截图

（待补充）

## 下载

[下载 APK](https://github.com/yuggg999/suishiji/releases)

## 技术栈

- **语言**: Kotlin
- **架构**: Single Activity + Navigation Component + ViewBinding
- **数据库**: Room (SQLite) + KSP
- **异步**: Coroutines
- **地图**: 高德地图 SDK
- **最低 SDK**: 24 (Android 7.0)
- **目标 SDK**: 34 (Android 14)

## 构建

```bash
./gradlew assembleRelease
```

APK 文件位于 `app/build/outputs/apk/release/`。

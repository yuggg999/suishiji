# 开发指南

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更新版本
- JDK 17
- Android SDK 34
- Gradle 8.5

## 构建

```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease
```

APK 文件位于 `app/build/outputs/apk/`。

## 运行

```bash
# 连接设备后安装 Debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 调试

### 日志

应用通过 `Log.d("Suishiji", ...)` 和 `LogManager` 输出调试日志：

```bash
# 过滤 Suishiji 日志
adb logcat -s Suishiji

# 无障碍服务日志
adb logcat -s Suishiji | findstr "A11y"

# 通知监听日志
adb logcat -s Suishiji | findstr "Notify"
```

### 无障碍服务

测试自动记账需要：
1. 开启手机的无障碍服务：设置 → 无障碍 → 随时记
2. 开启通知监听服务：设置 → 通知使用权 → 随时记
3. 打开微信/支付宝支付页面

## 权限

应用需要以下权限：

| 权限 | 用途 |
|------|------|
| `BIND_ACCESSIBILITY_SERVICE` | 识别支付成功页面 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 监听支付/退款通知 |
| `ACCESS_FINE_LOCATION` | 记录交易位置 |
| `POST_NOTIFICATIONS` | 发送确认通知 |

## 数据库

### 版本迁移

数据库版本更新时需：

1. 在 `AppDatabase.kt` 中新增 `Migration` 对象
2. 更新 `@Database(version = ...)`
3. 将新 Migration 添加到 `addMigrations()` 列表

### 查看数据库

```bash
# 从设备拉取数据库
adb exec-out run-as com.suishiji cat databases/suishiji.db > suishiji.db

# 使用 sqlite3 查看
sqlite3 suishiji.db
.tables
SELECT * FROM transactions;
```

## 贡献

1. Fork 仓库
2. 创建特性分支 (`git checkout -b feat/xxx`)
3. 提交改动 (`git commit -m "feat: xxx"`)
4. 推送到 GitHub (`git push origin feat/xxx`)
5. 创建 Pull Request

### 提交规范

- `feat:` — 新功能
- `fix:` — Bug 修复
- `docs:` — 文档更新
- `refactor:` — 重构
- `perf:` — 性能优化
- `chore:` — 构建/工具链

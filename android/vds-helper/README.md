# vds-helper

虚拟屏服务源码：由 WorkBuddy 通过 Shizuku 以 uid 2000/root 启动，
创建并持有一块 off-screen VirtualDisplay，写 id/pid/frame.png 到 App 缓存。

构建（Windows / JDK 17 / Android SDK 35）：

```powershell
javac -encoding UTF-8 -source 8 -target 8 `
  -cp "$env:ANDROID_HOME\platforms\android-35\android.jar" `
  -d classes src\com\wb\vds\Server.java
jar cf helper.jar -C classes .
& "$env:ANDROID_HOME\build-tools\35.0.0\d8.bat" `
  --lib "$env:ANDROID_HOME\platforms\android-35\android.jar" `
  --min-api 26 --output out helper.jar
Copy-Item out\classes.dex ..\app\src\main\assets\vds.dex -Force
```

产物 `classes.dex` 以 `assets/vds.dex` 随 APK 打包；App 启动时复制到 `files/vds.dex` 再执行。

说明：Android 17 的 `SecurityManagerService.isVirtualDisplayActive` 不允许普通 App 在自建
虚拟屏上启动其他 App；因此显示必须由 shell/root 侧创建，`am start --display` 也必须由同一
uid 执行。

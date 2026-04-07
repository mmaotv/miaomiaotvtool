#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
喵喵嗷影视 - 一键构建脚本
支持快速配置：APP名称、版本号、链接、提示文字、应用图标、UA伪装、首页按钮自定义
"""

import sys, os, subprocess, shutil, re

# ============================================================
#  快速配置区 —— 修改这里，或修改 CONFIG.txt，然后运行脚本
# ============================================================
APP_NAME      = "喵喵嗷影视"          # 应用名称
VERSION_NAME  = "1.2"                                        # 版本号（显示用）
VERSION_CODE  = 3                                            # 版本号（整数，每次发布+1）
LIVE_NAME     = "直播"                                        # 直播按钮名称
LIVE_URL      = "https://mengma.lanzoum.com/b01fphpgf"      # 直播按钮链接
VOD_NAME      = "点播"                                        # 点播按钮名称
VOD_URL       = "https://mengma.lanzoum.com/b01fhuuih"      # 点播按钮链接
TOOLS_NAME    = "工具"                                        # 工具按钮名称
TOOLS_URL     = "https://mengma.lanzoum.com/b0mcflmwf"      # 工具按钮链接
QR_NAME       = "扫码输入"                                    # 扫码按钮名称（固定功能）
FILE_NAME     = "文件管理"                                    # 文件按钮名称（固定功能）
USB_NAME      = "U盘"                                        # U盘按钮名称（固定功能）
USER_AGENT    = "chrome"                                     # UA类型：chrome/edge/safari/mobile/desktop/custom
CUSTOM_UA     = ""                                          # 自定义UA（当USER_AGENT=custom时）
INFO_TEXT     = "密码：miao"                                  # 首页提示文字
SUBTITLE_TEXT = "高清影视 · 随时随地"                          # 首页副标题（APP名下方小字）
HOME_TITLE    = ""                                           # 首页大标题（留空=与APP_NAME相同）
ICON_PATH     = ""                                           # 图标图片路径（留空=使用默认图标）
# ============================================================
# 以下无需修改
# ============================================================

JDK_PATH     = r"C:\temp\jdk17\jdk-17.0.12+7"
SDK_PATH     = r"C:\Users\1\AppData\Local\Android\Sdk"
PROJECT_PATH = r"f:\APPS\TVapp"
STRINGS_PATH = os.path.join(PROJECT_PATH, "app", "src", "main", "res", "values", "strings.xml")
GRADLE_PATH  = os.path.join(PROJECT_PATH, "app", "build.gradle")
APK_SRC      = os.path.join(PROJECT_PATH, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
APK_DST      = os.path.join(PROJECT_PATH, "喵喵嗷影视.apk")

# mipmap 目录及对应尺寸
MIPMAP_SIZES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}


def get_ua_string(ua_type, custom_ua=""):
    """根据UA类型返回对应的UserAgent字符串"""
    ua_map = {
        "chrome":  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "edge":    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
        "safari":  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
        "mobile":  "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "desktop": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    }
    if ua_type == "custom" and custom_ua:
        return custom_ua
    return ua_map.get(ua_type, ua_map["chrome"])


def update_strings(app_name, live_name, live_url, vod_name, vod_url, tools_name, tools_url,
                   qr_name, file_name, usb_name, info_text, subtitle_text="",
                   home_title="", user_agent="chrome", custom_ua=""):
    """更新 strings.xml 配置"""
    if not subtitle_text:
        subtitle_text = "高清影视 · 随时随地"
    # 首页大标题：若未单独设置则与 APP_NAME 相同
    if not home_title:
        home_title = app_name

    # 获取UA字符串
    ua_string = get_ua_string(user_agent, custom_ua)

    content = f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">{app_name}</string>

    <!-- ===== 快速配置区（修改这里后运行 build.bat 即可打包） ===== -->
    <string name="live_url">{live_url}</string>
    <string name="vod_url">{vod_url}</string>
    <string name="tools_url">{tools_url}</string>
    <string name="info_text">{info_text}</string>
    <!-- ===== 以上四项是主要配置 ===== -->

    <!-- 首页大标题（默认与 app_name 相同） -->
    <string name="home_title">{home_title}</string>
    <!-- 首页副标题（APP名称下方小字） -->
    <string name="subtitle_text">{subtitle_text}</string>

    <!-- 首页按钮名称 -->
    <string name="btn_live">{live_name}</string>
    <string name="btn_vod">{vod_name}</string>
    <string name="btn_tools">{tools_name}</string>
    <string name="btn_qr">{qr_name}</string>
    <string name="btn_file">{file_name}</string>
    <string name="btn_usb">{usb_name}</string>

    <!-- 浏览器UA伪装 -->
    <string name="user_agent">{ua_string}</string>
    <string name="user_agent_type">{user_agent}</string>

    <string name="download_notification_channel">下载</string>
    <string name="downloading">正在下载...</string>
    <string name="download_complete">下载完成</string>
    <string name="download_failed">下载失败</string>
    <string name="download_confirm_title">下载文件</string>
    <string name="download_confirm_msg">是否下载此文件？</string>
    <string name="download_yes">下载</string>
    <string name="download_no">取消</string>
</resources>
'''
    with open(STRINGS_PATH, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"  [OK] strings.xml 已更新 (app_name={app_name})")
    print(f"       按钮名称: {live_name} | {vod_name} | {tools_name} | {qr_name} | {file_name} | {usb_name}")
    print(f"       UA类型: {user_agent}")


def update_version(version_name, version_code):
    """更新 app/build.gradle 里的版本号"""
    with open(GRADLE_PATH, 'r', encoding='utf-8') as f:
        content = f.read()
    # 替换 versionCode 和 versionName
    content = re.sub(r'versionCode\s+\d+', f'versionCode {version_code}', content)
    content = re.sub(r'versionName\s+"[^"]+"', f'versionName "{version_name}"', content)
    with open(GRADLE_PATH, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"  [OK] build.gradle 已更新 (versionName={version_name}, versionCode={version_code})")


def update_icon(icon_path):
    """
    根据 icon_path 生成各密度图标。
    优先使用 Pillow，若未安装则提示并跳过。
    """
    if not icon_path or not icon_path.strip():
        print("  [--] ICON_PATH 未填写，保留现有图标")
        return

    icon_path = icon_path.strip()
    if not os.path.exists(icon_path):
        print(f"  [WARN] 图标文件不存在：{icon_path}，跳过图标设置")
        return

    try:
        from PIL import Image
    except ImportError:
        print("  [WARN] 未安装 Pillow，无法处理图标。")
        print("         请运行：  pip install Pillow")
        print("         然后重新打包，将自动应用图标。")
        return

    try:
        src_img = Image.open(icon_path).convert("RGBA")
        res_path = os.path.join(PROJECT_PATH, "app", "src", "main", "res")

        for folder, size in MIPMAP_SIZES.items():
            dir_path = os.path.join(res_path, folder)
            os.makedirs(dir_path, exist_ok=True)
            out_file = os.path.join(dir_path, "ic_launcher.png")
            resized = src_img.resize((size, size), Image.LANCZOS)
            resized.save(out_file, "PNG")
            # 同时保存圆形图标（直接用方形，系统会裁剪）
            out_round = os.path.join(dir_path, "ic_launcher_round.png")
            resized.save(out_round, "PNG")

        # 修改 AndroidManifest.xml 指向 PNG 图标（而非 VectorDrawable）
        update_manifest_icon()

        print(f"  [OK] 图标已生成：{len(MIPMAP_SIZES)} 个尺寸 <- {icon_path}")

    except Exception as e:
        print(f"  [ERROR] 图标处理失败：{e}")


def update_manifest_icon():
    """将 AndroidManifest.xml 的 icon 引用改为 @mipmap/ic_launcher"""
    manifest_path = os.path.join(PROJECT_PATH, "app", "src", "main", "AndroidManifest.xml")
    with open(manifest_path, 'r', encoding='utf-8') as f:
        content = f.read()
    # 将 @drawable/ic_launcher_foreground 替换为 @mipmap/ic_launcher
    content = content.replace(
        'android:icon="@drawable/ic_launcher_foreground"',
        'android:icon="@mipmap/ic_launcher"'
    )
    content = content.replace(
        'android:roundIcon="@drawable/ic_launcher_foreground"',
        'android:roundIcon="@mipmap/ic_launcher_round"'
    )
    with open(manifest_path, 'w', encoding='utf-8') as f:
        f.write(content)


def build():
    """执行 Gradle 构建"""
    env = os.environ.copy()
    env["JAVA_HOME"]    = JDK_PATH
    env["ANDROID_HOME"] = SDK_PATH
    env["PATH"]         = os.path.join(JDK_PATH, "bin") + os.pathsep + env.get("PATH", "")

    gradlew = os.path.join(PROJECT_PATH, "gradlew.bat")
    print(f"\n[3/4] 开始构建APK（约4-60秒）...")
    result = subprocess.run(
        [gradlew, "assembleDebug"],
        cwd=PROJECT_PATH,
        env=env
    )
    return result.returncode == 0


def copy_apk(app_name):
    """复制 APK 到项目根目录"""
    if os.path.exists(APK_SRC):
        # 用当前 app_name 生成文件名（但保留 ASCII 备份）
        dst_name = os.path.join(PROJECT_PATH, f"{app_name}.apk")
        shutil.copy2(APK_SRC, dst_name)
        size_mb = os.path.getsize(dst_name) / 1024 / 1024
        print(f"  [OK] APK 已保存：{dst_name}  ({size_mb:.1f} MB)")
        # ASCII 文件名副本
        ascii_dst = os.path.join(PROJECT_PATH, "miaomiaotv.apk")
        shutil.copy2(APK_SRC, ascii_dst)
        print(f"  [OK] APK 备份：{ascii_dst}  ({size_mb:.1f} MB)")
        return True
    print("  [ERROR] 找不到生成的APK文件！")
    return False


if __name__ == "__main__":
    # ---- 从 CONFIG.txt 读取配置 ----
    config_path = os.path.join(PROJECT_PATH, "CONFIG.txt")
    app_name     = APP_NAME
    version_name = VERSION_NAME
    version_code = VERSION_CODE
    live_name    = LIVE_NAME
    live_url     = LIVE_URL
    vod_name     = VOD_NAME
    vod_url      = VOD_URL
    tools_name   = TOOLS_NAME
    tools_url    = TOOLS_URL
    qr_name      = QR_NAME
    file_name    = FILE_NAME
    usb_name     = USB_NAME
    user_agent   = USER_AGENT
    custom_ua    = CUSTOM_UA
    info_text    = INFO_TEXT
    icon_path    = ICON_PATH
    subtitle_text = SUBTITLE_TEXT
    home_title   = HOME_TITLE

    if os.path.exists(config_path):
        with open(config_path, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line.startswith('#') or '=' not in line:
                    continue
                key, _, val = line.partition('=')
                key = key.strip()
                val = val.strip()
                if key == "APP_NAME":
                    app_name = val
                elif key == "VERSION_NAME":
                    version_name = val
                elif key == "VERSION_CODE":
                    try:
                        version_code = int(val)
                    except ValueError:
                        pass
                elif key == "LIVE_NAME":
                    live_name = val
                elif key == "LIVE_URL":
                    live_url = val
                elif key == "VOD_NAME":
                    vod_name = val
                elif key == "VOD_URL":
                    vod_url = val
                elif key == "TOOLS_NAME":
                    tools_name = val
                elif key == "TOOLS_URL":
                    tools_url = val
                elif key == "QR_NAME":
                    qr_name = val
                elif key == "FILE_NAME":
                    file_name = val
                elif key == "USB_NAME":
                    usb_name = val
                elif key == "USER_AGENT":
                    user_agent = val.lower()
                elif key == "CUSTOM_UA":
                    custom_ua = val
                elif key == "INFO_TEXT":
                    info_text = val
                elif key == "SUBTITLE_TEXT":
                    subtitle_text = val
                elif key == "HOME_TITLE":
                    home_title = val
                elif key == "ICON_PATH":
                    icon_path = val

    print("=" * 60)
    print(f"  {app_name} - 自动打包  v{version_name}({version_code})")
    print("=" * 60)

    print(f"\n[1/4] 更新应用配置...")
    print(f"  APP名称  : {app_name}")
    print(f"  版本号   : {version_name} (code={version_code})")
    print(f"  首页标题 : {home_title if home_title else app_name}")
    print(f"  副标题   : {subtitle_text}")
    print(f"  按钮1    : {live_name} -> {live_url}")
    print(f"  按钮2    : {vod_name} -> {vod_url}")
    print(f"  按钮3    : {tools_name} -> {tools_url}")
    print(f"  按钮4-6  : {qr_name} / {file_name} / {usb_name} (固定功能)")
    print(f"  UA伪装   : {user_agent}")
    print(f"  提示文字 : {info_text}")
    print(f"  图标路径 : {icon_path or '(默认)'}")

    update_strings(app_name, live_name, live_url, vod_name, vod_url, tools_name, tools_url,
                   qr_name, file_name, usb_name, info_text, subtitle_text, home_title,
                   user_agent, custom_ua)
    update_version(version_name, version_code)

    print(f"\n[2/4] 处理应用图标...")
    update_icon(icon_path)

    if not build():
        print("\n[ERROR] 构建失败！")
        sys.exit(1)

    print(f"\n[4/4] 复制APK...")
    if not copy_apk(app_name):
        sys.exit(1)

    print(f"\n" + "=" * 60)
    print(f"  [DONE] 打包完成！{app_name} v{version_name} -> {app_name}.apk")
    print("=" * 60 + "\n")

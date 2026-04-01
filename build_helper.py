#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
喵喵嗷影视 - 一键构建脚本
支持快速配置：APP名称、版本号、链接、提示文字、应用图标
"""

import sys, os, subprocess, shutil, re

# ============================================================
#  快速配置区 —— 修改这里，或修改 CONFIG.txt，然后运行脚本
# ============================================================
APP_NAME      = "\u55b5\u55b5\u5662\u5f71\u89c6"          # 应用名称
VERSION_NAME  = "1.0"                                        # 版本号（显示用）
VERSION_CODE  = 1                                            # 版本号（整数，每次发布+1）
LIVE_URL      = "https://mengma.lanzoum.com/b01fphpgf"      # 直播按钮链接
VOD_URL       = "https://mengma.lanzoum.com/b01fhuuih"      # 点播按钮链接
TOOLS_URL     = "https://mengma.lanzoum.com/b0mb2pq7e"      # 工具按钮链接
INFO_TEXT     = "\u5bc6\u7801\uff1amiao"                    # 首页提示文字
SUBTITLE_TEXT = "\u9ad8\u6e05\u5f71\u89c6 \u00b7 \u968f\u65f6\u968f\u5730"  # 首页副标题（APP名下方小字）
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
APK_DST      = os.path.join(PROJECT_PATH, "\u55b5\u55b5\u5662\u5f71\u89c6.apk")

# mipmap 目录及对应尺寸
MIPMAP_SIZES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}


def update_strings(app_name, live_url, vod_url, tools_url, info_text, subtitle_text="", home_title=""):
    """更新 strings.xml 配置"""
    if not subtitle_text:
        subtitle_text = "\u9ad8\u6e05\u5f71\u89c6 \u00b7 \u968f\u65f6\u968f\u5730"
    # 首页大标题：若未单独设置则与 APP_NAME 相同
    if not home_title:
        home_title = app_name
    content = f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">{app_name}</string>

    <!-- ===== \u5feb\u901f\u914d\u7f6e\u533a\uff08\u4fee\u6539\u8fd9\u91cc\u540e\u8fd0\u884c build.bat \u5373\u53ef\u6253\u5305\uff09 ===== -->
    <string name="live_url">{live_url}</string>
    <string name="vod_url">{vod_url}</string>
    <string name="tools_url">{tools_url}</string>
    <string name="info_text">{info_text}</string>
    <!-- ===== \u4ee5\u4e0a\u56db\u9879\u662f\u4e3b\u8981\u914d\u7f6e ===== -->

    <!-- \u9996\u9875\u5927\u6807\u9898\uff08\u9ed8\u8ba4\u4e0e app_name \u76f8\u540c\uff09 -->
    <string name="home_title">{home_title}</string>
    <!-- \u9996\u9875\u526f\u6807\u9898\uff08APP\u540d\u79f0\u4e0b\u65b9\u5c0f\u5b57\uff09 -->
    <string name="subtitle_text">{subtitle_text}</string>

    <string name="btn_live">\u76f4\u64ad</string>
    <string name="btn_vod">\u70b9\u64ad</string>
    <string name="btn_tools">\u5de5\u5177</string>
    <string name="download_notification_channel">\u4e0b\u8f7d</string>
    <string name="downloading">\u6b63\u5728\u4e0b\u8f7d...</string>
    <string name="download_complete">\u4e0b\u8f7d\u5b8c\u6210</string>
    <string name="download_failed">\u4e0b\u8f7d\u5931\u8d25</string>
    <string name="download_confirm_title">\u4e0b\u8f7d\u6587\u4ef6</string>
    <string name="download_confirm_msg">\u662f\u5426\u4e0b\u8f7d\u6b64\u6587\u4ef6\uff1f</string>
    <string name="download_yes">\u4e0b\u8f7d</string>
    <string name="download_no">\u53d6\u6d88</string>
</resources>
'''
    with open(STRINGS_PATH, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"  [OK] strings.xml \u5df2\u66f4\u65b0 (app_name={app_name}, subtitle={subtitle_text})")


def update_version(version_name, version_code):
    """更新 app/build.gradle 里的版本号"""
    with open(GRADLE_PATH, 'r', encoding='utf-8') as f:
        content = f.read()
    # 替换 versionCode 和 versionName
    content = re.sub(r'versionCode\s+\d+', f'versionCode {version_code}', content)
    content = re.sub(r'versionName\s+"[^"]+"', f'versionName "{version_name}"', content)
    with open(GRADLE_PATH, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"  [OK] build.gradle \u5df2\u66f4\u65b0 (versionName={version_name}, versionCode={version_code})")


def update_icon(icon_path):
    """
    根据 icon_path 生成各密度图标。
    优先使用 Pillow，若未安装则提示并跳过。
    """
    if not icon_path or not icon_path.strip():
        print("  [--] ICON_PATH \u672a\u586b\u5199\uff0c\u4fdd\u7559\u73b0\u6709\u56fe\u6807")
        return

    icon_path = icon_path.strip()
    if not os.path.exists(icon_path):
        print(f"  [WARN] \u56fe\u6807\u6587\u4ef6\u4e0d\u5b58\u5728\uff1a{icon_path}\uff0c\u8df3\u8fc7\u56fe\u6807\u8bbe\u7f6e")
        return

    try:
        from PIL import Image
    except ImportError:
        print("  [WARN] \u672a\u5b89\u88c5 Pillow\uff0c\u65e0\u6cd5\u5904\u7406\u56fe\u6807\u3002")
        print("         \u8bf7\u8fd0\u884c\uff1a  pip install Pillow")
        print("         \u7136\u540e\u91cd\u65b0\u6253\u5305\uff0c\u5c06\u81ea\u52a8\u5e94\u7528\u56fe\u6807\u3002")
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

        print(f"  [OK] \u56fe\u6807\u5df2\u751f\u6210\uff1a{len(MIPMAP_SIZES)} \u4e2a\u5c3a\u5bf8 <- {icon_path}")

    except Exception as e:
        print(f"  [ERROR] \u56fe\u6807\u5904\u7406\u5931\u8d25\uff1a{e}")


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


def restore_manifest_icon():
    """若未设置图标，确保 manifest 引用恢复默认（若已被修改则不动）"""
    pass  # 默认不操作，保留用户上次配置


def build():
    """执行 Gradle 构建"""
    env = os.environ.copy()
    env["JAVA_HOME"]    = JDK_PATH
    env["ANDROID_HOME"] = SDK_PATH
    env["PATH"]         = os.path.join(JDK_PATH, "bin") + os.pathsep + env.get("PATH", "")

    gradlew = os.path.join(PROJECT_PATH, "gradlew.bat")
    print(f"\n[3/4] \u5f00\u59cb\u6784\u5efaAPK\uff08\u52604-60\u79d2\uff09...")
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
        print(f"  [OK] APK \u5df2\u4fdd\u5b58\uff1a{dst_name}  ({size_mb:.1f} MB)")
        # ASCII 文件名副本
        ascii_dst = os.path.join(PROJECT_PATH, "miaomiaotv.apk")
        shutil.copy2(APK_SRC, ascii_dst)
        print(f"  [OK] APK \u5907\u4efd\uff1a{ascii_dst}  ({size_mb:.1f} MB)")
        return True
    print("  [ERROR] \u627e\u4e0d\u5230\u751f\u6210\u7684APK\u6587\u4ef6\uff01")
    return False


if __name__ == "__main__":
    # ---- 从 CONFIG.txt 读取配置 ----
    config_path = os.path.join(PROJECT_PATH, "CONFIG.txt")
    app_name     = APP_NAME
    version_name = VERSION_NAME
    version_code = VERSION_CODE
    live_url     = LIVE_URL
    vod_url      = VOD_URL
    tools_url    = TOOLS_URL
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
                elif key == "LIVE_URL":
                    live_url = val
                elif key == "VOD_URL":
                    vod_url = val
                elif key == "TOOLS_URL":
                    tools_url = val
                elif key == "INFO_TEXT":
                    info_text = val
                elif key == "SUBTITLE_TEXT":
                    subtitle_text = val
                elif key == "HOME_TITLE":
                    home_title = val
                elif key == "ICON_PATH":
                    icon_path = val

    print("=" * 60)
    print(f"  {app_name} - \u81ea\u52a8\u6253\u5305  v{version_name}({version_code})")
    print("=" * 60)

    print(f"\n[1/4] \u66f4\u65b0\u5e94\u7528\u914d\u7f6e...")
    print(f"  APP\u540d\u79f0  : {app_name}")
    print(f"  \u7248\u672c\u53f7   : {version_name} (code={version_code})")
    print(f"  \u9996\u9875\u6807\u9898 : {home_title if home_title else app_name}")
    print(f"  \u526f\u6807\u9898   : {subtitle_text}")
    print(f"  \u76f4\u64ad\u94fe\u63a5 : {live_url}")
    print(f"  \u70b9\u64ad\u94fe\u63a5 : {vod_url}")
    print(f"  \u5de5\u5177\u94fe\u63a5 : {tools_url}")
    print(f"  \u63d0\u793a\u6587\u5b57 : {info_text}")
    print(f"  \u56fe\u6807\u8def\u5f84 : {icon_path or '(\u9ed8\u8ba4)'}")

    update_strings(app_name, live_url, vod_url, tools_url, info_text, subtitle_text, home_title)
    update_version(version_name, version_code)

    print(f"\n[2/4] \u5904\u7406\u5e94\u7528\u56fe\u6807...")
    update_icon(icon_path)

    if not build():
        print("\n[ERROR] \u6784\u5efa\u5931\u8d25\uff01")
        sys.exit(1)

    print(f"\n[4/4] \u590d\u5236APK...")
    if not copy_apk(app_name):
        sys.exit(1)

    print(f"\n" + "=" * 60)
    print(f"  [DONE] \u6253\u5305\u5b8c\u6210\uff01{app_name} v{version_name} -> {app_name}.apk")
    print("=" * 60 + "\n")

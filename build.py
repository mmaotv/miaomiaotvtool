#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
喵喵嗷影视 - 快速打包脚本
读取 CONFIG.txt 并更新到项目资源文件，然后构建 APK
"""

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# 配置路径
BASE_DIR = Path(__file__).parent
CONFIG_FILE = BASE_DIR / "CONFIG.txt"
STRINGS_XML = BASE_DIR / "app" / "src" / "main" / "res" / "values" / "strings.xml"
BUILD_GRADLE = BASE_DIR / "app" / "build.gradle"

# UA 预设
UA_PRESETS = {
    "chrome": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "edge": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
    "safari": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15",
    "mobile": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
    "desktop": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
}


def parse_config():
    """解析 CONFIG.txt"""
    config = {}
    
    if not CONFIG_FILE.exists():
        print(f"[错误] 找不到配置文件: {CONFIG_FILE}")
        sys.exit(1)
    
    with open(CONFIG_FILE, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("["):
                continue
            if "=" in line:
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip()
    
    return config


def update_strings_xml(config):
    """更新 strings.xml - 使用正则替换保留格式"""
    print("[2/5] 正在更新 strings.xml...")
    
    with open(STRINGS_XML, "r", encoding="utf-8") as f:
        content = f.read()
    
    # 获取 UA
    ua_type = config.get("USER_AGENT", "chrome")
    if ua_type == "custom":
        ua_value = config.get("CUSTOM_UA", UA_PRESETS["chrome"])
    else:
        ua_value = UA_PRESETS.get(ua_type, UA_PRESETS["chrome"])
    
    # 定义替换映射
    replacements = {
        r'(<string name="app_name">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("APP_NAME", "喵喵嗷工具箱")}{m.group(2)}',
        r'(<string name="live_url">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("LIVE_URL", "")}{m.group(2)}',
        r'(<string name="vod_url">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("VOD_URL", "")}{m.group(2)}',
        r'(<string name="tools_url">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("TOOLS_URL", "")}{m.group(2)}',
        r'(<string name="info_text">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("INFO_TEXT", "")}{m.group(2)}',
        r'(<string name="home_title">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("HOME_TITLE", config.get("APP_NAME", "喵喵嗷影视"))}{m.group(2)}',
        r'(<string name="subtitle_text">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("SUBTITLE_TEXT", "")}{m.group(2)}',
        r'(<string name="btn_live">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("LIVE_NAME", "直播")}{m.group(2)}',
        r'(<string name="btn_vod">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("VOD_NAME", "点播")}{m.group(2)}',
        r'(<string name="btn_tools">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("TOOLS_NAME", "工具")}{m.group(2)}',
        r'(<string name="btn_qr">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("QR_NAME", "扫码推送")}{m.group(2)}',
        r'(<string name="btn_file">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("FILE_NAME", "文件管理")}{m.group(2)}',
        r'(<string name="btn_app">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("APP_MANAGER_NAME", config.get("APP_NAME", "应用管理"))}{m.group(2)}',
        r'(<string name="user_agent">)[^<]*(</string>)': lambda m: f'{m.group(1)}{ua_value}{m.group(2)}',
        r'(<string name="user_agent_type">)[^<]*(</string>)': lambda m: f'{m.group(1)}{ua_type}{m.group(2)}',
        r'(<string name="about_title">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("ABOUT_TITLE", "关于")}{m.group(2)}',
        r'(<string name="about_message">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("ABOUT_MESSAGE", "")}{m.group(2)}',
        r'(<string name="about_contact">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("ABOUT_CONTACT", "")}{m.group(2)}',
        r'(<string name="about_github">)[^<]*(</string>)': lambda m: f'{m.group(1)}{config.get("ABOUT_GITHUB", "")}{m.group(2)}',
    }
    
    # 执行替换
    for pattern, replacer in replacements.items():
        content = re.sub(pattern, replacer, content)
    
    with open(STRINGS_XML, "w", encoding="utf-8") as f:
        f.write(content)
    
    print("  [OK] strings.xml 更新完成")


def increment_version(version_str):
    """版本号 +0.0.1"""
    try:
        parts = version_str.split(".")
        if len(parts) >= 2:
            parts[-1] = str(int(parts[-1]) + 1)
        else:
            parts.append("1")
        return ".".join(parts)
    except:
        return version_str


def update_build_gradle(config):
    """更新 build.gradle 版本号"""
    print("[3/5] 正在更新 build.gradle...")
    
    with open(BUILD_GRADLE, "r", encoding="utf-8") as f:
        content = f.read()
    
    version_name = config.get("VERSION_NAME", "1.0")
    version_code = config.get("VERSION_CODE", "1")
    
    # 自动递增版本
    auto_increment = config.get("AUTO_INCREMENT_VERSION", "false").lower() == "true"
    if auto_increment:
        old_version = version_name
        version_name = increment_version(version_name)
        # 更新 CONFIG.txt 中的版本
        update_config_version(old_version, version_name)
        print(f"  [OK] 版本自动递增: {old_version} -> {version_name}")
    
    # 替换版本号
    content = re.sub(r'versionName "[^"]+"', f'versionName "{version_name}"', content)
    content = re.sub(r'versionCode \d+', f'versionCode {version_code}', content)
    
    # 更新签名配置（如果有）
    keystore_path = config.get("KEYSTORE_PATH", "").strip()
    if keystore_path and Path(keystore_path).exists():
        # 启用签名配置
        content = content.replace("// signingConfig signingConfigs.release", "signingConfig signingConfigs.release")
        print(f"  [OK] 启用 Release 签名")
    
    with open(BUILD_GRADLE, "w", encoding="utf-8") as f:
        f.write(content)
    
    print(f"  [OK] 版本更新为: {version_name} ({version_code})")


def update_config_version(old_version, new_version):
    """更新 CONFIG.txt 中的版本号"""
    with open(CONFIG_FILE, "r", encoding="utf-8") as f:
        content = f.read()
    
    content = content.replace(f"VERSION_NAME={old_version}", f"VERSION_NAME={new_version}")
    
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        f.write(content)


def process_icon(config):
    """处理应用图标"""
    print("[4/5] 正在处理图标...")
    
    icon_path = config.get("ICON_PATH", "").strip()
    if not icon_path:
        print("  未指定图标，使用默认图标")
        return
    
    icon_file = Path(icon_path)
    if not icon_file.exists():
        print(f"  [警告] 图标不存在: {icon_path}")
        print("  将使用默认图标")
        return
    
    print(f"  发现图标: {icon_path}")
    
    try:
        from PIL import Image
        
        img = Image.open(icon_file)
        
        # 图标尺寸映射
        sizes = {
            "mdpi": 48,
            "hdpi": 72,
            "xhdpi": 96,
            "xxhdpi": 144,
            "xxxhdpi": 192,
        }
        
        for dpi, size in sizes.items():
            output_dir = BASE_DIR / "app" / "src" / "main" / "res" / f"mipmap-{dpi}"
            output_file = output_dir / "ic_launcher.png"
            
            # 调整大小并保存
            resized = img.resize((size, size), Image.Resampling.LANCZOS)
            resized.save(output_file, "PNG")
            print(f"    生成: mipmap-{dpi} ({size}x{size})")
        
        print("  [OK] 图标处理完成")
        
    except ImportError:
        print("  [警告] 未安装 Pillow，无法处理图标")
        print("  请运行: pip install Pillow")
        print("  将使用默认图标")
    except Exception as e:
        print(f"  [错误] 图标处理失败: {e}")
        print("  将使用默认图标")


def build_apk(config):
    """构建 APK"""
    print("[5/5] 开始构建 APK...")
    print()
    
    # 运行 Gradle 构建
    gradlew = BASE_DIR / "gradlew.bat"
    if not gradlew.exists():
        print("[错误] 找不到 gradlew.bat")
        sys.exit(1)
    
    # 判断使用 Debug 还是 Release 构建
    keystore_path = config.get("KEYSTORE_PATH", "").strip()
    keystore_password = config.get("KEYSTORE_PASSWORD", "").strip()
    key_alias = config.get("KEY_ALIAS", "").strip()
    key_password = config.get("KEY_PASSWORD", "").strip()
    
    has_signing = (keystore_path and Path(keystore_path).exists() and 
                   keystore_password and key_alias and key_password)
    
    if has_signing:
        print("[INFO] 使用 Release 签名构建")
        build_type = "Release"
        gradle_task = "assembleRelease"
        output_dir = "release"
        output_apk_name = "app-release.apk"
    else:
        print("[INFO] 使用 Debug 构建（无签名）")
        if keystore_path and not Path(keystore_path).exists():
            print(f"[WARN] 密钥库不存在: {keystore_path}")
        build_type = "Debug"
        gradle_task = "assembleDebug"
        output_dir = "debug"
        output_apk_name = "app-debug.apk"
    
    result = subprocess.run(
        [str(gradlew), gradle_task, "--no-daemon"],
        cwd=BASE_DIR,
        capture_output=False,
    )
    
    if result.returncode != 0:
        print()
        print("[错误] 构建失败！")
        sys.exit(1)
    
    # 复制并重命名 APK
    app_name = config.get("APP_NAME", "喵喵嗷影视")
    output_apk = BASE_DIR / "app" / "build" / "outputs" / "apk" / output_dir / output_apk_name
    final_apk = BASE_DIR / f"{app_name}.apk"
    
    if output_apk.exists():
        shutil.copy2(output_apk, final_apk)
        print()
        print("=" * 50)
        print("  [成功] 打包完成！")
        print("=" * 50)
        print(f"  输出文件: {final_apk.name}")
        print(f"  版本: {config.get('VERSION_NAME', '1.0')} ({config.get('VERSION_CODE', '1')})")
        print(f"  应用名称: {app_name}")
        print(f"  构建类型: {build_type}")
        print(f"  文件大小: {final_apk.stat().st_size / 1024 / 1024:.2f} MB")
        print("=" * 50)
    else:
        print("[警告] 未找到输出 APK")
        print(f"请检查 app\\build\\outputs\\apk\\{output_dir}\\ 目录")


def setup_sdk():
    """设置 SDK 路径 - 优先使用实际存在的路径"""
    local_props = BASE_DIR / "local.properties"
    
    # 如果已存在且有效，直接返回
    if local_props.exists():
        with open(local_props, "r", encoding="utf-8") as f:
            content = f.read()
            if "sdk.dir=" in content:
                sdk_path = content.split("sdk.dir=")[1].split("\n")[0].strip()
                sdk_path = sdk_path.replace("\\\\", "\\").replace("\\:", ":")
                if Path(sdk_path).exists() and (Path(sdk_path) / "platforms").exists():
                    return
    
    # 尝试常见路径（优先使用实际存在的）
    common_paths = [
        Path("F:/Program Files/Sdk"),  # 用户实际路径
        Path.home() / "AppData" / "Local" / "Android" / "Sdk",
        Path("C:/Program Files/Android/Sdk"),
        Path("D:/Android/Sdk"),
    ]
    
    android_home = None
    for path in common_paths:
        if path.exists() and (path / "platforms").exists():
            android_home = str(path)
            break
    
    # 如果常见路径没找到，再尝试环境变量（但验证是否存在）
    if not android_home:
        env_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if env_sdk and Path(env_sdk).exists() and (Path(env_sdk) / "platforms").exists():
            android_home = env_sdk
    
    if not android_home:
        print("[错误] 找不到 Android SDK！")
        print("请设置 ANDROID_HOME 环境变量，或创建 local.properties 文件：")
        print("  sdk.dir=C:\\path\\to\\android-sdk")
        sys.exit(1)
    
    # 写入 local.properties（使用双反斜杠，保留盘符后的冒号）
    with open(local_props, "w", encoding="utf-8") as f:
        # 将 F:\path 转为 F:\\path 格式
        escaped = android_home.replace("\\", "\\\\")
        # 确保盘符后的冒号正确
        if len(escaped) > 1 and escaped[1] == ":":
            escaped = escaped[0] + ":" + escaped[2:]
        f.write(f"sdk.dir={escaped}\n")
    
    print(f"[自动] 已创建 local.properties: {android_home}")


def main():
    print("=" * 50)
    print("  喵喵嗷影视 - 快速打包工具")
    print("=" * 50)
    print()
    
    # 0. 设置 SDK
    setup_sdk()
    
    # 1. 读取配置
    print("[1/5] 正在读取配置...")
    config = parse_config()
    
    print("[配置信息]")
    print(f"  应用名称: {config.get('APP_NAME', 'N/A')}")
    print(f"  版本: {config.get('VERSION_NAME', 'N/A')} ({config.get('VERSION_CODE', 'N/A')})")
    print(f"  首页标题: {config.get('HOME_TITLE', 'N/A')}")
    print(f"  直播URL: {config.get('LIVE_URL', 'N/A')}")
    print(f"  点播URL: {config.get('VOD_URL', 'N/A')}")
    print()
    
    # 2-5. 执行构建步骤
    update_strings_xml(config)
    update_build_gradle(config)
    process_icon(config)
    build_apk(config)
    
    print()
    print("[INFO] Build completed. Press any key to exit...")
    # 非交互环境跳过等待
    if sys.stdin.isatty():
        input()


if __name__ == "__main__":
    main()

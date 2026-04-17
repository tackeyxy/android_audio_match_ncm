#!/usr/bin/env python3
"""
网易云音乐听歌识曲 Python 脚本

依赖安装:
pip install requests librosa numpy pythonmonkey

用法: python audio_match.py <音频文件路径>
"""

import sys
import os
import struct
import numpy as np


def load_audio(audio_path, duration=3, target_sr=8000):
    """使用librosa加载音频并重采样"""
    try:
        import librosa
    except ImportError:
        print("错误: 请安装 librosa")
        print("  pip install librosa")
        sys.exit(1)

    try:
        audio, sr = librosa.load(audio_path, sr=target_sr, mono=True, duration=duration)

        target_samples = duration * target_sr
        if len(audio) < target_samples:
            audio = np.pad(audio, (0, target_samples - len(audio)), mode="constant")

        return audio.astype(np.float32).tolist()
    except Exception as e:
        print(f"加载音频失败: {e}")
        sys.exit(1)


def generate_fp_with_pythonmonkey(samples):
    """使用pythonmonkey调用WASM生成指纹"""
    import asyncio
    from pythonmonkey import eval

    script_dir = os.path.dirname(os.path.abspath(__file__))
    afp_js = os.path.join(script_dir, "afp.js")
    wasm_js = os.path.join(script_dir, "afp.wasm.js")

    if not os.path.exists(afp_js):
        print(f"找不到 afp.js")
        return None

    with open(wasm_js, "r", encoding="utf-8") as f:
        wasm_code = f.read()
    with open(afp_js, "r", encoding="utf-8") as f:
        afp_code = f.read()

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    try:
        result = loop.run_until_complete(
            _generate_fp_async(samples, wasm_code, afp_code)
        )
        return result
    except Exception as e:
        print(f"pythonmonkey错误: {e}")
        import traceback

        traceback.print_exc()
        return None
    finally:
        loop.close()


async def _generate_fp_async(samples, wasm_code, afp_code):
    from pythonmonkey import eval

    result = eval(f"""
        (async function() {{
            {wasm_code}
            {afp_code}

            let fpRuntime = AudioFingerprintRuntime();

            await new Promise(resolve => {{
                let check = setInterval(() => {{
                    if (typeof fpRuntime.ExtractQueryFP === 'function') {{
                        clearInterval(check);
                        resolve();
                    }}
                }}, 10);
                setTimeout(() => {{ clearInterval(check); resolve(); }}, 5000);
            }});

            if (typeof fpRuntime.ExtractQueryFP !== 'function') {{
                return 'ERROR: ExtractQueryFP not found';
            }}

            let PCMBuffer = new Float32Array({samples});
            let fp_vector = fpRuntime.ExtractQueryFP(PCMBuffer.buffer);

            let result_buf = new Uint8Array(fp_vector.size());
            for (let t = 0; t < fp_vector.size(); t++) {{
                result_buf[t] = fp_vector.get(t);
            }}

            return btoa(String.fromCharCode(...result_buf));
        }})()
    """)

    return await result


def recognize_with_api(fp, duration):
    """直接调用网易云API进行歌曲识别"""
    import requests

    url = "https://interface.music.163.com/api/music/audio/match"
    print(f'指纹是：{fp}')
    params = {
        "sessionId": "0123456789abcdef",
        "algorithmCode": "shazam_v2",
        "duration": duration,
        "rawdata": fp,
        "times": "1",
        "decrypt": "1",
    }

    try:
        resp = requests.get(url, params=params, timeout=30)
        data = resp.json()

        if data.get("code") == 200 and data.get("data", {}).get("result"):
            return data["data"]["result"]
    except Exception as e:
        print(f"API调用失败: {e}")

    return None


def print_result(results):
    """打印识别结果"""
    if not results:
        print("未找到匹配结果")
        return

    print(f"\n找到 {len(results)} 个结果:")
    print("=" * 50)
    print(f'原始response：{results}')
    print('==========='*4)

    for i, song in enumerate(results, 1):
        song_info = song.get("song", {})
        album = song_info.get("album", {})
        artists = song_info.get("artists", [])

        artist_name = artists[0].get("name", "Unknown") if artists else "Unknown"
        song_name = song_info.get("name", "Unknown")
        album_name = album.get("name", "Unknown")

        print(f"\n[{i}] {song_name}")
        print(f"    歌手: {artist_name}")
        print(f"    专辑: {album_name}")

        song_id = song_info.get("id")
        if song_id:
            print(f"    链接: https://music.163.com/song?id={song_id}")


def main():
    if len(sys.argv) < 2:
        print("用法: python audio_match.py <音频文件路径>")
        print("示例: python audio_match.py 1.flac")
        print()
        print("依赖安装:")
        print("  pip install requests librosa numpy pythonmonkey")
        sys.exit(1)

    audio_path = sys.argv[1]

    if not os.path.exists(audio_path):
        print(f"文件不存在: {audio_path}")
        sys.exit(1)

    print(f"识别音频: {audio_path}")
    print("-" * 40)

    print("[1/3] 加载音频 (librosa)...")
    samples = load_audio(audio_path)
    print(f"    样本数: {len(samples)}")

    print("[2/3] 生成指纹...")
    try:
        fp = generate_fp_with_pythonmonkey(samples)
    except Exception as e:
        print(f"pythonmonkey失败: {e}")
        sys.exit(1)

    if not fp:
        print("指纹生成失败")
        sys.exit(1)

    print(f"    指纹长度: {len(fp)} 字符")

    print("[3/3] 识别歌曲...")
    result = recognize_with_api(fp, 3)

    if result:
        print_result(result)
    else:
        print("\n识别失败，请检查网络或音频内容")


if __name__ == "__main__":
    main()
# 宣传片原创配乐

`bucea-focus-original.wav` 为 45 秒原创器乐《把时间，留给专注》，90 BPM，F 大调，48 kHz / 24 bit 立体声。采用钢琴旋律、柔和电钢分解和弦、少量颤音琴、暖 pad 与轻打击乐，开头淡入 0.7 秒，片尾淡出 3.5 秒。

旋律、和声与编曲为本项目原创；没有采样第三方歌曲录音。乐器由 macOS 自带 General MIDI 音源 `gs_instruments.dls` 离线演奏。

在装有 Swift 与 FFmpeg 的 macOS 上，可在项目根目录重新构建：

```sh
python3 promo/music/build_music.py
```

脚本只需要 Python 标准库，调用 AVFoundation 离线渲染，执行两遍响度测量与母带处理，再写入 `music-validation.json`。源 MIDI 为 `bucea-focus-original.mid`，可在其他 DAW 中继续编辑。

当前 WAV 验证：45.000 秒，综合响度约 -17.9 LUFS，真峰值约 -4.9 dBFS，无削波、NaN 或 Inf。

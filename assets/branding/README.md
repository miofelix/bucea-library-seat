# 北建大图书馆选座图标

采用选定的「书页折椅」方案：展开的书页自然折叠成座椅，以不对称的曲线连接阅读与选座。纯色设计，不使用渐变、阴影或装饰线。

| 颜色 | 色值 | 用途 |
| --- | --- | --- |
| 北建大紫 | `#6C397F` | 底色 |
| 白色 | `#FFFFFF` | 左页与座面 |
| 浅紫 | `#EADDF2` | 右页与折角 |

主紫色取自[北建大官网样式](https://www.bucea.edu.cn/gbcss/style.css)，[首页样式](https://www.bucea.edu.cn/gbcss/index.css)也使用同一色值。

- `bucea-library-seat-icon.svg`：完整图标，1024 × 1024，圆角透明。
- `bucea-library-seat-icon.png`：从 SVG 渲染的 1024 × 1024 PNG。

SVG 由五条命名路径组成，`viewBox` 为 `0 0 256 256`，可编辑、可无损缩放，不依赖字体或外部图片。

Android 使用相同路径制作原生 VectorDrawable，提供自适应图标与 Android 13 单色主题图标。前景保持在 108dp 画布的中央安全区域，支持圆形及圆角方形裁切。应用名称为“北建大图书馆选座”。

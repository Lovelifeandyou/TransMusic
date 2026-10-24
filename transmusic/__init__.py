"""TransMusic —— 多格式音频互转工具。

模块划分：
    formats  格式注册表（可用目标格式、质量参数、扩展名、编码器）
    engine   ffmpeg 定位、媒体探测、命令构建、单任务转换
    jobs     批量队列（并发、进度事件、取消）
    ui       tkinter 图形界面
    cli      命令行批量模式
"""

__version__ = "1.0.0"
__app_name__ = "TransMusic"
__app_name_cn__ = "音频格式转换器"

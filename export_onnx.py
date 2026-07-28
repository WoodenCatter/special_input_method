"""
CSANet → ONNX 导出脚本
用法：把此文件复制到「耳机实时测试」目录下运行：
    cd C:\Users\Administrator\Desktop\耳机实时测试
    python export_onnx.py
输出：csanet_model.onnx
"""
import sys
from pathlib import Path

# 以脚本所在目录为项目根
PROJECT_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(PROJECT_ROOT))

import torch

def main():
    from model_utils import load_project_config, find_best_model
    config = load_project_config()

    # 导入 CSANet
    sys.path.insert(0, str(PROJECT_ROOT / "train"))
    from CSANet import CSANet

    model = CSANet(classes=int(config["num_classes"]))
    model.eval()

    # 加载最优权重
    model_path, metadata = find_best_model()
    model.load_state_dict(torch.load(model_path, map_location="cpu"))
    print(f"模型已加载: {model_path}\n准确率: {metadata.get('accuracy','N/A')}")

    # 导出 ONNX（shape 与训练一致，手机端自适应）
    dummy = torch.randn(1, 1, 2, 500)
    onnx_file = PROJECT_ROOT / "csanet_model.onnx"
    torch.onnx.export(model, dummy, str(onnx_file),
        input_names=["eeg_input"], output_names=["output"],
        dynamic_axes={"eeg_input":{0:"batch"}, "output":{0:"batch"}},
        opset_version=13)
    print(f"导出完成: {onnx_file}  ({onnx_file.stat().st_size/1024:.0f} KB)")
    print("下一步: 复制到 Android app/src/main/assets/ 后重新构建")

if __name__ == "__main__":
    main()

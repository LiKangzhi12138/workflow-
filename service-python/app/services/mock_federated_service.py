import time

def mock_train_process(job_id: str, update_callback):
    """
    模拟联邦学习训练流程
    """

    stages = [
        ("ACCEPTED", 0, "任务已接收"),
        ("PREPARING", 10, "准备数据中"),
        ("TRAINING_RUNNING", 30, "联邦训练进行中"),
        ("TRAINING_RUNNING", 60, "训练中-第2轮"),
        ("VALIDATING", 80, "模型验证中"),
        ("COMPLETED", 100, "任务完成")
    ]

    for status, progress, msg in stages:
        time.sleep(2)  # 模拟耗时

        update_callback(
            job_id=job_id,
            status=status,
            progress=progress,
            message=msg
        )
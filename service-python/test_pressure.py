import requests
import threading
import uuid
import time

BASE_URL = "http://127.0.0.1:8000"

def create_job():
    job_id = str(uuid.uuid4())

    payload = {
        "jobId": job_id,
        "workflowId": 1,  # ✅ 必须 int

        "modelPath": "/models/resnet.pt",
        "datasetPath": "/data/train.csv",
        "algorithmType": "qmix",

        "callbackUrl": "http://127.0.0.1:9000/callback",
        "callbackSecret": "abc123"
    }

    r = requests.post(f"{BASE_URL}/internal/jobs", json=payload)
    print(r.json())

def worker():
    for _ in range(5):
        create_job()
        time.sleep(0.1)

threads = []

for i in range(10):  # 10线程 × 5 = 50 jobs
    t = threading.Thread(target=worker)
    threads.append(t)
    t.start()

for t in threads:
    t.join()

print("压测完成")
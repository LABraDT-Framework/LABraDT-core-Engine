"""
demo_bot.py: automatically pushes CNC machine values to the engine
Run this while the engine is running to simulate live data from the Gateway.
Ctrl+C to stop.

Usage (needs: pip install requests):
  python3 demo_bot.py
  LABRADT_ENGINE_URL=http://<host>:8080 python3 demo_bot.py   # engine elsewhere
"""

import math
import os
import random
import time

import requests

# Engine base URL; override with the LABRADT_ENGINE_URL environment variable.
BASE = os.environ.get("LABRADT_ENGINE_URL", "http://localhost:8080").rstrip("/")
# Live data submodel the engine's rules read (gateway.submodelId, default CPS_SM).
SUBMODEL_ID = "CPS_SM"


def push(submodel_id, element_id, value):
    try:
        r = requests.post(f"{BASE}/engine/push", json={
            "submodelId": submodel_id,
            "elementId": element_id,
            "value": str(value)
        })
        print(f"[BOT] {element_id} = {value} → {r.text.strip()}")
    except Exception as e:
        print(f"[BOT] Failed to push {element_id}: {e}")


def startup():
    try:
        r = requests.get(f"{BASE}/engine/startup")
        print(f"[BOT] Startup: {r.text.strip()}")
    except Exception as e:
        print(f"[BOT] Startup failed: {e}")


if __name__ == "__main__":
    print("[BOT] Starting demo bot...")
    print(f"[BOT] Targeting engine at {BASE}")
    print("[BOT] Press Ctrl+C to stop\n")

    startup()
    time.sleep(2)

    tick = 0
    while True:
        tick += 1
        print(f"\n[BOT] --- Tick {tick} ---")

        # Temperature — oscillates between 60 and 90
        # Crosses 85.0 every ~20 ticks to trigger Rule_1
        temp = 75 + 15 * math.sin(tick * 0.15) + random.uniform(-2, 2)
        push(SUBMODEL_ID, "Temperature", round(temp, 2))
        time.sleep(1)

        # XAxisLoad — stays between 20-45%
        # Crosses 30.0 occasionally to trigger Rule_3
        load = 25 + 12 * math.sin(tick * 0.08) + random.uniform(-3, 3)
        push(SUBMODEL_ID, "XAxisLoad", round(load, 2))
        time.sleep(1)

        # XAxisPos — stays within [-0.45, 0.45] on each axis mostly
        # Every 30 ticks simulates a spike out of bounds to trigger Rule_2
        x = 0.2 * math.sin(tick * 0.05)
        y = 0.15 * math.cos(tick * 0.07)
        z = 0.1 * math.sin(tick * 0.1)

        if tick % 30 == 0:
            print("[BOT] Simulating out-of-bounds position spike!")
            x = 0.9  # exceeds xMax of 0.45

        push(SUBMODEL_ID, "XAxisPos",
             f"{round(x, 2)},{round(y, 2)},{round(z, 2)}")
        time.sleep(2)

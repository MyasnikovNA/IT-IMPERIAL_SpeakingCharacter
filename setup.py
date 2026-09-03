import os
import requests
from dotenv import load_dotenv

load_dotenv()

API_KEY = os.getenv("DID_API_KEY")

if not API_KEY:
    raise RuntimeError("Не найден DID_API_KEY в .env")

headers = {
    "Authorization": f"Basic {API_KEY}",
    "Content-Type": "application/json",
}

# Готовый публичный аватар D-ID
data = {
    "preview_name": "My Russian Avatar",

    "presenter": {
        "type": "clip",
        "presenter_id": "v2_public_Amber@0zSz8kflCN",

        "voice": {
            "type": "microsoft",
            "voice_id": "ru-RU-SvetlanaNeural"
        }
    },

    "embed": True
}

response = requests.post(
    "https://api.d-id.com/agents",
    headers=headers,
    json=data,
    timeout=30
)

print("HTTP:", response.status_code)

if not response.ok:
    print(response.text)
    raise SystemExit(1)

agent = response.json()

print()
print("Agent создан:")
print("ID:", agent["id"])
print("Status:", agent.get("status"))

with open("agent_id.txt", "w", encoding="utf-8") as f:
    f.write(agent["id"])

print()
print("Agent ID сохранён в agent_id.txt")
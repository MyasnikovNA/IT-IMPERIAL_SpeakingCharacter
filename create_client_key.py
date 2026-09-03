import os
import requests
from dotenv import load_dotenv

load_dotenv()

API_KEY = os.getenv("DID_API_KEY")

with open("agent_id.txt", "r", encoding="utf-8") as f:
    agent_id = f.read().strip()

headers = {
    "Authorization": f"Basic {API_KEY}",
    "Content-Type": "application/json",
}

data = {
    "name": "Local Windows Client",
    "allowed_domains": [
        "http://localhost:8000"
    ]
}

url = (
    f"https://api.d-id.com/"
    f"agents/{agent_id}/client-keys"
)

response = requests.post(
    url,
    headers=headers,
    json=data,
    timeout=30
)

print("HTTP:", response.status_code)

if not response.ok:
    print(response.text)
    raise SystemExit(1)

result = response.json()

client_key = result["client_key"]

print()
print("CLIENT KEY:")
print(client_key)

with open("client_key.txt", "w", encoding="utf-8") as f:
    f.write(client_key)

print()
print("Client key сохранён в client_key.txt")
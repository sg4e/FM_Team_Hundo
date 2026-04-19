import json
import requests


with open("../middleware/credentials_FM_Team_Hundo.json", "r") as f:
    client_config = json.load(f)


def send_update():
    url = f"{client_config['url']}/update"
    headers = {
        "X-API-Key": client_config['key'],
        "Content-Type": "application/json"
    }
    data = [
        {
            "type": "drop",
            "value": 122,  # Yamatano
            "last_rng": 0,
            "now_rng": 1
        },
        {
            "type": "starchips",
            "value": 5
        }
    ]
    response = requests.post(url, headers=headers, json=data)
    return response


if __name__ == "__main__":
    response = send_update()
    print(f"Status Code: {response.status_code}: {response.text}")

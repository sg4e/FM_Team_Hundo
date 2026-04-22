import json
import requests
from sys import argv
from itertools import chain


with open("../middleware/credentials_FM_Team_Hundo.json", "r") as f:
    client_config = json.load(f)


def create_emu_message(card_id: int, starchips: int = 3):
    return [
        {
            "type": "drop",
            "value": card_id,  # Yamatano
            "last_rng": 0,
            "now_rng": 1,
            "opp_id": 1
        },
        {
            "type": "starchips",
            "value": starchips
        }
    ]


def send_updates(post_body):
    url = f"{client_config['url']}/update"
    headers = {
        "X-API-Key": client_config['key'],
        "Content-Type": "application/json"
    }
    response = requests.post(url, headers=headers, json=post_body)
    return response


if __name__ == "__main__":
    ids = [int(arg) for arg in argv[1:]] if len(argv) > 1 else [122]  # Yamatano
    body = list(chain(*[create_emu_message(card_id, (index+1) * 3) for index, card_id in enumerate(ids)]))
    response = send_updates(body)
    print(f"Status Code: {response.status_code}: {response.text}")

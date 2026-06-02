def Starchips(value):
    return {"type": "starchips", "value": value}


def Drop(card_id, opp_id=0, last_rng=0, now_rng=0):
    return {"type": "drop", "value": card_id, "opp_id": opp_id, "last_rng": last_rng, "now_rng": now_rng}


def Fuse(card_id, opp_id=0, last_rng=0, now_rng=0):
    return {"type": "fuse", "value": card_id, "opp_id": opp_id, "last_rng": last_rng, "now_rng": now_rng}


def Ritual(card_id, opp_id=0, last_rng=0, now_rng=0):
    return {"type": "ritual", "value": card_id, "opp_id": opp_id, "last_rng": last_rng, "now_rng": now_rng}
